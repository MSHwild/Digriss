package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 오픈 이벤트 (openevent.yml)
 *  1) 매일 첫 접속 보상: 하루에 한 번 영혼 + DC
 *  2) 친구 추천: 새로 온 사람이 /추천 <초대한 사람> 을 하면 두 사람 모두 보상
 * 끝나는 날(end-date)이 지나거나 enabled: false 면 자동으로 멈춤. 수정 후 /디그리스 리로드
 */
class OpenEventManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    private val zone = ZoneId.of("Asia/Seoul")
    private val configFile = File(plugin.dataFolder, "openevent.yml")
    private val dataFile = File(plugin.dataFolder, "openevent-data.yml")
    private val logFile = File(plugin.dataFolder, "openevent.log")
    private lateinit var data: YamlConfiguration

    private var enabled = true
    private var endDate: LocalDate? = null
    private var loginSouls = 10L
    private var loginDC = 1L
    private var windowHours = 72
    private var minPlayMinutes = 10
    private var blockSameIp = true
    private var newcomerSouls = 100L
    private var newcomerDC = 3L
    private var inviterSouls = 100L
    private var inviterDC = 5L
    private var inviterMax = 10

    init {
        load()
    }

    fun load() {
        if (!configFile.exists()) {
            // jar 안에 openevent.yml이 없어도(IntelliJ 아티팩트로 빌드 등) 플러그인이 꺼지지 않게 기본값으로 만듦
            plugin.dataFolder.mkdirs()
            if (plugin.getResource("openevent.yml") != null) plugin.saveResource("openevent.yml", false)
            else YamlConfiguration().apply {
                set("enabled", true); set("end-date", "")
                set("login.souls", 10); set("login.dc", 1)
                set("invite.window-hours", 72); set("invite.min-playtime-minutes", 10); set("invite.block-same-ip", true)
                set("invite.newcomer-souls", 100); set("invite.newcomer-dc", 3)
                set("invite.inviter-souls", 100); set("invite.inviter-dc", 5); set("invite.inviter-max", 10)
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        enabled = c.getBoolean("enabled", true)
        endDate = c.getString("end-date")?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        loginSouls = c.getLong("login.souls", 10).coerceAtLeast(0)
        loginDC = c.getLong("login.dc", 1).coerceAtLeast(0)
        windowHours = c.getInt("invite.window-hours", 72).coerceAtLeast(1)
        minPlayMinutes = c.getInt("invite.min-playtime-minutes", 10).coerceAtLeast(0)
        blockSameIp = c.getBoolean("invite.block-same-ip", true)
        newcomerSouls = c.getLong("invite.newcomer-souls", 100).coerceAtLeast(0)
        newcomerDC = c.getLong("invite.newcomer-dc", 3).coerceAtLeast(0)
        inviterSouls = c.getLong("invite.inviter-souls", 100).coerceAtLeast(0)
        inviterDC = c.getLong("invite.inviter-dc", 5).coerceAtLeast(0)
        inviterMax = c.getInt("invite.inviter-max", 10).coerceAtLeast(1)
        data = YamlConfiguration.loadConfiguration(dataFile)
    }

    private fun today(): LocalDate = ZonedDateTime.now(zone).toLocalDate()

    private fun isOpen(): Boolean = enabled && (endDate?.let { !today().isAfter(it) } ?: true)

    private fun saveData() {
        runCatching { data.save(dataFile) }.onFailure { plugin.logger.severe("[이벤트] openevent-data.yml 저장 실패: ${it.message}") }
    }

    private fun log(text: String) {
        val time = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        runCatching { logFile.appendText("$time|$text\n") }
    }

    private fun give(target: org.bukkit.OfflinePlayer, souls: Long, dc: Long, reason: String) {
        if (souls > 0) plugin.soulManager.addSouls(target, souls)
        if (dc > 0) plugin.dcManager.addDC(target, dc)
        log("$reason|${target.uniqueId}|${target.name}|souls=$souls|dc=$dc")
    }

    // ── 1) 매일 접속 보상 ──
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        // 같은 컴퓨터의 두 번째 계정이 아닌지 확인할 수 있게 마지막 접속 주소를 기록
        p.address?.address?.hostAddress?.let { data.set("ip.${p.uniqueId}", it) }

        if (!isOpen()) { saveData(); return }
        val key = "last-login.${p.uniqueId}"
        val todayText = today().toString()
        if (data.getString(key) == todayText) { saveData(); return }
        data.set(key, todayText)
        saveData()

        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline) return@Runnable
            give(p, loginSouls, loginDC, "접속보상")
            p.sendMessage("§6[이벤트] §f오늘의 접속 보상! §b영혼 +$loginSouls" + if (loginDC > 0) " §e/ DC +$loginDC" else "")
        }, 60L)
    }

    // ── 2) 친구 추천 ──
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name) {
            "이벤트" -> { showInfo(sender); return true }
            "추천" -> {
                val player = sender as? Player ?: return sender.sendMessage("§c플레이어만 사용할 수 있습니다.").let { true }
                invite(player, args.getOrNull(0))
                return true
            }
        }
        return false
    }

    private fun showInfo(sender: CommandSender) {
        sender.sendMessage("§6§l[ 오픈 이벤트 ]")
        if (!isOpen()) { sender.sendMessage("§7지금은 진행 중인 이벤트가 없어요."); return }
        endDate?.let { sender.sendMessage("§7기간: §f${it}까지") }
        sender.sendMessage("§e1. 매일 접속 보상 §7- 하루 한 번 §b영혼 $loginSouls" + if (loginDC > 0) " §7+ §eDC $loginDC" else "")
        sender.sendMessage("§e2. 친구 추천 §7- 새로 온 사람이 §f/추천 <초대한 사람 닉네임> §7을 입력하면")
        sender.sendMessage("   §7본인: §b영혼 $newcomerSouls §e+ DC $newcomerDC  §7/  초대한 사람: §b영혼 $inviterSouls §e+ DC $inviterDC")
        sender.sendMessage("   §8(처음 접속 후 ${windowHours}시간 안에, ${minPlayMinutes}분 이상 플레이한 뒤 한 번만 가능 / 한 사람당 최대 ${inviterMax}명)")
    }

    private fun invite(player: Player, targetName: String?) {
        if (!isOpen()) return player.sendMessage("§c지금은 진행 중인 이벤트가 없어요.")
        if (targetName == null) return player.sendMessage("§e사용법: /추천 <초대한 사람 닉네임>  §7(자세한 내용: /이벤트)")

        if (data.contains("invited.${player.uniqueId}")) return player.sendMessage("§c이미 추천 보상을 받았어요.")

        val hoursSinceFirst = (System.currentTimeMillis() - player.firstPlayed) / 3_600_000.0
        if (player.firstPlayed <= 0 || hoursSinceFirst > windowHours)
            return player.sendMessage("§c처음 접속한 지 ${windowHours}시간이 지나서 추천 보상을 받을 수 없어요.")

        val playedMinutes = player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 / 60
        if (playedMinutes < minPlayMinutes)
            return player.sendMessage("§c서버를 ${minPlayMinutes}분 이상 플레이한 뒤에 입력할 수 있어요. §7(지금 ${playedMinutes}분)")

        val target = Bukkit.getPlayerExact(targetName) ?: Bukkit.getOfflinePlayerIfCached(targetName)
        if (target == null || (!target.isOnline && !target.hasPlayedBefore()))
            return player.sendMessage("§c'$targetName' 님은 서버에 접속한 적이 없어요. 닉네임을 정확히 입력해 주세요.")
        if (target.uniqueId == player.uniqueId) return player.sendMessage("§c자기 자신은 추천할 수 없어요.")

        val count = data.getInt("inviter-count.${target.uniqueId}", 0)
        if (count >= inviterMax) return player.sendMessage("§c그 사람은 추천 보상을 최대(${inviterMax}명)까지 받았어요.")

        if (blockSameIp) {
            val myIp = player.address?.address?.hostAddress
            val theirIp = data.getString("ip.${target.uniqueId}")
            if (myIp != null && myIp == theirIp)
                return player.sendMessage("§c같은 네트워크(같은 집·같은 컴퓨터)에서는 추천할 수 없어요.")
        }

        data.set("invited.${player.uniqueId}", target.uniqueId.toString())
        data.set("inviter-count.${target.uniqueId}", count + 1)
        saveData()

        give(player, newcomerSouls, newcomerDC, "추천받음(${target.name})")
        give(target, inviterSouls, inviterDC, "추천함(${player.name})")

        player.sendMessage("§6[이벤트] §f추천 보상! §b영혼 +$newcomerSouls" + if (newcomerDC > 0) " §e/ DC +$newcomerDC" else "")
        (target as? Player)?.sendMessage("§6[이벤트] §f${player.name} 님이 당신을 추천했어요! §b영혼 +$inviterSouls" + if (inviterDC > 0) " §e/ DC +$inviterDC" else "")
        Bukkit.broadcastMessage("§6[이벤트] §f${player.name} 님이 §e${target.name} §f님의 초대로 디그리스에 오셨어요! §7(/이벤트)")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (command.name == "추천" && args.size == 1)
            Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], ignoreCase = true) && it != sender.name }
        else emptyList()
}
