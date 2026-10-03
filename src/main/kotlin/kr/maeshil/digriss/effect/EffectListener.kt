package kr.maeshil.digriss.effect

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

class EffectListener(private val plugin: Digriss, private val gui: EffectGUI) : Listener {

    @EventHandler
    fun onClick(event: InventoryClickEvent) {
        if (event.view.title != gui.title) return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        val player = event.whoClicked as? Player ?: return
        val slot = event.slot
        if (slot == gui.BACK_SLOT) return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
        val effect = EffectRegistry.effects.getOrNull(slot) ?: return

        val manager = plugin.killEffectManager
        val owned = manager.hasEffect(player, effect.id)
        val equipped = manager.getEquipped(player)?.id == effect.id

        when {
            equipped -> {
                manager.unequip(player)
                Sounds.click(player)
                player.sendMessage("${ChatColor.GRAY}[${effect.displayName.replace("&", "§")}${ChatColor.GRAY}] 장착 해제했습니다.")
            }
            owned -> {
                manager.equip(player, effect.id)
                Sounds.equip(player)
                player.sendMessage("${ChatColor.GREEN}[${effect.displayName.replace("&", "§")}${ChatColor.GREEN}] 장착했습니다.")
            }
            else -> {
                val souls = plugin.soulManager.getSouls(player)
                if (souls < effect.price) {
                    player.sendMessage("${ChatColor.RED}영혼이 부족합니다. (보유: $souls / 필요: ${effect.price})")
                    Sounds.fail(player)
                    return
                }
                plugin.soulManager.removeSouls(player, effect.price)
                manager.buyEffect(player, effect.id)
                Sounds.purchase(player)
                EffectPlayer.play(effect, player.location) // 산 이펙트 미리보기
                player.sendMessage("${ChatColor.GREEN}[${effect.displayName.replace("&", "§")}${ChatColor.GREEN}] 구매 완료!")
            }
        }

        gui.open(player)
    }

    @EventHandler
    fun onDrag(event: InventoryDragEvent) {
        if (event.view.title == gui.title) event.isCancelled = true
    }
}