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
    }

    private val file = File(plugin.dataFolder, "war-score.yml")
    private val scores = mutableMapOf<String, MutableMap<String, Int>>() // "A|B" -> (국가 -> 킬 수)
    private val recentKills = HashMap<UUID, HashMap<UUID, Long>>()

    init {
        if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            config.getKeys(false).forEach { key ->
                val section = config.getConfigurationSection(key) ?: return@forEach
                scores[key] = section.getKeys(false).associateWith { section.getInt(it) }.toMutableMap()
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
        val war = scores.remove(key(a, b)) ?: return false
        save()
        val scoreA = war[a] ?: 0
        val scoreB = war[b] ?: 0
        if (scoreA == scoreB) {
            Bukkit.broadcastMessage("§7☮ [전쟁 결과] '$a' $scoreA : $scoreB '$b' 무승부로 끝났습니다.")
            return false
        }

        val (winner, loser) = if (scoreA > scoreB) a to b else b to a
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

    // 국가 해체·멸망 시 관련 점수 제거
    fun removeNation(name: String) {
        if (scores.keys.removeAll { it.split("|").contains(name) }) save()
    }

    fun resetAll() {
        scores.clear()
        recentKills.clear()
        save()
    }

    private fun save() {
        val config = YamlConfiguration()
        scores.forEach { (key, war) -> war.forEach { (nation, kills) -> config.set("$key.$nation", kills) } }
        runCatching { config.save(file) }.onFailure { plugin.logger.severe("war-score.yml 저장 실패: ${it.message}") }
    }
}
