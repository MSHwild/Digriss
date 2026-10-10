package kr.maeshil.digriss.addon

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.MenuType
import kr.maeshil.digriss.nation.NationMenuHolder
import org.bukkit.Material
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

// 배경 그림에 맞춘 칸 배치: 원래 코드의 칸 번호(원래 칸) → 배경 그림 위의 실제 칸 번호
// 각 GUI 코드는 원래 칸 번호 그대로 쓰고, 넣을 때 GuiSlots.set / 클릭 받을 때 GuiSlots.rawSlot 으로 바꿔 씀
object GuiSlots {

    private class Layout(val size: Int, val map: Map<Int, Int>) {
        val inverse: Map<Int, Int> = map.entries.associate { (from, to) -> to to from }
    }

    private fun layout(size: Int, vararg pairs: Pair<Int, Int>) = Layout(size, mapOf(*pairs))

    // 원래 칸 to 배경 위 칸
    private val NO_NATION = layout(18, 18 to 9, 11 to 11, 13 to 13, 15 to 15)
    private val NATION_MAIN = layout(
        36,
        4 to 9, 26 to 10, 27 to 11, 10 to 12, 24 to 13, 14 to 14, 12 to 15, 16 to 16, 18 to 17,
        20 to 27, 22 to 28, 28 to 30, 8 to 31, 34 to 32, 30 to 33, 32 to 34
    )
    private val BANK = layout(
        36,
        4 to 10, 31 to 28, 10 to 12, 12 to 13, 14 to 14, 16 to 15, 19 to 30, 21 to 31, 23 to 32, 25 to 33
    )
    private val DIPLOMACY_DETAIL = layout(18, 18 to 9, 4 to 10, 22 to 11, 10 to 13, 12 to 14, 14 to 15, 16 to 16)
    private val QUEST = layout(18, 18 to 9, 4 to 10, 11 to 12, 13 to 13, 15 to 14, 20 to 15, 24 to 16, 22 to 17)

    private val plugin: Digriss by lazy { JavaPlugin.getPlugin(Digriss::class.java) }

    private fun layoutOf(holder: InventoryHolder?): Layout? {
        if (holder is NationMenuHolder) return when (holder.type) {
            MenuType.NO_NATION -> NO_NATION
            MenuType.MAIN -> NATION_MAIN
            MenuType.BANK -> BANK
            else -> null
        }
        return when (holder?.javaClass?.name?.substringAfterLast('.')) {
            "DiplomacyDetailHolder" -> DIPLOMACY_DETAIL
            "QuestHolder" -> QUEST
            else -> null
        }
    }

    /** 배경에 맞춘 창 크기 (배치가 없는 창은 원래 크기) */
    @JvmStatic
    fun sizeOf(holder: InventoryHolder?, size: Int): Int = layoutOf(holder)?.size ?: size

    // 이름이 비어 있는 아이템 = 빈칸 채우기용 유리판
    private fun isFiller(item: ItemStack?): Boolean {
        val meta = item?.itemMeta ?: return false
        return meta.hasDisplayName() && meta.displayName.isBlank()
    }

    // 색유리판(빈칸)은 icons.yml의 common.filler 그림으로 바꿔서 배경 위에서 투명하게 보이게 (이름·설명은 유지)
    private fun unglass(item: ItemStack?): ItemStack? {
        if (item == null || !item.type.name.endsWith("_STAINED_GLASS_PANE")) return item
        if (item.hasItemMeta() && item.itemMeta.hasCustomModelData()) return item
        return try {
            val icon = plugin.iconManager.get("common.filler", item.type)
            val meta = icon.itemMeta
            val old = item.itemMeta
            if (meta != null && old != null) {
                if (old.hasDisplayName()) meta.setDisplayName(old.displayName)
                if (old.hasLore()) meta.lore = old.lore
                icon.itemMeta = meta
            }
            icon
        } catch (e: Throwable) {
            item
        }
    }

    /** inv.setItem 대신 사용: 원래 칸 번호를 배경 위 칸으로 바꿔서 넣음 (빈칸 채우기는 그대로) */
    @JvmStatic
    fun set(inv: Inventory, slot: Int, item: ItemStack?) {
        val stack = unglass(item)
        val l = layoutOf(inv.holder)
        val target = if (l != null && !isFiller(stack)) l.map[slot] ?: slot else slot
        if (target in 0 until inv.size) inv.setItem(target, stack)
    }

    // 배경 위 칸 → 원래 칸. 배치에 쓰인 칸인데 원래 칸이 없으면 -1 (클릭 무시)
    private fun back(holder: InventoryHolder?, slot: Int): Int {
        val l = layoutOf(holder) ?: return slot
        l.inverse[slot]?.let { return it }
        return if (l.map.containsKey(slot)) -1 else slot
    }

    /** e.slot 대신 사용 (위쪽 창이면 원래 칸 번호로) */
    @JvmStatic
    fun slot(e: InventoryClickEvent): Int {
        val s = e.slot
        return if (e.clickedInventory != e.view.topInventory) s else back(e.view.topInventory.holder, s)
    }

    /** e.rawSlot 대신 사용 (위쪽 창이면 원래 칸 번호로) */
    @JvmStatic
    fun rawSlot(e: InventoryClickEvent): Int {
        val s = e.rawSlot
        return if (s in 0 until e.view.topInventory.size) back(e.view.topInventory.holder, s) else s
    }
}
