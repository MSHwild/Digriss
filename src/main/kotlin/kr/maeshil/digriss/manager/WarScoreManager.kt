package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.UUID

// 전쟁 점수: 전쟁 중 상대 국가원을 처치하면 점수 + 보너스 영혼,
// 휴전할 때 점수가 높은 국가가 상대 금고의 일부를 가져감
class WarScoreManager(private val plugin: Digriss) {

    companion object {
        const val KILL_BONUS_SOULS = 5L       // 전쟁 킬 1회당 보너스 영혼
        const val TRUCE_BANK_RATE = 0.10      // 휴전 시 승리국이 가져가는 패배국 금고 비율
        private const val REPEAT_WINDOW_MS = 10 * 60 * 1000L // 같은 상대 10분 내 재처치는 점수 제외

        // 전쟁 종료 시 랭크 점수 = 국가 결과 점수 + 개인 기여 점수
        const val RANK_WIN_BASE = 30L         // 휴전 승리 기본
        const val RANK_WIN_PER_DIFF = 2L      // 전쟁 점수 차 1당 추가
        const val RANK_WIN_MAX = 70L          // 휴전 승리 국가 점수 상한
        const val RANK_CONQUER = 80L          // 신호기 점령 승리
        const val RANK_DRAW = 10L             // 무승부
        const val RANK_PER_KILL = 8L          // 내 전쟁 킬 1회당
        const val RANK_KILL_MAX = 80L         // 개인 기여 점수 상한
    }

    private val file = File(plugin.dataFolder, "war-score.yml")
    private val scores = mutableMapOf<String, MutableMap<String, Int>>() // "A|B" -> (국가 -> 킬 수)
    private val contributions = mutableMapOf<String, MutableMap<UUID, Int>>() // "A|B" -> (플레이어 -> 전쟁 킬 수)
    private val recentKills = HashMap<UUID, HashMap<UUID, Long>>()

    init {
        if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            config.getConfigurationSection("scores")?.let { all ->
                all.getKeys(false).forEach { key ->
                    val s = all.getConfigurationSection(key) ?: return@forEach
                    scores[key] = s.getKeys(false).associateWith { s.getInt(it) }.toMutableMap()
                }
            }
            config.getConfigurationSection("contrib")?.let { all ->
                all.getKeys(false).forEach { key ->
                    val s = all.getConfigurationSection(key) ?: return@forEach
                    contributions[key] = s.getKeys(false).mapNotNull { id ->
                        runCatching { UUID.fromString(id) }.getOrNull()?.let { it to s.getInt(id) }
                    }.toMap().toMutableMap()
                }
            }
        }
    }

    private fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    fun scoreOf(nation: String, enemy: String): Int = scores[key(nation, enemy)]?.get(nation) ?: 0

    // Event의 킬 처리에서 호출
    fun recordKill(killer: Player, victim: Player) {
        val nations = plugin.nationManager
        val killerNation = nations.getNationName(killer.uniqueId) ?: return
        val victimNation = nations.getNationName(victim.uniqueId) ?: return
        if (!nations.isAtWarBetween(killerNation, victimNation)) return

        // 같은 상대를 반복해서 잡는 점수 몰아주기 방지
        val now = System.currentTimeMillis()
        val history = recentKills.getOrPut(killer.uniqueId) { HashMap() }
        history.entries.removeIf { now - it.value >= REPEAT_WINDOW_MS }
        val repeated = history.containsKey(victim.uniqueId)
        history[victim.uniqueId] = now
        if (repeated) {
            killer.sendMessage("§7같은 상대를 10분 안에 다시 처치해 전쟁 점수가 오르지 않습니다.")
            return
        }

        val war = scores.getOrPut(key(killerNation, victimNation)) { mutableMapOf() }
        war[killerNation] = (war[killerNation] ?: 0) + 1
        val contrib = contributions.getOrPut(key(killerNation, victimNation)) { mutableMapOf() }
        contrib[killer.uniqueId] = (contrib[killer.uniqueId] ?: 0) + 1
        save()

        plugin.soulManager.addSouls(killer, KILL_BONUS_SOULS)
        val mine = war[killerNation] ?: 0
        val theirs = war[victimNation] ?: 0
        val message = "§4⚔ 전쟁 점수 §a$killerNation $mine §7: §c$theirs $victimNation"
        Nation.nations[killerNation]?.members?.forEach { Bukkit.getPlayer(it)?.sendMessage(message) }
        Nation.nations[victimNation]?.members?.forEach {
            Bukkit.getPlayer(it)?.sendMessage("§4⚔ 전쟁 점수 §c$victimNation $theirs §7: §a$mine $killerNation")
        }
        killer.sendMessage("§b전쟁 킬 보너스 영혼 +$KILL_BONUS_SOULS")
    }

    // 휴전 시 정산: 점수가 높은 국가가 패배국 금고의 10%를 가져감. 금고가 바뀌었으면 true
    fun settle(a: String, b: String): Boolean {
        val war = scores.remove(key(a, b)) ?: mutableMapOf()
        val contrib = contributions.remove(key(a, b)) ?: mutableMapOf()
        save()
        val scoreA = war[a] ?: 0
        val scoreB = war[b] ?: 0
        if (scoreA == scoreB) {
            Bukkit.broadcastMessage("§7☮ [전쟁 결과] '$a' $scoreA : $scoreB '$b' 무승부로 끝났습니다.")
            rewardRank(a, RANK_DRAW, contrib, "무승부")
            rewardRank(b, RANK_DRAW, contrib, "무승부")
            return false
        }

        val (winner, loser) = if (scoreA > scoreB) a to b else b to a
        val diff = kotlin.math.abs(scoreA - scoreB)
        rewardRank(winner, (RANK_WIN_BASE + RANK_WIN_PER_DIFF * diff).coerceAtMost(RANK_WIN_MAX), contrib, "승리")
        rewardRank(loser, 0L, contrib, "패배")
        val winNation = Nation.nations[winner] ?: return false
        val loseNation = Nation.nations[loser] ?: return false
        val amount = Math.floor(loseNation.bank * TRUCE_BANK_RATE)
        loseNation.bank -= amount
        winNation.bank += amount

        Bukkit.broadcastMessage(
            "§6🏆 [전쟁 결과] '$winner' 국가가 '$loser' 국가에게 ${maxOf(scoreA, scoreB)} : ${minOf(scoreA, scoreB)}로 승리! " +
                "§e배상금 ${String.format("%,.0f", amount)}원§6을 가져갑니다."
        )
        return amount > 0
    }

    // 신호기 점령으로 전쟁이 끝날 때 (NationManager.conquerNation에서 국가 데이터가 지워지기 전에 호출)
    fun settleConquest(attacker: String, defender: String) {
        val contrib = contributions.remove(key(attacker, defender)) ?: mutableMapOf()
        scores.remove(key(attacker, defender))
        save()
        rewardRank(attacker, RANK_CONQUER, contrib, "점령 승리")
        rewardRank(defender, 0L, contrib, "멸망")
    }

    // 국가 결과 점수 + 개인 기여 점수(전쟁 킬)를 국가원에게 지급. 전쟁 킬이 없으면 국가 결과 점수의 절반만
    private fun rewardRank(nationName: String, nationPart: Long, contrib: Map<UUID, Int>, result: String) {
        val members = Nation.nations[nationName]?.members ?: return
        for (uuid in members) {
            val kills = contrib[uuid] ?: 0
            val personal = (kills * RANK_PER_KILL).coerceAtMost(RANK_KILL_MAX)
            val base = if (kills > 0) nationPart else nationPart / 2
            val total = base + personal
            if (total <= 0) continue
            plugin.rankManager.addScore(Bukkit.getOfflinePlayer(uuid), total)
            Bukkit.getPlayer(uuid)?.sendMessage(
                "§d[전쟁 $result] §f랭크 점수 §a+$total §7(국가 결과 $base + 내 기여 $personal, 전쟁 킬 ${kills}회)"
            )
        }
    }

    // 국가 해체·멸망 시 관련 점수 제거
    fun removeNation(name: String) {
        val removedScores = scores.keys.removeAll { it.split("|").contains(name) }
        val removedContrib = contributions.keys.removeAll { it.split("|").contains(name) }
        if (removedScores || removedContrib) save()
    }

    fun resetAll() {
        scores.clear()
        contributions.clear()
        recentKills.clear()
        save()
    }

    private fun save() {
        val config = YamlConfiguration()
        scores.forEach { (key, war) -> war.forEach { (nation, kills) -> config.set("scores.$key.$nation", kills) } }
        contributions.forEach { (key, c) -> c.forEach { (uuid, kills) -> config.set("contrib.$key.$uuid", kills) } }
        runCatching { config.save(file) }.onFailure { plugin.logger.severe("war-score.yml 저장 실패: ${it.message}") }
    }
}
