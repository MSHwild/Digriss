package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.RankManager
import kr.maeshil.digriss.manager.RankTiers
import net.md_5.bungee.api.ChatColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class MyRankCommand(private val plugin: RankManager) : CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 관리자용: /랭크 지급|차감|설정 <닉네임> <수량>
        if (args.isNotEmpty() && (args[0] == "지급" || args[0] == "차감" || args[0] == "설정")) {
            if (!sender.hasPermission("digriss.admin")) {
                sender.sendMessage("${ChatColor.RED}권한이 없습니다.")
                return true
            }
            if (args.size < 3) {
                sender.sendMessage("${ChatColor.YELLOW}사용법: /랭크 <지급|차감|설정> <닉네임> <수량>")
                return true
            }

            val target = Bukkit.getOfflinePlayer(args[1])
            val amount = args[2].toLongOrNull()
            if (amount == null || amount < 0) {
                sender.sendMessage("${ChatColor.RED}수량은 0 이상의 숫자여야 합니다.")
                return true
            }

            when (args[0]) {
                "지급" -> {
                    plugin.addScore(target, amount)
                    sender.sendMessage("${ChatColor.GREEN}${target.name}님에게 랭크 점수 $amount 지급했습니다.")
                }
                "차감" -> {
                    val success = plugin.removeScore(target, amount)
                    if (success) {
                        sender.sendMessage("${ChatColor.GREEN}${target.name}님의 랭크 점수 $amount 차감했습니다.")
                    } else {
                        val current = plugin.getScore(target)
                        sender.sendMessage("${ChatColor.RED}보유 점수가 부족합니다. (보유: $current / 요청: $amount)")
                    }
                }
                "설정" -> {
                    plugin.setScore(target, amount)
                    sender.sendMessage("${ChatColor.GREEN}${target.name}님의 랭크 점수를 $amount(으)로 설정했습니다.")
                }
            }

            plugin.save()


            return true
        }

        // 일반: 본인 랭크 확인
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}플레이어만 사용할 수 있습니다.")
            return true
        }

        val tier = plugin.getTier(sender)
        val score = plugin.getScore(sender)
        val position = plugin.getRankPosition(sender)
        val tierName = ChatColor.translateAlternateColorCodes('&', tier.color + tier.name)

        val currentIndex = RankTiers.tiers.indexOf(tier)
        val nextTier = RankTiers.tiers.getOrNull(currentIndex + 1)

        val scoreLine = if (nextTier != null) {
            "${ChatColor.AQUA}$score${ChatColor.GRAY}/${ChatColor.WHITE}${nextTier.minScore}"
        } else {
            "${ChatColor.AQUA}$score${ChatColor.GRAY}/${ChatColor.WHITE}${RankTiers.DIGRISS_MAX_SCORE}"
        }

        sender.sendMessage("${ChatColor.GOLD}${ChatColor.BOLD}=== 내 랭크 정보 ===")
        sender.sendMessage("${ChatColor.YELLOW}랭크: $tierName")
        sender.sendMessage("${ChatColor.YELLOW}점수: $scoreLine")
        sender.sendMessage("${ChatColor.YELLOW}서버 순위: ${ChatColor.WHITE}${position}위")

        return true
    }
}