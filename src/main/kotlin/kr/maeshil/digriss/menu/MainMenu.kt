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

    private val buttons = listOf(
        Button(10, Material.BEACON, "§6§l국가", listOf("§7건국, 영토, 금고, 전쟁 관리"), "국가"),
        Button(11, Material.LIGHT_BLUE_BANNER, "§b§l연합", listOf("§7다른 국가와 연합 맺기"), "연합"),
        Button(12, Material.CHEST, "§6§l국가 창고", listOf("§7국가원이 함께 쓰는 창고"), "국가창고"),
        Button(13, Material.WRITABLE_BOOK, "§e§l일일 퀘스트", listOf("§7매일 오전 5시 갱신"), "퀘스트"),
        Button(14, Material.TOTEM_OF_UNDYING, "§a§l직업 상점", listOf("§7영혼 100으로 직업 구매"), "직업"),
        Button(15, Material.ARMOR_STAND, "§a§l직업 설정", listOf("§7구매한 직업 장착 / 해제"), "직업설정"),
        Button(16, Material.FIREWORK_STAR, "§d§l킬 이펙트", listOf("§7영혼으로 처치 연출 구매"), "킬이펙트"),
        Button(20, Material.GOLD_INGOT, "§6§l번들 상점", listOf("§7DC로 사는 패키지"), "번들"),
        Button(21, Material.GOLDEN_HELMET, "§d§l내 랭크", listOf("§7내 랭크와 점수 확인"), "랭크"),
        Button(22, Material.DIAMOND, "§b§l플레이어 랭킹", listOf("§7랭크 순위 TOP 10"), "랭킹"),
        Button(23, Material.EMERALD, "§a§l국가 랭킹", listOf("§7영토 · 금고 · 인원 · 레벨 순위"), "국가랭킹"),
        Button(24, Material.BOOK, "§f§l도움말", listOf("§7서버 기능 설명"), "도움말"),
        Button(19, Material.NAME_TAG, "§6§l업적 · 칭호", listOf("§7달성한 업적과 칭호 장착"), "칭호"),
        Button(25, Material.RED_BANNER, "§4§l전쟁 이벤트", listOf("§7이벤트 시간 · 진행 상황"), "전쟁이벤트"),
        Button(29, Material.EMERALD_BLOCK, "§a§l상점", listOf("§7블럭 · 음식 · 장비 등을 사고팔기"), "shop", "상점"),
        Button(30, Material.CRAFTING_TABLE, "§e§l레시피 보기", listOf("§7무기 제작법 확인"), "ia weapon_c", "레시피")
    )
    private val bySlot = buttons.associateBy { it.slot }

    companion object {
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
        val inv = Bukkit.createInventory(MainMenuHolder(), 36, "§8디그리스 메뉴")
        val filler = item(icon("common.filler", Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until inv.size) inv.setItem(i, filler)

        inv.setItem(4, profile(player))
        buttons.forEach { b -> inv.setItem(b.slot, item(icon("menu.${b.iconKey}", b.icon), b.name, b.lore + listOf("", "§e클릭하여 열기"))) }
        inv.setItem(31, item(icon("common.close", Material.BARRIER), "§c닫기", emptyList()))
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
        return item(head, "§f§l${player.name}", listOf(
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

        if (e.rawSlot == 31) {
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
