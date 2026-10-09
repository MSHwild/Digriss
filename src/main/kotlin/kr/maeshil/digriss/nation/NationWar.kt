package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.NationManager
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.io.IOException

data class WarRecord(
    val time: Long,
    val type: String,   // START, TRUCE, CONQUER, END
    val a: String,
    val b: String,
    val detail: String
)

// 국가 전쟁: 선포 / 수락 / 휴전 / 3일 자동 휴전 / 전쟁 기록 (war.yml)
class NationWar(private val plugin: Digriss, private val core: NationManager) {

    private val nations = Nation.nations

    private val activeWars = mutableSetOf<String>()                                   // "A|B" (이름순 정렬)
    internal val warRequests = mutableMapOf<String, MutableSet<String>>()             // 받는 국가 -> 선포한 국가들
    internal val truceRequests = mutableMapOf<String, MutableSet<String>>()           // 받는 국가 -> 휴전 요청한 국가들
    internal val warLog = mutableListOf<WarRecord>()                                  // 전쟁 기록 (최근 200개 보관)
    private val warStarts = mutableMapOf<String, Long>()                              // "A|B" -> 전쟁 시작 시각
    private val warDurationMs = 3L * 24 * 60 * 60 * 1000                               // 전쟁 최대 기간 3일

    private lateinit var warsFile: File

    // ───────────────────────── 조회 ─────────────────────────

    private fun warKey(a: String, b: String) = listOf(a, b).sorted().joinToString("|")

    fun isAtWar(a: String, b: String) = activeWars.contains(warKey(a, b))

    // 전쟁 남은 시간(ms), 전쟁 중이 아니면 null
    fun remainingMs(a: String, b: String): Long? {
        val start = warStarts[warKey(a, b)] ?: return null
        return (start + warDurationMs - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun warsOf(name: String): List<String> =
        activeWars.mapNotNull { key ->
            val parts = key.split("|")
            if (parts.size != 2) null
            else when (name) {
                parts[0] -> parts[1]
                parts[1] -> parts[0]
                else -> null
            }
        }

    // ───────────────────────── 전쟁 시작 / 종료 ─────────────────────────

    // 3일이 지난 전쟁은 자동 휴전 (휴전과 똑같이 전쟁 점수 정산)
    fun checkExpiry() {
        val now = System.currentTimeMillis()
        activeWars.toList().forEach { key ->
            val start = warStarts.getOrPut(key) { now }
            if (now - start < warDurationMs) return@forEach
            val parts = key.split("|")
            if (parts.size != 2) return@forEach
            Bukkit.broadcastMessage("${ChatColor.YELLOW}[전쟁] '${parts[0]}' 국가와 '${parts[1]}' 국가의 전쟁 기간(3일)이 끝나 자동으로 휴전합니다.")
            endWar(parts[0], parts[1])
        }
    }

    fun addRecord(type: String, a: String, b: String, detail: String = "") {
        warLog.add(WarRecord(System.currentTimeMillis(), type, a, b, detail))
        if (warLog.size > 200) warLog.removeAt(0)
    }

    private fun startWar(declarer: String, accepter: String) {
        activeWars.add(warKey(declarer, accepter))
        warStarts[warKey(declarer, accepter)] = System.currentTimeMillis()
        warRequests[declarer]?.remove(accepter)
        warRequests[accepter]?.remove(declarer)
        truceRequests[declarer]?.remove(accepter)
        truceRequests[accepter]?.remove(declarer)
        addRecord("START", declarer, accepter)
        plugin.diplomacyManager.onWarStart(declarer, accepter) // 무역 협정·조공 종료
        Bukkit.broadcastMessage("${ChatColor.RED}[전쟁] '$declarer' 국가와 '$accepter' 국가의 전쟁이 시작되었습니다! 이제 서로의 신호기를 점령할 수 있습니다. (최대 3일, 이후 자동 휴전)")
        Sounds.all(org.bukkit.Sound.EVENT_RAID_HORN, 1.0f, 1.0f)
        plugin.discordNotifier.notify("war-start", "⚔ 전쟁 시작!",
            "**$declarer** 국가와 **$accepter** 국가의 전쟁이 시작되었습니다.\n서로의 신호기를 점령할 수 있어요. (최대 3일, 이후 자동 휴전)",
            kr.maeshil.digriss.manager.DiscordNotifier.RED)
        save()
    }

    private fun endWar(a: String, b: String) {
        activeWars.remove(warKey(a, b))
        warStarts.remove(warKey(a, b))
        truceRequests[a]?.remove(b)
        truceRequests[b]?.remove(a)
        addRecord("TRUCE", a, b)
        Bukkit.broadcastMessage("${ChatColor.GREEN}[휴전] '$a' 국가와 '$b' 국가가 휴전했습니다.")
        Sounds.all(org.bukkit.Sound.BLOCK_BELL_USE, 1.0f, 1.0f)
        plugin.discordNotifier.notify("truce", "☮ 휴전", "**$a** 국가와 **$b** 국가가 휴전했습니다.",
            kr.maeshil.digriss.manager.DiscordNotifier.GREEN)
        if (plugin.warScoreManager.settle(a, b)) core.saveNations() // 전쟁 점수 정산 (배상금)
        save()
    }

    /** 국가가 사라질 때(해체/멸망) 관련 전쟁·요청 정리. except 국가와의 전쟁은 별도 기록이 있으므로 종료 기록을 남기지 않음 */
    fun removeNation(name: String, endDetail: String, except: String? = null) {
        warsOf(name).filter { it != except }.forEach { addRecord("END", name, it, endDetail) }
        plugin.allianceManager.removeNation(name)
        plugin.diplomacyManager.removeNation(name)
        plugin.warScoreManager.removeNation(name)
        activeWars.removeAll { it.split("|").contains(name) }
        warStarts.keys.removeAll { it.split("|").contains(name) }
        warRequests.remove(name)
        warRequests.values.forEach { it.remove(name) }
        truceRequests.remove(name)
        truceRequests.values.forEach { it.remove(name) }
        save()
    }

    /** 국가 이름이 바뀔 때 전쟁 키·요청·기록의 이름을 함께 바꿈 */
    fun rename(old: String, new: String) {
        fun fix(key: String) = key.split("|").map { if (it == old) new else it }.sorted().joinToString("|")
        val wars = activeWars.map(::fix); activeWars.clear(); activeWars.addAll(wars)
        val starts = warStarts.mapKeys { fix(it.key) }; warStarts.clear(); warStarts.putAll(starts)
        fun renameRequests(m: MutableMap<String, MutableSet<String>>) {
            m.remove(old)?.let { m[new] = it }
            m.values.forEach { if (it.remove(old)) it.add(new) }
        }
        renameRequests(warRequests)
        renameRequests(truceRequests)
        for (i in warLog.indices) {
            val r = warLog[i]
            if (r.a == old || r.b == old) warLog[i] = r.copy(a = if (r.a == old) new else r.a, b = if (r.b == old) new else r.b)
        }
        plugin.allianceManager.rename(old, new)
        plugin.diplomacyManager.rename(old, new)
        plugin.warScoreManager.rename(old, new)
        save()
    }

    fun resetAll() {
        activeWars.clear()
        warStarts.clear()
        warRequests.clear()
        truceRequests.clear()
        warLog.clear()
    }

    // ───────────────────────── 선포 / 휴전 ─────────────────────────

    private fun declareWar(player: Player, target: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 전쟁을 선포할 수 있습니다.")
        if (myName == target) return
        if (nations[target] == null) return core.deny(player, "${ChatColor.RED}존재하지 않는 국가입니다.")
        if (isAtWar(myName, target)) return core.deny(player, "${ChatColor.RED}이미 전쟁 중입니다.")
        if (plugin.allianceManager.areAllied(myName, target)) return core.deny(player, "${ChatColor.RED}연합국에는 전쟁을 선포할 수 없습니다. 먼저 /연합 에서 연합을 해제하세요.")
        if (plugin.diplomacyManager.hasPact(myName, target)) return core.deny(player, "${ChatColor.RED}불가침 조약을 맺은 국가에는 전쟁을 선포할 수 없습니다. /외교 에서 파기하면 ${kr.maeshil.digriss.manager.DiplomacyManager.PACT_BREAK_HOURS}시간 뒤에 가능해요.")

        val requests = warRequests.getOrPut(target) { mutableSetOf() }
        if (!requests.add(myName)) return core.deny(player, "${ChatColor.RED}이미 전쟁을 선포했습니다. 상대의 수락을 기다리는 중입니다.")

        player.sendMessage("${ChatColor.GREEN}'$target' 국가에 전쟁을 선포했습니다. 상대가 수락하면 전쟁이 시작됩니다.")
        Sounds.success(player)
        Sounds.nation(target) { Sounds.alert(it) }
        core.notifyNation(target, "${ChatColor.RED}'$myName' 국가가 전쟁을 선포했습니다! /국가 → 전쟁 관리에서 수락 또는 거절하세요.")
    }

    private fun acceptWar(player: Player, from: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        if (warRequests[myName]?.contains(from) != true) return core.deny(player, "${ChatColor.RED}해당 국가의 전쟁 선포가 없습니다.")
        if (nations[from] == null) {
            warRequests[myName]?.remove(from)
            return core.deny(player, "${ChatColor.RED}해당 국가는 더 이상 존재하지 않습니다.")
        }
        if (plugin.allianceManager.areAllied(myName, from)) return core.deny(player, "${ChatColor.RED}연합국과는 전쟁할 수 없습니다.")
        if (plugin.diplomacyManager.hasPact(myName, from)) return core.deny(player, "${ChatColor.RED}불가침 조약을 맺은 국가와는 전쟁할 수 없습니다.")
        startWar(from, myName)
    }

    private fun rejectWar(player: Player, from: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        if (warRequests[myName]?.remove(from) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$from' 국가의 전쟁 선포를 거절했습니다.")
            core.notifyNation(from, "${ChatColor.YELLOW}'$myName' 국가가 전쟁 선포를 거절했습니다.")
            Sounds.nation(from) { Sounds.notify(it) }
        }
    }

    private fun cancelDeclare(player: Player, target: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 취소할 수 있습니다.")
        if (warRequests[target]?.remove(myName) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$target' 국가에 대한 전쟁 선포를 취소했습니다.")
        }
    }

    private fun requestTruce(player: Player, target: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 휴전을 요청할 수 있습니다.")
        if (!isAtWar(myName, target)) return

        val requests = truceRequests.getOrPut(target) { mutableSetOf() }
        if (!requests.add(myName)) return core.deny(player, "${ChatColor.RED}이미 휴전을 요청했습니다.")

        player.sendMessage("${ChatColor.GREEN}'$target' 국가에 휴전을 요청했습니다.")
        Sounds.success(player)
        Sounds.nation(target) { Sounds.notify(it) }
        core.notifyNation(target, "${ChatColor.GREEN}'$myName' 국가가 휴전을 요청했습니다! 지도자는 /국가 → 전쟁 관리에서 응답하세요.")
    }

    private fun acceptTruce(player: Player, from: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 휴전을 수락할 수 있습니다.")
        if (truceRequests[myName]?.contains(from) != true) return core.deny(player, "${ChatColor.RED}해당 국가의 휴전 요청이 없습니다.")
        endWar(myName, from)
    }

    private fun rejectTruce(player: Player, from: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 거절할 수 있습니다.")
        if (truceRequests[myName]?.remove(from) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$from' 국가의 휴전 요청을 거절했습니다.")
            core.notifyNation(from, "${ChatColor.YELLOW}'$myName' 국가가 휴전 요청을 거절했습니다.")
            Sounds.nation(from) { Sounds.notify(it) }
        }
    }

    private fun cancelTruce(player: Player, target: String) {
        val myName = core.getNationName(player.uniqueId) ?: return
        val me = nations[myName] ?: return
        if (me.leader != player.uniqueId) return core.deny(player, "${ChatColor.RED}국가 지도자만 취소할 수 있습니다.")
        if (truceRequests[target]?.remove(myName) == true) {
            player.sendMessage("${ChatColor.YELLOW}'$target' 국가에 대한 휴전 요청을 취소했습니다.")
        }
    }

    // 전쟁 관리 메뉴에서 국가를 클릭했을 때 (상태에 따라 선포/수락/거절/휴전)
    fun handleClick(player: Player, target: String, right: Boolean) {
        val myName = core.getNationName(player.uniqueId) ?: return
        if (nations[target] == null) return core.deny(player, "${ChatColor.RED}해당 국가는 더 이상 존재하지 않습니다.")

        when {
            // 전쟁 중
            isAtWar(myName, target) -> when {
                truceRequests[myName]?.contains(target) == true ->
                    if (right) rejectTruce(player, target) else acceptTruce(player, target)
                truceRequests[target]?.contains(myName) == true ->
                    if (right) cancelTruce(player, target)
                    else player.sendMessage("${ChatColor.YELLOW}상대의 응답을 기다리는 중입니다. (우클릭: 요청 취소)")
                else -> requestTruce(player, target)
            }
            // 상대가 나에게 선포함
            warRequests[myName]?.contains(target) == true ->
                if (right) rejectWar(player, target) else acceptWar(player, target)
            // 내가 상대에게 선포함
            warRequests[target]?.contains(myName) == true ->
                if (right) cancelDeclare(player, target)
                else player.sendMessage("${ChatColor.YELLOW}상대의 수락을 기다리는 중입니다. (우클릭: 선포 취소)")
            // 아무 관계 없음
            else -> declareWar(player, target)
        }
    }

    // ───────────────────────── 저장 / 로드 ─────────────────────────

    fun save() {
        if (!::warsFile.isInitialized) return
        val config = YamlConfiguration()
        config.set("wars", activeWars.toList())
        config.set("war-starts", warStarts.map { "${it.key}|${it.value}" })
        config.set("log", warLog.map { "${it.time}|${it.type}|${it.a}|${it.b}|${it.detail}" })
        try { config.save(warsFile) } catch (e: IOException) { e.printStackTrace() }
    }

    // 국가 데이터를 먼저 불러온 뒤 호출해야 함 (없는 국가의 전쟁은 버림)
    fun load(file: File) {
        warsFile = file
        activeWars.clear()
        warLog.clear()
        if (!warsFile.exists()) return
        val config = YamlConfiguration.loadConfiguration(warsFile)

        config.getStringList("wars").forEach { key ->
            val parts = key.split("|")
            if (parts.size == 2 && nations.containsKey(parts[0]) && nations.containsKey(parts[1])) {
                activeWars.add(key)
            }
        }

        // 시작 시각 기록이 없는 기존 전쟁은 지금부터 3일
        val now = System.currentTimeMillis()
        config.getStringList("war-starts").forEach { line ->
            val p = line.split("|")
            val time = p.getOrNull(2)?.toLongOrNull() ?: return@forEach
            warStarts["${p[0]}|${p[1]}"] = time
        }
        warStarts.keys.retainAll(activeWars)
        activeWars.forEach { warStarts.putIfAbsent(it, now) }

        config.getStringList("log").forEach { line ->
            val p = line.split("|", limit = 5)
            val time = p.getOrNull(0)?.toLongOrNull() ?: return@forEach
            if (p.size >= 4) {
                warLog.add(WarRecord(time, p[1], p[2], p[3], p.getOrElse(4) { "" }))
            }
        }
    }
}
