package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 시즌 종료 예고 (season.yml)
 * end-date가 다가오면 정해진 시점(30일, 7일, 1일, 1시간 전 등)에 전체 공지 + 디스코드 알림,
 * 접속할 때와 스코어보드에도 남은 기간을 보여 줌. 실제 초기화는 관리자가 직접 진행.
 */
class SeasonManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val zone = ZoneId.of("Asia/Seoul")
    private val format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private val configFile = File(plugin.dataFolder, "season.yml")
    private val dataFile = File(plugin.dataFolder, "season-data.yml")

    private var name = "프리시즌"
    private var endMillis: Long? = null
    private var endText = ""
    private var warnAt: List<Long> = emptyList()      // 남은 시간(ms) 기준, 큰 것부터
    private var joinNoticeMs = 30L * DAY
    private var resetText = ""
    private var lockAfterEnd = true
    private var lockMessage = ""
    private val fired = HashSet<Long>()              // 이번 종료일에 이미 보낸 예고
    private var endedNotified = false

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
    }

    init {
        load()
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { check() }, 100L, 20L)
    }

    fun load() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            if (plugin.getResource("season.yml") != null) plugin.saveResource("season.yml", false)
            else YamlConfiguration().apply {
                set("name", "프리시즌"); set("end-date", "")
                set("warn", listOf("30d", "14d", "7d", "3d", "1d", "12h", "1h", "10m", "1m"))
                set("join-notice-days", 30)
                set("reset-text", "월드, 인벤토리, 돈, 영혼, 직업, 국가가 초기화됩니다. (DC · 칭호 · 킬 이펙트는 유지)")
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        name = c.getString("name", "프리시즌") ?: "프리시즌"
        endText = c.getString("end-date", "")?.trim() ?: ""
        endMillis = endText.takeIf { it.isNotBlank() }?.let { text ->
            runCatching { LocalDateTime.parse(text, format).atZone(zone).toInstant().toEpochMilli() }
                .onFailure { plugin.logger.warning("[시즌] end-date 형식이 잘못됐습니다: '$text' (예: 2027-10-01 20:00)") }
                .getOrNull()
        }
        warnAt = c.getStringList("warn").mapNotNull { parseDuration(it) }.sortedDescending()
        joinNoticeMs = c.getLong("join-notice-days", 30).coerceAtLeast(0) * DAY
        resetText = c.getString("reset-text", "") ?: ""
        lockAfterEnd = c.getBoolean("lock-after-end", true)
        lockMessage = c.getString("lock-message", "새 시즌을 준비 중입니다. 디스코드에서 오픈 소식을 확인해 주세요.") ?: ""

        // 종료일이 바뀌면 예고를 처음부터 다시 보냄
        val d = YamlConfiguration.loadConfiguration(dataFile)
        fired.clear()
        endedNotified = false
        if (d.getString("end-date") == endText) {
            fired.addAll(d.getLongList("fired"))
            endedNotified = d.getBoolean("ended", false)
        }
    }

    private fun saveData() {
        val d = YamlConfiguration()
        d.set("end-date", endText)
        d.set("fired", fired.toList())
        d.set("ended", endedNotified)
        runCatching { d.save(dataFile) }.onFailure { plugin.logger.severe("[시즌] season-data.yml 저장 실패: ${it.message}") }
    }

    // "30d", "12h", "10m" → ms
    private fun parseDuration(text: String): Long? {
        val t = text.trim().lowercase()
        val n = t.dropLast(1).toLongOrNull() ?: return null
        return when (t.last()) { 'd' -> n * DAY; 'h' -> n * HOUR; 'm' -> n * MINUTE; else -> null }
    }

    fun remainingMs(): Long? = endMillis?.let { it - System.currentTimeMillis() }

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

    /** 스코어보드용 한 줄. 예고 기간(join-notice-days)이 아니면 null */
    fun scoreboardLine(): String? {
        val left = remainingMs() ?: return null
        if (left > joinNoticeMs) return null
        if (left <= 0) return " §c$name 종료"
        val days = left / DAY
        return if (days >= 1) " §c$name 종료 D-$days" else " §c$name 종료 ${remainingText(left)} 전"
    }

    private fun check() {
        val left = remainingMs() ?: return

        if (left <= 0) {
            if (!endedNotified) {
                endedNotified = true
                saveData()
                Bukkit.broadcastMessage("§c§l[시즌] $name 이(가) 끝났습니다! §f곧 초기화가 진행됩니다. 함께해 주셔서 감사합니다.")
                Bukkit.getOnlinePlayers().forEach { it.sendTitle("§c§l$name 종료", "§f곧 새 시즌이 시작됩니다", 10, 80, 20) }
                Sounds.all(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 0.8f)
                plugin.discordNotifier.notify("season", "$name 종료", "$name 이(가) 끝났습니다. 곧 초기화 후 새 시즌이 시작됩니다!", DiscordNotifier.RED)
                // 제목을 볼 시간을 준 뒤 OP가 아닌 사람은 내보냄
                if (lockAfterEnd) Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                    Bukkit.getOnlinePlayers().filter { !canJoinLocked(it) }.forEach { it.kickPlayer(kickText()) }
                }, 200L)
            }
            return
        }

        // 지금 지나간 예고 시점 중 가장 가까운 것 하나만 보냄 (서버가 꺼져 있던 동안 지나간 예고는 건너뜀)
        val crossed = warnAt.filter { left <= it && it !in fired }
        if (crossed.isEmpty()) return
        fired.addAll(crossed)
        saveData()
        announce(left)
    }

    private fun announce(left: Long) {
        val text = remainingText(left)
        Bukkit.broadcastMessage("§c[시즌] §f$name 종료까지 §c$text§f 남았습니다.")
        if (resetText.isNotBlank()) Bukkit.broadcastMessage("§7$resetText")
        if (left <= DAY) {
            Bukkit.getOnlinePlayers().forEach { it.sendTitle("§c$name 종료까지 $text", "§7$resetText", 10, 70, 20) }
        }
        Sounds.all(org.bukkit.Sound.BLOCK_NOTE_BLOCK_BELL, 1f, 0.7f)
        plugin.discordNotifier.notify("season", "$name 종료까지 $text",
            "$name 이(가) **$endText**에 끝납니다.\n$resetText", DiscordNotifier.GOLD)
    }

    /** 시즌이 끝나 잠긴 상태인지 (OP만 접속 가능) */
    fun isLocked(): Boolean = lockAfterEnd && (remainingMs() ?: 1) <= 0

    private fun canJoinLocked(p: org.bukkit.entity.Player) = p.isOp || p.hasPermission("digriss.season.bypass")

    private fun kickText() = "§c§l시즌 종료

§f$lockMessage"

    // 시즌이 끝나면 OP가 아닌 사람은 접속 불가 (새 시즌 종료일을 정하거나 lock-after-end: false 후 /디그리스 리로드 하면 풀림)
    @EventHandler
    fun onLogin(e: org.bukkit.event.player.PlayerLoginEvent) {
        if (!isLocked() || canJoinLocked(e.player)) return
        e.disallow(org.bukkit.event.player.PlayerLoginEvent.Result.KICK_OTHER, kickText())
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val left = remainingMs() ?: return
        if (left <= 0 || left > joinNoticeMs) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!e.player.isOnline) return@Runnable
            e.player.sendMessage("§c[시즌] §f$name 종료까지 §c${remainingText(left)}§f 남았습니다. §7($endText)")
            if (resetText.isNotBlank()) e.player.sendMessage("§7$resetText")
        }, 80L)
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val left = remainingMs()
        sender.sendMessage("§6§l[ $name ]")
        when {
            left == null -> sender.sendMessage("§7아직 종료일이 정해지지 않았습니다.")
            left <= 0 -> sender.sendMessage("§c시즌이 끝났습니다. 곧 초기화가 진행됩니다." + if (isLocked()) " §7(지금은 OP만 접속 가능)" else "")
            else -> sender.sendMessage("§f종료: §e$endText §7(${remainingText(left)} 남음)")
        }
        if (resetText.isNotBlank()) sender.sendMessage("§7$resetText")
        return true
    }
}
