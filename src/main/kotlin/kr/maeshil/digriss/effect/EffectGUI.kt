package kr.maeshil.digriss.effect

import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta

class EffectGUI(private val plugin: Digriss) {

    val title = "${ChatColor.DARK_PURPLE}${ChatColor.BOLD}킬 이펙트 상점"
    val BACK_SLOT = 18

    fun open(player: Player) {
        val inv: Inventory = Bukkit.createInventory(null, 27, title)

        EffectRegistry.effects.forEachIndexed { index, effect ->
            val owned = plugin.killEffectManager.hasEffect(player, effect.id)
            val equipped = plugin.killEffectManager.getEquipped(player)?.id == effect.id

            val item = ItemStack(if (equipped) Material.NETHER_STAR else Material.FIREWORK_STAR)
            val meta = item.itemMeta

            meta?.setDisplayName(ChatColor.translateAlternateColorCodes('&', effect.displayName))

            val lore = mutableListOf<String>()
            effect.description.forEach { lore.add(ChatColor.translateAlternateColorCodes('&', it)) }
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

            inv.setItem(index, item)
        }
        inv.setItem(BACK_SLOT, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))

        player.openInventory(inv)
    }
}