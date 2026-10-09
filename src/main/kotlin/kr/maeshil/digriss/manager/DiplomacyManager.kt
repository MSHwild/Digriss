package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.Calendar

// 국가 외교 (연합 말고): 불가침 조약 / 무역 협정 / 조공 / 원조
//  - 불가침 조약: 서로 전쟁을 선포할 수 없음. 파기하면 24시간 뒤에 효력이 끝남 (기습 방지)
//  - 무역 협정: 서로 거래소 수수료 면제 + 매일 자정 양쪽 금고 수입·내실 점수 (국가당 최대 3개)
//  - 조공: 한 국가가 매일 자정 정한 금액을 상대 금고로 보냄 (어느 쪽이든 언제든 중단 가능)
//  - 원조: 지도자가 국가 금고 돈을 다른 국가 금고로 한 번 보냄
// 연합(AllianceManager)과 마찬가지로 요청 → 상대 지도자 수락으로 성사되고, 요청은 저장하지 않음
class DiplomacyManager(private val plugin: Digriss) {

    companion object {
        const val PACT_BREAK_HOURS = 24          // 불가침 조약 파기 후 효력이 끝나기까지
        const val MAX_TRADE_AGREEMENTS = 3       // 국가당 최대 무역 협정 수
        const val TRADE_DAILY_MONEY = 300.0      // 무역 협정 1개당 매일 금고 수입 (양쪽 모두)
        const val TRADE_DAILY_PEACE = 2.0        // 무역 협정 1개당 매일 내실 점수
        const val MAX_TRIBUTE = 1_000_000.0      // 조공 하루 최대 금액
    }

    private val file = File(plugin.dataFolder, "diplomacy.yml")

    private val pacts = mutableMapOf<String, Long>()          // "A|B" -> 파기 효력 시각 (0이면 유지 중)
    private val trades = mutableSetOf<String>()                // "A|B"
    private val tributes = mutableMapOf<String, Double>()      // "보내는국가>받는국가" -> 하루 금액
    private var lastDailyDay = -1                              // 마지막으로 자정 정산한 날 (재시작해도 두 번 정산 안 하게 저장)

    private val pactRequests = mutableMapOf<String, MutableSet<String>>()           // 받는 국가 -> 제안한 국가들
    private val tradeRequests = mutableMapOf<String, MutableSet<String>>()
    private val tributeOffers = mutableMapOf<String, MutableMap<String, Double>>()  // 받을 국가 -> (낼 국가 -> 금액)

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        load()
        // 1분마다: 파기 기간이 끝난 불가침 조약 정리 + 자정 정산
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1200L, 1200L)
    }

    private fun key(a: String, b: String) = listOf(a, b).sorted().joinToString("|")
    private fun tributeKey(from: String, to: String) = "$from>$to"
    private fun otherOf(k: String, name: String): String? {
        val parts = k.split("|")
        return when (name) {
            parts.getOrNull(0) -> parts.getOrNull(1)
            parts.getOrNull(1) -> parts.getOrNull(0)
            else -> null
        }
    }

    // ───────────────────────── 조회 ─────────────────────────

    // 파기 선언 후 24시간 동안도 조약은 유효 (전쟁 선포 불가)
    fun hasPact(a: String?, b: String?): Boolean = a != null && b != null && a != b && pacts.containsKey(key(a, b))

    /** 파기 효력까지 남은 시간(ms). 파기 선언 전이거나 조약이 없으면 null */
    fun pactBreakRemainingMs(a: String, b: String): Long? {
        val at = pacts[key(a, b)] ?: return null
        if (at == 0L) return null
        return (at - System.currentTimeMillis()).coerceAtLeast(0)
    }

    fun hasTrade(a: String?, b: String?): Boolean = a != null && b != null && a != b && trades.contains(key(a, b))

    fun tradePartnersOf(name: String): List<String> = trades.mapNotNull { otherOf(it, name) }

    fun pactPartnersOf(name: String): List<String> = pacts.keys.mapNotNull { otherOf(it, name) }

    /** a가 b에게 매일 내는 조공 (없으면 null) */
    fun tributeAmount(from: String, to: String): Double? = tributes[tributeKey(from, to)]

    fun hasPactRequest(to: String, from: String) = pactRequests[to]?.contains(from) == true
    fun hasTradeRequest(to: String, from: String) = tradeRequests[to]?.contains(from) == true
    /** from이 to에게 조공을 내겠다고 제안한 금액 (없으면 null) */
    fun tributeOffer(from: String, to: String): Double? = tributeOffers[to]?.get(from)

    /** 받은 외교 제안 수 (GUI 표시용) */
    fun pendingCountFor(name: String): Int =
        (pactRequests[name]?.size ?: 0) + (tradeRequests[name]?.size ?: 0) + (tributeOffers[name]?.size ?: 0)

    // ───────────────────────── 불가침 조약 ─────────────────────────

    fun proposePact(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (!validTarget(player, me, target)) return
        if (hasPact(me, target)) return deny(player, "§c이미 불가침 조약을 맺고 있습니다.")
        if (plugin.nationManager.isAtWarBetween(me, target)) return deny(player, "§c전쟁 중인 국가와는 조약을 맺을 수 없습니다. 먼저 휴전하세요.")
        if (hasPactRequest(me, target)) return formPact(target, me)
        if (!pactRequests.getOrPut(target) { mutableSetOf() }.add(me)) return deny(player, "§c이미 제안했습니다. 상대의 수락을 기다리는 중입니다.")
        player.sendMessage("§a'$target' 국가에 불가침 조약을 제안했습니다.")
        Sounds.success(player)
        notifyNation(target, "§e'$me' 국가가 §f불가침 조약§e을 제안했습니다! §7/외교 에서 수락 또는 거절하세요.")
    }

    fun acceptPact(player: Player, from: String) {
        val me = leaderNation(player) ?: return
        if (!hasPactRequest(me, from)) return deny(player, "§c해당 국가의 불가침 조약 제안이 없습니다.")
        if (Nation.nations[from] == null) { pactRequests[me]?.remove(from); return deny(player, "§c해당 국가는 더 이상 존재하지 않습니다.") }
        if (plugin.nationManager.isAtWarBetween(me, from)) return deny(player, "§c전쟁 중인 국가와는 조약을 맺을 수 없습니다. 먼저 휴전하세요.")
        formPact(from, me)
    }

    private fun formPact(a: String, b: String) {
        pacts[key(a, b)] = 0L
        pactRequests[a]?.remove(b); pactRequests[b]?.remove(a)
        save()
        Bukkit.broadcastMessage("§e[외교] '$a' 국가와 '$b' 국가가 §f불가침 조약§e을 맺었습니다!")
        listOf(a, b).forEach { n -> Sounds.nation(n) { Sounds.bigReward(it) } }
        plugin.discordNotifier.notify("diplomacy", "🤝 불가침 조약", "**$a** 국가와 **$b** 국가가 불가침 조약을 맺었습니다.", DiscordNotifier.BLUE)
    }

    // 바로 끝나지 않고 24시간 뒤에 효력이 끝남. 그동안 상대는 대비할 수 있음
    fun breakPact(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        val k = key(me, target)
        val at = pacts[k] ?: return deny(player, "§c불가침 조약을 맺고 있지 않습니다.")
        if (at != 0L) return deny(player, "§c이미 파기를 선언했습니다. 곧 효력이 끝납니다.")
        pacts[k] = System.currentTimeMillis() + PACT_BREAK_HOURS * 3_600_000L
        save()
        Bukkit.broadcastMessage("§c[외교] '$me' 국가가 '$target' 국가와의 불가침 조약 파기를 선언했습니다! §7(${PACT_BREAK_HOURS}시간 뒤 효력 종료)")
        listOf(me, target).forEach { n -> Sounds.nation(n) { Sounds.alert(it) } }
        plugin.discordNotifier.notify("diplomacy", "⚠ 불가침 조약 파기", "**$me** 국가가 **$target** 국가와의 불가침 조약 파기를 선언했습니다. (${PACT_BREAK_HOURS}시간 뒤 효력 종료)", DiscordNotifier.RED)
    }

    // ───────────────────────── 무역 협정 ─────────────────────────

    fun proposeTrade(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (!validTarget(player, me, target)) return
        if (hasTrade(me, target)) return deny(player, "§c이미 무역 협정을 맺고 있습니다.")
        if (plugin.nationManager.isAtWarBetween(me, target)) return deny(player, "§c전쟁 중인 국가와는 무역 협정을 맺을 수 없습니다.")
        if (!checkTradeLimit(player, me, target)) return
        if (hasTradeRequest(me, target)) return formTrade(target, me)
        if (!tradeRequests.getOrPut(target) { mutableSetOf() }.add(me)) return deny(player, "§c이미 제안했습니다. 상대의 수락을 기다리는 중입니다.")
        player.sendMessage("§a'$target' 국가에 무역 협정을 제안했습니다.")
        Sounds.success(player)
        notifyNation(target, "§e'$me' 국가가 §f무역 협정§e을 제안했습니다! §7/외교 에서 수락 또는 거절하세요.")
    }

    fun acceptTrade(player: Player, from: String) {
        val me = leaderNation(player) ?: return
        if (!hasTradeRequest(me, from)) return deny(player, "§c해당 국가의 무역 협정 제안이 없습니다.")
        if (Nation.nations[from] == null) { tradeRequests[me]?.remove(from); return deny(player, "§c해당 국가는 더 이상 존재하지 않습니다.") }
        if (plugin.nationManager.isAtWarBetween(me, from)) return deny(player, "§c전쟁 중인 국가와는 무역 협정을 맺을 수 없습니다.")
        if (!checkTradeLimit(player, me, from)) return
        formTrade(from, me)
    }

    private fun checkTradeLimit(player: Player, me: String, target: String): Boolean {
        if (tradePartnersOf(me).size >= MAX_TRADE_AGREEMENTS) {
            deny(player, "§c무역 협정은 최대 ${MAX_TRADE_AGREEMENTS}개국까지 맺을 수 있습니다.")
            return false
        }
        if (tradePartnersOf(target).size >= MAX_TRADE_AGREEMENTS) {
            deny(player, "§c'$target' 국가는 이미 무역 협정 한도(${MAX_TRADE_AGREEMENTS}개국)에 도달했습니다.")
            return false
        }
        return true
    }

    private fun formTrade(a: String, b: String) {
        trades.add(key(a, b))
        tradeRequests[a]?.remove(b); tradeRequests[b]?.remove(a)
        save()
        Bukkit.broadcastMessage("§6[외교] '$a' 국가와 '$b' 국가가 §f무역 협정§6을 맺었습니다!")
        listOf(a, b).forEach { n -> Sounds.nation(n) { Sounds.bigReward(it) } }
        plugin.discordNotifier.notify("diplomacy", "💰 무역 협정", "**$a** 국가와 **$b** 국가가 무역 협정을 맺었습니다.", DiscordNotifier.GOLD)
    }

    fun endTrade(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        if (!trades.remove(key(me, target))) return deny(player, "§c무역 협정을 맺고 있지 않습니다.")
        save()
        Bukkit.broadcastMessage("§7[외교] '$me' 국가가 '$target' 국가와의 무역 협정을 끝냈습니다.")
        listOf(me, target).forEach { n -> Sounds.nation(n) { Sounds.notify(it) } }
    }

    // ───────────────────────── 조공 ─────────────────────────

    /** 우리(me)가 target에게 매일 amount원을 내겠다고 제안 */
    fun offerTribute(player: Player, target: String, amount: Double) {
        val me = leaderNation(player) ?: return
        if (!validTarget(player, me, target)) return
        if (amount < 1 || amount > MAX_TRIBUTE) return deny(player, "§c조공은 하루 1원 ~ ${"%,.0f".format(MAX_TRIBUTE)}원까지 정할 수 있습니다.")
        if (tributeAmount(me, target) != null || tributeAmount(target, me) != null) return deny(player, "§c이미 이 국가와 조공 관계가 있습니다. 먼저 중단하세요.")
        tributeOffers.getOrPut(target) { mutableMapOf() }[me] = amount
        player.sendMessage("§a'$target' 국가에 매일 ${fmt(amount)}원 조공을 제안했습니다.")
        Sounds.success(player)
        notifyNation(target, "§e'$me' 국가가 매일 §6${fmt(amount)}원§e의 §f조공§e을 바치겠다고 제안했습니다! §7/외교 에서 수락 또는 거절하세요.")
    }

    /** from이 우리에게 제안한 조공 수락 */
    fun acceptTribute(player: Player, from: String) {
        val me = leaderNation(player) ?: return
        val amount = tributeOffers[me]?.get(from) ?: return deny(player, "§c해당 국가의 조공 제안이 없습니다.")
        tributeOffers[me]?.remove(from)
        if (Nation.nations[from] == null) return deny(player, "§c해당 국가는 더 이상 존재하지 않습니다.")
        if (tributeAmount(me, from) != null || tributeAmount(from, me) != null) return deny(player, "§c이미 이 국가와 조공 관계가 있습니다.")
        tributes[tributeKey(from, me)] = amount
        save()
        Bukkit.broadcastMessage("§6[외교] '$from' 국가가 '$me' 국가에 매일 ${fmt(amount)}원의 조공을 바치기로 했습니다.")
        listOf(from, me).forEach { n -> Sounds.nation(n) { Sounds.notify(it) } }
        plugin.discordNotifier.notify("diplomacy", "👑 조공", "**$from** 국가가 **$me** 국가에 매일 ${fmt(amount)}원의 조공을 바치기로 했습니다.", DiscordNotifier.GOLD)
    }

    /** 조공 관계 중단 (내는 쪽·받는 쪽 모두 가능) */
    fun stopTribute(player: Player, target: String) {
        val me = leaderNation(player) ?: return
        when {
            tributes.remove(tributeKey(me, target)) != null ->
                Bukkit.broadcastMessage("§c[외교] '$me' 국가가 '$target' 국가에 바치던 조공을 중단했습니다!")
            tributes.remove(tributeKey(target, me)) != null ->
                Bukkit.broadcastMessage("§7[외교] '$me' 국가가 '$target' 국가의 조공을 더 이상 받지 않기로 했습니다.")
            else -> return deny(player, "§c이 국가와 조공 관계가 없습니다.")
        }
        save()
        listOf(me, target).forEach { n -> Sounds.nation(n) { Sounds.notify(it) } }
    }

    // ───────────────────────── 원조 (한 번 보내기) ─────────────────────────

    fun sendAid(player: Player, target: String, amount: Double) {
        val me = leaderNation(player) ?: return
        if (!validTarget(player, me, target)) return
        if (plugin.nationManager.isAtWarBetween(me, target)) return deny(player, "§c전쟁 중인 국가에는 원조를 보낼 수 없습니다.")
        if (amount < 1) return deny(player, "§c1원 이상 입력하세요.")
        val mine = Nation.nations[me] ?: return
        val theirs = Nation.nations[target] ?: return
        if (mine.bank < amount) return deny(player, "§c국가 금고 잔액이 부족합니다. (금고: ${fmt(mine.bank)}원)")
        mine.bank -= amount
        theirs.bank += amount
        plugin.nationManager.saveNations()
        player.sendMessage("§a'$target' 국가 금고로 ${fmt(amount)}원을 보냈습니다. (우리 금고: ${fmt(mine.bank)}원)")
        Sounds.coin(player)
        notifyNation(me, "§7[원조] 지도자 ${player.name} 님이 '$target' 국가에 ${fmt(amount)}원을 보냈습니다.")
        notifyNation(target, "§a[원조] '$me' 국가가 우리 금고로 §6${fmt(amount)}원§a을 보냈습니다!")
        Sounds.nation(target) { Sounds.coin(it) }
    }

    // ───────────────────────── 요청 거절 / 취소 ─────────────────────────

    fun rejectPact(player: Player, from: String) = rejectOne(player, from, "불가침 조약") { me -> pactRequests[me]?.remove(from) == true }
    fun rejectTrade(player: Player, from: String) = rejectOne(player, from, "무역 협정") { me -> tradeRequests[me]?.remove(from) == true }
    fun rejectTribute(player: Player, from: String) = rejectOne(player, from, "조공") { me -> tributeOffers[me]?.remove(from) != null }

    private fun rejectOne(player: Player, from: String, what: String, remove: (String) -> Boolean) {
        val me = leaderNation(player) ?: return
        if (!remove(me)) return
        player.sendMessage("§e'$from' 국가의 $what 제안을 거절했습니다.")
        notifyNation(from, "§e'$me' 국가가 $what 제안을 거절했습니다.")
    }

    fun cancelPactRequest(player: Player, target: String) = cancelOne(player, target, "불가침 조약") { me -> pactRequests[target]?.remove(me) == true }
    fun cancelTradeRequest(player: Player, target: String) = cancelOne(player, target, "무역 협정") { me -> tradeRequests[target]?.remove(me) == true }
    fun cancelTributeOffer(player: Player, target: String) = cancelOne(player, target, "조공") { me -> tributeOffers[target]?.remove(me) != null }

    private fun cancelOne(player: Player, target: String, what: String, remove: (String) -> Boolean) {
        val me = leaderNation(player) ?: return
        if (remove(me)) player.sendMessage("§e'$target' 국가에 보낸 $what 제안을 취소했습니다.")
    }

    // ───────────────────────── 전쟁 · 국가 변화 연동 ─────────────────────────

    /** 전쟁이 시작되면 두 국가 사이의 무역 협정·조공·제안은 끝남 */
    fun onWarStart(a: String, b: String) {
        val hadTrade = trades.remove(key(a, b))
        val tributeAB = tributes.remove(tributeKey(a, b)) != null
        val tributeBA = tributes.remove(tributeKey(b, a)) != null
        val hadTribute = tributeAB || tributeBA
        listOf(pactRequests, tradeRequests).forEach { m -> m[a]?.remove(b); m[b]?.remove(a) }
        tributeOffers[a]?.remove(b); tributeOffers[b]?.remove(a)
        if (hadTrade || hadTribute) {
            save()
            Bukkit.broadcastMessage("§7[외교] 전쟁이 시작되어 '$a' 국가와 '$b' 국가의 ${listOfNotNull(if (hadTrade) "무역 협정" else null, if (hadTribute) "조공" else null).joinToString("·")}이 끝났습니다.")
        }
    }

    fun removeNation(name: String) {
        pacts.keys.removeAll { it.split("|").contains(name) }
        trades.removeAll { it.split("|").contains(name) }
        tributes.keys.removeAll { it.split(">").contains(name) }
        listOf(pactRequests, tradeRequests).forEach { m -> m.remove(name); m.values.forEach { it.remove(name) } }
        tributeOffers.remove(name); tributeOffers.values.forEach { it.remove(name) }
        save()
    }

    fun rename(old: String, new: String) {
        fun fix(k: String, sep: String, sorted: Boolean): String {
            val parts = k.split(sep).map { if (it == old) new else it }
            return (if (sorted) parts.sorted() else parts).joinToString(sep)
        }
        val p = pacts.mapKeys { fix(it.key, "|", true) }; pacts.clear(); pacts.putAll(p)
        val t = trades.map { fix(it, "|", true) }; trades.clear(); trades.addAll(t)
        val tr = tributes.mapKeys { fix(it.key, ">", false) }; tributes.clear(); tributes.putAll(tr)
        listOf(pactRequests, tradeRequests).forEach { m ->
            m.remove(old)?.let { m[new] = it }
            m.values.forEach { if (it.remove(old)) it.add(new) }
        }
        tributeOffers.remove(old)?.let { tributeOffers[new] = it }
        tributeOffers.values.forEach { offers -> offers.remove(old)?.let { offers[new] = it } }
        save()
    }

    fun resetAll() {
        pacts.clear(); trades.clear(); tributes.clear()
        pactRequests.clear(); tradeRequests.clear(); tributeOffers.clear()
        save()
    }

    // ───────────────────────── 1분마다: 조약 만료 · 자정 정산 ─────────────────────────

    private fun tick() {
        val now = System.currentTimeMillis()
        val expired = pacts.filter { it.value != 0L && it.value <= now }.keys
        if (expired.isNotEmpty()) {
            expired.forEach { k ->
                pacts.remove(k)
                val parts = k.split("|")
                Bukkit.broadcastMessage("§c[외교] '${parts[0]}' 국가와 '${parts[1]}' 국가의 불가침 조약 효력이 끝났습니다.")
            }
            save()
        }

        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 1000 + cal.get(Calendar.DAY_OF_YEAR)
        if (cal.get(Calendar.HOUR_OF_DAY) == 0 && lastDailyDay != today) {
            lastDailyDay = today
            processDaily()
            save()
        }
    }

    private fun processDaily() {
        var changed = false
        // 무역 협정 수입
        trades.toList().forEach { k ->
            k.split("|").forEach { name ->
                val nation = Nation.nations[name] ?: return@forEach
                nation.bank += TRADE_DAILY_MONEY
                plugin.nationManager.addPeace(name, TRADE_DAILY_PEACE)
                notifyNation(name, "§6[무역] '${otherOf(k, name)}' 국가와의 무역으로 금고 +${fmt(TRADE_DAILY_MONEY)}원, 내실 +${TRADE_DAILY_PEACE.toInt()}")
                changed = true
            }
        }
        // 조공
        tributes.toList().forEach { (k, amount) ->
            val (from, to) = k.split(">").let { (it.getOrNull(0) ?: return@forEach) to (it.getOrNull(1) ?: return@forEach) }
            val payer = Nation.nations[from] ?: return@forEach
            val receiver = Nation.nations[to] ?: return@forEach
            if (payer.bank >= amount) {
                payer.bank -= amount
                receiver.bank += amount
                notifyNation(from, "§7[조공] '$to' 국가에 조공 ${fmt(amount)}원을 바쳤습니다. (금고: ${fmt(payer.bank)}원)")
                notifyNation(to, "§6[조공] '$from' 국가가 조공 ${fmt(amount)}원을 바쳤습니다.")
                changed = true
            } else {
                notifyNation(from, "§c[조공] 금고가 부족해 '$to' 국가에 조공(${fmt(amount)}원)을 바치지 못했습니다!")
                notifyNation(to, "§c[조공] '$from' 국가가 금고 부족으로 오늘 조공(${fmt(amount)}원)을 바치지 못했습니다.")
            }
        }
        if (changed) plugin.nationManager.saveNations()
    }

    // ───────────────────────── 공통 ─────────────────────────

    private fun leaderNation(player: Player): String? {
        val name = plugin.nationManager.getNationName(player.uniqueId)
        if (name == null) { deny(player, "§c소속된 국가가 없습니다."); return null }
        if (Nation.nations[name]?.leader != player.uniqueId) { deny(player, "§c국가 지도자만 외교를 할 수 있습니다."); return null }
        return name
    }

    private fun validTarget(player: Player, me: String, target: String): Boolean {
        if (me == target) return false
        if (Nation.nations[target] == null) { deny(player, "§c존재하지 않는 국가입니다."); return false }
        return true
    }

    private fun deny(player: Player, message: String) {
        player.sendMessage(message)
        Sounds.fail(player)
    }

    private fun notifyNation(name: String, message: String) {
        Nation.nations[name]?.members?.forEach { Bukkit.getPlayer(it)?.sendMessage(message) }
    }

    fun fmt(amount: Double): String = "%,.0f".format(amount)

    // ───────────────────────── 저장 ─────────────────────────

    private fun load() {
        if (!file.exists()) return
        val c = YamlConfiguration.loadConfiguration(file)
        c.getConfigurationSection("pacts")?.getKeys(false)?.forEach { pacts[it] = c.getLong("pacts.$it") }
        trades.addAll(c.getStringList("trades"))
        c.getConfigurationSection("tributes")?.getKeys(false)?.forEach { tributes[it] = c.getDouble("tributes.$it") }
        lastDailyDay = c.getInt("last-daily-day", -1)
    }

    private fun save() {
        val c = YamlConfiguration()
        // 국가 이름에 '.'은 들어갈 수 없으므로(이름 규칙) 키로 그대로 써도 됨
        pacts.forEach { (k, v) -> c.set("pacts.$k", v) }
        c.set("trades", trades.toList())
        tributes.forEach { (k, v) -> c.set("tributes.$k", v) }
        c.set("last-daily-day", lastDailyDay)
        runCatching { c.save(file) }.onFailure { plugin.logger.severe("diplomacy.yml 저장 실패: ${it.message}") }
    }
}
