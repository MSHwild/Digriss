package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.Sound
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// 국가전쟁 이벤트 (event.yml): 정해진 시간에 전쟁 점수·전쟁 킬 영혼이 배로 늘어남
// 이벤트 중에는 모든 플레이어 화면 위에 남은 시간 보스바 표시
class WarEventManager(private val plugin: Digriss) {

    private data class Slot(val day: DayOfWeek, val start: LocalTime, val end: LocalTime)

    private val zone = ZoneId.of("Asia/Seoul")
    private val file = File(plugin.dataFolder, "event.yml")

    private var schedule: List<Slot> = emptyList()
    var scoreMultiplier = 2
        private set
    var soulMultiplier = 2
        private set
    private var noticeMinutes = 10

    private var manualEndsAt: Long? = null   // 관리자가 직접 연 이벤트의 종료 시각
    private var active = false
    private var currentEndsAt = 0L
    private var noticedSlot: String? = null   // 같은 예고를 두 번 하지 않게
    private var bar: BossBar? = null
    private var startedAt = 0L                // 이번 이벤트 시작 시각 (보스바 진행도 계산용)

    init {
        if (!file.exists()) plugin.saveResource("event.yml", false)
        load()
        // 10초마다 상태 확인 (시작/종료 감지, 예고, 보스바 갱신)
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 200L)
    }

    fun load() {
        val config = YamlConfiguration.loadConfiguration(file)
        schedule = config.getStringList("schedule").mapNotNull { parse(it) }
        scoreMultiplier = config.getInt("score-multiplier", 2).coerceAtLeast(1)
        soulMultiplier = config.getInt("soul-multiplier", 2).coerceAtLeast(1)
        noticeMinutes = config.getInt("notice-minutes", 10).coerceAtLeast(0)
    }

    // "SATURDAY 20:00-22:00"
    private fun parse(line: String): Slot? = runCatching {
        val (dayText, range) = line.trim().split(Regex("\\s+"), limit = 2)
        val (s, e) = range.split("-")
        Slot(DayOfWeek.valueOf(dayText.uppercase()), LocalTime.parse(s.trim()), LocalTime.parse(e.trim()))
    }.getOrElse {
        plugin.logger.warning("[전쟁 이벤트] 시간표 '$line'을(를) 읽을 수 없어 건너뜁니다. (예: SATURDAY 20:00-22:00)")
        null
    }

    fun isActive(): Boolean = active

    // 지금 시간표상 진행 중인 칸의 종료 시각(ms), 없으면 null
    private fun scheduledEnd(now: ZonedDateTime): Long? {
        val slot = schedule.firstOrNull { it.day == now.dayOfWeek && !now.toLocalTime().isBefore(it.start) && now.toLocalTime().isBefore(it.end) }
            ?: return null
        return now.with(slot.end).toInstant().toEpochMilli()
    }

    private fun tick() {
        val now = ZonedDateTime.now(zone)
        val nowMs = System.currentTimeMillis()
        manualEndsAt?.let { if (nowMs >= it) manualEndsAt = null }
        val endsAt = manualEndsAt ?: scheduledEnd(now)

        when {
            endsAt != null && !active -> start(endsAt)
            endsAt == null && active -> finish()
            endsAt != null -> currentEndsAt = endsAt
        }
        if (active) updateBar() else checkNotice(now)
    }

    // 시작 N분 전 예고
    private fun checkNotice(now: ZonedDateTime) {
        if (noticeMinutes <= 0) return
        val soon = now.plusMinutes(noticeMinutes.toLong())
        val slot = schedule.firstOrNull { it.day == soon.dayOfWeek && it.start.hour == soon.hour && it.start.minute == soon.minute }
            ?: return
        val key = "${now.toLocalDate()}|${slot.day}|${slot.start}"
        if (noticedSlot == key) return
        noticedSlot = key
        Bukkit.broadcastMessage("§6[전쟁 이벤트] §e${noticeMinutes}분 뒤 국가전쟁 이벤트가 시작됩니다! §7(전쟁 점수 ${scoreMultiplier}배 · 전쟁 킬 영혼 ${soulMultiplier}배)")
        Sounds.all(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f)
        plugin.discordNotifier.notify("war-event-notice", "⏰ 전쟁 이벤트 예고",
            "${noticeMinutes}분 뒤 국가전쟁 이벤트가 시작돼요!\n전쟁 점수 ${scoreMultiplier}배 · 전쟁 킬 영혼 ${soulMultiplier}배", DiscordNotifier.GOLD)
    }

    private fun start(endsAt: Long) {
        active = true
        startedAt = System.currentTimeMillis()
        currentEndsAt = endsAt
        Bukkit.broadcastMessage("§4§l국가전쟁 이벤트 시작! §e전쟁 점수 ${scoreMultiplier}배 · 전쟁 킬 영혼 ${soulMultiplier}배 §7(${remainingText()} 동안)")
        Bukkit.getOnlinePlayers().forEach { it.sendTitle("§4§l전쟁 이벤트", "§e전쟁 점수 ${scoreMultiplier}배!", 10, 60, 20) }
        Sounds.all(Sound.EVENT_RAID_HORN, 1f, 0.9f)
        plugin.discordNotifier.notify("war-event", "⚔ 국가전쟁 이벤트 시작!",
            "전쟁 점수 ${scoreMultiplier}배 · 전쟁 킬 영혼 ${soulMultiplier}배 (${remainingText()} 동안)\n지금 접속해서 전쟁에 참여하세요! `puritymc.kr`", DiscordNotifier.RED)
        bar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_10).also { b ->
            Bukkit.getOnlinePlayers().forEach { b.addPlayer(it) }
        }
        updateBar()
    }

    private fun finish() {
        active = false
        manualEndsAt = null
        bar?.removeAll()
        bar = null
        Bukkit.broadcastMessage("§6[전쟁 이벤트] §f국가전쟁 이벤트가 끝났습니다. 수고하셨습니다!")
        Sounds.all(Sound.BLOCK_BELL_USE, 1f, 0.8f)
        plugin.discordNotifier.notify("war-event", "🔔 전쟁 이벤트 종료", "국가전쟁 이벤트가 끝났습니다. 수고하셨습니다!", DiscordNotifier.GRAY)
    }

    private fun updateBar() {
        val b = bar ?: return
        // 이벤트 중에 들어온 사람도 보이게
        Bukkit.getOnlinePlayers().forEach { if (it !in b.players) b.addPlayer(it) }
        b.setTitle("§4국가전쟁 이벤트 §f- 전쟁 점수 §e${scoreMultiplier}배 §8| §7남은 시간 §f${remainingText()}")
        val total = (currentEndsAt - startedAt).coerceAtLeast(1)
        b.progress = ((currentEndsAt - System.currentTimeMillis()).toDouble() / total).coerceIn(0.0, 1.0)
    }

    private fun remainingText(): String {
        val left = ((currentEndsAt - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)
        return if (left >= 60) "${left / 60}시간 ${left % 60}분" else "${left}분"
    }

    // ───────────────────────── 관리자 ─────────────────────────

    fun startManual(minutes: Int) {
        manualEndsAt = System.currentTimeMillis() + minutes * 60_000L
        if (active) currentEndsAt = manualEndsAt!! else start(manualEndsAt!!)
    }

    // 시간표 진행 중인 이벤트는 그 칸이 끝날 때까지 다시 켜지므로 직접 연 이벤트만 끌 수 있음
    fun stopManual(): Boolean {
        if (manualEndsAt == null) return false
        manualEndsAt = null
        if (scheduledEnd(ZonedDateTime.now(zone)) == null) finish()
        return true
    }

    fun statusText(): String =
        if (active) "§a진행 중 §7(남은 시간 ${remainingText()}, 점수 ${scoreMultiplier}배 · 영혼 ${soulMultiplier}배)"
        else "§7진행 중 아님 §8| §7시간표: §f" + schedule.joinToString(", ") { "${dayKo(it.day)} ${it.start}-${it.end}" }.ifEmpty { "없음" }

    private fun dayKo(d: DayOfWeek) = when (d) {
        DayOfWeek.MONDAY -> "월"; DayOfWeek.TUESDAY -> "화"; DayOfWeek.WEDNESDAY -> "수"; DayOfWeek.THURSDAY -> "목"
        DayOfWeek.FRIDAY -> "금"; DayOfWeek.SATURDAY -> "토"; DayOfWeek.SUNDAY -> "일"
    }

    fun shutdown() {
        bar?.removeAll()
        bar = null
    }
}
