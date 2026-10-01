package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.effect.EffectGUI
import net.md_5.bungee.api.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class KillEffectCommand(private val plugin: EffectGUI) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}플레이어만 사용할 수 있습니다.")
            return true
        }
        plugin.open(sender)
        return true
    }
}