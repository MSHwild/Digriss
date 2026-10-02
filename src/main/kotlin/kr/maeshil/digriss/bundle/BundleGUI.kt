package kr.maeshil.digriss.bundle

import kr.maeshil.digriss.Digriss
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

// /번들: 판매 중인 번들 목록. slots[i] = i번 칸의 번들 이름
class BundleListHolder(val slots: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// 번들 하나의 내용물 미리보기 + 구매 버튼
class BundlePreviewHolder(val name: String) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// 관리자 아이템 편집 창. 닫으면 저장됨. isNew면 닫을 때 번들이 새로 만들어짐
class BundleEditHolder(val name: String, val isNew: Boolean, val price: Long, val days: Int) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

object BundleGUI {

    const val EDIT_SIZE = 45            // 번들 하나에 넣을 수 있는 최대 칸 수
    const val PREVIEW_BACK_SLOT = 45
    const val PREVIEW_BUY_SLOT = 49
    private const val PREVIEW_DC_SLOT = 53
    private const val LIST_DC_SLOT = 49
    private const val CONTENT_LINES = 8 // 목록 설명에 보여줄 내용물 줄 수

    private val legacy = LegacyComponentSerializer.legacySection()

    // ───────────────────────── 목록 ─────────────────────────

    fun openList(player: Player, plugin: Digriss) {
        val bundles = plugin.bundleManager.onSale().take(45)
        val slots = bundles.mapIndexed { i, b -> i to b.name }.toMap()
        val inv = Bukkit.createInventory(BundleListHolder(slots), 54, "§8번들 상점")

        bundles.forEachIndexed { i, b ->
            val lore = mutableListOf<Component>(
                text("§7가격 §b${b.price} DC"),
                text("§7남은 판매 기간 §f${b.remainingText()}"),
                text(""),
                text("§7구성품")
            )
            lore += contentLines(b.items)
            lore += text("")
            lore += text("§e클릭: 자세히 보기 / 구매")
            inv.setItem(i, item(Material.CHEST, "§6§l${b.name}", lore))
        }

        if (bundles.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "§7지금 판매 중인 번들이 없습니다.", emptyList()))
        }
        inv.setItem(LIST_DC_SLOT, dcItem(player, plugin))
        player.openInventory(inv)
    }

    // ───────────────────────── 미리보기 ─────────────────────────

    fun openPreview(player: Player, plugin: Digriss, bundle: Bundle) {
        val inv = Bukkit.createInventory(BundlePreviewHolder(bundle.name), 54, "§8번들 - ${bundle.name}")
        bundle.items.take(EDIT_SIZE).forEachIndexed { i, stack -> inv.setItem(i, stack.clone()) }

        inv.setItem(PREVIEW_BACK_SLOT, item(Material.ARROW, "§7← 목록으로", emptyList()))
        inv.setItem(PREVIEW_BUY_SLOT, item(Material.EMERALD_BLOCK, "§a§l구매하기",
            listOf(
                text("§7가격 §b${bundle.price} DC"),
                text("§7남은 판매 기간 §f${bundle.remainingText()}"),
                text(""),
                text("§7위 아이템을 전부 받습니다."),
                text("§e클릭하여 구매")
            )))
        inv.setItem(PREVIEW_DC_SLOT, dcItem(player, plugin))
        player.openInventory(inv)
    }

    // ───────────────────────── 편집 ─────────────────────────

    fun openEdit(player: Player, holder: BundleEditHolder, items: List<ItemStack>) {
        val title = if (holder.isNew) "§8번들 생성 - ${holder.name}" else "§8번들 수정 - ${holder.name}"
        val inv = Bukkit.createInventory(holder, EDIT_SIZE, title)
        items.take(EDIT_SIZE).forEachIndexed { i, stack -> inv.setItem(i, stack.clone()) }
        player.openInventory(inv)
        player.sendMessage("§e[번들] 아이템을 넣고 창을 닫으면 저장됩니다.")
    }

    // ───────────────────────── 아이콘 ─────────────────────────

    // 같은 아이템은 합쳐서 "이름 x개수"로 표시
    private fun contentLines(items: List<ItemStack>): List<Component> {
        val merged = LinkedHashMap<ItemStack, Int>()
        items.forEach { stack ->
            val key = merged.keys.firstOrNull { it.isSimilar(stack) } ?: stack.asOne()
            merged[key] = (merged[key] ?: 0) + stack.amount
        }
        val lines = merged.entries.take(CONTENT_LINES).map { (stack, count) ->
            Component.text("§8- ").append(nameOf(stack)).append(Component.text(" §7x$count"))
                .decoration(TextDecoration.ITALIC, false)
        }.toMutableList<Component>()
        if (merged.size > CONTENT_LINES) lines += text("§8  외 ${merged.size - CONTENT_LINES}종")
        return lines
    }

    // 이름이 붙은 아이템(IA 포함)은 그 이름, 아니면 클라이언트 언어로 번역된 기본 이름
    private fun nameOf(stack: ItemStack): Component {
        val meta = stack.itemMeta
        return if (meta != null && meta.hasDisplayName()) meta.displayName()!!.colorIfAbsent(net.kyori.adventure.text.format.NamedTextColor.WHITE)
        else Component.translatable(stack.translationKey()).color(net.kyori.adventure.text.format.NamedTextColor.WHITE)
    }

    private fun dcItem(player: Player, plugin: Digriss): ItemStack =
        item(Material.SUNFLOWER, "§b§l보유 DC §f${plugin.dcManager.getDC(player)}", emptyList())

    private fun text(s: String): Component = legacy.deserialize(s).decoration(TextDecoration.ITALIC, false)

    private fun item(material: Material, name: String, lore: List<Component>): ItemStack {
        val item = ItemStack(material)
        val meta = item.itemMeta
        meta.displayName(text(name))
        meta.lore(lore)
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        item.itemMeta = meta
        return item
    }
}
