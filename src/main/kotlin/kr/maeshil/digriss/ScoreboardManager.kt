package kr.maeshil.digriss

import kr.maeshil.digriss.effect.KillEffectManager
import kr.maeshil.digriss.job.JobManager
import kr.maeshil.digriss.manager.DCManager
import kr.maeshil.digriss.manager.KDManager
import kr.maeshil.digriss.manager.RankManager
import kr.maeshil.digriss.manager.SoulManager
import kr.maeshil.digriss.nation.Nation_D
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
    private val nationManager: Nation_D,
    private val dcManager: DCManager
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
            objective = scoreboard.registerNewObjective("digriss", "dummy", "§8§m      §r §b§l⚔ §f§lD I G R I S S §b§l⚔ §8§m      ")
            objective.displaySlot = DisplaySlot.SIDEBAR
        } else {
            scoreboard.entries.forEach { scoreboard.resetScores(it) }
        }

        val uuid = player.uniqueId
        val soul = soulManager.getSouls(player)
        val rank = rankManager.getTier(player)
        val nation = nationManager.getNationName(uuid) ?: "§8없음"
        val equippedEffect = killEffectManager.getEquipped(player)
        val job = jobManager.getJob(uuid)
        val online = Bukkit.getOnlinePlayers().size
        val rankColor = ChatColor.translateAlternateColorCodes('&', rank.color)

        val money = getEconomy()?.let { String.format("%,.0f", it.getBalance(player)) } ?: "§8-"
        val dc = dcManager.getDC(player)

        val lines = listOf(
            "§8§m――――――――――――§r",
            " §b✦ §7영혼 §8» §b§l$soul",
            " §6✦ §7돈 §8» §6§l$money§7원",
            " §3✦ §7DC §8» §3§l$dc",
            " §d✦ §7랭크 §8» $rankColor§l${rank.name}",
            " §e✦ §7직업 §8» §e§l${job?.displayName ?: "§8없음"}",
            "",
            " §a✦ §7국가 §8» §a$nation",
            " §c✦ §7이펙트 §8» §c${equippedEffect?.displayName ?: "§8없음"}",
            "§8§m――――――――――――§r",
            " §f접속자 §8» §f§l$online§7명",
            "§8§m――――――――――――§r"
        )

        lines.reversed().forEachIndexed { index, line ->
            // 빈 줄/중복 라인은 스코어보드에서 하나로 합쳐지므로 뒤에 §r을 붙여 유니크하게 만듦
            val uniqueLine = if (line.isEmpty()) "§r".repeat(index + 1) else line + "§r".repeat(index + 1)
            objective.getScore(uniqueLine).score = index
        }

        player.scoreboard = scoreboard
    }
}