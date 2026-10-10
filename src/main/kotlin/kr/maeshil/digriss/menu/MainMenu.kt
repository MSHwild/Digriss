package kr.maeshil.digriss.menu

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.addon.GuiBg
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class MainMenuHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// Shift+F 메인 메뉴 (B안, 공동작업자 디자인): 큰 카드 6장(국가 · 경제 · 성장 · 랭킹 · 행사 · 꾸밈) + 아래 바로가기 줄
// 카드를 누르면 그 묶음의 버튼들이 있는 작은 메뉴가 열림. 배경 그림은 digriss:gui_main, digriss:gui_cat_<키>
class MainMenu(private val plugin: Digriss) : Listener {

    private data class Button(val slot: Int, val icon: Material, val name: String, val lore: List<String>, val command: String, val iconKey: String = command)

    // cardSlots: 큰 카드가 차지하는 칸들 (배경 그림의 카드 위에 투명 아이템을 깔아 클릭을 받음)
    private class Category(val key: String, val title: String, val name: String, val lore: List<String>, val cardSlots: List<Int>, val buttons: List<Button>)

    class CategoryHolder(val key: String) : InventoryHolder {
        private var inv: Inventory? = null
        fun attach(inventory: Inventory) { inv = inventory }
        override fun getInventory(): Inventory = inv!!
    }

    // 카드 한 장 = 시작 칸들에서 가로 3칸씩
    private fun card(vararg base: Int): List<Int> = base.flatMap { listOf(it, it + 1, it + 2) }

    private val categories = listOf(
        Category("nation", "§f국가", "§a국가", listOf("§7국가 · 연합 · 외교 · 국가 창고"), card(9, 18), listOf(
            Button(10, Material.BEACON, "§a국가", listOf("§7건국 · 영토 · 금고 · 전쟁"), "국가"),
            Button(12, Material.LIGHT_BLUE_BANNER, "§a연합", listOf("§7다른 국가와 손잡기"), "연합"),
            Button(14, Material.WRITABLE_BOOK, "§a외교", listOf("§7불가침 조약 · 무역 협정 · 조공 · 원조"), "외교"),
            Button(16, Material.CHEST, "§a국가 창고", listOf("§7국가원이 함께 쓰는 창고"), "국가창고")
        )),
        Category("economy", "§f경제", "§6경제", listOf("§7상점 · 거래소 · 1:1 거래 · 번들 · 레시피"), card(12, 21), listOf(
            Button(9, Material.EMERALD_BLOCK, "§6상점", listOf("§7블럭 · 음식 · 장비 사고팔기"), "shop", "상점"),
            Button(11, Material.EMERALD, "§6거래소", listOf("§7아이템을 올려 팔고 사기"), "거래소"),
            Button(13, Material.PLAYER_HEAD, "§61:1 거래", listOf("§7다른 플레이어와 아이템 · 돈 교환"), "거래"),
            Button(15, Material.GOLD_INGOT, "§6번들 상점", listOf("§7DC로 사는 패키지"), "번들"),
            Button(17, Material.CRAFTING_TABLE, "§6레시피", listOf("§7무기 제작법"), "ia weapon_c", "레시피")
        )),
        Category("growth", "§f성장", "§e성장", listOf("§7일일 퀘스트 · 직업 · 직업 설정"), card(15, 24), listOf(
            Button(11, Material.WRITABLE_BOOK, "§e일일 퀘스트", listOf("§7매일 오전 5시 갱신"), "퀘스트"),
            Button(13, Material.TOTEM_OF_UNDYING, "§e직업", listOf("§7영혼 100으로 직업 구매"), "직업"),
            Button(15, Material.ARMOR_STAND, "§e직업 설정", listOf("§7구매한 직업 장착 · 해제"), "직업설정")
        )),
        Category("rank", "§f랭킹", "§b랭킹", listOf("§7내 랭크 · 플레이어 랭킹 · 국가 랭킹"), card(27, 36), listOf(
            Button(11, Material.GOLDEN_HELMET, "§b내 랭크", listOf("§7랭크와 점수"), "랭크"),
            Button(13, Material.DIAMOND, "§b플레이어 랭킹", listOf("§7랭크 순위"), "랭킹"),
            Button(15, Material.EMERALD, "§b국가 랭킹", listOf("§7영토 · 금고 · 인원 · 내실"), "국가랭킹")
        )),
        Category("event", "§f행사", "§d행사", listOf("§7전쟁 이벤트 · 이벤트 · 대축제"), card(30, 39), listOf(
            Button(11, Material.RED_BANNER, "§d전쟁 이벤트", listOf("§7주말 저녁 점수 2배"), "전쟁이벤트"),
            Button(13, Material.CAKE, "§d이벤트", listOf("§7접속 보상 · 친구 추천"), "이벤트"),
            Button(15, Material.DRAGON_EGG, "§6대축제", listOf("§7접속 보상 · 보스 레이드 일정"), "빅이벤트")
        )),
        Category("style", "§f꾸밈", "§d꾸밈", listOf("§7업적 · 칭호 · 킬 이펙트"), card(33, 42), listOf(
            Button(12, Material.NAME_TAG, "§d업적 · 칭호", listOf("§7칭호 장착"), "칭호"),
            Button(14, Material.FIREWORK_STAR, "§d킬 이펙트", listOf("§7처치 연출 구매"), "킬이펙트")
        ))
    )
    private val byKey = categories.associateBy { it.key }
    private val cardBySlot: Map<Int, Category> = categories.flatMap { c -> c.cardSlots.map { it to c } }.toMap()

    // 맨 아래 줄 · 오른쪽 위 바로가기
    private val shortcuts = listOf(
        Button(8, Material.BOOK, "§f도움말", listOf("§7서버 기능 설명"), "도움말"),
        Button(45, Material.WRITABLE_BOOK, "§e일일 퀘스트", listOf("§7매일 오전 5시 갱신"), "퀘스트"),
        Button(46, Material.CHEST, "§a국가 창고", listOf("§7국가원이 함께 쓰는 창고"), "국가창고"),
        Button(47, Material.EMERALD, "§6거래소", listOf("§7아이템을 올려 팔고 사기"), "거래소"),
        Button(51, Material.LODESTONE, "§d워프", listOf("§7역참으로 순간이동"), "워프"),
        Button(52, Material.SADDLE, "§d탈것", listOf("§7빠른 말 부르기"), "탈것"),
        Button(53, Material.FILLED_MAP, "§f실시간 지도", listOf("§7웹 지도 주소"), "지도")
    )
    private val shortcutBySlot = shortcuts.associateBy { it.slot }

    companion object {
        private const val SIZE = 54
        private const val CLOSE_SLOT = 49
        private const val CATEGORY_SIZE = 36
        private const val CATEGORY_BACK = 27
        private const val CATEGORY_CLOSE = 35

        // 다른 메뉴에 넣는 "메뉴로 돌아가기" 버튼
        fun backItem(plugin: Digriss): ItemStack {
            val stack = plugin.iconManager.get("common.back", Material.ARROW)
            val meta = stack.itemMeta ?: return stack
            meta.setDisplayName("§7← 메뉴로")
            meta.lore = listOf("§8Shift+F 메뉴로 돌아갑니다")
            meta.addItemFlags(*ItemFlag.entries.toTypedArray())
            stack.itemMeta = meta
            return stack
        }

        // 클릭 이벤트 안에서 바로 열지 않고 다음 틱에 메인 메뉴 열기
        fun back(plugin: Digriss, player: Player) {
            Sounds.click(player)
            Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) plugin.mainMenu.open(player) })
        }
    }

    // 웅크린 채로 F(양손 바꾸기)를 누르면 메뉴 열기. 그냥 F는 원래대로 동작
    @EventHandler(ignoreCancelled = true)
    fun onSwap(e: PlayerSwapHandItemsEvent) {
        if (!e.player.isSneaking) return
        e.isCancelled = true
        open(e.player)
        Sounds.open(e.player)
    }

    fun open(player: Player) {
        val inv = Bukkit.createInventory(MainMenuHolder(), SIZE, GuiBg.title("main", "§f디그리스"))
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until inv.size) inv.setItem(i, filler)

        inv.setItem(0, profile(player))
        // 카드 칸: 투명(빈칸 그림) 아이템에 카드 이름 · 설명만 붙임 (그림은 배경에)
        cardBySlot.forEach { (slot, c) ->
            inv.setItem(slot, item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), c.name, c.lore + listOf("", "§8클릭해서 열기")))
        }
        shortcuts.forEach { b -> inv.setItem(b.slot, buttonItem(b)) }
        inv.setItem(CLOSE_SLOT, item(icon("common.close", Material.BARRIER), "§7닫기", emptyList()))
        player.openInventory(inv)
    }

    private fun openCategory(player: Player, c: Category) {
        val holder = CategoryHolder(c.key)
        val inv = Bukkit.createInventory(holder, CATEGORY_SIZE, GuiBg.title("cat_${c.key}", c.title))
        holder.attach(inv)
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until inv.size) inv.setItem(i, filler)
        c.buttons.filter { isShown(it) }.forEach { b -> inv.setItem(b.slot, buttonItem(b)) }
        inv.setItem(CATEGORY_BACK, backItem(plugin))
        inv.setItem(CATEGORY_CLOSE, item(icon("common.close", Material.BARRIER), "§7닫기", emptyList()))
        player.openInventory(inv)
    }

    // 아이콘은 icons.yml에서 바꿀 수 있음 (menu.<명령어>)
    private fun buttonItem(b: Button): ItemStack =
        item(icon("menu.${b.iconKey}", b.icon), b.name, b.lore + listOf("", "§8클릭해서 열기"))

    // 대축제 버튼은 축제 기간이 끝나면 숨김
    private fun isShown(b: Button): Boolean = b.command != "빅이벤트" || !plugin.bigEventManager.isOver()

    // 맨 위 내 정보: 랭크 · 영혼 · DC · 국가 · 직업 · 칭호
    private fun profile(player: Player): ItemStack {
        val tier = plugin.rankManager.getTier(player)
        val head = ItemStack(Material.PLAYER_HEAD)
        (head.itemMeta as? SkullMeta)?.let { meta ->
            meta.owningPlayer = player
            head.itemMeta = meta
        }
        return item(head, "§f${player.name}", listOf(
            "§7랭크 " + ChatColor.translateAlternateColorCodes('&', tier.color + tier.name) + " §8(${plugin.rankManager.getScore(player)}점)",
            "§7영혼 §b${plugin.soulManager.getSouls(player)}",
            "§7DC §3${plugin.dcManager.getDC(player)}",
            "§7국가 §a${plugin.nationManager.getNationName(player.uniqueId) ?: "§8없음"}",
            "§7직업 §e${plugin.jobManager.getJob(player.uniqueId)?.displayName ?: "§8없음"}",
            "§7칭호 ${plugin.achievementManager.titleOf(player.uniqueId) ?: "§8없음"}"
        ))
    }

    private fun icon(key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(stack: ItemStack, name: String, lore: List<String>): ItemStack {
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        stack.itemMeta = meta
        return stack
    }

    // 창을 닫고 다음 틱에 명령어 실행 (권한 확인도 명령어 쪽에서 그대로 적용)
    private fun run(player: Player, command: String) {
        Sounds.click(player)
        player.closeInventory()
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.performCommand(command) })
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder
        if (holder !is MainMenuHolder && holder !is CategoryHolder) return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return
        val slot = e.rawSlot

        if (holder is CategoryHolder) {
            val c = byKey[holder.key] ?: return
            when (slot) {
                CATEGORY_BACK -> back(plugin, player)
                CATEGORY_CLOSE -> { Sounds.click(player); player.closeInventory() }
                else -> c.buttons.firstOrNull { it.slot == slot && isShown(it) }?.let { run(player, it.command) }
            }
            return
        }

        if (slot == CLOSE_SLOT) {
            Sounds.click(player)
            player.closeInventory()
            return
        }
        cardBySlot[slot]?.let { c ->
            Sounds.click(player)
            Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) openCategory(player, c) })
            return
        }
        shortcutBySlot[slot]?.let { run(player, it.command) }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is MainMenuHolder || holder is CategoryHolder) e.isCancelled = true
    }
}
