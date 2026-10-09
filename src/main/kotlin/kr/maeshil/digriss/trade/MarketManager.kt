package kr.maeshil.digriss.trade

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class MarketListing(
    val id: String,
    val seller: UUID,
    val sellerName: String,
    val price: Double,
    val item: ItemStack,
    val createdAt: Long
)

// slotIds: 슬롯 번호 -> 그 칸의 판매 물품 ID
class MarketHolder(val page: Int, val mineOnly: Boolean, val slotIds: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

class MarketStorageHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /거래소: 아이템을 정한 가격에 올려두면 접속하지 않아도 다른 사람이 사 감
//  - 팔리면 판매 금액에서 수수료를 뺀 돈이 바로 들어옴 (같은 국가·무역 협정 국가끼리는 수수료 면제)
//  - 기간이 끝나거나 취소한 물품은 보관함으로 (다음 접속 때 알림)
class MarketManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    companion object {
        const val MAX_LISTINGS = 10                 // 1인당 동시에 올릴 수 있는 물품 수
        const val LISTING_DAYS = 7                  // 판매 기간
        const val FEE_RATE = 0.05                   // 판매 수수료 5%
        const val MAX_PRICE = 100_000_000.0
        private const val PAGE_SIZE = 45
        private const val SLOT_BACK = 45
        private const val SLOT_PREV = 47
        private const val SLOT_MINE = 48
        private const val SLOT_INFO = 49
        private const val SLOT_STORAGE = 50
        private const val SLOT_NEXT = 51
    }

    private val file = File(plugin.dataFolder, "market.yml")
    private val logFile = File(plugin.dataFolder, "market.log")
    private val zone = ZoneId.of("Asia/Seoul")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private val listings = LinkedHashMap<String, MarketListing>()          // 올린 순서
    private val storage = mutableMapOf<UUID, MutableList<ItemStack>>()      // 돌려받을 아이템 (기간 만료·취소)
    private val notices = mutableMapOf<UUID, MutableList<String>>()         // 접속하지 않았을 때 팔린 소식

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        load()
        // 1분마다 기간이 끝난 물품을 보관함으로
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { expire() }, 1200L, 1200L)
    }

    private fun economy(): Economy? = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider

    // ───────────────────────── 명령어 ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        when (args.getOrNull(0)) {
            null -> { open(player, 0, false); Sounds.open(player) }
            "등록", "판매" -> {
                val price = args.getOrNull(1)?.replace(",", "")?.toDoubleOrNull()
                    ?: return true.also { deny(player, "§c사용법: /거래소 등록 <가격> §7(손에 든 아이템을 올려요)") }
                register(player, Math.floor(price))
            }
            "내물품" -> { open(player, 0, true); Sounds.open(player) }
            "보관함" -> { openStorage(player); Sounds.open(player) }
            else -> player.sendMessage("§e/거래소 §7목록 열기 §8| §e/거래소 등록 <가격> §7손에 든 아이템 판매 §8| §e/거래소 보관함")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1) listOf("등록", "내물품", "보관함").filter { it.startsWith(args[0]) } else emptyList()

    // ───────────────────────── 등록 / 구매 / 취소 ─────────────────────────

    fun register(player: Player, price: Double) {
        val hand = player.inventory.itemInMainHand
        if (hand.type.isAir) return deny(player, "§c판매할 아이템을 손에 들고 입력하세요.")
        if (!plugin.tradeManager.isTradeable(hand)) return deny(player, "§c직업 아이템은 거래소에 올릴 수 없습니다.")
        if (price < 1 || price > MAX_PRICE) return deny(player, "§c가격은 1원 ~ ${fmt(MAX_PRICE)}원 사이로 정하세요.")
        if (listings.values.count { it.seller == player.uniqueId } >= MAX_LISTINGS) return deny(player, "§c물품은 최대 ${MAX_LISTINGS}개까지 올릴 수 있습니다.")

        val item = hand.clone()
        player.inventory.setItemInMainHand(null)
        val id = UUID.randomUUID().toString().substring(0, 8)
        listings[id] = MarketListing(id, player.uniqueId, player.name, price, item, System.currentTimeMillis())
        save()
        player.sendMessage("§a거래소에 §f${plugin.tradeManager.describe(item)}§a을(를) §6${fmt(price)}원§a에 올렸습니다. §7(${LISTING_DAYS}일 동안 판매)")
        Sounds.success(player)
        log("등록 | ${player.name} | ${plugin.tradeManager.describe(item)} | ${fmt(price)}원 | $id")
    }

    /** 판매자·구매자 국가가 같거나 무역 협정 중이면 수수료 면제 */
    fun feeFor(seller: UUID, buyer: UUID, price: Double): Double {
        val sn = plugin.nationManager.getNationName(seller)
        val bn = plugin.nationManager.getNationName(buyer)
        if (sn != null && bn != null && (sn == bn || plugin.diplomacyManager.hasTrade(sn, bn))) return 0.0
        return Math.floor(price * FEE_RATE)
    }

    private fun buy(player: Player, id: String) {
        val listing = listings[id] ?: return deny(player, "§c이미 팔렸거나 내려간 물품입니다.")
        if (listing.seller == player.uniqueId) return
        val econ = economy() ?: return deny(player, "§c경제 플러그인이 연동되어 있지 않습니다.")
        if (!econ.has(player, listing.price)) return deny(player, "§c소지금이 부족합니다. (소지금: ${fmt(econ.getBalance(player))}원)")
        if (!hasRoom(player, listing.item)) return deny(player, "§c인벤토리 공간이 부족합니다.")

        listings.remove(id)
        save()
        econ.withdrawPlayer(player, listing.price)
        player.inventory.addItem(listing.item.clone())
        val fee = feeFor(listing.seller, player.uniqueId, listing.price)
        val seller: OfflinePlayer = Bukkit.getOfflinePlayer(listing.seller)
        econ.depositPlayer(seller, listing.price - fee)

        player.sendMessage("§a§f${plugin.tradeManager.describe(listing.item)}§a을(를) §6${fmt(listing.price)}원§a에 샀습니다.")
        Sounds.purchase(player)
        val notice = "§6[거래소] §f${plugin.tradeManager.describe(listing.item)}§6이(가) ${player.name} 님에게 ${fmt(listing.price)}원에 팔렸습니다." +
            if (fee > 0) " §7(수수료 ${fmt(fee)}원 제외 ${fmt(listing.price - fee)}원 입금)" else " §7(수수료 면제)"
        val online = seller.player
        if (online != null) { online.sendMessage(notice); Sounds.coin(online) }
        else { notices.getOrPut(listing.seller) { mutableListOf() }.add(notice); save() }
        log("구매 | ${player.name} <- ${listing.sellerName} | ${plugin.tradeManager.describe(listing.item)} | ${fmt(listing.price)}원 (수수료 ${fmt(fee)}) | $id")
    }

    private fun cancelListing(player: Player, id: String) {
        val listing = listings[id] ?: return
        if (listing.seller != player.uniqueId) return
        listings.remove(id)
        if (hasRoom(player, listing.item)) player.inventory.addItem(listing.item.clone())
        else {
            storage.getOrPut(player.uniqueId) { mutableListOf() }.add(listing.item.clone())
            player.sendMessage("§e인벤토리가 가득 차서 보관함으로 보냈습니다. §7(/거래소 보관함)")
        }
        save()
        player.sendMessage("§e판매를 취소했습니다: §f${plugin.tradeManager.describe(listing.item)}")
        Sounds.click(player)
        log("취소 | ${player.name} | ${plugin.tradeManager.describe(listing.item)} | $id")
    }

    private fun expire() {
        val limit = System.currentTimeMillis() - LISTING_DAYS * 86_400_000L
        val expired = listings.values.filter { it.createdAt < limit }
        if (expired.isEmpty()) return
        expired.forEach { l ->
            listings.remove(l.id)
            storage.getOrPut(l.seller) { mutableListOf() }.add(l.item.clone())
            Bukkit.getPlayer(l.seller)?.sendMessage("§e[거래소] 판매 기간이 끝난 §f${plugin.tradeManager.describe(l.item)}§e을(를) 보관함으로 옮겼습니다. §7(/거래소 보관함)")
            log("만료 | ${l.sellerName} | ${plugin.tradeManager.describe(l.item)} | ${l.id}")
        }
        save()
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            notices.remove(player.uniqueId)?.let { list ->
                list.forEach { player.sendMessage(it) }
                save()
            }
            val count = storage[player.uniqueId]?.size ?: 0
            if (count > 0) player.sendMessage("§e[거래소] 보관함에 찾아가지 않은 아이템이 ${count}개 있습니다. §7(/거래소 보관함)")
        }, 60L)
    }

    // ───────────────────────── 목록 GUI ─────────────────────────

    fun open(player: Player, page: Int, mineOnly: Boolean) {
        val all = listings.values.reversed().filter { !mineOnly || it.seller == player.uniqueId } // 최신순
        val pages = maxOf(1, (all.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val p = page.coerceIn(0, pages - 1)
        val shown = all.drop(p * PAGE_SIZE).take(PAGE_SIZE)
        val slotIds = shown.withIndex().associate { it.index to it.value.id }
        val inv = Bukkit.createInventory(MarketHolder(p, mineOnly, slotIds), 54, "§8거래소${if (mineOnly) " §7- 내 물품" else ""} §7(${p + 1}/$pages)")

        shown.forEachIndexed { i, l ->
            val stack = l.item.clone()
            val meta = stack.itemMeta
            if (meta != null) {
                val lore = (meta.lore ?: emptyList()).toMutableList()
                val left = l.createdAt + LISTING_DAYS * 86_400_000L - System.currentTimeMillis()
                lore += listOf("§8──────────", "§7판매자: §f${l.sellerName}", "§7가격: §6${fmt(l.price)}원", "§7남은 기간: §f${remain(left)}", "")
                lore += if (l.seller == player.uniqueId) "§c클릭: 판매 취소" else "§e쉬프트+클릭: 구매"
                meta.lore = lore
                stack.itemMeta = meta
            }
            inv.setItem(i, stack)
        }
        if (shown.isEmpty()) inv.setItem(22, item(icon("common.empty", Material.BARRIER),
            if (mineOnly) "§7올린 물품이 없습니다." else "§7판매 중인 물품이 없습니다.", listOf("§7손에 아이템을 들고 §e/거래소 등록 <가격>")))

        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(SLOT_BACK, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        if (p > 0) inv.setItem(SLOT_PREV, item(icon("common.prev", Material.ARROW), "§7← 이전 페이지", emptyList()))
        if (p < pages - 1) inv.setItem(SLOT_NEXT, item(icon("common.next", Material.ARROW), "§7다음 페이지 →", emptyList()))
        val myCount = listings.values.count { it.seller == player.uniqueId }
        inv.setItem(SLOT_MINE, item(icon("market.mine", Material.PLAYER_HEAD),
            if (mineOnly) "§f§l전체 물품 보기" else "§f§l내 물품만 보기", listOf("§7올린 물품: §f$myCount/$MAX_LISTINGS")))
        inv.setItem(SLOT_INFO, item(icon("market.info", Material.BOOK), "§6§l거래소 안내", listOf(
            "§7손에 아이템을 들고 §e/거래소 등록 <가격>",
            "§7올린 물품은 §f${LISTING_DAYS}일§7 동안 팔리고, 안 팔리면 보관함으로 와요.",
            "§7팔리면 접속하지 않아도 돈이 바로 들어와요.",
            "§7판매 수수료 §f${(FEE_RATE * 100).toInt()}%§7 §8(같은 국가 · 무역 협정 국가끼리는 면제)",
            "§8직업 아이템은 올릴 수 없어요.",
            "",
            "§7내 소지금: §6${fmt(economy()?.getBalance(player) ?: 0.0)}원")))
        val stored = storage[player.uniqueId]?.size ?: 0
        inv.setItem(SLOT_STORAGE, item(icon("market.storage", Material.ENDER_CHEST), "§e§l보관함 §7($stored)",
            listOf("§7기간이 끝났거나 취소한 물품", "", "§e클릭: 열기")))
        player.openInventory(inv)
    }

    private fun openStorage(player: Player) {
        val items = storage[player.uniqueId].orEmpty()
        val inv = Bukkit.createInventory(MarketStorageHolder(), 54, "§8거래소 보관함")
        items.take(45).forEachIndexed { i, it -> inv.setItem(i, it.clone()) }
        if (items.isEmpty()) inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§7보관함이 비어 있습니다.", emptyList()))
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(SLOT_BACK, item(icon("common.back", Material.ARROW), "§7← 거래소로", emptyList()))
        inv.setItem(SLOT_INFO, item(icon("market.info", Material.BOOK), "§6§l보관함", listOf("§7아이템을 클릭하면 인벤토리로 가져와요.")))
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder
        if (holder !is MarketHolder && holder !is MarketStorageHolder) return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return
        val slot = e.rawSlot

        if (holder is MarketStorageHolder) {
            if (slot == SLOT_BACK) { Sounds.click(player); later(player) { open(player, 0, false) }; return }
            val list = storage[player.uniqueId] ?: return
            if (slot !in 0 until minOf(list.size, PAGE_SIZE)) return
            val stack = list[slot]
            if (!hasRoom(player, stack)) return deny(player, "§c인벤토리 공간이 부족합니다.")
            list.removeAt(slot)
            if (list.isEmpty()) storage.remove(player.uniqueId)
            save()
            player.inventory.addItem(stack.clone())
            Sounds.success(player)
            later(player) { openStorage(player) }
            return
        }

        holder as MarketHolder
        when (slot) {
            SLOT_BACK -> return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
            SLOT_PREV -> { Sounds.click(player); later(player) { open(player, holder.page - 1, holder.mineOnly) }; return }
            SLOT_NEXT -> { Sounds.click(player); later(player) { open(player, holder.page + 1, holder.mineOnly) }; return }
            SLOT_MINE -> { Sounds.click(player); later(player) { open(player, 0, !holder.mineOnly) }; return }
            SLOT_STORAGE -> { Sounds.click(player); later(player) { openStorage(player) }; return }
        }
        val id = holder.slotIds[slot] ?: return
        val listing = listings[id] ?: return later(player) { open(player, holder.page, holder.mineOnly) }
        if (listing.seller == player.uniqueId) cancelListing(player, id)
        else if (e.isShiftClick) buy(player, id)
        else return deny(player, "§e실수로 사지 않도록 §f쉬프트+클릭§e으로 구매해요.")
        later(player) { open(player, holder.page, holder.mineOnly) }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is MarketHolder || holder is MarketStorageHolder) e.isCancelled = true
    }

    // ───────────────────────── 초기화 / 저장 ─────────────────────────

    /** 시즌 초기화: 이전 시즌 아이템이 남지 않게 판매 목록·보관함을 모두 비움 */
    fun resetAll() {
        listings.clear(); storage.clear(); notices.clear()
        save()
    }

    private fun load() {
        if (!file.exists()) return
        val c = YamlConfiguration.loadConfiguration(file)
        c.getConfigurationSection("listings")?.getKeys(false)?.forEach { id ->
            val s = c.getConfigurationSection("listings.$id") ?: return@forEach
            val item = s.getItemStack("item") ?: return@forEach
            val seller = runCatching { UUID.fromString(s.getString("seller")) }.getOrNull() ?: return@forEach
            listings[id] = MarketListing(id, seller, s.getString("seller-name") ?: "?", s.getDouble("price"), item, s.getLong("created-at"))
        }
        c.getConfigurationSection("storage")?.getKeys(false)?.forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            val items = c.getList("storage.$key")?.filterIsInstance<ItemStack>()?.toMutableList() ?: return@forEach
            if (items.isNotEmpty()) storage[uuid] = items
        }
        c.getConfigurationSection("notices")?.getKeys(false)?.forEach { key ->
            val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
            notices[uuid] = c.getStringList("notices.$key").toMutableList()
        }
    }

    // 아이템이 오가므로 바뀔 때마다 바로 저장
    private fun save() {
        val c = YamlConfiguration()
        listings.values.forEach { l ->
            c.set("listings.${l.id}.seller", l.seller.toString())
            c.set("listings.${l.id}.seller-name", l.sellerName)
            c.set("listings.${l.id}.price", l.price)
            c.set("listings.${l.id}.item", l.item)
            c.set("listings.${l.id}.created-at", l.createdAt)
        }
        storage.forEach { (uuid, items) -> c.set("storage.$uuid", items) }
        notices.forEach { (uuid, list) -> c.set("notices.$uuid", list) }
        runCatching { c.save(file) }.onFailure { plugin.logger.severe("[거래소] market.yml 저장 실패: ${it.message}") }
    }

    private fun log(line: String) {
        val time = ZonedDateTime.now(zone).format(timeFormat)
        runCatching { logFile.appendText("$time | $line\n") }.onFailure { plugin.logger.severe("[거래소] market.log 기록 실패: ${it.message}") }
    }

    // ───────────────────────── 공통 ─────────────────────────

    // 실제 인벤토리를 건드리지 않고 복사본에 넣어 보며 공간 확인
    private fun hasRoom(player: Player, item: ItemStack): Boolean {
        val test = Bukkit.createInventory(null, 36)
        test.storageContents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return test.addItem(item.clone()).isEmpty()
    }

    private fun remain(ms: Long): String {
        if (ms <= 0) return "곧 종료"
        val h = ms / 3_600_000
        return if (h >= 24) "${h / 24}일 ${h % 24}시간" else if (h > 0) "${h}시간" else "${ms / 60_000}분"
    }

    private fun later(player: Player, task: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) task() })
    }

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
