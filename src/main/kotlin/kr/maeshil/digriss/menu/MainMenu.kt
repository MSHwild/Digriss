package kr.maeshil.digriss.menu

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
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

// Shift+F 메인 메뉴: 각 기능의 명령어를 대신 실행해 주는 바로가기 모음
class MainMenu(private val plugin: Digriss) : Listener {

    private data class Button(val slot: Int, val icon: Material, val name: String, val lore: List<String>, val command: String, val iconKey: String = command)

    // 왼쪽 묶음 / 가운데 세로줄(구분선) / 오른쪽 묶음. 묶음마다 같은 색을 써서 색이 너무 많아 보이지 않게
    private val buttons = listOf(
        // 국가
        Button(10, Material.BEACON, "§a국가", listOf("§7건국 · 영토 · 금고 · 전쟁"), "국가"),
        Button(11, Material.LIGHT_BLUE_BANNER, "§a연합", listOf("§7다른 국가와 손잡기"), "연합"),
        Button(12, Material.CHEST, "§a국가 창고", listOf("§7국가원이 함께 쓰는 창고"), "국가창고"),
        // 상점
        Button(14, Material.EMERALD_BLOCK, "§6상점", listOf("§7블럭 · 음식 · 장비 사고팔기"), "shop", "상점"),
        Button(15, Material.GOLD_INGOT, "§6번들 상점", listOf("§7DC로 사는 패키지"), "번들"),
        Button(16, Material.CRAFTING_TABLE, "§6레시피", listOf("§7무기 제작법"), "ia weapon_c", "레시피"),
        // 성장
        Button(19, Material.WRITABLE_BOOK, "§e일일 퀘스트", listOf("§7매일 오전 5시 갱신"), "퀘스트"),
        Button(20, Material.TOTEM_OF_UNDYING, "§e직업", listOf("§7영혼 100으로 직업 구매"), "직업"),
        Button(21, Material.ARMOR_STAND, "§e직업 설정", listOf("§7구매한 직업 장착 · 해제"), "직업설정"),
        // 순위
        Button(23, Material.GOLDEN_HELMET, "§b내 랭크", listOf("§7랭크와 점수"), "랭크"),
        Button(24, Material.DIAMOND, "§b플레이어 랭킹", listOf("§7랭크 순위"), "랭킹"),
        Button(25, Material.EMERALD, "§b국가 랭킹", listOf("§7영토 · 금고 · 인원 · 내실"), "국가랭킹"),
        // 꾸미기 · 전쟁
        Button(28, Material.NAME_TAG, "§d업적 · 칭호", listOf("§7칭호 장착"), "칭호"),
        Button(29, Material.FIREWORK_STAR, "§d킬 이펙트", listOf("§7처치 연출 구매"), "킬이펙트"),
        Button(30, Material.RED_BANNER, "§d전쟁 이벤트", listOf("§7주말 저녁 점수 2배"), "전쟁이벤트"),
        // 안내
        Button(32, Material.CAKE, "§f이벤트", listOf("§7접속 보상 · 친구 추천"), "이벤트"),
        Button(33, Material.FILLED_MAP, "§f실시간 지도", listOf("§7웹 지도 주소"), "지도"),
        Button(34, Material.BOOK, "§f도움말", listOf("§7서버 기능 설명"), "도움말")
    )
    private val bySlot = buttons.associateBy { it.slot }

    companion object {
        private const val SIZE = 45
        private const val CLOSE_SLOT = 40

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
        val inv = Bukkit.createInventory(MainMenuHolder(), SIZE, "§8디그리스")
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        val divider = item(icon("common.divider", Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until inv.size) inv.setItem(i, if (i % 9 == 4 && i in 9..35) divider else filler)

        inv.setItem(4, profile(player))
        buttons.forEach { b -> inv.setItem(b.slot, item(icon("menu.${b.iconKey}", b.icon), b.name, b.lore + listOf("", "§8클릭해서 열기"))) }
        inv.setItem(CLOSE_SLOT, item(icon("common.close", Material.BARRIER), "§7닫기", emptyList()))
        player.openInventory(inv)
    }

    // 맨 위 내 정보: 랭크 · 영혼 · DC · 국가 · 직업
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

    // 아이콘은 icons.yml에서 바꿀 수 있음 (menu.<명령어>)
    private fun icon(key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(stack: ItemStack, name: String, lore: List<String>): ItemStack {
        val meta = stack.itemMeta ?: return stack
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        stack.itemMeta = meta
        return stack
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        if (e.view.topInventory.holder !is MainMenuHolder) return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return

        if (e.rawSlot == CLOSE_SLOT) {
            Sounds.click(player)
            player.closeInventory()
            return
        }
        val button = bySlot[e.rawSlot] ?: return
        Sounds.click(player)
        player.closeInventory()
        // 클릭 이벤트 안에서 바로 다른 창을 열지 않고 다음 틱에 명령어 실행 (권한 확인도 명령어 쪽에서 그대로 적용)
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.performCommand(button.command) })
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is MainMenuHolder) e.isCancelled = true
    }
}
