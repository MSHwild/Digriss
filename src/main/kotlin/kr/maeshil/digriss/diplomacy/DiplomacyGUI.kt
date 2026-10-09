package kr.maeshil.digriss.diplomacy

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.alliance.AllianceGUI
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.AsyncPlayerChatEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.util.UUID

// slotNations: 슬롯 번호 -> 그 칸에 표시된 국가 이름
class DiplomacyListHolder(val slotNations: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

class DiplomacyDetailHolder(val target: String) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /외교 [국가]: 국가 목록 → 국가별 외교 창 (불가침 조약 · 무역 협정 · 조공 · 원조)
class DiplomacyGUI(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    private enum class Input { TRIBUTE, AID }

    // 채팅으로 금액을 입력받는 중인 사람 -> (무엇, 상대 국가)
    private val pendingInput = mutableMapOf<UUID, Pair<Input, String>>()

    private val dip get() = plugin.diplomacyManager

    companion object {
        private const val SLOT_PACT = 10
        private const val SLOT_TRADE = 12
        private const val SLOT_TRIBUTE = 14
        private const val SLOT_AID = 16
        private const val SLOT_DETAIL_BACK = 18
        private const val SLOT_DETAIL_ALLIANCE = 22
        private const val SLOT_LIST_BACK = 45
        private const val SLOT_LIST_ALLIANCE = 48
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        val target = args.getOrNull(0)
        if (target != null && Nation.nations[target] != null) openDetail(player, target) else openList(player)
        Sounds.open(player)
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1) Nation.nations.keys.filter { it.startsWith(args[0]) } else emptyList()

    // ───────────────────────── 국가 목록 ─────────────────────────

    fun openList(player: Player) {
        val me = plugin.nationManager.getNationName(player.uniqueId) ?: return deny(player, "§c소속된 국가가 없습니다.")

        // 받은 제안 → 관계 있음 → 나머지 순, 최대 45개국
        val others = Nation.nations.keys.filter { it != me }
            .sortedWith(compareBy({ !hasIncoming(me, it) }, { relationLines(me, it).isEmpty() }, { it }))
            .take(45)
        val slotNations = others.withIndex().associate { it.index to it.value }
        val inv = Bukkit.createInventory(DiplomacyListHolder(slotNations), 54, "§8외교 §7- $me")

        slotNations.forEach { (slot, name) ->
            val n = Nation.nations[name] ?: return@forEach
            val relations = relationLines(me, name)
            val incoming = hasIncoming(me, name)
            val (key, default) = when {
                incoming -> "diplomacy.request" to Material.BELL
                plugin.nationManager.isAtWarBetween(me, name) -> "diplomacy.at_war" to Material.RED_BANNER
                relations.isNotEmpty() -> "diplomacy.friendly" to Material.LIME_BANNER
                else -> "diplomacy.neutral" to Material.WHITE_BANNER
            }
            val lore = mutableListOf("§7국가원 §f${n.members.size}명 §8| §7영토 §f${n.claims.size}개 §8| §7Lv.${n.level}", "")
            if (relations.isEmpty()) lore += "§8맺은 관계 없음" else lore += relations
            if (incoming) lore += listOf("", "§e§l받은 외교 제안이 있습니다!")
            lore += listOf("", "§e클릭: 외교 창 열기")
            inv.setItem(slot, item(icon(key, default), "${if (incoming) "§e" else "§f"}§l$name", lore))
        }

        val filler = item(icon("common.filler_dark", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(SLOT_LIST_BACK, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        inv.setItem(SLOT_LIST_ALLIANCE, item(icon("diplomacy.alliance", Material.LIGHT_BLUE_BANNER), "§b§l연합 관리",
            listOf("§7연합은 /연합 에서 따로 관리해요", "", "§e클릭: 연합 창 열기")))
        inv.setItem(49, item(icon("diplomacy.info", Material.BOOK), "§6§l외교 안내", infoLore(me)))
        player.openInventory(inv)
    }

    private fun infoLore(me: String): List<String> {
        val pacts = dip.pactPartnersOf(me)
        val trades = dip.tradePartnersOf(me)
        return listOf(
            "§e불가침 조약§7: 서로 전쟁을 선포할 수 없어요.",
            "§7  파기하면 §f${kr.maeshil.digriss.manager.DiplomacyManager.PACT_BREAK_HOURS}시간 뒤§7에 효력이 끝나요.",
            "§6무역 협정§7: 매일 자정 양쪽 금고 §f+${dip.fmt(kr.maeshil.digriss.manager.DiplomacyManager.TRADE_DAILY_MONEY)}원§7, 내실 §f+${kr.maeshil.digriss.manager.DiplomacyManager.TRADE_DAILY_PEACE.toInt()}",
            "§7  서로 거래소 수수료 면제 (최대 ${kr.maeshil.digriss.manager.DiplomacyManager.MAX_TRADE_AGREEMENTS}개국)",
            "§6조공§7: 매일 자정 정한 금액을 상대 금고로 바쳐요.",
            "§a원조§7: 우리 금고 돈을 상대 금고로 한 번 보내요.",
            "§8전쟁이 시작되면 무역 협정과 조공은 끝나요.",
            "",
            "§7불가침 조약 (${pacts.size}): §f${pacts.ifEmpty { listOf("없음") }.joinToString(", ")}",
            "§7무역 협정 (${trades.size}/${kr.maeshil.digriss.manager.DiplomacyManager.MAX_TRADE_AGREEMENTS}): §f${trades.ifEmpty { listOf("없음") }.joinToString(", ")}"
        )
    }

    private fun hasIncoming(me: String, other: String) =
        dip.hasPactRequest(me, other) || dip.hasTradeRequest(me, other) || dip.tributeOffer(other, me) != null

    // 두 국가 사이의 관계를 한 줄씩
    private fun relationLines(me: String, other: String): List<String> {
        val lines = mutableListOf<String>()
        if (plugin.nationManager.isAtWarBetween(me, other)) lines += "§c⚔ 전쟁 중"
        if (plugin.allianceManager.areAllied(me, other)) lines += "§b● 연합"
        if (dip.hasPact(me, other)) {
            val remain = dip.pactBreakRemainingMs(me, other)
            lines += if (remain == null) "§e● 불가침 조약" else "§e● 불가침 조약 §c(파기 예정: ${hours(remain)})"
        }
        if (dip.hasTrade(me, other)) lines += "§6● 무역 협정"
        dip.tributeAmount(me, other)?.let { lines += "§6● 조공 바치는 중 §7(매일 ${dip.fmt(it)}원)" }
        dip.tributeAmount(other, me)?.let { lines += "§6● 조공 받는 중 §7(매일 ${dip.fmt(it)}원)" }
        return lines
    }

    // ───────────────────────── 국가별 외교 창 ─────────────────────────

    fun openDetail(player: Player, target: String) {
        val me = plugin.nationManager.getNationName(player.uniqueId) ?: return deny(player, "§c소속된 국가가 없습니다.")
        if (me == target || Nation.nations[target] == null) return openList(player)
        val isLeader = Nation.nations[me]?.leader == player.uniqueId
        val atWar = plugin.nationManager.isAtWarBetween(me, target)
        val inv = Bukkit.createInventory(DiplomacyDetailHolder(target), 27, "§8외교 §7- $target")

        val filler = item(icon("common.filler_dark", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until 27) inv.setItem(i, filler)

        val n = Nation.nations[target]!!
        inv.setItem(4, item(icon("diplomacy.nation", Material.BEACON), "§f§l$target",
            listOf("§7국가원 §f${n.members.size}명 §8| §7영토 §f${n.claims.size}개 §8| §7Lv.${n.level}", "") +
                relationLines(me, target).ifEmpty { listOf("§8맺은 관계 없음") }))

        // 지도자가 아니면 조작 안내 대신 이 줄을 보여줌
        fun act(vararg lines: String): List<String> = if (isLeader) lines.toList() else listOf("§8국가 지도자만 외교를 할 수 있습니다.")

        // 불가침 조약
        val pactRemain = dip.pactBreakRemainingMs(me, target)
        inv.setItem(SLOT_PACT, when {
            dip.hasPact(me, target) && pactRemain != null -> item(icon("diplomacy.pact_breaking", Material.ORANGE_BANNER), "§6§l불가침 조약 §7(파기 예정)",
                listOf("§c${hours(pactRemain)} 뒤 효력이 끝납니다.", "§7그때까지는 서로 전쟁을 선포할 수 없어요."))
            dip.hasPact(me, target) -> item(icon("diplomacy.pact", Material.YELLOW_BANNER), "§e§l불가침 조약 §7(맺는 중)",
                listOf("§7서로 전쟁을 선포할 수 없습니다.", "") + act("§c쉬프트+클릭: 파기 선언", "§8(${kr.maeshil.digriss.manager.DiplomacyManager.PACT_BREAK_HOURS}시간 뒤 효력 종료)"))
            dip.hasPactRequest(me, target) -> item(icon("diplomacy.request", Material.BELL), "§e§l불가침 조약 §7(제안 받음)",
                listOf("§e'$target' 국가가 불가침 조약을 제안했습니다!", "") + act("§a좌클릭: 수락 §8| §c우클릭: 거절"))
            dip.hasPactRequest(target, me) -> item(icon("diplomacy.pending", Material.GRAY_BANNER), "§7§l불가침 조약 §7(제안 보냄)",
                listOf("§7상대의 수락을 기다리는 중입니다.", "") + act("§c우클릭: 제안 취소"))
            atWar -> item(icon("diplomacy.at_war", Material.RED_BANNER), "§4§l불가침 조약", listOf("§c전쟁 중에는 조약을 맺을 수 없습니다."))
            else -> item(icon("diplomacy.pact_none", Material.WHITE_BANNER), "§f§l불가침 조약",
                listOf("§7맺으면 서로 전쟁을 선포할 수 없어요.", "§7파기하면 ${kr.maeshil.digriss.manager.DiplomacyManager.PACT_BREAK_HOURS}시간 뒤에 효력이 끝나요.", "") + act("§a클릭: 조약 제안"))
        })

        // 무역 협정
        inv.setItem(SLOT_TRADE, when {
            dip.hasTrade(me, target) -> item(icon("diplomacy.trade", Material.EMERALD_BLOCK), "§6§l무역 협정 §7(맺는 중)",
                listOf(tradeBenefit(), "") + act("§c쉬프트+클릭: 협정 종료"))
            dip.hasTradeRequest(me, target) -> item(icon("diplomacy.request", Material.BELL), "§6§l무역 협정 §7(제안 받음)",
                listOf("§e'$target' 국가가 무역 협정을 제안했습니다!", tradeBenefit(), "") + act("§a좌클릭: 수락 §8| §c우클릭: 거절"))
            dip.hasTradeRequest(target, me) -> item(icon("diplomacy.pending", Material.GRAY_BANNER), "§7§l무역 협정 §7(제안 보냄)",
                listOf("§7상대의 수락을 기다리는 중입니다.", "") + act("§c우클릭: 제안 취소"))
            atWar -> item(icon("diplomacy.at_war", Material.RED_BANNER), "§4§l무역 협정", listOf("§c전쟁 중에는 협정을 맺을 수 없습니다."))
            else -> item(icon("diplomacy.trade_none", Material.EMERALD), "§f§l무역 협정",
                listOf(tradeBenefit(), "§7국가당 최대 ${kr.maeshil.digriss.manager.DiplomacyManager.MAX_TRADE_AGREEMENTS}개국", "") + act("§a클릭: 협정 제안"))
        })

        // 조공
        val paying = dip.tributeAmount(me, target)
        val receiving = dip.tributeAmount(target, me)
        val offerIn = dip.tributeOffer(target, me)
        val offerOut = dip.tributeOffer(me, target)
        inv.setItem(SLOT_TRIBUTE, when {
            paying != null -> item(icon("diplomacy.tribute", Material.GOLD_BLOCK), "§6§l조공 §7(바치는 중)",
                listOf("§7매일 자정 우리 금고에서 §6${dip.fmt(paying)}원§7을 바칩니다.", "") + act("§c쉬프트+클릭: 조공 중단 §8(전체 공지)"))
            receiving != null -> item(icon("diplomacy.tribute", Material.GOLD_BLOCK), "§6§l조공 §7(받는 중)",
                listOf("§7매일 자정 '$target' 국가가 §6${dip.fmt(receiving)}원§7을 바칩니다.", "") + act("§c쉬프트+클릭: 더 이상 받지 않기"))
            offerIn != null -> item(icon("diplomacy.request", Material.BELL), "§6§l조공 §7(제안 받음)",
                listOf("§e'$target' 국가가 매일 §6${dip.fmt(offerIn)}원§e을 바치겠다고 합니다!", "") + act("§a좌클릭: 수락 §8| §c우클릭: 거절"))
            offerOut != null -> item(icon("diplomacy.pending", Material.GRAY_BANNER), "§7§l조공 §7(제안 보냄)",
                listOf("§7매일 ${dip.fmt(offerOut)}원을 바치겠다고 제안했습니다.", "§7상대의 수락을 기다리는 중입니다.", "") + act("§c우클릭: 제안 취소"))
            else -> item(icon("diplomacy.tribute_none", Material.GOLD_INGOT), "§f§l조공",
                listOf("§7매일 자정 정한 금액을 상대 금고로 바쳐요.", "§7전쟁을 피하거나 보호를 받을 때 써 보세요.", "§7어느 쪽이든 언제든 중단할 수 있어요.", "") + act("§a클릭: 조공 제안 §8(금액은 채팅으로 입력)"))
        })

        // 원조
        inv.setItem(SLOT_AID, item(icon("diplomacy.aid", Material.CHEST_MINECART), "§a§l원조 보내기",
            listOf("§7우리 국가 금고 돈을 '$target' 국가 금고로", "§7한 번 보냅니다. §8(우리 금고: ${dip.fmt(Nation.nations[me]?.bank ?: 0.0)}원)", "") +
                (if (atWar) listOf("§c전쟁 중인 국가에는 보낼 수 없습니다.") else act("§a클릭: 금액 입력 §8(채팅)"))))

        inv.setItem(SLOT_DETAIL_BACK, item(icon("common.back", Material.ARROW), "§7← 외교 목록으로", emptyList()))
        inv.setItem(SLOT_DETAIL_ALLIANCE, item(icon("diplomacy.alliance", Material.LIGHT_BLUE_BANNER),
            if (plugin.allianceManager.areAllied(me, target)) "§b§l연합 §7(연합 중)" else "§f§l연합",
            listOf("§7연합은 연합 창에서 관리해요", "", "§e클릭: 연합 창 열기")))
        player.openInventory(inv)
    }

    private fun tradeBenefit() =
        "§7매일 양쪽 금고 §f+${dip.fmt(kr.maeshil.digriss.manager.DiplomacyManager.TRADE_DAILY_MONEY)}원§7 · 서로 거래소 수수료 면제"

    // ───────────────────────── 클릭 ─────────────────────────

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder
        if (holder !is DiplomacyListHolder && holder !is DiplomacyDetailHolder) return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return

        if (holder is DiplomacyListHolder) {
            when (e.rawSlot) {
                SLOT_LIST_BACK -> return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
                SLOT_LIST_ALLIANCE -> { Sounds.click(player); later(player) { AllianceGUI.open(player, plugin) }; return }
            }
            val target = holder.slotNations[e.rawSlot] ?: return
            Sounds.click(player)
            later(player) { openDetail(player, target) }
            return
        }

        val target = (holder as DiplomacyDetailHolder).target
        val me = plugin.nationManager.getNationName(player.uniqueId) ?: return
        val shift = e.isShiftClick
        val right = e.isRightClick
        when (e.rawSlot) {
            SLOT_DETAIL_BACK -> { Sounds.click(player); later(player) { openList(player) }; return }
            SLOT_DETAIL_ALLIANCE -> { Sounds.click(player); later(player) { AllianceGUI.open(player, plugin) }; return }
            SLOT_PACT -> when {
                dip.hasPact(me, target) -> if (shift && dip.pactBreakRemainingMs(me, target) == null) dip.breakPact(player, target) else return
                dip.hasPactRequest(me, target) -> if (right) dip.rejectPact(player, target) else dip.acceptPact(player, target)
                dip.hasPactRequest(target, me) -> if (right) dip.cancelPactRequest(player, target) else return
                else -> dip.proposePact(player, target)
            }
            SLOT_TRADE -> when {
                dip.hasTrade(me, target) -> if (shift) dip.endTrade(player, target) else return
                dip.hasTradeRequest(me, target) -> if (right) dip.rejectTrade(player, target) else dip.acceptTrade(player, target)
                dip.hasTradeRequest(target, me) -> if (right) dip.cancelTradeRequest(player, target) else return
                else -> dip.proposeTrade(player, target)
            }
            SLOT_TRIBUTE -> when {
                dip.tributeAmount(me, target) != null || dip.tributeAmount(target, me) != null ->
                    if (shift) dip.stopTribute(player, target) else return
                dip.tributeOffer(target, me) != null -> if (right) dip.rejectTribute(player, target) else dip.acceptTribute(player, target)
                dip.tributeOffer(me, target) != null -> if (right) dip.cancelTributeOffer(player, target) else return
                else -> return startInput(player, Input.TRIBUTE, target)
            }
            SLOT_AID -> return startInput(player, Input.AID, target)
            else -> return
        }
        Sounds.click(player)
        // 클릭 이벤트 안에서 바로 다시 열지 않고 다음 틱에 새로고침
        later(player) { openDetail(player, target) }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is DiplomacyListHolder || holder is DiplomacyDetailHolder) e.isCancelled = true
    }

    // ───────────────────────── 채팅으로 금액 입력 ─────────────────────────

    private fun startInput(player: Player, type: Input, target: String) {
        val me = plugin.nationManager.getNationName(player.uniqueId) ?: return
        if (Nation.nations[me]?.leader != player.uniqueId) return deny(player, "§c국가 지도자만 외교를 할 수 있습니다.")
        if (type == Input.AID && plugin.nationManager.isAtWarBetween(me, target)) return deny(player, "§c전쟁 중인 국가에는 원조를 보낼 수 없습니다.")
        pendingInput[player.uniqueId] = type to target
        Sounds.notify(player)
        later(player) { player.closeInventory() }
        player.sendMessage(when (type) {
            Input.TRIBUTE -> "§6'$target' 국가에 매일 바칠 조공 금액을 채팅으로 입력하세요. §7(취소: '취소' 입력)"
            Input.AID -> "§a'$target' 국가에 보낼 원조 금액을 채팅으로 입력하세요. §7(우리 금고: ${dip.fmt(Nation.nations[me]?.bank ?: 0.0)}원, 취소: '취소' 입력)"
        })
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onChat(e: AsyncPlayerChatEvent) {
        val (type, target) = pendingInput.remove(e.player.uniqueId) ?: return
        e.isCancelled = true
        val input = e.message.trim().replace(",", "")
        val player = e.player
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            if (input == "취소") return@Runnable player.sendMessage("§7입력을 취소했습니다.")
            val amount = input.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1 }
                ?: return@Runnable deny(player, "§c숫자로 입력하세요. 다시 하려면 /외교 $target")
            when (type) {
                Input.TRIBUTE -> dip.offerTribute(player, target, Math.floor(amount))
                Input.AID -> dip.sendAid(player, target, Math.floor(amount))
            }
        })
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        pendingInput.remove(e.player.uniqueId)
    }

    // ───────────────────────── 공통 ─────────────────────────

    private fun later(player: Player, task: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) task() })
    }

    private fun hours(ms: Long): String {
        val totalMin = ms / 60_000
        return if (totalMin >= 60) "${totalMin / 60}시간 ${totalMin % 60}분" else "${totalMin}분"
    }

    private fun deny(player: Player, message: String) {
        player.sendMessage(message)
        Sounds.fail(player)
    }

    private fun icon(key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(base: ItemStack, name: String, lore: List<String>): ItemStack {
        val meta = base.itemMeta ?: return base
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        base.itemMeta = meta
        return base
    }
}
