package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.BlueMapBridge
import kr.maeshil.digriss.nation.Nation
import kr.maeshil.digriss.nation.NationMenu
import kr.maeshil.digriss.nation.NationMenuHolder
import kr.maeshil.digriss.nation.NationTerritory
import kr.maeshil.digriss.nation.NationWar
import kr.maeshil.digriss.nation.Nations
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
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.io.IOException
import java.util.*

// 국가 핵심: 건국 / 영토 / 금고 / 레벨 / 스폰 / 초대 / 해체 / 점령 / 저장
// 전쟁은 NationWar, /국가 메뉴는 NationMenu, 영토 표시·레벨 효과는 NationTerritory
class NationManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val nations = Nation.nations
    private val playerNations = mutableMapOf<UUID, String>()
    private val nationInvites = mutableMapOf<UUID, String>()
    private val nationBeacons = mutableMapOf<String, Location>()
    private val nationSpawns = mutableMapOf<String, Location>()
    private val pendingCreate = mutableSetOf<UUID>()
    private val teleporting = mutableSetOf<UUID>()

    val war = NationWar(plugin, this)
    private val menu = NationMenu(plugin, this)
    private val territory = NationTerritory(plugin, this)

    private lateinit var nationsFile: File
    private var nationsConfig: YamlConfiguration? = null

    private var econ: Economy? = null
    private var lastTaxDay = -1

    // ───────────────────────── 활성화 / 비활성화 ─────────────────────────

    fun enable() {
        if (!setupEconomy()) {
            plugin.logger.warning("Vault 또는 경제 플러그인을 찾을 수 없습니다! 금고 및 유지비 기능이 제한될 수 있습니다.")
        }

        nationsFile = File(plugin.dataFolder, "nation.yml")
        if (!nationsFile.exists()) {
            nationsFile.parentFile.mkdirs()
            try { nationsFile.createNewFile() } catch (e: IOException) { e.printStackTrace() }
        }
        nationsConfig = YamlConfiguration.loadConfiguration(nationsFile)
        loadNations()
        BlueMapBridge.onReady { redrawMap() }
        war.load(File(plugin.dataFolder, "war.yml"))

        plugin.getCommand("국가")?.setExecutor(this)
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.pluginManager.registerEvents(menu, plugin)

        plugin.server.scheduler.runTaskTimer(plugin, Runnable { saveNations() }, 6000L, 6000L)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { war.checkExpiry() }, 1200L, 1200L) // 1분마다
        startDailyTaxTask()
        territory.start()

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
        war.resetAll()

        // nation.yml, war.yml 비우기
        saveNations()
    }

    private fun setupEconomy(): Boolean {
        if (plugin.server.pluginManager.getPlugin("Vault") == null) return false
        val rsp = plugin.server.servicesManager.getRegistration(Economy::class.java) ?: return false
        econ = rsp.provider
        return econ != null
    }

    // ───────────────────────── 다른 기능에서 쓰는 조회 ─────────────────────────

    fun getNationName(uuid: UUID): String? = playerNations[uuid]

    fun isAtWarBetween(a: String, b: String) = war.isAtWar(a, b)

    // 전쟁 남은 시간(ms), 전쟁 중이 아니면 null
    fun warRemainingMs(a: String, b: String): Long? = war.remainingMs(a, b)

    // 해당 위치를 점령한 국가 (없으면 null)
    fun territoryOwnerAt(location: Location): String? = Nation.chunkClaims[chunkKeyOf(location)]

    // 플레이어 국가가 전쟁 중인 상대 국가 목록 (스코어보드 표시용)
    fun warsOfPlayer(uuid: UUID): List<String> {
        val myNation = playerNations[uuid] ?: return emptyList()
        return war.warsOf(myNation)
    }

    fun territoryText(player: Player): String = territory.territoryText(player)

    fun beaconOf(nationName: String): Location? = nationBeacons[nationName]

    fun inviteOf(uuid: UUID): String? = nationInvites[uuid]

    fun chunkKeyOf(loc: Location): String {
        val chunk = loc.chunk
        return "${chunk.world.name},${chunk.x},${chunk.z}"
    }

    fun notifyNation(name: String, message: String) {
        nations[name]?.members?.forEach { Bukkit.getPlayer(it)?.sendMessage(message) }
    }

    fun later(task: () -> Unit) {
        plugin.server.scheduler.runTask(plugin, Runnable { task() })
    }

    // ───────────────────────── 비용 (기존의 2배) ─────────────────────────

    fun dailyTax(level: Int) = (50.0 + (level - 1) * 50.0) * 2
    fun upgradeCost(level: Int) = (100.0 + (level - 1) * 50.0) * 2

    fun teleportDelaySeconds(level: Int) = if (level >= 2) 3 else 6

    fun levelEffectMessage(level: Int): String {
        return when (level) {
            2 -> "스폰 이동 대기시간 6초→3초, 영토 내 신속 I"
            3 -> "국가 신호기 주변 30블록 내 재생 I"
            4 -> "국가 영토 내 저항 I"
            5 -> "영토 내 성급(채광속도) I"
            else -> "기본 국가"
        }
    }

    // ───────────────────────── 명령어 → GUI ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) return true
        menu.openMenu(sender)
        return true
    }

    // ───────────────────────── 채팅으로 국가 이름 입력 ─────────────────────────

    fun startCreate(player: Player) {
        pendingCreate.add(player.uniqueId)
    }

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
        territory.forget(event.player.uniqueId)
    }

    // ───────────────────────── 국가 로직 ─────────────────────────

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

    fun claimChunk(player: Player) {
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

    fun setNationBeacon(player: Player) {
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

    fun invitePlayer(player: Player, target: Player) {
        val nationName = playerNations[player.uniqueId] ?: return

        if (playerNations.containsKey(target.uniqueId)) {
            return player.sendMessage("${ChatColor.RED}${target.name} 님은 이미 다른 국가에 소속되어 있습니다.")
        }

        nationInvites[target.uniqueId] = nationName
        target.sendMessage("${ChatColor.GOLD}'$nationName' 국가에서 초대가 도착했습니다.")
        target.sendMessage("${ChatColor.YELLOW}/국가 를 입력해 GUI에서 수락하세요.")
        player.sendMessage("${ChatColor.GREEN}${target.name} 님에게 국가 초대를 보냈습니다.")
    }

    fun acceptInvite(player: Player) {
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

    fun depositBank(player: Player, amount: Double) {
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

    fun upgradeNation(player: Player) {
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
        player.sendMessage("${ChatColor.AQUA}해금된 효과: ${levelEffectMessage(nation.level)}")

        nation.members.forEach { uuid -> Bukkit.getPlayer(uuid)?.let { territory.applyLevelEffects(it) } }

        saveNations()
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

    fun setNationSpawn(player: Player) {
        val nationName = playerNations[player.uniqueId] ?: return
        if (nations[nationName]?.leader != player.uniqueId) return player.sendMessage("${ChatColor.RED}지도자만 사용할 수 있습니다.")
        nationSpawns[nationName] = player.location
        player.sendMessage("${ChatColor.GREEN}국가 스폰 지점을 현재 위치로 설정했습니다.")
        saveNations()
    }

    fun centerTP(player: Player) {
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

    fun dissolveNation(player: Player) {
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
        war.removeNation(nationName, "국가 해체로 종료")

        BlueMapBridge.removeNationMarker(nationName)
        BlueMapBridge.removeTerritory(nationName)

        player.sendMessage("${ChatColor.RED}국가가 해체되었습니다.")
        saveNations()
    }

    fun leaveNation(player: Player) {
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
            !war.isAtWar(breakerNation, defenderName) -> {
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
        war.addRecord("CONQUER", attackerNationName, defenderNationName,
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
        war.removeNation(defenderNationName, "국가 멸망으로 종료", attackerNationName)

        Bukkit.broadcastMessage("${ChatColor.RED}⚔ '$defenderNationName' 국가가 '$attackerNationName' 국가에 의해 점령 및 멸망했습니다!")
        attacker.sendMessage("${ChatColor.GOLD}${defenderNation.claims.size}개의 영토와 금고 잔액 ${defenderNation.bank}원을 모두 흡수했습니다!")

        saveNations()
    }

    // BlueMap 지도에 모든 국가 신호기·영토를 다시 표시
    private fun redrawMap() {
        nations.values.forEach { nation ->
            nationBeacons[nation.name]?.let { BlueMapBridge.addNationMarker(nation.name, it) }
            nation.claims.map { it.substringBefore(',') }.distinct().forEach { world ->
                BlueMapBridge.updateTerritory(nation, world)
            }
        }
    }

    // ───────────────────────── 저장 / 로드 ─────────────────────────

    fun saveNations() {
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
        war.save()
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
