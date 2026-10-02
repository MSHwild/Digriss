package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.achievement.Achievement
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

// 업적 달성 기록 + 장착한 칭호 + 업적용 누적 통계 (achievements.yml)
class AchievementManager(private val plugin: Digriss) {

    private class Data(
        val unlocked: MutableSet<Achievement> = mutableSetOf(),
        var title: Achievement? = null,
        var quests: Int = 0
    )

    private val file = File(plugin.dataFolder, "achievements.yml")
    private val players = HashMap<UUID, Data>()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            config.getKeys(false).forEach { key ->
                val uuid = runCatching { UUID.fromString(key) }.getOrNull() ?: return@forEach
                val s = config.getConfigurationSection(key) ?: return@forEach
                players[uuid] = Data(
                    s.getStringList("unlocked").mapNotNull { Achievement.of(it) }.toMutableSet(),
                    s.getString("title")?.let { Achievement.of(it) },
                    s.getInt("quests")
                )
            }
        }
    }

    private fun data(uuid: UUID) = players.getOrPut(uuid) { Data() }

    fun has(uuid: UUID, a: Achievement): Boolean = a in data(uuid).unlocked

    fun unlockedCount(uuid: UUID): Int = data(uuid).unlocked.size

    // 장착한 칭호 문자열 (없으면 null)
    fun titleOf(uuid: UUID): String? = data(uuid).title?.takeIf { it in data(uuid).unlocked }?.title

    fun equippedOf(uuid: UUID): Achievement? = data(uuid).title

    fun equip(uuid: UUID, a: Achievement?): Boolean {
        if (a != null && !has(uuid, a)) return false
        data(uuid).title = a
        save()
        return true
    }

    // 업적 달성. 이미 있으면 아무것도 안 함. 접속 중이면 타이틀·소리, 전체 공지
    fun unlock(player: OfflinePlayer, a: Achievement) {
        val d = data(player.uniqueId)
        if (!d.unlocked.add(a)) return
        if (d.title == null) d.title = a // 첫 칭호는 자동 장착
        save()

        Bukkit.broadcastMessage("§6🏆 §f${player.name}§e님이 업적 §f[${a.displayName}]§e을(를) 달성했습니다! §7칭호 ${a.title}")
        player.player?.let { p ->
            p.sendTitle("§6🏆 ${a.displayName}", "§7칭호 ${a.title} §7획득 §8(/칭호)", 10, 60, 20)
            Sounds.bigReward(p)
        }
    }

    // 국가원 전원에게 (오프라인 포함)
    fun unlockNation(nationName: String, a: Achievement) {
        Nation.nations[nationName]?.members?.toList()?.forEach { unlock(Bukkit.getOfflinePlayer(it), a) }
    }

    // ── 이벤트 연결 ──

    fun onKill(killer: OfflinePlayer, totalKills: Int, streak: Int, brokeStreak: Boolean) {
        if (totalKills >= 1) unlock(killer, Achievement.FIRST_BLOOD)
        if (totalKills >= 50) unlock(killer, Achievement.KILLS_50)
        if (totalKills >= 200) unlock(killer, Achievement.KILLS_200)
        if (streak >= 5) unlock(killer, Achievement.STREAK_5)
        if (streak >= 10) unlock(killer, Achievement.STREAK_10)
        if (brokeStreak) unlock(killer, Achievement.STREAK_BREAKER)
    }

    fun onQuestComplete(player: OfflinePlayer, attendanceStreak: Int) {
        val d = data(player.uniqueId)
        d.quests++
        save()
        if (d.quests >= 10) unlock(player, Achievement.QUESTS_10)
        if (d.quests >= 100) unlock(player, Achievement.QUESTS_100)
        if (attendanceStreak >= 7) unlock(player, Achievement.STREAK_DAYS_7)
    }

    fun onRankReached(player: OfflinePlayer, tierName: String) {
        val index = RankTiers.tiers.indexOfFirst { it.name == tierName }
        if (index >= RankTiers.tiers.indexOfFirst { it.name == "다이아" }) unlock(player, Achievement.RANK_DIAMOND)
        if (tierName == RankTiers.tiers.last().name) unlock(player, Achievement.RANK_DIGRISS)
    }

    // ── 저장 ──

    // /초기화: 운영자가 지급한 칭호(베타 테스터 등)만 남기고 전부 지움
    fun resetAll() {
        players.entries.removeIf { (_, d) ->
            d.unlocked.retainAll { it.manual }
            if (d.title?.manual != true) d.title = null
            d.quests = 0
            d.unlocked.isEmpty()
        }
        save()
    }

    // 운영자 회수: 달성 기록과 장착 상태 제거
    fun revoke(uuid: UUID, a: Achievement): Boolean {
        val d = data(uuid)
        if (!d.unlocked.remove(a)) return false
        if (d.title == a) d.title = null
        save()
        return true
    }

    private fun save() {
        val config = YamlConfiguration()
        players.forEach { (uuid, d) ->
            config.set("$uuid.unlocked", d.unlocked.map { it.name })
            config.set("$uuid.title", d.title?.name)
            config.set("$uuid.quests", d.quests)
        }
        runCatching { config.save(file) }.onFailure { plugin.logger.severe("[업적] achievements.yml 저장 실패: ${it.message}") }
    }
}
