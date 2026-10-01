package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.Team

class RankNameTag(private val plugin: Digriss) {

    /**
     * 스코어보드 팀 기능을 이용해 닉네임 위 네임태그 + TAB 목록에 랭크 접두사 표시
     */
    fun updateNameTag(player: Player) {
        val tier = plugin.rankManager.getTier(player)
        val scoreboard = Bukkit.getScoreboardManager()?.mainScoreboard ?: return

        val teamName = "rank_${tier.name}"
        var team: Team? = scoreboard.getTeam(teamName)
        if (team == null) {
            team = scoreboard.registerNewTeam(teamName)
        }

        team.prefix = ChatColor.translateAlternateColorCodes('&', "${tier.color}[${tier.name}] ")

        // 기존 다른 랭크 팀에서 제거 후 새 팀에 추가
        scoreboard.teams.forEach { it.removeEntry(player.name) }
        team.addEntry(player.name)
    }

    
}