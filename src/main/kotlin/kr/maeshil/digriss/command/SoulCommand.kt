package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class SoulCommand(private val plugin: Digriss) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("${ChatColor.RED}권한이 없습니다.")
            return true
        }

        if (args.size < 3) {
            sender.sendMessage("${ChatColor.YELLOW}사용법: /영혼 <지급|차감|설정> <닉네임> <수량>")
            return true
        }

        val action = args[0]
        val targetName = args[1]
        val amount = args[2].toLongOrNull()

        if (amount == null) {
            sender.sendMessage("${ChatColor.RED}수량은 숫자여야 합니다.")
            return true
        }

        val target = Bukkit.getOfflinePlayer(targetName)
        if (!target.hasPlayedBefore() && !target.isOnline) {
            sender.sendMessage("${ChatColor.RED}존재하지 않는 플레이어입니다.")
            return true
        }

        when (action) {
            "지급" -> {
                plugin.soulManager.addSouls(target, amount)
                sender.sendMessage("${ChatColor.GREEN}${target.name}님에게 영혼 $amount 지급했습니다.")
            }
            "차감" -> {
                val success = plugin.soulManager.removeSouls(target, amount)
                if (success) sender.sendMessage("${ChatColor.GREEN}${target.name}님의 영혼 $amount 차감했습니다.")
                else sender.sendMessage("${ChatColor.RED}보유 영혼이 부족합니다.")
            }
            "설정" -> {
                plugin.soulManager.setSouls(target, amount)
                sender.sendMessage("${ChatColor.GREEN}${target.name}님의 영혼을 $amount(으)로 설정했습니다.")
            }
            else -> {
                sender.sendMessage("${ChatColor.YELLOW}사용법: /영혼 <지급|차감|설정> <닉네임> <수량>")
                return true
            }
        }

        plugin.soulManager.save()
        return true
    }
}