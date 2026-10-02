package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

data class RankTier(val name: String, val color: String, val minScore: Long)

object RankTiers {
    val tiers = listOf(
        RankTier("언랭크","&f",0),
        RankTier("브론즈", "&6", 100),
        RankTier("실버", "&7", 200),
        RankTier("골드", "&6", 300),
        RankTier("플래티넘", "&b", 400),
        RankTier("다이아", "&3", 500),
        RankTier("장교", "&c", 700),
        RankTier("황제", "&e", 800),
        RankTier("디그리스", "&2&l", 1000)
    )

    val DIGRISS_MAX_SCORE = tiers.last().minScore + 2000L // 디그리스 상한 = 진입점수 + 2000

    fun getTier(score: Long): RankTier {
        return tiers.lastOrNull { score >= it.minScore } ?: tiers.first()
    }

    fun capScore(score: Long): Long {
        return if (score >= tiers.last().minScore) score.coerceAtMost(DIGRISS_MAX_SCORE) else score
    }
}

class RankManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "rank.yml")
    private lateinit var config: YamlConfiguration
    private val scores = mutableMapOf<UUID, Long>()

    private val recentKills = mutableMapOf<UUID, MutableMap<UUID, Long>>()
    private val REPEAT_WINDOW_MS = 5 * 60 * 1000L

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            scores[UUID.fromString(key)] = config.getLong(key)
        }
    }

    fun save() {
        scores.forEach { (uuid, score) -> config.set(uuid.toString(), score) }
        config.save(file)
    }

    fun getScore(player: OfflinePlayer): Long = scores[player.uniqueId] ?: 0L

    fun getTier(player: OfflinePlayer): RankTier = RankTiers.getTier(getScore(player))

    fun getSortedPlayers(): List<OfflinePlayer> {
        return Bukkit.getOfflinePlayers()
            .filter { it.hasPlayedBefore() }
            .sortedByDescending { getScore(it) }
    }

    fun getRankPosition(player: OfflinePlayer): Int {
        val sorted = getSortedPlayers()
        val index = sorted.indexOfFirst { it.uniqueId == player.uniqueId }
        return if (index == -1) sorted.size + 1 else index + 1
    }

    // 얻은 랭크 점수를 돌려줌 (킬 보상 알림용)
    fun onKill(killer: Player, victim: Player, killStreak: Int): Long {
        val killerScore = getScore(killer)
        val victimScore = getScore(victim)

        val baseScore = 30.0

        val diff = (victimScore - killerScore).coerceIn(-2000, 2000)
        var rankMultiplier = 1.0 + (diff / 2000.0) * 0.7
        rankMultiplier = rankMultiplier.coerceIn(0.3, 1.7)

        val streakMultiplier = when {
            killStreak >= 10 -> 2.0
            killStreak >= 5 -> 1.5
            killStreak >= 3 -> 1.2
            else -> 1.0
        }

        val now = System.currentTimeMillis()
        val history = recentKills.getOrPut(killer.uniqueId) { mutableMapOf() }
        val repeatCount = history.entries.count { (uuid, time) ->
            uuid == victim.uniqueId && now - time < REPEAT_WINDOW_MS
        }
        val repeatMultiplier = when (repeatCount) {
            0 -> 1.0
            1 -> 0.7
            2 -> 0.4
            else -> 0.2
        }
        history[victim.uniqueId] = now

        val finalGain = (baseScore * rankMultiplier * streakMultiplier * repeatMultiplier).toLong().coerceAtLeast(5)

        val newScore = RankTiers.capScore(getScore(killer) + finalGain)
        scores[killer.uniqueId] = newScore
        killer.sendMessage("${ChatColor.GREEN}랭크 점수 +$finalGain")

        checkPromotion(killer)
        return finalGain
    }

    fun onDeath(victim: Player) {
        val current = getScore(victim)
        val penalty = (current * 0.05).toLong().coerceIn(10, 100)
        scores[victim.uniqueId] = (current - penalty).coerceAtLeast(0)
        victim.sendMessage("${ChatColor.RED}랭크 점수 -$penalty")
    }

    private fun checkPromotion(player: Player) {
        val tier = getTier(player)
        player.sendMessage("${ChatColor.translateAlternateColorCodes('&', tier.color)}현재 랭크: ${tier.name}")
    }

    fun resetAll() {
        scores.clear()
        recentKills.clear()
        config.getKeys(false).forEach { config.set(it, null) }
        save()
    }

    fun addScore(player: OfflinePlayer, amount: Long) {
        val newScore = RankTiers.capScore(getScore(player) + amount)
        scores[player.uniqueId] = newScore
    }

    fun removeScore(player: OfflinePlayer, amount: Long): Boolean {
        val current = getScore(player)
        if (current < amount) return false
        scores[player.uniqueId] = current - amount
        return true
    }

    fun setScore(player: OfflinePlayer, amount: Long) {
        scores[player.uniqueId] = RankTiers.capScore(amount.coerceAtLeast(0))
    }
}