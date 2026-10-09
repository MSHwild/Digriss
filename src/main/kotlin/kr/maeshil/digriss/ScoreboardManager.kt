package kr.maeshil.digriss

import kr.maeshil.digriss.effect.KillEffectManager
import kr.maeshil.digriss.job.JobManager
import kr.maeshil.digriss.manager.DCManager
import kr.maeshil.digriss.manager.AllianceManager
import kr.maeshil.digriss.manager.KDManager
import kr.maeshil.digriss.manager.RankTiers
import kr.maeshil.digriss.manager.RankManager
import kr.maeshil.digriss.manager.SoulManager
import kr.maeshil.digriss.manager.NationManager
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.entity.Player
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard

class ScoreboardManager(
    private val soulManager: SoulManager,
    private val kdManager: KDManager,
    private val rankManager: RankManager,
    private val killEffectManager: KillEffectManager,
    private val jobManager: JobManager,
    private val nationManager: NationManager,
    private val dcManager: DCManager,
    private val allianceManager: AllianceManager
) {

    private var economy: Economy? = null

    // Vault 경제 플러그인이 늦게 로드될 수 있어서 처음 필요할 때 연결
    private fun getEconomy(): Economy? {
        if (economy == null) {
            economy = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider
        }
        return economy
    }

    fun update(player: Player) {
        val manager = Bukkit.getScoreboardManager() ?: return
        val scoreboard: Scoreboard = player.scoreboard.takeIf { it != manager.mainScoreboard }
            ?: manager.newScoreboard

        var objective = scoreboard.getObjective("digriss")
        if (objective == null) {
            objective = scoreboard.registerNewObjective("digriss", "dummy", "§6§l디그리스")
            objective.displaySlot = DisplaySlot.SIDEBAR
        } else {
            scoreboard.entries.forEach { scoreboard.resetScores(it) }
        }

        val uuid = player.uniqueId
        val soul = soulManager.getSouls(player)
        val rank = rankManager.getTier(player)
        val nation = nationManager.getNationName(uuid) ?: "§8없음"
        val wars = nationManager.warsOfPlayer(uuid)
        val allies = allianceManager.alliesOfPlayer(player)
        val allyText = when {
            allies.isEmpty() -> "§8없음"
            allies.size <= 2 -> "§b" + allies.joinToString(", ")
            else -> "§b${allies[0]} 외 ${allies.size - 1}개국"
        }
        val warText = when {
            wars.isEmpty() -> "§8없음"
            wars.size <= 2 -> "§c" + wars.joinToString(", ")
            else -> "§c${wars[0]} 외 ${wars.size - 1}개국"
        }
        val job = jobManager.getJob(uuid)
        val online = Bukkit.getOnlinePlayers().size
        val rankColor = ChatColor.translateAlternateColorCodes('&', rank.color)

        val money = getEconomy()?.let { String.format("%,.0f", it.getBalance(player)) } ?: "§8-"
        val dc = dcManager.getDC(player)

        // 이모지·장식 기호 없이 "항목  값" 형태로만 정리 (내 정보 / 국가 / 서버 순)
        val lines = listOf(
            "",
            " §f${player.name}",
            " §7랭크  $rankColor${rank.name}",
            " §7직업  §f${job?.displayName ?: "§8없음"}",
            "",
            " §7돈  §e${money}원",
            " §7영혼  §b$soul",
            " §7DC  §3$dc",
            "",
            " §7국가  §a$nation",
            " §7전쟁  $warText",
            " §7연합  $allyText",
            "",
            " §8접속 ${online}명 · puritymc.kr"
        ).let { base ->
            // 시즌 종료가 가까우면 맨 위에 D-day 표시
            val season = (Bukkit.getPluginManager().getPlugin("Digriss") as? Digriss)?.seasonManager?.scoreboardLine()
            if (season == null) base else listOf("", season) + base
        }

        lines.reversed().forEachIndexed { index, line ->
            // 빈 줄/중복 라인은 스코어보드에서 하나로 합쳐지므로 뒤에 §r을 붙여 유니크하게 만듦
            val uniqueLine = if (line.isEmpty()) "§r".repeat(index + 1) else line + "§r".repeat(index + 1)
            objective.getScore(uniqueLine).score = index
        }

        updateNameColors(player, scoreboard)
        player.scoreboard = scoreboard
    }

    // 보는 사람 기준 이름표: [랭크] 접두사 + 관계 색 (같은 국가 초록, 전쟁 중인 국가 빨강, 나머지 흰색)
    // 각 플레이어가 자기 스코어보드를 쓰므로 사람마다 다르게 보임 (머리 위 이름표 + TAB 목록)
    private fun updateNameColors(viewer: Player, scoreboard: Scoreboard) {
        val myNation = nationManager.getNationName(viewer.uniqueId)
        val wars = nationManager.warsOfPlayer(viewer.uniqueId)
        val allies = allianceManager.alliesOfPlayer(viewer)

        Bukkit.getOnlinePlayers().forEach { other ->
            val otherNation = nationManager.getNationName(other.uniqueId)
            val (relation, color) = when {
                myNation != null && otherNation == myNation -> "ally" to org.bukkit.ChatColor.GREEN
                otherNation != null && otherNation in wars -> "enemy" to org.bukkit.ChatColor.RED
                otherNation != null && otherNation in allies -> "allied" to org.bukkit.ChatColor.AQUA
                else -> "none" to org.bukkit.ChatColor.WHITE
            }
            val tierIndex = RankTiers.tiers.indexOf(rankManager.getTier(other))
            val tier = RankTiers.tiers[tierIndex]

            // 팀 하나 = (관계, 랭크) 조합. 한 이름은 한 팀에만 속하므로 addEntry 시 이전 팀에서 자동으로 빠짐
            val teamName = "dg_${relation}_$tierIndex"
            val team = scoreboard.getTeam(teamName) ?: scoreboard.registerNewTeam(teamName).apply {
                this.color = color
                prefix = ChatColor.translateAlternateColorCodes('&', "${tier.color}[${tier.name}] ")
            }
            if (!team.hasEntry(other.name)) team.addEntry(other.name)
        }
    }
}