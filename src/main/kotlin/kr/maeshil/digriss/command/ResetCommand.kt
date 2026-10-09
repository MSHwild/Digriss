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

    // /초기화        → DC까지 전부 초기화 (서버 처음 열 때용)
    // /초기화 시즌   → DC만 남기고 전부 초기화 (시즌 종료용)
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("${ChatColor.RED}권한이 없습니다.")
            return true
        }

        val season = args.getOrNull(0) == "시즌"
        val confirmArg = if (season) args.getOrNull(1) else args.getOrNull(0)
        val key = sender.name + if (season) ":season" else ":all"

        if (confirmArg == "확인" && pendingConfirm.contains(key)) {
            pendingConfirm.remove(key)
            val moneyReset = executeReset(keepDC = season)
            if (season) plugin.seasonManager.markReset() // 오픈일에 자동으로 열 수 있게 표시
            plugin.adminLogManager.log(sender, if (season) "시즌 초기화 (DC 유지)" else "서버 전체 데이터 초기화")
            sender.sendMessage("${ChatColor.GREEN}${if (season) "시즌 초기화를 완료했습니다. (DC 유지)" else "서버 전체 데이터를 초기화했습니다."}")
            if (!moneyReset) {
                sender.sendMessage("${ChatColor.YELLOW}Vault 경제 플러그인을 찾을 수 없어 돈 초기화는 건너뛰었습니다.")
            }
            if (season) {
                sender.sendMessage("${ChatColor.YELLOW}남은 작업: 서버를 끄고 월드를 원본 지구 지도로 교체, Essentials 홈 정리. 오픈일이 되면 자동으로 열립니다 (/시즌)")
            }
            Bukkit.broadcastMessage("${ChatColor.RED}[알림] ${if (season) "시즌 데이터가 초기화되었습니다." else "서버 데이터가 전체 초기화되었습니다."}")
            return true
        }

        pendingConfirm.add(key)
        sender.sendMessage("${ChatColor.RED}${ChatColor.BOLD}경고: 이 작업은 되돌릴 수 없습니다.")
        if (season) {
            sender.sendMessage("${ChatColor.RED}DC를 뺀 모든 데이터가 삭제됩니다: 영혼, 돈, 직업, 국가·영토·창고·기술·외교, 자원 거점, 거래소, K/D, 랭크, 칭호, 킬이펙트, 퀘스트")
            sender.sendMessage("${ChatColor.YELLOW}정말로 진행하려면 10초 안에 ${ChatColor.WHITE}/초기화 시즌 확인${ChatColor.YELLOW}을 입력하세요.")
        } else {
            sender.sendMessage("${ChatColor.RED}영혼, 돈, DC, 국가, K/D, 랭크, 킬이펙트 등 모든 유저 데이터가 삭제됩니다.")
            sender.sendMessage("${ChatColor.GRAY}시즌이 끝나서 DC는 남기려면 /초기화 시즌 을 쓰세요.")
            sender.sendMessage("${ChatColor.YELLOW}정말로 진행하려면 10초 안에 ${ChatColor.WHITE}/초기화 확인${ChatColor.YELLOW}을 입력하세요.")
        }

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            pendingConfirm.remove(key)
        }, 200L) // 10초 후 확인 만료

        return true
    }

    /** @return Vault 돈 초기화까지 성공했으면 true */
    private fun executeReset(keepDC: Boolean): Boolean {
        plugin.soulManager.resetAll()
        plugin.kdManager.resetAll()
        plugin.rankManager.resetAll()
        plugin.killEffectManager.resetAll()
        if (!keepDC) plugin.dcManager.resetAll()
        plugin.nationManager.resetAll()
        plugin.allianceManager.resetAll()
        plugin.diplomacyManager.resetAll()
        plugin.marketManager.resetAll()
        plugin.warScoreManager.resetAll()
        plugin.nationStorageManager.resetAll()
        plugin.nationTechManager.resetAll()
        plugin.resourceSiteManager.resetAll()
        plugin.jobManager.resetAll()
        plugin.questManager.resetAll()
        plugin.achievementManager.resetAll()

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