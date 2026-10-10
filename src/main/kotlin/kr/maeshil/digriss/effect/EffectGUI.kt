package kr.maeshil.digriss.effect

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.addon.GuiBg
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

class EffectGUI(private val plugin: Digriss) {

    // 창 구분은 Holder로 함 (제목 앞에 배경 그림이 붙어서 제목 비교로는 안 됨)
    class Holder : InventoryHolder {
        private var inv: Inventory? = null
        fun attach(inventory: Inventory) { inv = inventory }
        override fun getInventory(): Inventory = inv!!
    }

    val title = "${ChatColor.DARK_PURPLE}${ChatColor.BOLD}킬 이펙트 상점"
    val BACK_SLOT = 18
    val FIRST_SLOT = 10 // 이펙트는 10번 칸부터 (배경 그림에 맞춤)

    fun open(player: Player) {
        val holder = Holder()
        val inv: Inventory = Bukkit.createInventory(holder, 27, GuiBg.title("kill_effect", "§f킬 이펙트 상점"))
        holder.attach(inv)
        val manager = plugin.killEffectManager

        EffectRegistry.effects.forEachIndexed { index, effect ->
            val slot = FIRST_SLOT + index
            if (slot >= inv.size || slot == BACK_SLOT) return@forEachIndexed
            val owned = manager.hasEffect(player, effect.id)
            val equipped = manager.getEquipped(player)?.id == effect.id

            val item = ItemStack(if (equipped) Material.NETHER_STAR else Material.FIREWORK_STAR)
            val meta = item.itemMeta

            meta?.setDisplayName(ChatColor.translateAlternateColorCodes('&', effect.displayName))

            val lore = effect.description.map { ChatColor.translateAlternateColorCodes('&', it) }.toMutableList()
            lore.add("")
            when {
                equipped -> lore.add("${ChatColor.GREEN}장착중")
                owned -> lore.add("${ChatColor.YELLOW}보유중 - 클릭시 장착")
                else -> {
                    lore.add("${ChatColor.GRAY}가격: ${ChatColor.AQUA}${effect.price} 영혼")
                    lore.add("${ChatColor.YELLOW}클릭하여 구매")
                }
            }
            meta?.lore = lore
            item.itemMeta = meta

            inv.setItem(slot, item)
        }
        inv.setItem(BACK_SLOT, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))

        player.openInventory(inv)
    }

    /** 칸 번호 → 그 칸의 이펙트 (없으면 null) */
    fun effectAt(slot: Int): KillEffect? =
        if (slot < FIRST_SLOT || slot == BACK_SLOT) null else EffectRegistry.effects.getOrNull(slot - FIRST_SLOT)
}
