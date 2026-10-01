package kr.maeshil.digriss.job

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class JobPurchaseCommand(private val jobManager: JobManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        JobGUI.openPurchase(player, jobManager)
        return true
    }
}

class JobConfirmCommand(private val jobManager: JobManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        JobGUI.openConfirm(player, jobManager)
        return true
    }
}