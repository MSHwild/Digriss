package kr.maeshil.digriss

import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

class ChatEvent(private val plugin: Digriss) : Listener {

    @EventHandler
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        val tier = plugin.rankManager.getTier(player)
        val coloredTier = ChatColor.translateAlternateColorCodes('&', tier.color + tier.name)

        val title = plugin.achievementManager.titleOf(player.uniqueId)?.let { "$it " } ?: ""
        event.format = "$title${ChatColor.GRAY}[$coloredTier${ChatColor.GRAY}] ${ChatColor.WHITE}${player.name}${ChatColor.GRAY}: ${ChatColor.RESET}%2\$s"
    }
}