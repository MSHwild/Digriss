package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.alliance.AllianceGUI
import kr.maeshil.digriss.quest.QuestType
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

enum class MenuType { MAIN, NO_NATION, INVITE, CONFIRM_DISSOLVE, WAR, WAR_LOG }

class NationMenuHolder(val type: MenuType) : InventoryHolder {
    private lateinit var inv: Inventory
    val slotNations = mutableMapOf<Int, String>()
    fun setInventory(inventory: Inventory) { inv = inventory }
    override fun getInventory(): Inventory = inv
}

class Nation_D(private val plugin: Digriss) : Listener, CommandExecutor {

    private data class WarRecord(
        val time: Long,
        val type: String,   // START, TRUCE, CONQUER, END
        val a: String,
        val b: String,
        val detail: String
    )

    private val nations = Nation.nations
    private val playerNations = mutableMapOf<UUID, String>()
    private val nationInvites = mutableMapOf<UUID, String>()
    private val nationBeacons = mutableMapOf<String, Location>()
    private val nationSpawns = mutableMapOf<String, Location>()
    private val pendingCreate = mutableSetOf<UUID>()
    private val teleporting = mutableSetOf<UUID>()

    // 전쟁 데이터
    private val activeWars = mutableSetOf<String>()                          // "A|B" (이름순 정렬)
    private val warRequests = mutableMapOf<String, MutableSet<String>>()     // 받는 국가 -> 선포한 국가들
    private val truceRequests = mutableMapOf<String, MutableSet<String>>()   // 받는 국가 -> 휴전 요청한 국가들
    private val warLog = mutableListOf<WarRecord>()                          // 전쟁 기록 (최근 200개 보관)
    private val warStarts = mutableMapOf<String, Long>()                     // "A|B" -> 전쟁 시작 시각
    private val warDurationMs = 3L * 24 * 60 * 60 * 1000                      // 전쟁 최대 기간 3일
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm")

    private lateinit var nationsFile: File
    private lateinit var warsFile: File
    private var nationsConfig: YamlConfiguration? = null

    private var econ: Economy? = null
    private var lastTaxDay = -1

    // ───────────────────────── 활성화 / 비활성화 ─────────────────────────

    fun enable() {
        if (!setupEconomy()) {
            plugin.logger.warning("Vault 또는 경제 플러그인을 찾을 수 없습니다! 금고 및 유지비 기능이 제한될 수 있습니다.")
        }

        nationsFile = File(plugin.dataFolder, "nation.yml")
        warsFile = File(plugin.dataFolder, "war.yml")
        if (!nationsFile.exists()) {
            nationsFile.parentFile.mkdirs()
            try { nationsFile.createNewFile() } catch (e: IOException) { e.printStackTrace() }
        }
        nationsConfig = YamlConfiguration.loadConfiguration(nationsFile)
        loadNations()
        loadWars()

        plugin.getCommand("국가")?.setExecutor(this)
        plugin.server.pluginManager.registerEvents(this, plugin)

        plugin.server.scheduler.runTaskTimer(plugin, Runnable { saveNations() }, 6000L, 6000L)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { checkWarExpiry() }, 1200L, 1200L) // 1분마다
        startDailyTaxTask()

        // 영토 표시는 ActionBarManager가 다른 액션바와 합쳐서 출력
        ActionBarManager.addProvider { territoryText(it) }

        // 국가 레벨 효과 (1초마다)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                applyLevelEffects(player)
            }
        }, 20L, 20L)

        // 영토 경계를 넘으면 화면 가운데 타이틀 표시 (0.5초마다 확인)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                checkTerritoryEntry(player)
                checkEnemyBeaconVisit(player)
            }
        }, 10L, 10L)

        plugin.logger.info("국가 시스템이 활성화되었습니다.")
    }

    fun disable() {
        saveNations()
    }

    /**
     * 서버 초기화용: 모든 국가/영토/전쟁 데이터를 지우고
     * 신호기 블록과 BlueMap 표시도 함께 제거한다.
     */
    fun resetAll() {
        // 열려 있는 국가 GUI 닫기
        Bukkit.getOnlinePlayers().forEach {
            if (it.openInventory.topInventory.holder is NationMenuHolder) it.closeInventory()
        }

        // 신호기 블록 + BlueMap 표시 제거
        (nations.keys + nationBeacons.keys).toSet().forEach { name ->
            nationBeacons[name]?.block?.type = Material.AIR
            BlueMapBridge.removeNationMarker(name)
            BlueMapBridge.removeTerritory(name)
        }

        // 메모리 데이터 비우기
        nations.clear()
        playerNations.clear()
        nationInvites.clear()
        nationBeacons.clear()
        nationSpawns.clear()
        pendingCreate.clear()
        teleporting.clear()
        Nation.chunkClaims.clear()

        activeWars.clear()
        warStarts.clear()
        warRequests.clear()
        truceRequests.clear()
        warLog.clear()

        // nation.yml, war.yml 비우기
        saveNations()
    }

    fun getNationName(uuid: UUID): String? = playerNations[uuid]

    private fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) return false
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return false
        econ = rsp.provider
        return econ != null
    }

    // ───────────────────────── 비용 (기존의 2배) ─────────────────────────

    private fun dailyTax(level: Int) = (50.0 + (level - 1) * 50.0) * 2
    private fun upgradeCost(level: Int) = (100.0 + (level - 1) * 50.0) * 2

    // ───────────────────────── 레벨 효과 ─────────────────────────

    private fun teleportDelaySeconds(level: Int) = if (level >= 2) 3 else 6

    private fun applyLevelEffects(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        val nation = nations[nationName] ?: return
        val level = nation.level
        if (level < 2) return

        val inOwnTerritory = Nation.chunkClaims[chunkKeyOf(player.location)] == nationName

        if (inOwnTerritory) {
            giveEffect(player, PotionEffectType.SPEED)
        }

        if (level >= 3) {
            val beacon = nationBeacons[nationName]
            if (beacon != null && beacon.world == player.world && beacon.distanceSquared(player.location) <= 30.0 * 30.0) {
                giveEffect(player, PotionEffectType.REGENERATION)
            }
        }

        if (level >= 4 && inOwnTerritory) {
            giveEffect(player, PotionEffectType.RESISTANCE)
        }

        if (level >= 5 && inOwnTerritory) {
            giveEffect(player, PotionEffectType.HASTE)
        }
    }

    private fun giveEffect(player: Player, type: PotionEffectType) {
        player.addPotionEffect(PotionEffect(type, 100, 0, true, false, true))
    }

    // ───────────────────────── 전쟁 로직 ─────────────────────────

    private fun warKey(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    private fun isAtWar(a: String, b: String) = activeWars.contains(warKey(a, b))

    fun isAtWarBetween(a: String, b: String) = isAtWar(a, b)

    // 전쟁 남은 시간(ms), 전쟁 중이 아니면 null
    fun warRemainingMs(a: String, b: String): Long? {
        val start = warStarts[warKey(a, b)] ?: return null
        return (start + warDurationMs - System.currentTimeMillis()).coerceAtLeast(0)
    }

    // 3일이 지난 전쟁은 자동 휴전 (휴전과 똑같이 전쟁 점수 정산)
    private fun checkWarExpiry() {
        val now = System.currentTimeMillis()
        activeWars.toList().forEach { key ->
            val start = warStarts.getOrPut(key) { now }
            if (now - start < warDurationMs) return@forEach
            val parts = key.split("|")
            if (parts.size != 2) return@forEach
            Bukkit.broadcastMessage("${ChatColor.YELLOW}⏳ [전쟁] '${parts[0]}' 국가와 '${parts[1]}' 국가의 전쟁 기간(3일)이 끝나 자동으로 휴전합니다.")
            endWar(parts[0], parts[1])
        }
    }

    private fun warsOf(name: String): List<String> =
        activeWars.mapNotNull { key ->
            val parts = key.split("|")
            if (parts.size != 2) null
            else when (name) {
                parts[0] -> parts[1]
                parts[1] -> parts[0]
                else -> null
            }
        }

    private fun notifyNation(name: String, message: String) {
        nations[name]?.members?.forEach { Bukkit.getPlayer(it)?.sendMessage(message) }
    }

    private fun addRecord(type: String, a: String, b: String, detail: String = "") {
        warLog.add(WarRecord(System.currentTimeMillis(), type, a, b, detail))
        if (warLog.size > 200) warLog.removeAt(0)
    }

    private fun startWar(declarer: String, accepter: String) {
        activeWars.add(warKey(declarer, accepter))
        warStarts[warKey(declarer, accepter)] = System.currentTimeMillis()
        warRequests[declarer]?.remove(accepter)
        warRequests[accepter]?.remove(declarer)
        truceRequests[declarer]?.remove(accepter)
        truceRequests[accepter]?.remove(declarer)
        addRecord("START", declarer, accepter)
        Bukkit.broadcastMessage("${ChatColor.RED}⚔ [전쟁] '$declarer' 국가와 '$accepter' 국가의 전쟁이 시작되었습니다! 이제 서로의 신호기를 점령할 수 있습니다. (최대 3일, 이후 자동 휴전)")
        saveWars()
    }

    private fun endWar(a: String, b: String) {
        activeWars.remove(warKey(a, b))
        warStarts.remove(warKey(a, b))
        truceRequests[a]?.remove(b)
        truceRequests[b]?.remove(a)
        addRecord("TRUCE", a, b)
        Bukkit.broadcastMessage("${ChatColor.GREEN}☮ [휴전] '$a' 국가와 '$b' 국가가 휴전했습니다.")
        if (plugin.warScoreManager.settle(a, b)) saveNations() // 전쟁 점수 정산 (배상금)
        saveWars()
    }

    /** 국가가 사라질 때(해체/멸망) 관련 전쟁·요청 정리. except 국가와의 전쟁은 별도 기록이 있으므로 종료 기록을 남기지 않음 */
    private fun removeWarData(name: String, endDetail: String, except: String? = null) {
        warsOf(name).filter { it != except }.forEach { addRecord("END", name, it, endDetail) }
        plugin.allianceManager.removeNation(name)
        plugin.warScoreManager.removeNation(name)
        activeWars.removeAll { it.split("|").contains(name) }
        warStarts.keys.removeAll { it.split("|").contains(name) }
        warRequests.remove(name)
        warRequests.values.forEach { it.remove(name) }
        truceRequests.remove(name)
        truceRequests.values.forEach { it.remove(name) }
        saveWars()
    }

    private fun declareWar(player: Player, target: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 전쟁을 선포할 수 있습니다.")
        if (myName == target) return
        if (nations[target] == null) return player.sendMessage("${ChatColor.RED}존재하지 않는 국가입니다.")
        if (isAtWar(myName, target)) return player.sendMessage("${ChatColor.RED}이미 전쟁 중입니다.")
        if (plugin.allianceManager.areAllied(myName, target)) return player.sendMessage("${ChatColor.RED}연합국에는 전쟁을 선포할 수 없습니다. 먼저 /연합 에서 연합을 해제하세요.")

        val requests = warRequests.getOrPut(target) { mutableSetOf() }
        if (!requests.add(myName)) return player.sendMessage("${ChatColor.RED}이미 전쟁을 선포했습니다. 상대의 수락을 기다리는 중입니다.")

        player.sendMessage("${ChatColor.GREEN}'$target' 국가에 전쟁을 선포했습니다. 상대가 수락하면 전쟁이 시작됩니다.")
        notifyNation(target, "${ChatColor.RED}⚔ '$myName' 국가가 전쟁을 선포했습니다! /국가 → 전쟁 관리에서 수락 또는 거절하세요.")
    }

    private fun acceptWar(player: Player, from: String) {
        val myName = playerNations[player.uniqueId] ?: return
        if (warRequests[myName]?.contains(from) != true) return player.sendMessage("${ChatColor.RED}해당 국가의 전쟁 선포가 없습니다.")
        if (nations[from] == null) {
            warRequests[myName]?.remove(from)
            return player.sendMessage("${ChatColor.RED}해당 국가는 더 이상 존재하지 않습니다.")
        }
        if (plugin.allianceManager.areAllied(myName, from)) return player.sendMessage("${ChatColor.RED}연합국과는 전쟁할 수 없습니다.")
        startWar(from, myName)
    }

    private fun rejectWar(player: Player, from: String) {
        val myName = playerNations[player.uniqueId] ?: return
        if (warRequests[myName]?.remove(from) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$from' 국가의 전쟁 선포를 거절했습니다.")
            notifyNation(from, "${ChatColor.YELLOW}'$myName' 국가가 전쟁 선포를 거절했습니다.")
        }
    }

    private fun cancelDeclare(player: Player, target: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 취소할 수 있습니다.")
        if (warRequests[target]?.remove(myName) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$target' 국가에 대한 전쟁 선포를 취소했습니다.")
        }
    }

    private fun requestTruce(player: Player, target: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 휴전을 요청할 수 있습니다.")
        if (!isAtWar(myName, target)) return

        val requests = truceRequests.getOrPut(target) { mutableSetOf() }
        if (!requests.add(myName)) return player.sendMessage("${ChatColor.RED}이미 휴전을 요청했습니다.")

        player.sendMessage("${ChatColor.GREEN}'$target' 국가에 휴전을 요청했습니다.")
        notifyNation(target, "${ChatColor.GREEN}☮ '$myName' 국가가 휴전을 요청했습니다! 지도자는 /국가 → 전쟁 관리에서 응답하세요.")
    }

    private fun acceptTruce(player: Player, from: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 휴전을 수락할 수 있습니다.")
        if (truceRequests[myName]?.contains(from) != true) return player.sendMessage("${ChatColor.RED}해당 국가의 휴전 요청이 없습니다.")
        endWar(myName, from)
    }

    private fun rejectTruce(player: Player, from: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 거절할 수 있습니다.")
        if (truceRequests[myName]?.remove(from) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$from' 국가의 휴전 요청을 거절했습니다.")
            notifyNation(from, "${ChatColor.YELLOW}'$myName' 국가가 휴전 요청을 거절했습니다.")
        }
    }

    private fun cancelTruce(player: Player, target: String) {
        val myName = playerNations[player.uniqueId] ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 취소할 수 있습니다.")
        if (truceRequests[target]?.remove(myName) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$target' 국가에 대한 휴전 요청을 취소했습니다.")
        }
    }

    private fun handleWarClick(player: Player, target: String, right: Boolean) {
        val myName = playerNations[player.uniqueId] ?: return
        if (nations[target] == null) return player.sendMessage("${ChatColor.RED}해당 국가는 더 이상 존재하지 않습니다.")

        when {
            // 전쟁 중
            isAtWar(myName, target) -> when {
                truceRequests[myName]?.contains(target) == true ->
                    if (right) rejectTruce(player, target) else acceptTruce(player, target)
                truceRequests[target]?.contains(myName) == true ->
                    if (right) cancelTruce(player, target)
                    else player.sendMessage("${ChatColor.YELLOW}상대의 응답을 기다리는 중입니다. (우클릭: 요청 취소)")
                else -> requestTruce(player, target)
            }
            // 상대가 나에게 선포함
            warRequests[myName]?.contains(target) == true ->
                if (right) rejectWar(player, target) else acceptWar(player, target)
            // 내가 상대에게 선포함
            warRequests[target]?.contains(myName) == true ->
                if (right) cancelDeclare(player, target)
                else player.sendMessage("${ChatColor.YELLOW}상대의 수락을 기다리는 중입니다. (우클릭: 선포 취소)")
            // 아무 관계 없음
            else -> declareWar(player, target)
        }
    }

    // ───────────────────────── 액션바 ─────────────────────────

    // 플레이어별 마지막으로 있던 영토 (""는 무소속 땅)
    private val lastTerritory = HashMap<UUID, String>()

    private fun checkTerritoryEntry(player: Player) {
        val owner = Nation.chunkClaims[chunkKeyOf(player.location)] ?: ""
        val previous = lastTerritory.put(player.uniqueId, owner)
        // 처음 확인(접속 직후)이거나 같은 영토 안이면 표시 안 함
        if (previous == null || previous == owner) return

        val myNation = playerNations[player.uniqueId]
        if (owner.isNotEmpty() && owner != myNation) plugin.questManager.onEnterTerritory(player, owner)
        val (title, subtitle) = when {
            owner.isEmpty() -> "§7무소속 지역" to "§8누구의 영토도 아닙니다"
            owner == myNation -> "§a$owner" to "§2내 국가 영토"
            plugin.allianceManager.areAllied(myNation, owner) -> "§b$owner" to "§3연합국 영토"
            myNation != null && isAtWar(myNation, owner) -> "§4⚔ $owner ⚔" to "§c전쟁 중인 국가의 영토입니다"
            else -> "§c$owner" to "§7다른 국가의 영토"
        }
        player.sendTitle(title, subtitle, 5, 30, 10)
    }

    // 전쟁 중인 국가의 신호기 15블록 이내 방문 (퀘스트용)
    private fun checkEnemyBeaconVisit(player: Player) {
        for (enemy in warsOfPlayer(player.uniqueId)) {
            val beacon = nationBeacons[enemy] ?: continue
            if (beacon.world == player.world && beacon.distanceSquared(player.location) <= 15.0 * 15.0) {
                plugin.questManager.onBeaconVisit(player, enemy)
            }
        }
    }

    // 해당 위치를 점령한 국가 (없으면 null)
    fun territoryOwnerAt(location: Location): String? = Nation.chunkClaims[chunkKeyOf(location)]

    // 플레이어 국가가 전쟁 중인 상대 국가 목록 (스코어보드 표시용)
    fun warsOfPlayer(uuid: UUID): List<String> {
        val myNation = playerNations[uuid] ?: return emptyList()
        return warsOf(myNation)
    }

    fun territoryText(player: Player): String {
        val ownerNation = Nation.chunkClaims[chunkKeyOf(player.location)]
        val myNation = playerNations[player.uniqueId]

        return when {
            ownerNation == null -> "§f소속 국가 : 무소속"
            ownerNation == myNation -> "§a소속 국가 : $ownerNation(내 국가)"
            plugin.allianceManager.areAllied(myNation, ownerNation) -> "§b소속 국가 : $ownerNation(연합국)"
            myNation != null && isAtWar(myNation, ownerNation) -> "§4소속 국가 : $ownerNation(전쟁 중)"
            else -> "§c소속 국가 : $ownerNation"
        }
    }

    // ───────────────────────── 명령어 → GUI ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        openMenu(sender)
        return true
    }

    private fun openMenu(player: Player) {
        if (playerNations.containsKey(player.uniqueId)) openMainMenu(player) else openNoNationMenu(player)
    }

    private fun later(task: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { task() })
    }

    // ───────────────────────── GUI 유틸 ─────────────────────────

    private fun item(material: Material, name: String, vararg lore: String): ItemStack {
        val stack = ItemStack(material)
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName(name)
        meta.lore = lore.toList()
        stack.itemMeta = meta
        return stack
    }

    private fun fill(inv: Inventory) {
        val pane = item(Material.GRAY_STAINED_GLASS_PANE, " ")
        for (i in 0 until inv.size) inv.setItem(i, pane)
    }

    // ───────────────────────── 메뉴: 무소속 ─────────────────────────

    private fun openNoNationMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.NO_NATION)
        val inv = Bukkit.createInventory(holder, 27, "§8국가")
        holder.setInventory(inv)
        fill(inv)

        inv.setItem(11, item(Material.WHITE_BANNER, "§a§l국가 건국",
            "§7클릭 후 채팅으로 국가 이름을 입력하세요.",
            "§7현재 서 있는 곳에 신호기가 설치됩니다.",
            "",
            "§8이름: 2~12자, 한글/영문/숫자/_ 만 가능"))

        val invite = nationInvites[player.uniqueId]
        if (invite != null) {
            inv.setItem(15, item(Material.LIME_DYE, "§e§l초대 수락",
                "§f'$invite' §7국가에서 초대가 도착했습니다.",
                "",
                "§a클릭하여 가입"))
        } else {
            inv.setItem(15, item(Material.GRAY_DYE, "§7받은 초대 없음"))
        }

        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 메인 ─────────────────────────

    private fun openMainMenu(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return openNoNationMenu(player)
        val nation = nations[nationName] ?: return
        val isLeader = nation.leader == player.uniqueId
        val leaderOnly = if (isLeader) "§a클릭하여 실행" else "§c지도자 전용"

        val holder = NationMenuHolder(MenuType.MAIN)
        val inv = Bukkit.createInventory(holder, 36, "§8국가 관리 - $nationName")
        holder.setInventory(inv)
        fill(inv)

        val leaderName = Bukkit.getOfflinePlayer(nation.leader).name ?: "알 수 없음"
        val enemies = warsOf(nationName)
        inv.setItem(4, item(Material.BEACON, "§6§l${nation.name} §7(Lv.${nation.level})",
            "§e지도자 §f$leaderName",
            "§e국가원 §f${nation.members.size}명",
            "§e영토 §f${nation.claims.size} / ${nation.members.size * 10} 청크",
            "§a금고 §f${nation.bank}원",
            "§b일일 유지비 §f${dailyTax(nation.level)}원 §7(매일 자정)",
            if (nation.level < 5) "§7다음 업그레이드 §f${upgradeCost(nation.level)}원" else "§7최고 레벨 도달",
            if (enemies.isEmpty()) "§7전쟁 중인 국가 §f없음"
            else "§c전쟁 중 §f" + enemies.joinToString(", ") { e ->
                val left = (warRemainingMs(nationName, e) ?: 0) / 60000
                "$e §7(${left / 60}시간 ${left % 60}분 남음)§f"
            }))

        inv.setItem(10, item(Material.GRASS_BLOCK, "§a§l영토 점령",
            "§7현재 서 있는 청크를 국가 영토로 점령합니다.", "", leaderOnly))

        inv.setItem(12, item(Material.GOLD_INGOT, "§e§l금고 입금",
            "§7좌클릭 §f100원",
            "§7우클릭 §f1,000원",
            "§7쉬프트+좌클릭 §f10,000원",
            "§7쉬프트+우클릭 §f100,000원"))

        inv.setItem(14, item(Material.EXPERIENCE_BOTTLE, "§b§l국가 업그레이드",
            if (nation.level < 5) "§7필요 금액 §f${upgradeCost(nation.level)}원" else "§7이미 최고 레벨입니다.",
            if (nation.level < 5) "§7다음 효과: ${getLevelEffectMessage(nation.level + 1)}" else "",
            "", leaderOnly))

        inv.setItem(16, item(Material.CHEST, "§6§l국가 창고",
            "§7국가원 모두가 함께 쓰는 창고입니다.",
            "§7크기 §f${9 * (nation.level + 2).coerceIn(3, 6)}칸 §7(국가 레벨이 오르면 커짐)",
            "", "§a클릭하여 열기"))

        inv.setItem(20, item(Material.ENDER_PEARL, "§d§l국가 스폰 이동",
            "§7국가 스폰 지점으로 이동합니다.",
            "§7대기시간 §f${teleportDelaySeconds(nation.level)}초 §7(움직이면 취소)",
            "", "§a클릭하여 이동"))

        inv.setItem(22, item(Material.RED_BED, "§a§l스폰 설정",
            "§7현재 위치를 국가 스폰으로 설정합니다.", "", leaderOnly))

        inv.setItem(24, item(Material.LODESTONE, "§b§l신호기 이동",
            "§7현재 위치로 신호기를 옮깁니다.", "§7기존 신호기는 제거됩니다.", "", leaderOnly))

        inv.setItem(28, item(Material.PLAYER_HEAD, "§a§l국가원 초대",
            "§7접속 중인 무소속 유저를 초대합니다.", "", "§a클릭하여 선택"))

        val incoming = warRequests[nationName]?.size ?: 0
        inv.setItem(30, item(Material.NETHERITE_SWORD, "§c§l전쟁 관리",
            "§7전쟁 선포 / 수락 / 휴전을 관리합니다.",
            "§7진행 중인 전쟁 §f${enemies.size}개",
            if (incoming > 0) "§e받은 전쟁 선포 §c${incoming}건!" else "§7받은 전쟁 선포 없음",
            "", "§a클릭하여 열기"))

        val allies = plugin.allianceManager.alliesOf(nationName)
        val allyRequests = Nation.nations.keys.count { plugin.allianceManager.hasRequest(nationName, it) }
        inv.setItem(32, item(Material.LIGHT_BLUE_BANNER, "§b§l연합 관리",
            "§7다른 국가와 연합을 맺거나 해제합니다.",
            "§7연합국 §f${if (allies.isEmpty()) "없음" else allies.joinToString(", ")}",
            if (allyRequests > 0) "§e받은 연합 요청 §b${allyRequests}건!" else "§7받은 연합 요청 없음",
            "", "§a클릭하여 열기"))

        if (isLeader) {
            inv.setItem(34, item(Material.TNT, "§c§l국가 해체",
                "§7국가와 모든 영토가 사라집니다.", "", "§c클릭하여 진행"))
        } else {
            inv.setItem(34, item(Material.OAK_DOOR, "§c§l국가 탈퇴",
                "§7현재 국가에서 탈퇴합니다.", "", "§c클릭하여 탈퇴"))
        }

        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 전쟁 관리 ─────────────────────────

    private fun warPriority(myName: String, target: String): Int = when {
        isAtWar(myName, target) -> 0
        warRequests[myName]?.contains(target) == true -> 1
        warRequests[target]?.contains(myName) == true -> 2
        else -> 3
    }

    private fun warItem(myName: String, target: String): ItemStack {
        val n = nations[target]!!
        val base = arrayOf("§7국가원 §f${n.members.size}명", "§7레벨 §fLv.${n.level}")

        return when {
            isAtWar(myName, target) -> when {
                truceRequests[myName]?.contains(target) == true ->
                    item(Material.LIME_BANNER, "§a§l$target §7(휴전 요청 받음)", *base, "",
                        "§a좌클릭: 휴전 수락 §7(지도자)", "§c우클릭: 거절 §7(지도자)")
                truceRequests[target]?.contains(myName) == true ->
                    item(Material.YELLOW_BANNER, "§e§l$target §7(휴전 요청 보냄)", *base, "",
                        "§7상대의 응답을 기다리는 중", "§c우클릭: 요청 취소 §7(지도자)")
                else ->
                    item(Material.NETHERITE_SWORD, "§4§l$target §c(전쟁 중)", *base, "",
                        "§7상대 신호기를 부수면 국가를 점령합니다.", "§e클릭: 휴전 요청 §7(지도자)")
            }
            warRequests[myName]?.contains(target) == true ->
                item(Material.RED_BANNER, "§c§l$target §7(전쟁 선포 받음)", *base, "",
                    "§a좌클릭: 수락 §7(국가원 누구나)", "§c우클릭: 거절")
            warRequests[target]?.contains(myName) == true ->
                item(Material.YELLOW_BANNER, "§e§l$target §7(선포 대기 중)", *base, "",
                    "§7상대의 수락을 기다리는 중", "§c우클릭: 선포 취소 §7(지도자)")
            else ->
                item(Material.WHITE_BANNER, "§f§l$target", *base, "",
                    "§c클릭: 전쟁 선포 §7(지도자)")
        }
    }

    private fun openWarMenu(player: Player) {
        val myName = playerNations[player.uniqueId] ?: return openNoNationMenu(player)

        val holder = NationMenuHolder(MenuType.WAR)
        val inv = Bukkit.createInventory(holder, 54, "§8전쟁 관리")
        holder.setInventory(inv)
        fill(inv)

        val others = nations.keys
            .filter { it != myName }
            .sortedWith(compareBy({ warPriority(myName, it) }, { it }))
            .take(45)

        others.forEachIndexed { index, name ->
            inv.setItem(index, warItem(myName, name))
            holder.slotNations[index] = name
        }

        if (others.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "§c다른 국가가 없습니다."))
        }

        inv.setItem(45, item(Material.BOOK, "§e§l전쟁 기록",
            "§7최근 전쟁 시작 / 휴전 / 점령 기록을 봅니다.", "", "§a클릭하여 열기"))
        inv.setItem(49, item(Material.ARROW, "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 전쟁 기록 ─────────────────────────

    private fun openWarLogMenu(player: Player) {
        val myName = playerNations[player.uniqueId]

        val holder = NationMenuHolder(MenuType.WAR_LOG)
        val inv = Bukkit.createInventory(holder, 54, "§8전쟁 기록")
        holder.setInventory(inv)
        fill(inv)

        val records = warLog.takeLast(45).reversed()

        records.forEachIndexed { index, r ->
            val mine = myName != null && (r.a == myName || r.b == myName)
            val (material, title, line) = when (r.type) {
                "START" -> Triple(Material.IRON_SWORD, "§c⚔ 전쟁 시작",
                    "§f${r.a} §7의 선포를 §f${r.b} §7이(가) 수락")
                "TRUCE" -> Triple(Material.WHITE_BANNER, "§a☮ 휴전",
                    "§f${r.a} §7과(와) §f${r.b} §7이(가) 휴전")
                "CONQUER" -> Triple(Material.TNT, "§4☠ 국가 점령",
                    "§f${r.a} §7이(가) §f${r.b} §7을(를) 점령")
                else -> Triple(Material.BARRIER, "§7전쟁 종료",
                    "§f${r.a} §7소멸 → §f${r.b} §7와(과)의 전쟁 종료")
            }

            val lore = mutableListOf("§7${dateFormat.format(Date(r.time))}", line)
            if (r.detail.isNotEmpty()) lore.add("§7${r.detail}")
            if (mine) {
                lore.add("")
                lore.add("§e★ 우리 국가 관련 기록")
            }
            inv.setItem(index, item(material, title, *lore.toTypedArray()))
        }

        if (records.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "§7아직 전쟁 기록이 없습니다."))
        }

        if (myName != null) {
            val wars = warLog.count { it.type == "START" && (it.a == myName || it.b == myName) }
            val wins = warLog.count { it.type == "CONQUER" && it.a == myName }
            val truces = warLog.count { it.type == "TRUCE" && (it.a == myName || it.b == myName) }
            inv.setItem(45, item(Material.WRITTEN_BOOK, "§6§l$myName §e전적",
                "§7전쟁 참여 §f${wars}회",
                "§7국가 점령 승리 §f${wins}회",
                "§7휴전 §f${truces}회",
                "",
                "§8최근 200개 기록 기준"))
        }

        inv.setItem(49, item(Material.ARROW, "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 초대 대상 선택 ─────────────────────────

    private fun openInviteMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.INVITE)
        val inv = Bukkit.createInventory(holder, 54, "§8초대할 유저 선택")
        holder.setInventory(inv)

        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && !playerNations.containsKey(it.uniqueId) }
            .take(45)

        candidates.forEachIndexed { index, target ->
            val head = ItemStack(Material.PLAYER_HEAD)
            val meta = head.itemMeta as SkullMeta
            meta.owningPlayer = target
            meta.setDisplayName("§f${target.name}")
            meta.lore = listOf("§a클릭하여 초대")
            head.itemMeta = meta
            inv.setItem(index, head)
        }

        if (candidates.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "§c초대 가능한 유저가 없습니다."))
        }

        inv.setItem(49, item(Material.ARROW, "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 해체 확인 ─────────────────────────

    private fun openConfirmDissolveMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.CONFIRM_DISSOLVE)
        val inv = Bukkit.createInventory(holder, 27, "§8정말 해체하시겠습니까?")
        holder.setInventory(inv)
        fill(inv)

        inv.setItem(11, item(Material.LIME_CONCRETE, "§a§l해체 확인", "§7되돌릴 수 없습니다."))
        inv.setItem(15, item(Material.RED_CONCRETE, "§c§l취소"))

        player.openInventory(inv)
    }

    // ───────────────────────── GUI 클릭 처리 ─────────────────────────

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? NationMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return

        when (holder.type) {
            MenuType.NO_NATION -> when (event.slot) {
                11 -> {
                    player.closeInventory()
                    pendingCreate.add(player.uniqueId)
                    player.sendMessage("${ChatColor.GOLD}건국할 국가 이름을 채팅으로 입력하세요. (취소: '취소' 입력)")
                }
                15 -> {
                    if (nationInvites.containsKey(player.uniqueId)) {
                        acceptInvite(player)
                        later { openMenu(player) }
                    }
                }
            }

            MenuType.MAIN -> when (event.slot) {
                10 -> { player.closeInventory(); claimChunk(player) }
                12 -> {
                    val amount = when (event.click) {
                        ClickType.LEFT -> 100.0
                        ClickType.RIGHT -> 1000.0
                        ClickType.SHIFT_LEFT -> 10000.0
                        ClickType.SHIFT_RIGHT -> 100000.0
                        else -> return
                    }
                    depositBank(player, amount)
                    later { openMainMenu(player) }
                }
                14 -> { upgradeNation(player); later { openMainMenu(player) } }
                16 -> later { plugin.nationStorageManager.open(player) }
                20 -> { player.closeInventory(); centerTP(player) }
                22 -> { player.closeInventory(); setNationSpawn(player) }
                24 -> { player.closeInventory(); setNationBeacon(player) }
                28 -> later { openInviteMenu(player) }
                30 -> later { openWarMenu(player) }
                32 -> later { AllianceGUI.open(player, plugin) }
                34 -> {
                    val nation = nations[playerNations[player.uniqueId]] ?: return
                    if (nation.leader == player.uniqueId) {
                        later { openConfirmDissolveMenu(player) }
                    } else {
                        player.closeInventory()
                        leaveNation(player)
                    }
                }
            }

            MenuType.WAR -> {
                if (event.slot == 49) { later { openMainMenu(player) }; return }
                if (event.slot == 45) { later { openWarLogMenu(player) }; return }
                val target = holder.slotNations[event.slot] ?: return
                handleWarClick(player, target, event.click.isRightClick)
                later { openWarMenu(player) }
            }

            MenuType.WAR_LOG -> {
                if (event.slot == 49) later { openWarMenu(player) }
            }

            MenuType.INVITE -> {
                if (event.slot == 49) { later { openMainMenu(player) }; return }
                val uuid = (event.currentItem?.itemMeta as? SkullMeta)?.owningPlayer?.uniqueId ?: return
                val target = Bukkit.getPlayer(uuid)
                if (target == null) {
                    player.sendMessage("${ChatColor.RED}해당 플레이어를 찾을 수 없습니다.")
                } else {
                    invitePlayer(player, target)
                }
                later { openInviteMenu(player) }
            }

            MenuType.CONFIRM_DISSOLVE -> when (event.slot) {
                11 -> { player.closeInventory(); dissolveNation(player) }
                15 -> later { openMainMenu(player) }
            }
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is NationMenuHolder) event.isCancelled = true
    }

    // ───────────────────────── 채팅으로 국가 이름 입력 ─────────────────────────

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (!pendingCreate.contains(player.uniqueId)) return

        event.isCancelled = true
        val input = event.message.trim()
        pendingCreate.remove(player.uniqueId)

        later {
            if (input == "취소") {
                player.sendMessage("${ChatColor.GRAY}국가 건국을 취소했습니다.")
            } else {
                createNation(player, input)
            }
        }
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        pendingCreate.remove(event.player.uniqueId)
        teleporting.remove(event.player.uniqueId)
        lastTerritory.remove(event.player.uniqueId)
    }

    // ───────────────────────── 국가 로직 ─────────────────────────

    private fun chunkKeyOf(loc: Location): String {
        val chunk = loc.chunk
        return "${chunk.world.name},${chunk.x},${chunk.z}"
    }

    private fun createNation(player: Player, nationName: String) {
        if (!Regex("^[가-힣a-zA-Z0-9_]{2,12}$").matches(nationName)) {
            return player.sendMessage("${ChatColor.RED}국가 이름은 2~12자의 한글/영문/숫자/_ 만 사용할 수 있습니다.")
        }
        if (playerNations.containsKey(player.uniqueId)) return player.sendMessage("${ChatColor.RED}이미 소속된 국가가 있습니다.")
        if (nations.containsKey(nationName)) return player.sendMessage("${ChatColor.RED}이미 존재하는 국가 이름입니다.")

        val loc = player.location.block.location
        val chunkKey = chunkKeyOf(loc)

        val owner = Nation.chunkClaims[chunkKey]
        if (owner != null) {
            return player.sendMessage("${ChatColor.RED}이곳은 이미 '$owner' 국가의 영토입니다. 다른 장소에서 건국해주세요.")
        }

        loc.block.type = Material.BEACON

        val newNation = Nations(nationName, player.uniqueId, mutableListOf(player.uniqueId))
        nations[nationName] = newNation
        playerNations[player.uniqueId] = nationName
        nationBeacons[nationName] = loc

        Nation.chunkClaims[chunkKey] = nationName
        newNation.claims.add(chunkKey)

        BlueMapBridge.addNationMarker(nationName, loc)
        BlueMapBridge.updateTerritory(newNation, loc.world.name)

        player.sendMessage("${ChatColor.GREEN}'$nationName' 국가를 성공적으로 건국했습니다!")
        saveNations()
    }

    private fun claimChunk(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return player.sendMessage("${ChatColor.RED}소속된 국가가 없습니다.")
        val nation = nations[nationName]!!
        if (nation.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 영토를 점령할 수 있습니다.")

        val chunkKey = chunkKeyOf(player.location)

        if (Nation.chunkClaims.containsKey(chunkKey)) {
            return player.sendMessage("${ChatColor.RED}이미 점령된 영토입니다. (점령국: ${Nation.chunkClaims[chunkKey]})")
        }

        val maxClaims = nation.members.size * 10
        if (nation.claims.size >= maxClaims) {
            return player.sendMessage("${ChatColor.RED}국가 인원수 대비 점령 한도를 초과했습니다! (최대 $maxClaims 개)")
        }

        Nation.chunkClaims[chunkKey] = nationName
        nation.claims.add(chunkKey)

        BlueMapBridge.updateTerritory(nation, player.world.name)

        player.sendMessage("${ChatColor.GREEN}현재 청크를 점령했습니다! (현재 점령지: ${nation.claims.size}/$maxClaims 개)")
        plugin.questManager.addProgress(player, QuestType.CLAIM_CHUNK)
        saveNations()
    }

    private fun setNationBeacon(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        if (nations[nationName]?.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}지도자만 사용할 수 있습니다.")

        val oldBeaconLoc = nationBeacons[nationName]
        val newLoc = player.location.block.location

        if (oldBeaconLoc != null && oldBeaconLoc != newLoc) {
            oldBeaconLoc.block.type = Material.AIR
        }

        newLoc.block.type = Material.BEACON
        nationBeacons[nationName] = newLoc

        BlueMapBridge.addNationMarker(nationName, newLoc)

        player.sendMessage("${ChatColor.GREEN}국가 신호기 위치를 변경했습니다. 기존 신호기는 제거되었습니다.")
        saveNations()
    }

    private fun invitePlayer(player: Player, target: Player) {
        val nationName = playerNations[player.uniqueId] ?: return

        if (playerNations.containsKey(target.uniqueId)) {
            return player.sendMessage("${ChatColor.RED}${target.name} 님은 이미 다른 국가에 소속되어 있습니다.")
        }

        nationInvites[target.uniqueId] = nationName
        target.sendMessage("${ChatColor.GOLD}'$nationName' 국가에서 초대가 도착했습니다.")
        target.sendMessage("${ChatColor.YELLOW}/국가 를 입력해 GUI에서 수락하세요.")
        player.sendMessage("${ChatColor.GREEN}${target.name} 님에게 국가 초대를 보냈습니다.")
    }

    private fun acceptInvite(player: Player) {
        if (playerNations.containsKey(player.uniqueId)) {
            return player.sendMessage("${ChatColor.RED}이미 소속된 국가가 있어 초대를 수락할 수 없습니다.")
        }

        val nationName = nationInvites.remove(player.uniqueId) ?: return player.sendMessage("${ChatColor.RED}받은 국가 초대가 없습니다.")
        val nation = nations[nationName] ?: return player.sendMessage("${ChatColor.RED}해당 국가는 더 이상 존재하지 않습니다.")
        nation.members.add(player.uniqueId)
        playerNations[player.uniqueId] = nationName
        player.sendMessage("${ChatColor.GREEN}'$nationName' 국가에 가입을 완료했습니다!")
        saveNations()
    }

    private fun depositBank(player: Player, amount: Double) {
        val economy = econ ?: return player.sendMessage("${ChatColor.RED}Vault 경제 시스템이 연동되어 있지 않습니다.")
        val nationName = playerNations[player.uniqueId] ?: return player.sendMessage("${ChatColor.RED}소속된 국가가 없습니다.")
        val nation = nations[nationName]!!

        if (!economy.has(player, amount)) {
            return player.sendMessage("${ChatColor.RED}소지금이 부족합니다. (현재 소지금: ${economy.getBalance(player)}원)")
        }

        economy.withdrawPlayer(player, amount)
        nation.bank += amount

        player.sendMessage("${ChatColor.GREEN}국가 금고에 $amount 원을 입금했습니다. (금고 총액: ${nation.bank}원)")
        plugin.questManager.addProgress(player, QuestType.BANK_DEPOSIT, amount.toInt())
        saveNations()
    }

    private fun upgradeNation(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return player.sendMessage("${ChatColor.RED}소속된 국가가 없습니다.")
        val nation = nations[nationName]!!
        if (nation.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 업그레이드를 진행할 수 있습니다.")

        if (nation.level >= 5) {
            return player.sendMessage("${ChatColor.GOLD}이미 최고 레벨(5레벨)에 도달했습니다!")
        }

        val cost = upgradeCost(nation.level)

        if (nation.bank < cost) {
            return player.sendMessage("${ChatColor.RED}국가 금고 잔액이 부족합니다! 필요 금액: $cost 원 (현재 금고: ${nation.bank}원)")
        }

        nation.bank -= cost
        nation.level += 1

        player.sendMessage("${ChatColor.GREEN}🎉 국가 레벨이 ${nation.level} 레벨로 업그레이드되었습니다!")
        player.sendMessage("${ChatColor.AQUA}해금된 효과: ${getLevelEffectMessage(nation.level)}")

        nation.members.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { applyLevelEffects(it) } }

        saveNations()
    }

    private fun getLevelEffectMessage(level: Int): String {
        return when (level) {
            2 -> "스폰 이동 대기시간 6초→3초, 영토 내 신속 I"
            3 -> "국가 신호기 주변 30블록 내 재생 I"
            4 -> "국가 영토 내 저항 I"
            5 -> "영토 내 성급(채광속도) I"
            else -> "기본 국가"
        }
    }

    private fun startDailyTaxTask() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            val cal = Calendar.getInstance()
            val hour = cal.get(Calendar.HOUR_OF_DAY)
            val currentDay = cal.get(Calendar.DAY_OF_YEAR)

            if (hour == 0 && lastTaxDay != currentDay) {
                lastTaxDay = currentDay
                processDailyTax()
            }
        }, 1200L, 1200L)
    }

    private fun processDailyTax() {
        Bukkit.broadcastMessage("${ChatColor.GOLD}[국가 시스템] 자정이 되어 일일 국가 유지비 차감이 진행됩니다.")

        nations.values.forEach { nation ->
            val tax = dailyTax(nation.level)
            val leaderPlayer = Bukkit.getPlayer(nation.leader)

            if (nation.bank >= tax) {
                nation.bank -= tax
                leaderPlayer?.sendMessage("${ChatColor.YELLOW}[유지비] 국가 금고에서 일일 유지비 $tax 원이 차감되었습니다. (남은 잔액: ${nation.bank}원)")
            } else {
                leaderPlayer?.sendMessage("${ChatColor.RED}[경고] 국가 금고 잔액이 부족하여 유지비($tax 원)를 납부하지 못했습니다!")
            }
        }
        saveNations()
    }

    private fun setNationSpawn(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        if (nations[nationName]?.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}지도자만 사용할 수 있습니다.")
        nationSpawns[nationName] = player.location
        player.sendMessage("${ChatColor.GREEN}국가 스폰 지점을 현재 위치로 설정했습니다.")
        saveNations()
    }

    private fun centerTP(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        val nation = nations[nationName] ?: return
        val target = nationSpawns[nationName] ?: nationBeacons[nationName]?.clone()?.add(0.5, 1.0, 0.5)
        if (target == null) return player.sendMessage("${ChatColor.RED}설정된 스폰 위치가 없습니다.")

        if (!teleporting.add(player.uniqueId)) {
            return player.sendMessage("${ChatColor.RED}이미 이동 대기 중입니다.")
        }

        val delay = teleportDelaySeconds(nation.level)
        val start = player.location.clone()
        player.sendMessage("${ChatColor.YELLOW}${delay}초 뒤 국가 스폰으로 이동합니다. 움직이면 취소됩니다.")

        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            teleporting.remove(player.uniqueId)
            if (!player.isOnline) return@Runnable

            val now = player.location
            if (now.world != start.world || now.distanceSquared(start) > 1.0) {
                player.sendMessage("${ChatColor.RED}움직여서 이동이 취소되었습니다.")
                return@Runnable
            }

            player.teleport(target)
            player.sendMessage("${ChatColor.GREEN}국가 스폰 지점으로 이동했습니다.")
        }, delay * 20L)
    }

    private fun dissolveNation(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        val nation = nations[nationName] ?: return
        if (nation.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자만 해체할 수 있습니다.")

        plugin.nationStorageManager.dropAll(nationName, player.location) // 창고 아이템은 지도자 발밑에 떨어뜨림
        nation.claims.forEach { Nation.chunkClaims.remove(it) }
        nation.members.forEach { playerNations.remove(it) }
        nations.remove(nationName)
        nationBeacons[nationName]?.block?.type = Material.AIR
        nationBeacons.remove(nationName)
        nationSpawns.remove(nationName)
        removeWarData(nationName, "국가 해체로 종료")

        BlueMapBridge.removeNationMarker(nationName)
        BlueMapBridge.removeTerritory(nationName)

        player.sendMessage("${ChatColor.RED}국가가 해체되었습니다.")
        saveNations()
    }

    private fun leaveNation(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        val nation = nations[nationName] ?: return
        if (nation.leader == player.uniqueId) return player.sendMessage("${ChatColor.RED}국가 지도자는 탈퇴할 수 없습니다. 국가 해체를 이용하세요.")
        nation.members.remove(player.uniqueId)
        playerNations.remove(player.uniqueId)
        player.sendMessage("${ChatColor.GREEN}국가를 탈퇴했습니다.")
        saveNations()
    }

    // ───────────────────────── 신호기 점령 (전쟁 중일 때만) ─────────────────────────

    @EventHandler(ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (event.block.type != Material.BEACON) return

        val breaker = event.player
        val defenderName = nationBeacons.entries.find { it.value == event.block.location }?.key ?: return
        if (nations[defenderName] == null) return

        val breakerNation = playerNations[breaker.uniqueId]

        when {
            breakerNation == null -> {
                event.isCancelled = true
                breaker.sendMessage("${ChatColor.RED}국가에 소속되어 있어야 다른 국가의 신호기를 점령할 수 있습니다.")
            }
            breakerNation == defenderName -> {
                event.isCancelled = true
                breaker.sendMessage("${ChatColor.RED}자국 신호기는 부술 수 없습니다. 이동/해체는 /국가 메뉴를 이용하세요.")
            }
            !isAtWar(breakerNation, defenderName) -> {
                event.isCancelled = true
                breaker.sendMessage("${ChatColor.RED}'$defenderName' 국가와 전쟁 중이 아닙니다! /국가 → 전쟁 관리에서 전쟁을 선포하고 수락받아야 점령할 수 있습니다.")
            }
            else -> {
                event.isDropItems = false
                conquerNation(breakerNation, defenderName, breaker)
            }
        }
    }

    private fun conquerNation(attackerNationName: String, defenderNationName: String, attacker: Player) {
        val attackerNation = nations[attackerNationName] ?: return
        val defenderNation = nations[defenderNationName] ?: return

        // 전쟁 랭크 점수 지급 (국가 데이터가 사라지기 전에)
        plugin.warScoreManager.settleConquest(attackerNationName, defenderNationName)
        plugin.nationStorageManager.absorb(attackerNationName, defenderNationName, attacker.location)

        // 전쟁 기록 (국가 데이터가 사라지기 전에 기록)
        addRecord("CONQUER", attackerNationName, defenderNationName,
            "영토 ${defenderNation.claims.size}개, 금고 ${defenderNation.bank}원 흡수")

        defenderNation.claims.forEach { chunkKey ->
            Nation.chunkClaims[chunkKey] = attackerNationName
        }
        attackerNation.claims.addAll(defenderNation.claims)
        attackerNation.bank += defenderNation.bank

        defenderNation.members.forEach { playerNations.remove(it) }

        BlueMapBridge.removeNationMarker(defenderNationName)
        BlueMapBridge.removeTerritory(defenderNationName)
        BlueMapBridge.updateTerritory(attackerNation, attacker.world.name)

        nationBeacons[defenderNationName]?.block?.type = Material.AIR
        nationBeacons.remove(defenderNationName)
        nationSpawns.remove(defenderNationName)
        nations.remove(defenderNationName)
        removeWarData(defenderNationName, "국가 멸망으로 종료", attackerNationName)

        Bukkit.broadcastMessage("${ChatColor.RED}⚔ '$defenderNationName' 국가가 '$attackerNationName' 국가에 의해 점령 및 멸망했습니다!")
        attacker.sendMessage("${ChatColor.GOLD}${defenderNation.claims.size}개의 영토와 금고 잔액 ${defenderNation.bank}원을 모두 흡수했습니다!")

        saveNations()
    }

    // ───────────────────────── 저장 / 로드 ─────────────────────────

    private fun saveNations() {
        val config = YamlConfiguration()
        nations.forEach { (name, nation) ->
            config.set("$name.leader", nation.leader.toString())
            config.set("$name.members", nation.members.map { it.toString() })
            config.set("$name.claims", nation.claims)
            config.set("$name.bank", nation.bank)
            config.set("$name.level", nation.level)
            nationBeacons[name]?.let { config.set("$name.beacon", "${it.world?.name},${it.x},${it.y},${it.z}") }
            nationSpawns[name]?.let { config.set("$name.spawn", "${it.world?.name},${it.x},${it.y},${it.z},${it.yaw},${it.pitch}") }
        }
        try { config.save(nationsFile) } catch (e: IOException) { e.printStackTrace() }
        saveWars()
    }

    private fun saveWars() {
        if (!::warsFile.isInitialized) return
        val config = YamlConfiguration()
        config.set("wars", activeWars.toList())
        config.set("war-starts", warStarts.map { "${it.key}|${it.value}" })
        config.set("log", warLog.map { "${it.time}|${it.type}|${it.a}|${it.b}|${it.detail}" })
        try { config.save(warsFile) } catch (e: IOException) { e.printStackTrace() }
    }

    private fun loadWars() {
        activeWars.clear()
        warLog.clear()
        if (!warsFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(warsFile)

        config.getStringList("wars").forEach { key ->
            val parts = key.split("|")
            if (parts.size == 2 && nations.containsKey(parts[0]) && nations.containsKey(parts[1])) {
                activeWars.add(key)
            }
        }

        // 시작 시각 기록이 없는 기존 전쟁은 지금부터 3일
        val now = System.currentTimeMillis()
        config.getStringList("war-starts").forEach { line ->
            val p = line.split("|")
            val time = p.getOrNull(2)?.toLongOrNull() ?: return@forEach
            warStarts["${p[0]}|${p[1]}"] = time
        }
        warStarts.keys.retainAll(activeWars)
        activeWars.forEach { warStarts.putIfAbsent(it, now) }

        config.getStringList("log").forEach { line ->
            val p = line.split("|", limit = 5)
            val time = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
            if (p.size >= 4) {
                warLog.add(WarRecord(time, p[1], p[2], p[3], p.getOrElse(4) { "" }))
            }
        }
    }

    private fun loadNations() {
        val config = nationsConfig ?: return
        nations.clear()
        playerNations.clear()
        Nation.chunkClaims.clear()

        config.getKeys(false).forEach { name ->
            val leader = UUID.fromString(config.getString("$name.leader")!!)
            val members = config.getStringList("$name.members").map { UUID.fromString(it) }.toMutableList()
            val claims = config.getStringList("$name.claims").toMutableList()
            val bank = config.getDouble("$name.bank", 0.0)
            val level = config.getInt("$name.level", 1)

            val nationObj = Nations(name, leader, members, claims, bank, level)
            nations[name] = nationObj
            members.forEach { playerNations[it] = name }
            playerNations[leader] = name
            claims.forEach { Nation.chunkClaims[it] = name }

            config.getString("$name.beacon")?.split(",")?.let {
                val world = Bukkit.getWorld(it[0])
                if (world != null) {
                    val loc = Location(world, it[1].toDouble(), it[2].toDouble(), it[3].toDouble())
                    nationBeacons[name] = loc
                    BlueMapBridge.addNationMarker(name, loc)
                    BlueMapBridge.updateTerritory(nationObj, world.name)
                }
            }
            config.getString("$name.spawn")?.split(",")?.let {
                val world = Bukkit.getWorld(it[0])
                if (world != null && it.size >= 6) {
                    val loc = Location(world, it[1].toDouble(), it[2].toDouble(), it[3].toDouble())
                    loc.yaw = it[4].toFloat(); loc.pitch = it[5].toFloat()
                    nationSpawns[name] = loc
                }
            }
        }
    }
}