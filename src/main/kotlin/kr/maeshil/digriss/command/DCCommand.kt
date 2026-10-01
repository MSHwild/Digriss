package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class DCCommand(private val plugin: Digriss) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty() && (args[0] == "지급" || args[0] == "차감")) {
            if (!sender.hasPermission("digriss.admin")) {
                sender.sendMessage("${ChatColor.RED}권한이 없습니다.")
                return true
            }
            if (args.size < 3) {
                sender.sendMessage("${ChatColor.YELLOW}사용법: /dc <지급|차감> <닉네임> <수량>")
                return true
            }

            val target = Bukkit.getOfflinePlayer(args[1])
            val amount = args[2].toLongOrNull()
            if (amount == null || amount <= 0) {
                sender.sendMessage("${ChatColor.RED}수량은 0보다 큰 숫자여야 합니다.")
                return true
            }

            if (args[0] == "지급") {
                plugin.dcManager.addDC(target, amount)
                sender.sendMessage("${ChatColor.GREEN}${target.name}님에게 DC $amount 지급했습니다.")
            } else {
                val success = plugin.dcManager.removeDC(target, amount)
                if (success) {
                    sender.sendMessage("${ChatColor.GREEN}${target.name}님의 DC $amount 차감했습니다.")
                } else {
                    val current = plugin.dcManager.getDC(target)
                    sender.sendMessage("${ChatColor.RED}보유 DC가 부족합니다. (보유: $current / 요청: $amount)")
                }
            }
            return true
        }

        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}플레이어만 사용할 수 있습니다.")
            return true
        }

        val balance = plugin.dcManager.getDC(sender)
        sender.sendMessage("${ChatColor.AQUA}보유 DC: ${ChatColor.WHITE}$balance")
        return true
    }
}