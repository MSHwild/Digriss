package kr.maeshil.digriss.trade

import kr.maeshil.digriss.addon.GuiBg

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// 거래 창 하나에 두 사람이 각자 자기 창을 봄. 내 쪽 칸(왼쪽)에 넣은 아이템은 실제로 내 창에 들어 있고,
// 상대 창 오른쪽에는 그 복사본을 보여주기만 함 (상대는 만질 수 없음)
class TradeSession(val a: Player, val b: Player) {
    lateinit var invA: Inventory
    lateinit var invB: Inventory
    var moneyA = 0.0
    var moneyB = 0.0
    var readyA = false
    var readyB = false
    var finished = false

    fun isA(p: Player) = p.uniqueId == a.uniqueId
    fun other(p: Player) = if (isA(p)) b else a
    fun invOf(p: Player) = if (isA(p)) invA else invB
    fun moneyOf(p: Player) = if (isA(p)) moneyA else moneyB
    fun readyOf(p: Player) = if (isA(p)) readyA else readyB
    fun setMoney(p: Player, v: Double) { if (isA(p)) moneyA = v else moneyB = v }
    fun setReady(p: Player, v: Boolean) { if (isA(p)) readyA = v else readyB = v }
}

class TradeHolder(val session: TradeSession) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

class TradePickHolder(val slotPlayers: Map<Int, UUID>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /거래 <닉네임> : 1:1 아이템·돈 거래. 둘 다 "준비 완료"를 누르면 교환되고, 내용이 바뀌면 준비가 풀림
class TradeManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    companion object {
        private const val REQUEST_SECONDS = 60
        // 내 칸: 왼쪽 4줄 x 5행, 상대 칸: 오른쪽 4줄 x 5행 (가운데 줄은 구분선)
        val MY_SLOTS = (0 until 5).flatMap { r -> (0 until 4).map { c -> r * 9 + c } }
        val THEIR_SLOTS = MY_SLOTS.map { it + 5 }
        private val DIVIDER = (0 until 5).map { it * 9 + 4 }
        private const val SLOT_MONEY = 45
        private const val SLOT_READY = 46
        private const val SLOT_CANCEL = 49
        private const val SLOT_THEIR_READY = 52
        private const val SLOT_THEIR_MONEY = 53
        private const val MAX_MONEY = 100_000_000.0

        // 내 칸에서 허용하는 바닐라 동작 (나머지는 모두 막음)
        private val ALLOWED_IN_MY_SLOTS = setOf(
            InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME,
            InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR,
            InventoryAction.MOVE_TO_OTHER_INVENTORY, InventoryAction.HOTBAR_SWAP,
            InventoryAction.DROP_ALL_SLOT, InventoryAction.DROP_ONE_SLOT
        )
    }

    private val requests = mutableMapOf<UUID, MutableMap<UUID, Long>>()   // 받는 사람 -> (보낸 사람 -> 만료 시각)
    private val sessions = mutableMapOf<UUID, TradeSession>()             // 거래 중인 사람 -> 거래
    private val logFile = File(plugin.dataFolder, "trade.log")
    private val zone = ZoneId.of("Asia/Seoul")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun economy(): Economy? = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider

    /** 거래·거래소에 올릴 수 없는 아이템 (직업 아이템은 영혼으로 산 개인 아이템) */
    fun isTradeable(item: ItemStack?): Boolean {
        if (item == null || item.type.isAir) return true
        return plugin.jobManager.getJobFromItem(item) == null
    }

    // ───────────────────────── 명령어 ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        when (args.getOrNull(0)) {
            null -> openPicker(player)
            "수락" -> args.getOrNull(1)?.let { accept(player, it) } ?: player.sendMessage("§e/거래 수락 <닉네임>")
            "거절" -> args.getOrNull(1)?.let { reject(player, it) } ?: player.sendMessage("§e/거래 거절 <닉네임>")
            else -> {
                val target = Bukkit.getPlayerExact(args[0]) ?: return true.also { deny(player, "§c접속 중인 플레이어가 아닙니다.") }
                request(player, target)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val player = sender as? Player ?: return emptyList()
        return when (args.size) {
            1 -> (listOf("수락", "거절") + Bukkit.getOnlinePlayers().filter { it != player }.map { it.name }).filter { it.startsWith(args[0], true) }
            2 -> if (args[0] == "수락" || args[0] == "거절") requests[player.uniqueId].orEmpty().keys.mapNotNull { Bukkit.getPlayer(it)?.name }.filter { it.startsWith(args[1], true) } else emptyList()
            else -> emptyList()
        }
    }

    // ───────────────────────── 요청 / 수락 ─────────────────────────

    private fun canTrade(player: Player, other: Player): Boolean {
        if (player == other) { deny(player, "§c자기 자신과는 거래할 수 없습니다."); return false }
        if (sessions.containsKey(player.uniqueId)) { deny(player, "§c이미 거래 중입니다."); return false }
        if (sessions.containsKey(other.uniqueId)) { deny(player, "§c${other.name} 님은 다른 사람과 거래 중입니다."); return false }
        if (plugin.combatManager.isInCombat(player)) { deny(player, "§c전투 중에는 거래할 수 없습니다."); return false }
        if (plugin.combatManager.isInCombat(other)) { deny(player, "§c${other.name} 님이 전투 중이라 거래할 수 없습니다."); return false }
        return true
    }

    fun request(player: Player, target: Player) {
        if (!canTrade(player, target)) return
        // 상대도 나에게 요청해 둔 상태면 바로 시작
        if (hasRequest(player, target)) return start(target, player)

        requests.getOrPut(target.uniqueId) { mutableMapOf() }[player.uniqueId] = System.currentTimeMillis() + REQUEST_SECONDS * 1000L
        player.sendMessage("§a${target.name} 님에게 거래를 요청했습니다. §7(${REQUEST_SECONDS}초 안에 수락해야 해요)")
        Sounds.success(player)
        Sounds.notify(target)
        target.sendMessage(
            Component.text("${player.name} 님이 거래를 요청했습니다. ", NamedTextColor.YELLOW)
                .append(Component.text("[수락]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand("/거래 수락 ${player.name}")))
                .append(Component.text(" "))
                .append(Component.text("[거절]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand("/거래 거절 ${player.name}")))
        )
    }

    /** from이 player에게 보낸 요청이 아직 유효한지 */
    private fun hasRequest(player: Player, from: Player): Boolean {
        val expire = requests[player.uniqueId]?.get(from.uniqueId) ?: return false
        if (expire < System.currentTimeMillis()) {
            requests[player.uniqueId]?.remove(from.uniqueId)
            return false
        }
        return true
    }

    private fun accept(player: Player, fromName: String) {
        val from = Bukkit.getPlayerExact(fromName) ?: return deny(player, "§c접속 중인 플레이어가 아닙니다.")
        if (!hasRequest(player, from)) return deny(player, "§c${from.name} 님의 거래 요청이 없거나 만료되었습니다.")
        if (!canTrade(player, from)) return
        start(from, player)
    }

    private fun reject(player: Player, fromName: String) {
        val from = Bukkit.getPlayerExact(fromName)
        val removed = requests[player.uniqueId]?.entries?.removeIf { Bukkit.getOfflinePlayer(it.key).name.equals(fromName, true) } == true
        if (!removed) return
        player.sendMessage("§e$fromName 님의 거래 요청을 거절했습니다.")
        from?.sendMessage("§e${player.name} 님이 거래 요청을 거절했습니다.")
        from?.let { Sounds.fail(it) }
    }

    // ───────────────────────── 거래 창 ─────────────────────────

    private fun start(a: Player, b: Player) {
        requests[a.uniqueId]?.remove(b.uniqueId)
        requests[b.uniqueId]?.remove(a.uniqueId)
        val s = TradeSession(a, b)
        s.invA = GuiBg.createInventory(TradeHolder(s), 54, "§8거래: 나 §7↔ §8${b.name}")
        s.invB = GuiBg.createInventory(TradeHolder(s), 54, "§8거래: 나 §7↔ §8${a.name}")
        listOf(s.invA, s.invB).forEach { inv ->
            val divider = item(icon("trade.divider", Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
            DIVIDER.forEach { inv.setItem(it, divider) }
            val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
            (45 until 54).forEach { inv.setItem(it, filler) }
        }
        sessions[a.uniqueId] = s
        sessions[b.uniqueId] = s
        render(s, a); render(s, b)
        a.openInventory(s.invA)
        b.openInventory(s.invB)
        listOf(a, b).forEach { Sounds.open(it) }
    }

    // 상대가 넣은 아이템(복사본)과 아래 버튼들을 다시 그림. 내 칸은 실제 아이템이라 건드리지 않음
    private fun render(s: TradeSession, viewer: Player) {
        val inv = s.invOf(viewer)
        val other = s.other(viewer)
        val otherInv = s.invOf(other)
        MY_SLOTS.zip(THEIR_SLOTS).forEach { (mine, theirs) -> inv.setItem(theirs, otherInv.getItem(mine)?.clone()) }

        val myMoney = s.moneyOf(viewer)
        inv.setItem(SLOT_MONEY, item(icon("trade.money", Material.GOLD_INGOT), "§6내가 줄 돈: §f${fmt(myMoney)}원", listOf(
            "§7소지금: §f${fmt(economy()?.getBalance(viewer) ?: 0.0)}원", "",
            "§a좌클릭 +100 §8| §a우클릭 +1,000", "§c쉬프트+좌클릭 -100 §8| §c쉬프트+우클릭 -1,000", "§7휠클릭(또는 Q): 0원으로")))
        inv.setItem(SLOT_READY, if (s.readyOf(viewer))
            item(icon("trade.ready", Material.LIME_CONCRETE), "§a§l준비 완료", listOf("§7상대도 준비하면 거래가 이루어집니다.", "", "§e클릭: 준비 취소"))
        else
            item(icon("trade.not_ready", Material.RED_CONCRETE), "§c§l준비 안 됨", listOf("§7왼쪽 칸에 줄 아이템을 넣고", "§7준비 버튼을 누르세요.", "§8내용이 바뀌면 준비가 자동으로 풀려요.", "", "§e클릭: 준비 완료")))
        inv.setItem(SLOT_CANCEL, item(icon("common.cancel", Material.BARRIER), "§c거래 취소", listOf("§7넣은 아이템은 모두 돌려받습니다.")))
        inv.setItem(SLOT_THEIR_READY, if (s.readyOf(other))
            item(icon("trade.ready", Material.LIME_CONCRETE), "§a${other.name}: 준비 완료", emptyList())
        else
            item(icon("trade.not_ready", Material.RED_CONCRETE), "§c${other.name}: 준비 안 됨", emptyList()))
        inv.setItem(SLOT_THEIR_MONEY, item(icon("trade.their_money", Material.GOLD_NUGGET), "§6${other.name}이(가) 줄 돈: §f${fmt(s.moneyOf(other))}원", emptyList()))
    }

    // 누군가 제안을 바꾸면 둘 다 준비를 풀고 다시 그림 (몰래 바꿔치기 방지)
    private fun changed(s: TradeSession) {
        if (s.finished) return
        if (s.readyA || s.readyB) {
            listOf(s.a, s.b).forEach { it.sendMessage("§e거래 내용이 바뀌어 준비가 풀렸습니다. 다시 확인하세요.") }
        }
        s.readyA = false; s.readyB = false
        render(s, s.a); render(s, s.b)
    }

    // 아이템 칸이 바뀔 때: 준비는 지금 바로 풀고(같은 틱에 완료되는 것 방지), 화면은 바닐라 처리가 끝난 다음 틱에 다시 그림
    private fun changedNextTick(s: TradeSession) {
        val wasReady = s.readyA || s.readyB
        s.readyA = false; s.readyB = false
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (s.finished) return@Runnable
            if (wasReady) listOf(s.a, s.b).forEach { it.sendMessage("§e거래 내용이 바뀌어 준비가 풀렸습니다. 다시 확인하세요.") }
            render(s, s.a); render(s, s.b)
        })
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder
        if (holder is TradePickHolder) return onPickClick(e, holder)
        if (holder !is TradeHolder) return
        val s = holder.session
        val player = e.whoClicked as? Player ?: return
        if (s.finished) { e.isCancelled = true; return }
        // 더블클릭으로 모으기는 상대 칸 복사본까지 끌어올 수 있어 막음
        if (e.action == InventoryAction.COLLECT_TO_CURSOR || e.click == ClickType.SWAP_OFFHAND) { e.isCancelled = true; return }
        val top = e.view.topInventory
        val clicked = e.clickedInventory ?: return // 창 밖 클릭(아이템 버리기)은 그대로

        // 내 인벤토리: 쉬프트+클릭만 직접 처리 (바닐라는 상대 칸 빈자리에 넣어 버림)
        if (clicked != top) {
            if (!e.isShiftClick) return
            e.isCancelled = true
            val stack = e.currentItem ?: return
            if (stack.type.isAir) return
            if (!isTradeable(stack)) return deny(player, "§c직업 아이템은 거래할 수 없습니다.")
            val slot = MY_SLOTS.firstOrNull { top.getItem(it) == null } ?: return deny(player, "§c거래 칸이 가득 찼습니다.")
            top.setItem(slot, stack.clone())
            clicked.setItem(e.slot, null)
            changedNextTick(s)
            return
        }

        val raw = e.rawSlot
        if (raw in MY_SLOTS) {
            val incoming = when (e.action) {
                InventoryAction.PLACE_ALL, InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.SWAP_WITH_CURSOR -> e.cursor
                InventoryAction.HOTBAR_SWAP -> if (e.hotbarButton >= 0) player.inventory.getItem(e.hotbarButton) else null
                else -> null
            }
            if (e.action !in ALLOWED_IN_MY_SLOTS) { e.isCancelled = true; return }
            if (!isTradeable(incoming)) { e.isCancelled = true; return deny(player, "§c직업 아이템은 거래할 수 없습니다.") }
            changedNextTick(s)
            return
        }

        e.isCancelled = true
        when (raw) {
            SLOT_MONEY -> {
                val step = if (e.isRightClick) 1000.0 else 100.0
                val current = s.moneyOf(player)
                val next = when {
                    e.click == ClickType.MIDDLE || e.click == ClickType.DROP || e.click == ClickType.CONTROL_DROP -> 0.0
                    e.isShiftClick -> (current - step).coerceAtLeast(0.0)
                    else -> (current + step).coerceAtMost(MAX_MONEY)
                }
                val balance = economy()?.getBalance(player) ?: 0.0
                if (next > current && next > balance) return deny(player, "§c소지금이 부족합니다. (소지금: ${fmt(balance)}원)")
                if (next == current) return
                s.setMoney(player, next)
                Sounds.coin(player)
                changed(s)
            }
            SLOT_READY -> {
                val ready = !s.readyOf(player)
                s.setReady(player, ready)
                Sounds.click(player)
                render(s, s.a); render(s, s.b)
                if (ready) {
                    Sounds.notify(s.other(player))
                    if (s.readyA && s.readyB) Bukkit.getScheduler().runTask(plugin, Runnable { complete(s) })
                }
            }
            SLOT_CANCEL -> {
                Sounds.click(player)
                Bukkit.getScheduler().runTask(plugin, Runnable { cancel(s, "§c${player.name} 님이 거래를 취소했습니다.") })
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is TradePickHolder) { e.isCancelled = true; return }
        if (holder !is TradeHolder) return
        val s = holder.session
        val topSize = e.view.topInventory.size
        val topSlots = e.rawSlots.filter { it < topSize }
        if (s.finished || topSlots.any { it !in MY_SLOTS } || (topSlots.isNotEmpty() && !isTradeable(e.oldCursor))) {
            e.isCancelled = true
            return
        }
        if (topSlots.isNotEmpty()) changedNextTick(s)
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val s = (e.inventory.holder as? TradeHolder)?.session ?: return
        if (s.finished) return
        val player = e.player as? Player ?: return
        cancel(s, "§c${player.name} 님이 거래 창을 닫아 거래가 취소되었습니다.")
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val uuid = e.player.uniqueId
        requests.remove(uuid)
        requests.values.forEach { it.remove(uuid) }
        sessions[uuid]?.let { cancel(it, "§c${e.player.name} 님이 나가서 거래가 취소되었습니다.") }
    }

    // ───────────────────────── 완료 / 취소 ─────────────────────────

    private fun offered(inv: Inventory): List<ItemStack> =
        MY_SLOTS.mapNotNull { inv.getItem(it) }.filter { !it.type.isAir }

    private fun clearOffer(inv: Inventory) = MY_SLOTS.forEach { inv.setItem(it, null) }

    private fun complete(s: TradeSession) {
        if (s.finished || !s.readyA || !s.readyB) return
        if (!s.a.isOnline || !s.b.isOnline) return cancel(s, "§c상대가 없어 거래가 취소되었습니다.")
        val econ = economy()
        if ((s.moneyA > 0 || s.moneyB > 0) && econ == null) {
            s.readyA = false; s.readyB = false
            listOf(s.a, s.b).forEach { deny(it, "§c경제 플러그인이 연동되지 않아 돈은 거래할 수 없습니다.") }
            render(s, s.a); render(s, s.b)
            return
        }
        listOf(s.a to s.moneyA, s.b to s.moneyB).forEach { (p, money) ->
            if (money > 0 && econ?.has(p, money) != true) {
                s.readyA = false; s.readyB = false
                listOf(s.a, s.b).forEach { deny(it, "§c${p.name} 님의 소지금이 부족해 거래할 수 없습니다.") }
                render(s, s.a); render(s, s.b)
                return
            }
        }

        val itemsA = offered(s.invA).map { it.clone() }
        val itemsB = offered(s.invB).map { it.clone() }
        // 먼저 창에서 비우고 끝났다고 표시해야 창을 닫을 때 돌려주는 일이 생기지 않음
        s.finished = true
        clearOffer(s.invA); clearOffer(s.invB)
        sessions.remove(s.a.uniqueId); sessions.remove(s.b.uniqueId)

        if (econ != null) {
            if (s.moneyA > 0) { econ.withdrawPlayer(s.a, s.moneyA); econ.depositPlayer(s.b, s.moneyA) }
            if (s.moneyB > 0) { econ.withdrawPlayer(s.b, s.moneyB); econ.depositPlayer(s.a, s.moneyB) }
        }
        give(s.b, itemsA)
        give(s.a, itemsB)

        listOf(s.a, s.b).forEach {
            it.closeInventory()
            it.sendMessage("§a거래가 완료되었습니다!")
            Sounds.purchase(it)
        }
        log(s, itemsA, itemsB)
    }

    private fun cancel(s: TradeSession, message: String) {
        if (s.finished) return
        s.finished = true
        sessions.remove(s.a.uniqueId); sessions.remove(s.b.uniqueId)
        val itemsA = offered(s.invA).map { it.clone() }
        val itemsB = offered(s.invB).map { it.clone() }
        clearOffer(s.invA); clearOffer(s.invB)
        give(s.a, itemsA)
        give(s.b, itemsB)
        listOf(s.a, s.b).forEach { p ->
            p.sendMessage(message)
            Sounds.fail(p)
            // 아직 거래 창을 보고 있으면 닫음 (닫기 이벤트에서는 finished라 아무것도 안 함)
            if (p.isOnline && (p.openInventory.topInventory.holder as? TradeHolder)?.session == s) {
                // 서버가 꺼지는 중에는 작업을 예약할 수 없으므로 바로 닫음
                if (!plugin.isEnabled) p.closeInventory()
                else Bukkit.getScheduler().runTask(plugin, Runnable { if ((p.openInventory.topInventory.holder as? TradeHolder)?.session == s) p.closeInventory() })
            }
        }
    }

    /** 서버가 꺼질 때 진행 중인 거래를 모두 취소하고 아이템을 돌려줌 */
    fun cancelAll() {
        sessions.values.toSet().forEach { cancel(it, "§c서버가 다시 시작되어 거래가 취소되었습니다.") }
    }

    // 인벤토리에 넣고 남는 건 발밑에 떨어뜨림
    private fun give(player: Player, items: List<ItemStack>) {
        if (items.isEmpty()) return
        val leftover = player.inventory.addItem(*items.toTypedArray())
        if (leftover.isNotEmpty()) {
            leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
            player.sendMessage("§e인벤토리가 가득 차서 일부 아이템을 발밑에 떨어뜨렸습니다.")
        }
    }

    private fun log(s: TradeSession, itemsA: List<ItemStack>, itemsB: List<ItemStack>) {
        val time = ZonedDateTime.now(zone).format(timeFormat)
        fun summary(items: List<ItemStack>, money: Double): String {
            val parts = items.map { describe(it) } + (if (money > 0) listOf("${fmt(money)}원") else emptyList())
            return parts.ifEmpty { listOf("없음") }.joinToString(", ")
        }
        runCatching {
            logFile.appendText("$time | ${s.a.name} -> ${s.b.name}: ${summary(itemsA, s.moneyA)} | ${s.b.name} -> ${s.a.name}: ${summary(itemsB, s.moneyB)}\n")
        }.onFailure { plugin.logger.severe("[거래] trade.log 기록 실패: ${it.message}") }
    }

    fun describe(item: ItemStack): String {
        val name = item.itemMeta?.takeIf { it.hasDisplayName() }?.displayName?.replace(Regex("§."), "")
        return "${name ?: item.type.name} x${item.amount}"
    }

    // ───────────────────────── 거래 상대 고르기 (/거래) ─────────────────────────

    private fun openPicker(player: Player) {
        val others = Bukkit.getOnlinePlayers().filter { it != player }.sortedBy { it.name }.take(45)
        val slotPlayers = others.withIndex().associate { it.index to it.value.uniqueId }
        val inv = GuiBg.createInventory(TradePickHolder(slotPlayers), 54, "§8거래할 사람 고르기")
        others.forEachIndexed { i, p ->
            val head = ItemStack(Material.PLAYER_HEAD)
            (head.itemMeta as? SkullMeta)?.let { meta -> meta.owningPlayer = p; head.itemMeta = meta }
            val nation = plugin.nationManager.getNationName(p.uniqueId)
            val incoming = hasRequest(player, p)
            inv.setItem(i, item(head, "${if (incoming) "§e" else "§f"}${p.name}", listOfNotNull(
                "§7국가: ${nation?.let { "§a$it" } ?: "§8없음"}",
                if (incoming) "§e이 사람이 거래를 요청했습니다!" else null,
                "",
                if (incoming) "§a클릭: 거래 수락" else "§e클릭: 거래 요청"
            )))
        }
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(45, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        inv.setItem(49, item(icon("trade.info", Material.BOOK), "§6§l1:1 거래 안내", listOf(
            "§7/거래 <닉네임> §8또는 여기서 클릭해 요청하고,",
            "§7상대가 수락하면 거래 창이 열려요.",
            "§7왼쪽 칸에 줄 아이템, 금괴 버튼으로 줄 돈을 정하고",
            "§7둘 다 §a준비 완료§7를 누르면 교환돼요.",
            "§8내용이 바뀌면 준비가 풀리니 마지막에 꼭 확인하세요.",
            "§8직업 아이템은 거래할 수 없어요.")))
        if (others.isEmpty()) inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§7접속 중인 다른 플레이어가 없습니다.", emptyList()))
        player.openInventory(inv)
        Sounds.open(player)
    }

    private fun onPickClick(e: InventoryClickEvent, holder: TradePickHolder) {
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return
        if (e.rawSlot == 45) return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
        val target = holder.slotPlayers[e.rawSlot]?.let { Bukkit.getPlayer(it) } ?: return
        Sounds.click(player)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            player.closeInventory()
            if (target.isOnline) request(player, target)
        })
    }

    // ───────────────────────── 공통 ─────────────────────────

    private fun fmt(v: Double) = "%,.0f".format(v)

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
