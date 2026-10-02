package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.alliance.AllianceGUI
import kr.maeshil.digriss.manager.NationManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.text.SimpleDateFormat
import java.util.Date

enum class MenuType { MAIN, NO_NATION, INVITE, CONFIRM_DISSOLVE, WAR, WAR_LOG }

class NationMenuHolder(val type: MenuType) : InventoryHolder {
    private lateinit var inv: Inventory
    val slotNations = mutableMapOf<Int, String>()
    fun setInventory(inventory: Inventory) { inv = inventory }
    override fun getInventory(): Inventory = inv
}

// /국가 GUI: 무소속 / 메인 / 전쟁 관리 / 전쟁 기록 / 초대 / 해체 확인
class NationMenu(private val plugin: Digriss, private val core: NationManager) : Listener {

    private val nations = Nation.nations
    private val war get() = core.war
    private val dateFormat = SimpleDateFormat("MM/dd HH:mm")

    fun openMenu(player: Player) {
        if (core.getNationName(player.uniqueId) != null) openMainMenu(player) else openNoNationMenu(player)
    }

    // ───────────────────────── GUI 유틸 ─────────────────────────

    private fun icon(key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(material: Material, name: String, vararg lore: String): ItemStack = item(ItemStack(material), name, *lore)

    private fun item(base: ItemStack, name: String, vararg lore: String): ItemStack {
        val stack = base
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName(name)
        meta.lore = lore.toList()
        stack.itemMeta = meta
        return stack
    }

    private fun fill(inv: Inventory) {
        val pane = item(icon("common.filler", Material.GRAY_STAINED_GLASS_PANE), " ")
        for (i in 0 until inv.size) inv.setItem(i, pane)
    }

    // ───────────────────────── 메뉴: 무소속 ─────────────────────────

    private fun openNoNationMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.NO_NATION)
        val inv = Bukkit.createInventory(holder, 27, "§8국가")
        holder.setInventory(inv)
        fill(inv)

        inv.setItem(11, item(icon("nation.create", Material.WHITE_BANNER), "§a§l국가 건국",
            "§7클릭 후 채팅으로 국가 이름을 입력하세요.",
            "§7현재 서 있는 곳에 신호기가 설치됩니다.",
            "",
            "§8이름: 2~12자, 한글/영문/숫자/_ 만 가능"))

        val invite = core.inviteOf(player.uniqueId)
        if (invite != null) {
            inv.setItem(15, item(icon("nation.invite_accept", Material.LIME_DYE), "§e§l초대 수락",
                "§f'$invite' §7국가에서 초대가 도착했습니다.",
                "",
                "§a클릭하여 가입"))
        } else {
            inv.setItem(15, item(icon("nation.invite_none", Material.GRAY_DYE), "§7받은 초대 없음"))
        }

        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 메인 ─────────────────────────

    private fun openMainMenu(player: Player) {
        val nationName = core.getNationName(player.uniqueId) ?: return openNoNationMenu(player)
        val nation = nations[nationName] ?: return
        val isLeader = nation.leader == player.uniqueId
        val leaderOnly = if (isLeader) "§a클릭하여 실행" else "§c지도자 전용"

        val holder = NationMenuHolder(MenuType.MAIN)
        val inv = Bukkit.createInventory(holder, 36, "§8국가 관리 - $nationName")
        holder.setInventory(inv)
        fill(inv)

        val leaderName = Bukkit.getOfflinePlayer(nation.leader).name ?: "알 수 없음"
        val enemies = war.warsOf(nationName)
        inv.setItem(4, item(icon("nation.info", Material.BEACON), "§6§l${nation.name} §7(Lv.${nation.level})",
            "§e지도자 §f$leaderName",
            "§e국가원 §f${nation.members.size}명",
            "§e영토 §f${nation.claims.size} / ${nation.members.size * 10} 청크",
            "§a금고 §f${nation.bank}원",
            "§b일일 유지비 §f${core.dailyTax(nation.level)}원 §7(매일 자정)",
            if (nation.level < 5) "§7다음 업그레이드 §f${core.upgradeCost(nation.level)}원" else "§7최고 레벨 도달",
            if (enemies.isEmpty()) "§7전쟁 중인 국가 §f없음"
            else "§c전쟁 중 §f" + enemies.joinToString(", ") { e ->
                val left = (war.remainingMs(nationName, e) ?: 0) / 60000
                "$e §7(${left / 60}시간 ${left % 60}분 남음)§f"
            }))

        inv.setItem(10, item(icon("nation.claim", Material.GRASS_BLOCK), "§a§l영토 점령",
            "§7현재 서 있는 청크를 국가 영토로 점령합니다.", "", leaderOnly))

        inv.setItem(12, item(icon("nation.bank", Material.GOLD_INGOT), "§e§l금고 입금",
            "§7좌클릭 §f100원",
            "§7우클릭 §f1,000원",
            "§7쉬프트+좌클릭 §f10,000원",
            "§7쉬프트+우클릭 §f100,000원"))

        inv.setItem(14, item(icon("nation.upgrade", Material.EXPERIENCE_BOTTLE), "§b§l국가 업그레이드",
            if (nation.level < 5) "§7필요 금액 §f${core.upgradeCost(nation.level)}원" else "§7이미 최고 레벨입니다.",
            if (nation.level < 5) "§7다음 효과: ${core.levelEffectMessage(nation.level + 1)}" else "",
            "", leaderOnly))

        inv.setItem(16, item(icon("nation.storage", Material.CHEST), "§6§l국가 창고",
            "§7국가원 모두가 함께 쓰는 창고입니다.",
            "§7크기 §f${9 * (nation.level + 2).coerceIn(3, 6)}칸 §7(국가 레벨이 오르면 커짐)",
            "", "§a클릭하여 열기"))

        inv.setItem(20, item(icon("nation.spawn_tp", Material.ENDER_PEARL), "§d§l국가 스폰 이동",
            "§7국가 스폰 지점으로 이동합니다.",
            "§7대기시간 §f${core.teleportDelaySeconds(nation.level)}초 §7(움직이면 취소)",
            "", "§a클릭하여 이동"))

        inv.setItem(22, item(icon("nation.spawn_set", Material.RED_BED), "§a§l스폰 설정",
            "§7현재 위치를 국가 스폰으로 설정합니다.", "", leaderOnly))

        inv.setItem(24, item(icon("nation.beacon_move", Material.LODESTONE), "§b§l신호기 이동",
            "§7현재 위치로 신호기를 옮깁니다.", "§7기존 신호기는 제거됩니다.", "", leaderOnly))

        inv.setItem(28, item(icon("nation.invite", Material.PLAYER_HEAD), "§a§l국가원 초대",
            "§7접속 중인 무소속 유저를 초대합니다.", "", "§a클릭하여 선택"))

        val incoming = war.warRequests[nationName]?.size ?: 0
        inv.setItem(30, item(icon("nation.war", Material.NETHERITE_SWORD), "§c§l전쟁 관리",
            "§7전쟁 선포 / 수락 / 휴전을 관리합니다.",
            "§7진행 중인 전쟁 §f${enemies.size}개",
            if (incoming > 0) "§e받은 전쟁 선포 §c${incoming}건!" else "§7받은 전쟁 선포 없음",
            "", "§a클릭하여 열기"))

        val allies = plugin.allianceManager.alliesOf(nationName)
        val allyRequests = Nation.nations.keys.count { plugin.allianceManager.hasRequest(nationName, it) }
        inv.setItem(32, item(icon("nation.alliance", Material.LIGHT_BLUE_BANNER), "§b§l연합 관리",
            "§7다른 국가와 연합을 맺거나 해제합니다.",
            "§7연합국 §f${if (allies.isEmpty()) "없음" else allies.joinToString(", ")}",
            if (allyRequests > 0) "§e받은 연합 요청 §b${allyRequests}건!" else "§7받은 연합 요청 없음",
            "", "§a클릭하여 열기"))

        if (isLeader) {
            inv.setItem(34, item(icon("nation.dissolve", Material.TNT), "§c§l국가 해체",
                "§7국가와 모든 영토가 사라집니다.", "", "§c클릭하여 진행"))
        } else {
            inv.setItem(34, item(icon("nation.leave", Material.OAK_DOOR), "§c§l국가 탈퇴",
                "§7현재 국가에서 탈퇴합니다.", "", "§c클릭하여 탈퇴"))
        }

        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 전쟁 관리 ─────────────────────────

    private fun warPriority(myName: String, target: String): Int = when {
        war.isAtWar(myName, target) -> 0
        war.warRequests[myName]?.contains(target) == true -> 1
        war.warRequests[target]?.contains(myName) == true -> 2
        else -> 3
    }

    private fun warItem(myName: String, target: String): ItemStack {
        val n = nations[target]!!
        val base = arrayOf("§7국가원 §f${n.members.size}명", "§7레벨 §fLv.${n.level}")
        val warRequests = war.warRequests
        val truceRequests = war.truceRequests

        return when {
            war.isAtWar(myName, target) -> when {
                truceRequests[myName]?.contains(target) == true ->
                    item(icon("war.truce_received", Material.LIME_BANNER), "§a§l$target §7(휴전 요청 받음)", *base, "",
                        "§a좌클릭: 휴전 수락 §7(지도자)", "§c우클릭: 거절 §7(지도자)")
                truceRequests[target]?.contains(myName) == true ->
                    item(icon("war.truce_sent", Material.YELLOW_BANNER), "§e§l$target §7(휴전 요청 보냄)", *base, "",
                        "§7상대의 응답을 기다리는 중", "§c우클릭: 요청 취소 §7(지도자)")
                else ->
                    item(icon("war.at_war", Material.NETHERITE_SWORD), "§4§l$target §c(전쟁 중)", *base, "",
                        "§7상대 신호기를 부수면 국가를 점령합니다.", "§e클릭: 휴전 요청 §7(지도자)")
            }
            warRequests[myName]?.contains(target) == true ->
                item(icon("war.declare_received", Material.RED_BANNER), "§c§l$target §7(전쟁 선포 받음)", *base, "",
                    "§a좌클릭: 수락 §7(국가원 누구나)", "§c우클릭: 거절")
            warRequests[target]?.contains(myName) == true ->
                item(icon("war.declare_sent", Material.YELLOW_BANNER), "§e§l$target §7(선포 대기 중)", *base, "",
                    "§7상대의 수락을 기다리는 중", "§c우클릭: 선포 취소 §7(지도자)")
            else ->
                item(icon("war.neutral", Material.WHITE_BANNER), "§f§l$target", *base, "",
                    "§c클릭: 전쟁 선포 §7(지도자)")
        }
    }

    private fun openWarMenu(player: Player) {
        val myName = core.getNationName(player.uniqueId) ?: return openNoNationMenu(player)

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
            inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§c다른 국가가 없습니다."))
        }

        inv.setItem(45, item(icon("war.log", Material.BOOK), "§e§l전쟁 기록",
            "§7최근 전쟁 시작 / 휴전 / 점령 기록을 봅니다.", "", "§a클릭하여 열기"))
        inv.setItem(49, item(icon("common.back", Material.ARROW), "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 전쟁 기록 ─────────────────────────

    private fun openWarLogMenu(player: Player) {
        val myName = core.getNationName(player.uniqueId)
        val warLog = war.warLog

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
            inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§7아직 전쟁 기록이 없습니다."))
        }

        if (myName != null) {
            val wars = warLog.count { it.type == "START" && (it.a == myName || it.b == myName) }
            val wins = warLog.count { it.type == "CONQUER" && it.a == myName }
            val truces = warLog.count { it.type == "TRUCE" && (it.a == myName || it.b == myName) }
            inv.setItem(45, item(icon("war.record", Material.WRITTEN_BOOK), "§6§l$myName §e전적",
                "§7전쟁 참여 §f${wars}회",
                "§7국가 점령 승리 §f${wins}회",
                "§7휴전 §f${truces}회",
                "",
                "§8최근 200개 기록 기준"))
        }

        inv.setItem(49, item(icon("common.back", Material.ARROW), "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 초대 대상 선택 ─────────────────────────

    private fun openInviteMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.INVITE)
        val inv = Bukkit.createInventory(holder, 54, "§8초대할 유저 선택")
        holder.setInventory(inv)

        val candidates = Bukkit.getOnlinePlayers()
            .filter { it.uniqueId != player.uniqueId && core.getNationName(it.uniqueId) == null }
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
            inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§c초대 가능한 유저가 없습니다."))
        }

        inv.setItem(49, item(icon("common.back", Material.ARROW), "§7뒤로가기"))
        player.openInventory(inv)
    }

    // ───────────────────────── 메뉴: 해체 확인 ─────────────────────────

    private fun openConfirmDissolveMenu(player: Player) {
        val holder = NationMenuHolder(MenuType.CONFIRM_DISSOLVE)
        val inv = Bukkit.createInventory(holder, 27, "§8정말 해체하시겠습니까?")
        holder.setInventory(inv)
        fill(inv)

        inv.setItem(11, item(icon("common.confirm", Material.LIME_CONCRETE), "§a§l해체 확인", "§7되돌릴 수 없습니다."))
        inv.setItem(15, item(icon("common.cancel", Material.RED_CONCRETE), "§c§l취소"))

        player.openInventory(inv)
    }

    // ───────────────────────── GUI 클릭 처리 ─────────────────────────

    private fun later(task: () -> Unit) = core.later(task)

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.view.topInventory.holder as? NationMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        val clicked = event.currentItem
        if (clicked != null && clicked.type != Material.GRAY_STAINED_GLASS_PANE && !clicked.type.isAir) Sounds.click(player)

        when (holder.type) {
            MenuType.NO_NATION -> when (event.slot) {
                11 -> {
                    player.closeInventory()
                    core.startCreate(player)
                    Sounds.notify(player)
                    player.sendMessage("${ChatColor.GOLD}건국할 국가 이름을 채팅으로 입력하세요. (취소: '취소' 입력)")
                }
                15 -> {
                    if (core.inviteOf(player.uniqueId) != null) {
                        core.acceptInvite(player)
                        later { openMenu(player) }
                    }
                }
            }

            MenuType.MAIN -> when (event.slot) {
                10 -> { player.closeInventory(); core.claimChunk(player) }
                12 -> {
                    val amount = when (event.click) {
                        ClickType.LEFT -> 100.0
                        ClickType.RIGHT -> 1000.0
                        ClickType.SHIFT_LEFT -> 10000.0
                        ClickType.SHIFT_RIGHT -> 100000.0
                        else -> return
                    }
                    core.depositBank(player, amount)
                    later { openMainMenu(player) }
                }
                14 -> { core.upgradeNation(player); later { openMainMenu(player) } }
                16 -> later { plugin.nationStorageManager.open(player) }
                20 -> { player.closeInventory(); core.centerTP(player) }
                22 -> { player.closeInventory(); core.setNationSpawn(player) }
                24 -> { player.closeInventory(); core.setNationBeacon(player) }
                28 -> later { openInviteMenu(player) }
                30 -> later { openWarMenu(player) }
                32 -> later { AllianceGUI.open(player, plugin) }
                34 -> {
                    val nation = core.getNationName(player.uniqueId)?.let { nations[it] } ?: return
                    if (nation.leader == player.uniqueId) {
                        later { openConfirmDissolveMenu(player) }
                    } else {
                        player.closeInventory()
                        core.leaveNation(player)
                    }
                }
            }

            MenuType.WAR -> {
                if (event.slot == 49) { later { openMainMenu(player) }; return }
                if (event.slot == 45) { later { openWarLogMenu(player) }; return }
                val target = holder.slotNations[event.slot] ?: return
                war.handleClick(player, target, event.click.isRightClick)
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
                    core.invitePlayer(player, target)
                }
                later { openInviteMenu(player) }
            }

            MenuType.CONFIRM_DISSOLVE -> when (event.slot) {
                11 -> { player.closeInventory(); core.dissolveNation(player) }
                15 -> later { openMainMenu(player) }
            }
        }
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.view.topInventory.holder is NationMenuHolder) event.isCancelled = true
    }
}
