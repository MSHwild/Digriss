package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File

// 국가 간 연합: 연합국끼리는 서로 공격할 수 없고 전쟁을 선포할 수 없음
class AllianceManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "alliance.yml")
    private val alliances = mutableSetOf<String>()                       // "A|B" (이름순 정렬)
    private val requests = mutableMapOf<String, MutableSet<String>>()    // 받는 국가 -> 요청한 국가들 (저장 안 함)

    companion object {
        const val MAX_ALLIES = 3 // 국가당 최대 연합국 수
    }

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (file.exists()) alliances.addAll(YamlConfiguration.loadConfiguration(file).getStringList("alliances"))
    }

    private fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    fun areAllied(a: String?, b: String?): Boolean =
        a != null && b != null && a != b && alliances.contains(key(a, b))

    fun alliesOf(name: String): List<String> =
        alliances.mapNotNull { k ->
            val parts = k.split("|")
            when (name) {
                parts.getOrNull(0) -> parts.getOrNull(1)
                parts.getOrNull(1) -> parts.getOrNull(0)
                else -> null
            }
        }

    fun alliesOfPlayer(player: Player): List<String> =
        plugin.nationManager.getNationName(player.uniqueId)?.let { alliesOf(it) } ?: emptyList()

    // 아군 판정: 본인, 같은 국가, 연합국 (무소속은 본인만 아군)
    fun isFriendly(a: Player, b: Player): Boolean {
        if (a == b) return true
        val na = plugin.nationManager.getNationName(a.uniqueId) ?: return false
        val nb = plugin.nationManager.getNationName(b.uniqueId) ?: return false
        return na == nb || areAllied(na, nb)
    }

    // 두 국가 모두 연합 한도에 여유가 있는지 확인, 아니면 안내 후 false
    private fun checkLimit(player: Player, me: String, target: String): Boolean {
        if (alliesOf(me).size >= MAX_ALLIES) {
            deny(player, "§c연합은 최대 ${MAX_ALLIES}개국까지 맺을 수 있습니다. 기존 연합을 해제하세요.")
            return false
        }
        if (alliesOf(target).size >= MAX_ALLIES) {
            deny(player, "§c'$target' 국가는 이미 연합 한도(${MAX_ALLIES}개국)에 도달했습니다.")
            return false
        }
        return true
    }

    fun hasRequest(to: String, from: String): Boolean = requests[to]?.contains(from) == true

    // ───────────────────────── 요청 / 수락 / 해제 ─────────────────────────

    // 국가 지도자인지 확인하고 소속 국가 이름을 돌려줌
    private fun leaderNation(player: Player): String? {
        val name = plugin.nationManager.getNationName(player.uniqueId)
        if (name == null) {
            deny(player, "§c소속된 국가가 없습니다.")
            return null
        }
        if (Nation.nations[name]?.leader != player.uniqueId) {
            deny(player, "§c국가 지도자만 연합을 관리할 수 있습니다.")
            return null
        }
        return name
    }

    fun request(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (me == target || Nation.nations[target] == null) return
        if (areAllied(me, target)) return deny(player, "§c이미 연합 중입니다.")
        if (plugin.nationManager.isAtWarBetween(me, target)) return deny(player, "§c전쟁 중인 국가와는 연합할 수 없습니다. 먼저 휴전하세요.")
        if (!checkLimit(player, me, target)) return

        // 상대도 나에게 요청해 둔 상태면 바로 성사
        if (hasRequest(me, target)) return form(me, target)

        if (!requests.getOrPut(target) { mutableSetOf() }.add(me)) {
            return deny(player, "§c이미 연합을 요청했습니다. 상대의 수락을 기다리는 중입니다.")
        }
        player.sendMessage("§a'$target' 국가에 연합을 요청했습니다.")
        Sounds.success(player)
        Sounds.nation(target) { Sounds.notify(it) }
        notifyNation(target, "§b'$me' 국가가 연합을 요청했습니다! §7/연합 에서 수락 또는 거절하세요.")
    }

    fun accept(player: Player, from: String) {
        val me = leaderNation(player) ?: return
        if (!hasRequest(me, from)) return deny(player, "§c해당 국가의 연합 요청이 없습니다.")
        if (Nation.nations[from] == null) {
            requests[me]?.remove(from)
            return deny(player, "§c해당 국가는 더 이상 존재하지 않습니다.")
        }
        if (plugin.nationManager.isAtWarBetween(me, from)) return deny(player, "§c전쟁 중인 국가와는 연합할 수 없습니다. 먼저 휴전하세요.")
        if (!checkLimit(player, me, from)) return
        form(from, me)
    }

    fun reject(player: Player, from: String) {
        val me = leaderNation(player) ?: return
        if (requests[me]?.remove(from) == true) {
            player.sendMessage("§e'$from' 국가의 연합 요청을 거절했습니다.")
            notifyNation(from, "§e'$me' 국가가 연합 요청을 거절했습니다.")
        }
    }

    fun cancelRequest(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (requests[target]?.remove(me) == true) player.sendMessage("§e'$target' 국가에 대한 연합 요청을 취소했습니다.")
    }

    fun breakAlliance(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (!alliances.remove(key(me, target))) return
        save()
        Bukkit.broadcastMessage("§7[연합 해제] '$me' 국가가 '$target' 국가와의 연합을 해제했습니다.")
        listOf(me, target).forEach { n -> Sounds.nation(n) { Sounds.play(it, org.bukkit.Sound.BLOCK_GLASS_BREAK, 0.8f, 0.8f) } }
    }

    private fun form(a: String, b: String) {
        alliances.add(key(a, b))
        requests[a]?.remove(b)
        requests[b]?.remove(a)
        save()
        Bukkit.broadcastMessage("§b[연합] '$a' 국가와 '$b' 국가가 연합을 맺었습니다!")
        listOf(a, b).forEach { n -> Sounds.nation(n) { Sounds.bigReward(it) } }
        listOf(a, b).mapNotNull { Nation.nations[it]?.leader }.forEach {
            plugin.achievementManager.unlock(Bukkit.getOfflinePlayer(it), kr.maeshil.digriss.achievement.Achievement.DIPLOMAT)
        }
    }

    private fun deny(player: Player, message: String) {
        player.sendMessage(message)
        Sounds.fail(player)
    }

    private fun notifyNation(name: String, message: String) {
        Nation.nations[name]?.members?.forEach { Bukkit.getPlayer(it)?.sendMessage(message) }
    }

    // ───────────────────────── 정리 / 저장 ─────────────────────────

    // 국가가 해체·멸망하면 관련 연합과 요청 제거
    fun removeNation(name: String) {
        alliances.removeAll { it.split("|").contains(name) }
        requests.remove(name)
        requests.values.forEach { it.remove(name) }
        save()
    }

    // 국가 이름이 바뀌면 연합 목록과 요청의 이름을 바꿈
    fun rename(old: String, new: String) {
        val fixed = alliances.map { k -> k.split("|").map { if (it == old) new else it }.sorted().joinToString("|") }
        alliances.clear(); alliances.addAll(fixed)
        requests.remove(old)?.let { requests[new] = it }
        requests.values.forEach { if (it.remove(old)) it.add(new) }
        save()
    }

    fun resetAll() {
        alliances.clear()
        requests.clear()
        save()
    }

    private fun save() {
        val config = YamlConfiguration()
        config.set("alliances", alliances.toList())
        runCatching { config.save(file) }.onFailure { plugin.logger.severe("alliance.yml 저장 실패: ${it.message}") }
    }
}
