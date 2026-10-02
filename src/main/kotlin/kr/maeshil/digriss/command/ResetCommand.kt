package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender

class ResetCommand(private val plugin: Digriss) : CommandExecutor {

    private val pendingConfirm = mutableSetOf<String>()

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("${ChatColor.RED}권한이 없습니다.")
            return true
        }

        val senderName = sender.name

        if (args.isNotEmpty() && args[0] == "확인" && pendingConfirm.contains(senderName)) {
            pendingConfirm.remove(senderName)
            val moneyReset = executeReset()
            sender.sendMessage("${ChatColor.GREEN}서버 전체 데이터를 초기화했습니다.")
            if (!moneyReset) {
                sender.sendMessage("${ChatColor.YELLOW}Vault 경제 플러그인을 찾을 수 없어 돈 초기화는 건너뛰었습니다.")
            }
            Bukkit.broadcastMessage("${ChatColor.RED}[알림] 서버 데이터가 전체 초기화되었습니다.")
            return true
        }

        pendingConfirm.add(senderName)
        sender.sendMessage("${ChatColor.RED}${ChatColor.BOLD}경고: 이 작업은 되돌릴 수 없습니다.")
        sender.sendMessage("${ChatColor.RED}영혼, 돈, DC, 국가, K/D, 랭크, 킬이펙트 등 모든 유저 데이터가 삭제됩니다.")
        sender.sendMessage("${ChatColor.YELLOW}정말로 진행하려면 10초 안에 ${ChatColor.WHITE}/초기화 확인${ChatColor.YELLOW}을 입력하세요.")

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pendingConfirm.remove(senderName)
        }, 200L) // 10초 후 확인 만료

        return true
    }

    /** @return Vault 돈 초기화까지 성공했으면 true */
    private fun executeReset(): Boolean {
        plugin.soulManager.resetAll()
        plugin.kdManager.resetAll()
        plugin.rankManager.resetAll()
        plugin.killEffectManager.resetAll()
        plugin.dcManager.resetAll()
        plugin.nationManager.resetAll()
        plugin.questManager.resetAll()

        return resetMoney()
    }

    /** 서버에 접속 기록이 있는 모든 유저(오프라인 포함)의 Vault 잔액을 0으로 만듦 */
    private fun resetMoney(): Boolean {
        val economy = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider ?: return false

        for (offline in Bukkit.getOfflinePlayers()) {
            if (!economy.hasAccount(offline)) continue
            val balance = economy.getBalance(offline)
            when {
                balance > 0.0 -> economy.withdrawPlayer(offline, balance)
                balance < 0.0 -> economy.depositPlayer(offline, -balance)
            }
        }
        return true
    }
}