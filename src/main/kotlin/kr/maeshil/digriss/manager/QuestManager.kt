package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.quest.ActiveQuest
import kr.maeshil.digriss.quest.PlayerQuestData
import kr.maeshil.digriss.quest.QuestDefinition
import kr.maeshil.digriss.quest.QuestDifficulty
import kr.maeshil.digriss.quest.QuestType
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class QuestManager(private val plugin: Digriss) {

    private val zone = ZoneId.of("Asia/Seoul")
    private val resetHour = 5L

    private val configFile = File(plugin.dataFolder, "quest.yml")
    private val file = File(plugin.dataFolder, "player-quests.yml")
    private val dcLogFile = File(plugin.dataFolder, "quest-dc.log")
    private var data = YamlConfiguration()

    private val players = HashMap<UUID, PlayerQuestData>()
    var definitions: Map<String, QuestDefinition> = emptyMap()
        private set

    // 주간 보너스 설정
    private var weeklyRequiredDays = 5
    private var weeklySouls = 0L
    private var weeklyMoney = 0.0
    private var weeklyDC = 10L

    // 연속 출석(연속 완료) 보너스 설정
    private var streakSoulsPerDay = 0L
    private var streakMoneyPerDay = 0.0
    var streakMaxDays = 10
        private set

    // 플레이어 킬: 같은 상대를 10분 안에 다시 죽이면 진행 제외 (킬러 -> (피해자 -> 시각))
    private val recentKills = HashMap<UUID, HashMap<UUID, Long>>()
    private val repeatKillWindowMs = 10 * 60 * 1000L

    init {
        if (!configFile.exists()) plugin.saveResource("quest.yml", false)
        loadDefinitions()
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        data = YamlConfiguration.loadConfiguration(file)
        data.getKeys(false).forEach { key ->
            runCatching { UUID.fromString(key) }.getOrNull()?.let { players[it] = readPlayer(key) }
        }
    }

    // ───────────────────────── 설정 ─────────────────────────

    fun loadDefinitions() {
        val config = YamlConfiguration.loadConfiguration(configFile)
        val section = config.getConfigurationSection("quests")
        val result = LinkedHashMap<String, QuestDefinition>()

        section?.getKeys(false)?.forEach { id ->
            val q = section.getConfigurationSection(id) ?: return@forEach
            val type = runCatching { QuestType.valueOf(q.getString("type", "")!!.uppercase()) }.getOrNull()
            val difficulty = runCatching { QuestDifficulty.valueOf(q.getString("difficulty", "")!!.uppercase()) }.getOrNull()
            if (type == null || difficulty == null) {
                plugin.logger.warning("[퀘스트] '$id'의 type 또는 difficulty가 잘못되어 건너뜁니다.")
                return@forEach
            }
            if (type == QuestType.PLAYER_KILL && difficulty != QuestDifficulty.HARD) {
                plugin.logger.warning("[퀘스트] '$id': PLAYER_KILL은 HARD 전용이라 건너뜁니다.")
                return@forEach
            }
            val diffKey = "rewards.${difficulty.name.lowercase()}"
            result[id] = QuestDefinition(
                id = id,
                type = type,
                difficulty = difficulty,
                target = q.getInt("target", 1).coerceAtLeast(1),
                name = q.getString("name", id)!!,
                warOnly = q.getBoolean("war-only", false),
                // 퀘스트별 souls/money가 없으면 난이도 기본 보상 사용
                souls = q.getLong("souls", config.getLong("$diffKey.souls")),
                money = q.getDouble("money", config.getDouble("$diffKey.money")),
                dc = q.getLong("dc", 0).coerceAtLeast(0)
            )
        }
        definitions = result

        weeklyRequiredDays = config.getInt("weekly-bonus.required-days", 5)
        weeklySouls = config.getLong("weekly-bonus.souls", 0)
        weeklyMoney = config.getDouble("weekly-bonus.money", 0.0)
        weeklyDC = config.getLong("weekly-bonus.dc", 10)
        streakSoulsPerDay = config.getLong("streak-bonus.souls-per-day", 2)
        streakMoneyPerDay = config.getDouble("streak-bonus.money-per-day", 150.0)
        streakMaxDays = config.getInt("streak-bonus.max-days", 10).coerceAtLeast(1)

        QuestDifficulty.entries.forEach { d ->
            if (result.values.none { it.difficulty == d && !it.warOnly }) {
                plugin.logger.warning("[퀘스트] ${d.name} 난이도에 평시 퀘스트가 없습니다. quest.yml의 quests를 확인하세요.")
            }
        }
    }

    // ───────────────────────── 날짜 ─────────────────────────

    // 오전 5시 전이면 전날로 취급
    private fun todayDate(): LocalDate = ZonedDateTime.now(zone).minusHours(resetHour).toLocalDate()

    fun todayKey(): String = todayDate().toString()

    private fun weekKeyOf(date: LocalDate): String =
        date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()

    // ───────────────────────── 일일 갱신 / 뽑기 ─────────────────────────

    fun getData(player: Player): PlayerQuestData = ensureToday(player)

    // 날짜 키가 바뀌었으면 새 퀘스트 3개를 뽑음 (접속, /퀘스트, 진행 시 호출)
    fun ensureToday(player: Player): PlayerQuestData {
        val pd = players.getOrPut(player.uniqueId) { PlayerQuestData() }
        val today = todayDate()
        val todayKey = today.toString()
        if (pd.dateKey == todayKey) return pd

        pd.dateKey = todayKey
        pd.rerolled = false
        pd.quests.clear()
        QuestDifficulty.entries.forEach { d ->
            drawFor(player, d, emptySet())?.let { pd.quests.add(ActiveQuest(it.id)) }
        }

        val weekKey = weekKeyOf(today)
        if (pd.weekKey != weekKey) {
            pd.weekKey = weekKey
            pd.weekDays.clear()
            pd.weeklyClaimed = false
        }
        save(player.uniqueId)
        return pd
    }

    private fun isAtWar(player: Player): Boolean =
        plugin.nationManager.warsOfPlayer(player.uniqueId).isNotEmpty()

    private fun drawFor(player: Player, difficulty: QuestDifficulty, excludeIds: Set<String>): QuestDefinition? {
        val atWar = isAtWar(player)
        val candidates = definitions.values
            .filter { it.difficulty == difficulty && it.id !in excludeIds && (!it.warOnly || atWar) }
        // 영토 점령은 지도자만, 금고 입금은 국가원만 할 수 있으므로 할 수 있는 것 우선 (없으면 전체에서)
        return candidates.filter { canDo(player, it.type) }.randomOrNull() ?: candidates.randomOrNull()
    }

    private fun canDo(player: Player, type: QuestType): Boolean {
        val nationName = plugin.nationManager.getNationName(player.uniqueId)
        return when (type) {
            QuestType.CLAIM_CHUNK -> nationName != null && kr.maeshil.digriss.nation.Nation.nations[nationName]?.leader == player.uniqueId
            QuestType.BANK_DEPOSIT -> nationName != null
            else -> true
        }
    }

    // 하루 1회, 완료하지 않은 퀘스트 1개를 같은 난이도의 다른 퀘스트로 교체
    fun reroll(player: Player, index: Int): Boolean {
        val pd = ensureToday(player)
        val quest = pd.quests.getOrNull(index) ?: return false
        if (pd.rerolled) {
            Sounds.fail(player)
            player.sendMessage("§c오늘은 이미 리롤을 사용했습니다.")
            return false
        }
        if (quest.done) {
            Sounds.fail(player)
            player.sendMessage("§c완료한 퀘스트는 리롤할 수 없습니다.")
            return false
        }
        val def = definitions[quest.id]
        val difficulty = def?.difficulty ?: QuestDifficulty.entries[index.coerceIn(0, 2)]
        val next = drawFor(player, difficulty, pd.quests.map { it.id }.toSet())
        if (next == null) {
            Sounds.fail(player)
            player.sendMessage("§c교체할 수 있는 다른 퀘스트가 없습니다.")
            return false
        }
        quest.id = next.id
        quest.progress = 0
        quest.done = false
        pd.rerolled = true
        save(player.uniqueId)
        player.sendMessage("§a퀘스트가 교체되었습니다: ${next.difficulty.color}${next.name}")
        Sounds.play(player, org.bukkit.Sound.BLOCK_ENCHANTMENT_TABLE_USE, 0.8f, 1.2f)
        return true
    }

    // ───────────────────────── 진행 / 완료 ─────────────────────────

    fun addProgress(player: Player, type: QuestType, amount: Int = 1) {
        if (amount <= 0) return
        val pd = ensureToday(player)
        var changed = false

        pd.quests.forEach { quest ->
            if (quest.done) return@forEach
            val def = definitions[quest.id] ?: return@forEach
            if (def.type != type) return@forEach

            quest.progress = (quest.progress + amount).coerceAtMost(def.target)
            changed = true
            if (quest.progress >= def.target) {
                quest.done = true
                complete(player, pd, def)
            }
        }
        if (changed) save(player.uniqueId)
    }

    // 플레이어 킬 + 전쟁 중 적국 영토 킬. 같은 상대를 10분 안에 다시 죽인 건 둘 다 제외
    fun onPlayerKill(killer: Player, victim: Player) {
        if (killer == victim) return
        val now = System.currentTimeMillis()
        val history = recentKills.getOrPut(killer.uniqueId) { HashMap() }
        history.entries.removeIf { now - it.value >= repeatKillWindowMs }
        val repeated = history.containsKey(victim.uniqueId)
        history[victim.uniqueId] = now
        if (repeated) return

        addProgress(killer, QuestType.PLAYER_KILL)

        // 피해자가 죽은 곳이 킬러 국가와 전쟁 중인 국가의 영토면 전쟁 킬
        val owner = plugin.nationManager.territoryOwnerAt(victim.location) ?: return
        if (owner in plugin.nationManager.warsOfPlayer(killer.uniqueId)) {
            addProgress(killer, QuestType.WAR_KILL_ENEMY_TERRITORY)
        }
    }

    // 다른 국가 영토 진입: 경계를 왔다갔다 하는 반복을 막기 위해 같은 국가는 5분에 1회만 인정
    private val territoryEnterCooldown = HashMap<UUID, HashMap<String, Long>>()
    private val territoryEnterCooldownMs = 5 * 60 * 1000L

    fun onEnterTerritory(player: Player, nationName: String) {
        val now = System.currentTimeMillis()
        val history = territoryEnterCooldown.getOrPut(player.uniqueId) { HashMap() }
        val last = history[nationName]
        if (last != null && now - last < territoryEnterCooldownMs) return
        history[nationName] = now
        addProgress(player, QuestType.ENEMY_TERRITORY_ENTER)
    }

    // 적 신호기 방문: 같은 국가 신호기는 하루 1회만 인정 (근처에서 잠수해도 계속 오르지 않음)
    private val beaconVisits = HashMap<UUID, MutableSet<String>>() // "날짜|국가"

    fun onBeaconVisit(player: Player, nationName: String) {
        val visits = beaconVisits.getOrPut(player.uniqueId) { mutableSetOf() }
        val today = todayKey()
        visits.removeIf { !it.startsWith("$today|") }
        if (!visits.add("$today|$nationName")) return
        addProgress(player, QuestType.ENEMY_BEACON_VISIT)
    }

    private fun complete(player: Player, pd: PlayerQuestData, def: QuestDefinition) {
        giveSouls(player, def.souls)
        giveMoney(player, def.money)
        if (def.dc > 0) {
            plugin.dcManager.addDC(player, def.dc)
            logDC(player, def.dc)
        }

        player.sendMessage(
            "§6[퀘스트 완료] ${def.difficulty.color}[${def.difficulty.displayName}] §f${def.name} " +
                "§7- 보상: §b영혼 ${def.souls}" + (if (def.money > 0) " §6${formatMoney(def.money)}원" else "") +
                (if (def.dc > 0) " §3DC ${def.dc}" else "")
        )
        ActionBarManager.showTemp(player, "§6✔ 퀘스트 완료: §f${def.name}", 3.0)
        Sounds.reward(player)

        // 그날 첫 완료면 연속 출석 갱신 + 보너스
        if (pd.streakLast != pd.dateKey) {
            pd.streak = currentStreak(pd) + 1
            pd.streakLast = pd.dateKey
            giveStreakBonus(player, pd.streak)
        }

        plugin.achievementManager.onQuestComplete(player, pd.streak)
        plugin.nationManager.addPeaceFor(player.uniqueId, when (def.difficulty) {
            QuestDifficulty.EASY -> 2.0
            QuestDifficulty.NORMAL -> 4.0
            else -> 6.0
        })

        // 주간 보너스: 이번 주에 퀘스트를 완료한 날짜 기록
        pd.weekDays.add(pd.dateKey)
        if (!pd.weeklyClaimed && pd.weekDays.size >= weeklyRequiredDays) {
            pd.weeklyClaimed = true
            giveWeeklyBonus(player)
        }
    }

    // 놓친 날 하루마다 절반으로 줄어든 현재 연속 일수 (0으로 초기화하지 않음)
    fun currentStreak(pd: PlayerQuestData): Int {
        if (pd.streakLast.isEmpty()) return 0
        val last = runCatching { LocalDate.parse(pd.streakLast) }.getOrNull() ?: return 0
        val missed = java.time.temporal.ChronoUnit.DAYS.between(last, todayDate()) - 1
        if (missed <= 0) return pd.streak
        return pd.streak shr missed.coerceAtMost(31).toInt()
    }

    fun streakBonusSouls(streak: Int): Long = streakSoulsPerDay * streak.coerceAtMost(streakMaxDays)
    fun streakBonusMoney(streak: Int): Double = streakMoneyPerDay * streak.coerceAtMost(streakMaxDays)

    private fun giveStreakBonus(player: Player, streak: Int) {
        val souls = streakBonusSouls(streak)
        val money = streakBonusMoney(streak)
        if (souls <= 0 && money <= 0) return
        giveSouls(player, souls)
        giveMoney(player, money)
        player.sendMessage("§e[연속 출석 ${streak}일] §f추가 보상 §b영혼 $souls §6${formatMoney(money)}원")
    }

    private fun giveWeeklyBonus(player: Player) {
        giveSouls(player, weeklySouls)
        giveMoney(player, weeklyMoney)
        if (weeklyDC > 0) {
            plugin.dcManager.addDC(player, weeklyDC)
            logDC(player, weeklyDC)
        }
        player.sendMessage(
            "§d§l[주간 보너스] §f이번 주 ${weeklyRequiredDays}일 퀘스트 달성! " +
                "§b영혼 $weeklySouls §6${formatMoney(weeklyMoney)}원 §3DC $weeklyDC"
        )
        ActionBarManager.showTemp(player, "§d★ 주간 보너스 획득!", 3.0)
        Sounds.bigReward(player)
    }

    // DC는 실결제 재화라 무료 지급 내역을 별도 로그로 남김 (시각|UUID|닉네임|DC)
    private fun logDC(player: Player, amount: Long) {
        val time = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        runCatching { dcLogFile.appendText("$time|${player.uniqueId}|${player.name}|$amount\n") }
            .onFailure { plugin.logger.severe("[퀘스트] quest-dc.log 기록 실패: ${it.message}") }
    }

    private fun giveSouls(player: Player, amount: Long) {
        if (amount > 0) plugin.soulManager.addSouls(player, amount)
    }

    // Vault가 없으면 돈 보상은 건너뜀
    private fun giveMoney(player: Player, amount: Double) {
        if (amount <= 0 || Bukkit.getPluginManager().getPlugin("Vault") == null) return
        val economy = Bukkit.getServicesManager()
            .getRegistration(net.milkbowl.vault.economy.Economy::class.java)?.provider
        if (economy == null) {
            plugin.logger.warning("[퀘스트] 경제 플러그인이 없어 ${player.name}의 돈 보상을 지급하지 못했습니다.")
            return
        }
        economy.depositPlayer(player, amount)
    }

    fun formatMoney(amount: Double): String = String.format("%,.0f", amount)

    // ───────────────────────── 초기화 ─────────────────────────

    // 해당 플레이어의 오늘 퀘스트를 초기화 (주간·연속 기록은 유지). 오프라인이면 다음 접속 때 새로 뽑힘
    fun resetToday(uuid: UUID): Boolean {
        val pd = players[uuid] ?: return false
        pd.dateKey = ""
        pd.rerolled = false
        pd.quests.clear()
        Bukkit.getPlayer(uuid)?.let { ensureToday(it) } ?: save(uuid)
        return true
    }

    val weeklyRequired get() = weeklyRequiredDays

    // /초기화: player-quests.yml을 비움
    fun resetAll() {
        players.clear()
        recentKills.clear()
        territoryEnterCooldown.clear()
        beaconVisits.clear()
        data = YamlConfiguration()
        data.save(file)
    }

    // ───────────────────────── 저장 ─────────────────────────

    private fun readPlayer(key: String): PlayerQuestData {
        val s = data.getConfigurationSection(key)!!
        val pd = PlayerQuestData(
            dateKey = s.getString("date", "")!!,
            rerolled = s.getBoolean("rerolled"),
            weekKey = s.getString("week", "")!!,
            weeklyClaimed = s.getBoolean("weekly-claimed"),
            streak = s.getInt("streak"),
            streakLast = s.getString("streak-last", "")!!
        )
        pd.weekDays.addAll(s.getStringList("week-days"))
        s.getConfigurationSection("quests")?.let { qs ->
            qs.getKeys(false).sortedBy { it.toIntOrNull() ?: 0 }.forEach { i ->
                val q = qs.getConfigurationSection(i) ?: return@forEach
                pd.quests.add(ActiveQuest(q.getString("id", "")!!, q.getInt("progress"), q.getBoolean("done")))
            }
        }
        return pd
    }

    fun save(uuid: UUID) {
        writePlayer(uuid)
        saveFile()
    }

    private fun writePlayer(uuid: UUID) {
        val pd = players[uuid] ?: return
        val key = uuid.toString()
        data.set(key, null)
        data.set("$key.date", pd.dateKey)
        data.set("$key.rerolled", pd.rerolled)
        pd.quests.forEachIndexed { i, q ->
            data.set("$key.quests.$i.id", q.id)
            data.set("$key.quests.$i.progress", q.progress)
            data.set("$key.quests.$i.done", q.done)
        }
        data.set("$key.week", pd.weekKey)
        data.set("$key.week-days", pd.weekDays.toList())
        data.set("$key.weekly-claimed", pd.weeklyClaimed)
        data.set("$key.streak", pd.streak)
        data.set("$key.streak-last", pd.streakLast)
    }

    fun saveAll() {
        players.keys.forEach { writePlayer(it) }
        saveFile()
    }

    private fun saveFile() {
        runCatching { data.save(file) }
            .onFailure { plugin.logger.severe("[퀘스트] player-quests.yml 저장 실패: ${it.message}") }
    }
}
