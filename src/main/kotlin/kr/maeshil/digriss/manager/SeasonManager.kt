package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.FireworkEffect
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Firework
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerLoginEvent
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 시즌 (season.yml)
 * 매년 end 날짜에 시즌이 끝나고 → 서버 잠금(OP만) → open 날짜에 자동 오픈 (시즌 번호 +1).
 * 진행 상태(시즌 번호, 이번 종료일/오픈일, 보낸 예고)는 season-data.yml에 저장.
 * 초기화와 월드 교체는 관리자가 직접 하고, /초기화 시즌 을 안 했으면 자동 오픈하지 않음.
 */
class SeasonManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val zone = ZoneId.of("Asia/Seoul")
    private val fullFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val dayFormat = DateTimeFormatter.ofPattern("MM-dd HH:mm")
    private val configFile = File(plugin.dataFolder, "season.yml")
    private val dataFile = File(plugin.dataFolder, "season-data.yml")

    // season.yml
    private var endTemplate = "10-31 23:59"
    private var openTemplate = "01-01 00:00"
    private var minSeasonMs = 180L * DAY
    private var warnAt: List<Long> = emptyList()      // 남은 시간(ms) 기준, 큰 것부터
    private var openWarnAt: List<Long> = emptyList()
    private var joinNoticeMs = 30L * DAY
    private var resetText = ""
    private var lockMessage = ""

    // season-data.yml
    private var season = 1
    private var closed = false          // 시즌이 끝나 잠긴 상태
    private var endAt = 0L              // 이번 시즌 종료 시각
    private var openAt = 0L             // 다음 시즌 오픈 시각 (잠긴 동안만 의미 있음)
    private var resetDone = false       // 잠긴 뒤 /초기화 시즌 을 했는지
    private val fired = HashSet<Long>() // 이번 단계에서 이미 보낸 예고
    private var lastStuckWarn = 0L

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
    }

    init {
        load()
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { check() }, 100L, 20L)
    }

    val name get() = "시즌 $season"
    private val nextName get() = "시즌 ${season + 1}"

    fun load() {
        plugin.dataFolder.mkdirs()
        var oldSeason: Int? = null
        if (configFile.exists() && !YamlConfiguration.loadConfiguration(configFile).contains("end")) {
            // 예전 형식(end-date 한 번짜리) → 새 형식으로 교체. 예전 파일은 season-old.yml로 보관
            oldSeason = YamlConfiguration.loadConfiguration(configFile).getString("name")
                ?.filter { it.isDigit() }?.toIntOrNull()
            configFile.renameTo(File(plugin.dataFolder, "season-old.yml"))
            plugin.logger.info("[시즌] season.yml을 새 형식으로 바꿨습니다. (예전 파일: season-old.yml)")
        }
        if (!configFile.exists()) plugin.saveResource("season.yml", false)

        val c = YamlConfiguration.loadConfiguration(configFile)
        endTemplate = c.getString("end", "10-31 23:59") ?: "10-31 23:59"
        openTemplate = c.getString("open", "01-01 00:00") ?: "01-01 00:00"
        minSeasonMs = c.getLong("min-season-days", 180).coerceAtLeast(0) * DAY
        warnAt = c.getStringList("warn").mapNotNull { parseDuration(it) }.sortedDescending()
        openWarnAt = c.getStringList("open-warn").mapNotNull { parseDuration(it) }.sortedDescending()
        joinNoticeMs = c.getLong("join-notice-days", 30).coerceAtLeast(0) * DAY
        resetText = c.getString("reset-text", "") ?: ""
        lockMessage = c.getString("lock-message", "") ?: ""
        if (parseTemplate(endTemplate) == null) plugin.logger.warning("[시즌] end 형식이 잘못됐습니다: '$endTemplate' (예: 10-31 23:59)")
        if (parseTemplate(openTemplate) == null) plugin.logger.warning("[시즌] open 형식이 잘못됐습니다: '$openTemplate' (예: 01-01 00:00)")

        val d = YamlConfiguration.loadConfiguration(dataFile)
        if (d.contains("end-at")) {
            season = d.getInt("season", 1)
            closed = d.getBoolean("closed", false)
            endAt = d.getLong("end-at")
            openAt = d.getLong("open-at")
            resetDone = d.getBoolean("reset-done", false)
            fired.clear(); fired.addAll(d.getLongList("fired"))
        } else {
            // 처음 실행: 지금 시즌을 시작한 것으로 보고 종료일 계산
            season = oldSeason ?: c.getInt("season", 1)
            closed = false
            endAt = nextOccurrence(endTemplate, System.currentTimeMillis() + minSeasonMs) ?: 0L
            openAt = 0L
            resetDone = false
            fired.clear()
            saveData()
            plugin.logger.info("[시즌] $name 종료일: ${formatTime(endAt)}")
        }
    }

    private fun saveData() {
        val d = YamlConfiguration()
        d.set("season", season)
        d.set("closed", closed)
        d.set("end-at", endAt)
        d.set("end-at-text", formatTime(endAt)) // 사람이 보기 쉽게 (읽지는 않음)
        d.set("open-at", openAt)
        d.set("open-at-text", if (openAt > 0) formatTime(openAt) else "")
        d.set("reset-done", resetDone)
        d.set("fired", fired.toList())
        runCatching { d.save(dataFile) }.onFailure { plugin.logger.severe("[시즌] season-data.yml 저장 실패: ${it.message}") }
    }

    // "30d", "12h", "10m" → ms
    private fun parseDuration(text: String): Long? {
        val t = text.trim().lowercase()
        val n = t.dropLast(1).toLongOrNull() ?: return null
        return when (t.lastOrNull()) { 'd' -> n * DAY; 'h' -> n * HOUR; 'm' -> n * MINUTE; else -> null }
    }

    // "10-31 23:59" → (MonthDay, LocalTime)
    private fun parseTemplate(text: String): Pair<MonthDay, LocalTime>? = runCatching {
        val parts = text.trim().split(" ")
        MonthDay.parse("--" + parts[0]) to LocalTime.parse(parts.getOrElse(1) { "00:00" })
    }.getOrNull()

    /** after 이후 처음 오는 "MM-dd HH:mm" 시각 */
    private fun nextOccurrence(template: String, after: Long): Long? {
        val (md, time) = parseTemplate(template) ?: return null
        val startYear = Instant.ofEpochMilli(after).atZone(zone).year
        for (year in startYear..startYear + 2) {
            val t = md.atYear(year).atTime(time).atZone(zone).toInstant().toEpochMilli()
            if (t > after) return t
        }
        return null
    }

    private fun formatTime(ms: Long) = if (ms <= 0) "-" else Instant.ofEpochMilli(ms).atZone(zone).format(fullFormat)

    private fun parseFull(text: String): Long? = runCatching {
        LocalDateTime.parse(text.trim(), fullFormat).atZone(zone).toInstant().toEpochMilli()
    }.getOrNull()

    fun remainingText(ms: Long): String {
        val days = ms / DAY
        val hours = (ms % DAY) / HOUR
        val minutes = (ms % HOUR) / MINUTE
        return when {
            days > 0 -> if (hours > 0) "${days}일 ${hours}시간" else "${days}일"
            hours > 0 -> if (minutes > 0) "${hours}시간 ${minutes}분" else "${hours}시간"
            else -> "${minutes.coerceAtLeast(1)}분"
        }
    }

    /** 시즌이 끝나 잠긴 상태인지 (OP만 접속 가능) */
    fun isLocked(): Boolean = closed

    /** 스코어보드용 한 줄. 예고 기간(join-notice-days) 안이면 빨간색 */
    fun scoreboardLine(): String? {
        val now = System.currentTimeMillis()
        if (closed) {
            val left = openAt - now
            return if (left > 0) " §e$nextName 오픈까지 ${remainingText(left)}" else " §c$nextName 오픈 대기 (초기화 필요)"
        }
        if (endAt <= 0) return null
        val left = endAt - now
        if (left <= 0) return " §c$name 종료"
        val color = if (left <= joinNoticeMs) "§c" else "§7"
        val days = left / DAY
        return if (days >= 1) " ${color}$name 종료까지 ${days}일" else " §c$name 종료까지 ${remainingText(left)}"
    }

    private fun check() {
        val now = System.currentTimeMillis()
        if (!closed) {
            if (endAt <= 0) return
            val left = endAt - now
            if (left <= 0) { closeSeason(); return }
            // 지금 지나간 예고 시점 중 가장 가까운 것 하나만 보냄 (서버가 꺼져 있던 동안 지나간 예고는 건너뜀)
            val crossed = warnAt.filter { left <= it && it !in fired }
            if (crossed.isEmpty()) return
            fired.addAll(crossed); saveData()
            announceEnd(left)
        } else {
            if (openAt <= 0) return
            val left = openAt - now
            if (left <= 0) {
                if (resetDone) openSeason()
                else if (now - lastStuckWarn > 10 * MINUTE) {
                    lastStuckWarn = now
                    val msg = "[시즌] 오픈 시간이 지났지만 /초기화 시즌 을 하지 않아 $nextName 을(를) 열지 않았습니다. 초기화 후 자동으로 열리고, 그대로 열려면 /시즌 오픈 강제"
                    plugin.logger.warning(msg)
                    Bukkit.getOnlinePlayers().filter { it.isOp }.forEach { it.sendMessage("§c$msg") }
                }
                return
            }
            val crossed = openWarnAt.filter { left <= it && it !in fired }
            if (crossed.isEmpty()) return
            fired.addAll(crossed); saveData()
            plugin.discordNotifier.notify("season", "$nextName 오픈까지 ${remainingText(left)}",
                "$nextName 이(가) **${formatTime(openAt)}**에 열립니다!", DiscordNotifier.BLUE)
        }
    }

    private fun announceEnd(left: Long) {
        val text = remainingText(left)
        Bukkit.broadcastMessage("§c[시즌] §f$name 종료까지 §c$text§f 남았습니다.")
        if (resetText.isNotBlank()) Bukkit.broadcastMessage("§7$resetText")
        if (left <= DAY) {
            Bukkit.getOnlinePlayers().forEach { it.sendTitle("§c$name 종료까지 $text", "§7$resetText", 10, 70, 20) }
        }
        Sounds.all(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.7f)
        plugin.discordNotifier.notify("season", "$name 종료까지 $text",
            "$name 이(가) **${formatTime(endAt)}**에 끝납니다.\n$resetText", DiscordNotifier.GOLD)
    }

    private fun closeSeason() {
        closed = true
        openAt = nextOccurrence(openTemplate, endAt) ?: 0L
        resetDone = false
        fired.clear()
        saveData()
        val openText = if (openAt > 0) " $nextName 은(는) ${formatTime(openAt)}에 열립니다." else ""
        Bukkit.broadcastMessage("§c§l[시즌] $name 이(가) 끝났습니다! §f함께해 주셔서 감사합니다.$openText")
        Bukkit.getOnlinePlayers().forEach { it.sendTitle("§c§l$name 종료", "§f함께해 주셔서 감사합니다", 10, 80, 20) }
        Sounds.all(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f)
        plugin.discordNotifier.notify("season", "$name 종료",
            "$name 이(가) 끝났습니다. 함께해 주셔서 감사합니다!$openText", DiscordNotifier.RED)
        // 제목을 볼 시간을 준 뒤 OP가 아닌 사람은 내보냄
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            Bukkit.getOnlinePlayers().filter { !canJoinLocked(it) }.forEach { it.kickPlayer(kickText()) }
        }, 200L)
    }

    private fun openSeason() {
        season++
        closed = false
        endAt = nextOccurrence(endTemplate, System.currentTimeMillis() + minSeasonMs) ?: 0L
        openAt = 0L
        resetDone = false
        fired.clear()
        saveData()
        Bukkit.broadcastMessage("§a§l[시즌] $name 이(가) 열렸습니다! §f종료: §e${formatTime(endAt)}")
        Bukkit.getOnlinePlayers().forEach { p ->
            p.sendTitle("§6§l$name 오픈!", "§f새로운 전쟁이 시작됩니다", 10, 80, 20)
            launchFirework(p)
        }
        Sounds.all(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1.2f)
        plugin.discordNotifier.notify("season", "$name 오픈!",
            "$name 이(가) 열렸습니다! 지금 **puritymc.kr** 로 접속하세요.\n종료 예정: ${formatTime(endAt)}", DiscordNotifier.GREEN)
        plugin.logger.info("[시즌] $name 오픈. 종료일: ${formatTime(endAt)}")
    }

    private fun launchFirework(p: Player) {
        val fw = p.world.spawn(p.location, Firework::class.java)
        fw.fireworkMeta = fw.fireworkMeta.apply {
            addEffect(FireworkEffect.builder().with(FireworkEffect.Type.BALL_LARGE)
                .withColor(Color.ORANGE, Color.YELLOW).withFade(Color.WHITE).trail(true).build())
            power = 1
        }
    }

    /** /초기화 시즌 을 했을 때 호출 → 오픈일에 자동으로 열 수 있음 */
    fun markReset() {
        resetDone = true
        saveData()
    }

    private fun canJoinLocked(p: Player) = p.isOp || p.hasPermission("digriss.season.bypass")

    private fun kickText(): String {
        val left = openAt - System.currentTimeMillis()
        val openLine = when {
            openAt <= 0 -> ""
            left > 0 -> "§f$nextName 오픈까지 §e${remainingText(left)}\n§7(${formatTime(openAt)})\n\n"
            else -> "§f$nextName 곧 오픈합니다\n\n"
        }
        return "§c§l$name 종료\n\n$openLine§7$lockMessage"
    }

    // 시즌이 끝나 잠겨 있으면 OP가 아닌 사람은 접속 불가
    @EventHandler
    fun onLogin(e: PlayerLoginEvent) {
        if (!isLocked() || canJoinLocked(e.player)) return
        e.disallow(PlayerLoginEvent.Result.KICK_OTHER, kickText())
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        if (closed || endAt <= 0) return
        val left = endAt - System.currentTimeMillis()
        if (left <= 0 || left > joinNoticeMs) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!e.player.isOnline) return@Runnable
            e.player.sendMessage("§c[시즌] §f$name 종료까지 §c${remainingText(left)}§f 남았습니다. §7(${formatTime(endAt)})")
            if (resetText.isNotBlank()) e.player.sendMessage("§7$resetText")
        }, 80L)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val admin = sender.hasPermission("digriss.admin")
        when (args.getOrNull(0)) {
            "종료일" -> if (admin) {
                val t = parseFull(args.drop(1).joinToString(" "))
                    ?: return sender.sendMessage("§c형식: /시즌 종료일 2027-10-31 23:59").let { true }
                if (closed) return sender.sendMessage("§c지금은 시즌이 끝난 상태입니다. /시즌 오픈일 을 쓰세요.").let { true }
                endAt = t; fired.clear(); saveData()
                sender.sendMessage("§a$name 종료일을 ${formatTime(endAt)} 으로 바꿨습니다.")
                return true
            }
            "오픈일" -> if (admin) {
                val t = parseFull(args.drop(1).joinToString(" "))
                    ?: return sender.sendMessage("§c형식: /시즌 오픈일 2028-01-01 00:00").let { true }
                if (!closed) return sender.sendMessage("§c지금은 시즌 진행 중입니다. 오픈일은 시즌이 끝난 뒤에 바꿀 수 있어요.").let { true }
                openAt = t; fired.clear(); saveData()
                sender.sendMessage("§a$nextName 오픈일을 ${formatTime(openAt)} 으로 바꿨습니다.")
                return true
            }
            "오픈" -> if (admin) {
                if (!closed) return sender.sendMessage("§c이미 시즌 진행 중입니다.").let { true }
                if (!resetDone && args.getOrNull(1) != "강제")
                    return sender.sendMessage("§c아직 /초기화 시즌 을 하지 않았습니다. 그대로 열려면 /시즌 오픈 강제").let { true }
                openSeason()
                plugin.adminLogManager.log(sender, "$name 수동 오픈")
                return true
            }
        }

        val now = System.currentTimeMillis()
        if (closed) {
            sender.sendMessage("§6§l[ $name 종료 ]")
            sender.sendMessage(if (openAt > now) "§f$nextName 오픈: §e${formatTime(openAt)} §7(${remainingText(openAt - now)} 남음)"
                               else "§f$nextName 곧 오픈합니다.")
            if (admin) sender.sendMessage("§7초기화: ${if (resetDone) "§a완료" else "§c아직 안 함 (/초기화 시즌)"}")
        } else {
            sender.sendMessage("§6§l[ $name ]")
            if (endAt > 0) sender.sendMessage("§f종료: §e${formatTime(endAt)} §7(${remainingText((endAt - now).coerceAtLeast(0))} 남음)")
            if (resetText.isNotBlank()) sender.sendMessage("§7$resetText")
        }
        if (admin) sender.sendMessage("§8관리자: /시즌 종료일 <날짜 시간>, /시즌 오픈일 <날짜 시간>, /시즌 오픈 [강제]")
        return true
    }
}
