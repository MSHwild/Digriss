package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class RankCommand(private val plugin: Digriss) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        val sorted = plugin.rankManager.getSortedPlayers().take(10)

        sender.sendMessage("${ChatColor.GOLD}${ChatColor.BOLD}=== 랭크 순위 TOP 10 ===")
        sorted.forEachIndexed { index, player ->
            val score = plugin.rankManager.getScore(player)
            val tier = plugin.rankManager.getTier(player)
            val tierName = ChatColor.translateAlternateColorCodes('&', tier.color + tier.name)
            sender.sendMessage("${ChatColor.YELLOW}${index + 1}. ${ChatColor.WHITE}${player.name} ${ChatColor.GRAY}- $tierName ${ChatColor.GRAY}($score)")
        }

        return true
    }
}