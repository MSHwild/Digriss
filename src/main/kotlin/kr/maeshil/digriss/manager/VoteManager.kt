package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * 서버 목록 사이트(마인리스트 등) 투표 보상 (vote.yml)
 * NuVotifier 플러그인이 사이트에서 투표 신호를 받으면 VotifierEvent가 오고, 여기서 보상을 줌.
 * NuVotifier를 직접 의존하지 않도록 이벤트 클래스는 이름으로 찾아서 등록 (없으면 보상만 꺼짐).
 * 투표한 사람이 접속 중이 아니면 기록해 두었다가 다음 접속 때 지급.
 */
class VoteManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val zone = ZoneId.of("Asia/Seoul")
    private val configFile = File(plugin.dataFolder, "vote.yml")
    private val dataFile = File(plugin.dataFolder, "vote-data.yml")
    private val logFile = File(plugin.dataFolder, "vote.log")
    private var data = YamlConfiguration()

    private var enabled = true
    private var voteUrl = ""
    private var services: List<String> = emptyList()
    private var souls = 0L
    private var dc = 0L
    private var money = 0.0
    private var commands: List<String> = emptyList()
    private var broadcast = true
    private var hooked = false

    init {
        load()
        hookVotifier()
    }

    fun load() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            if (plugin.getResource("vote.yml") != null) plugin.saveResource("vote.yml", false)
            else YamlConfiguration().apply {
                // jar 안에 vote.yml이 없을 때(IntelliJ 아티팩트 빌드 등) 최소한의 기본값
                set("enabled", true); set("vote-url", ""); set("services", emptyList<String>())
                set("rewards.souls", 30); set("rewards.dc", 2); set("rewards.money", 0)
                set("rewards.commands", emptyList<String>()); set("broadcast", true)
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        enabled = c.getBoolean("enabled", true)
        voteUrl = c.getString("vote-url", "") ?: ""
        services = c.getStringList("services").map { it.trim() }.filter { it.isNotEmpty() }
        souls = c.getLong("rewards.souls", 0).coerceAtLeast(0)
        dc = c.getLong("rewards.dc", 0).coerceAtLeast(0)
        money = c.getDouble("rewards.money", 0.0).coerceAtLeast(0.0)
        commands = c.getStringList("rewards.commands")
        broadcast = c.getBoolean("broadcast", true)
        data = YamlConfiguration.loadConfiguration(dataFile)
    }

    private fun saveData() {
        runCatching { data.save(dataFile) }.onFailure { plugin.logger.severe("[투표] vote-data.yml 저장 실패: ${it.message}") }
    }

    private fun log(text: String) {
        val time = ZonedDateTime.now(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        runCatching { logFile.appendText("$time|$text\n") }
    }

    // NuVotifier의 VotifierEvent를 이름으로 찾아 등록 (컴파일 때 NuVotifier jar가 필요 없음)
    private fun hookVotifier() {
        val cls = runCatching {
            Class.forName("com.vexsoftware.votifier.model.VotifierEvent", true, plugin.javaClass.classLoader)
        }.getOrNull()
        if (cls == null || !Event::class.java.isAssignableFrom(cls)) {
            plugin.logger.info("[투표] NuVotifier가 없어 투표 보상을 받을 수 없습니다. (/투표 안내는 그대로 동작)")
            return
        }
        @Suppress("UNCHECKED_CAST")
        Bukkit.getPluginManager().registerEvent(cls as Class<out Event>, this, EventPriority.NORMAL, { _, event ->
            if (!cls.isInstance(event)) return@registerEvent
            runCatching {
                val vote = cls.getMethod("getVote").invoke(event)
                val name = vote.javaClass.getMethod("getUsername").invoke(vote) as? String
                val service = vote.javaClass.getMethod("getServiceName").invoke(vote) as? String ?: ""
                if (!name.isNullOrBlank()) {
                    // 혹시 비동기로 오면 메인 스레드에서 처리
                    if (Bukkit.isPrimaryThread()) onVote(name.trim(), service)
                    else Bukkit.getScheduler().runTask(plugin, Runnable { onVote(name.trim(), service) })
                }
            }.onFailure { plugin.logger.warning("[투표] 투표 정보를 읽지 못했습니다: ${it.message}") }
        }, plugin)
        hooked = true
        plugin.logger.info("[투표] NuVotifier 연결 완료")
    }

    /** 사이트에서 투표가 들어옴 */
    fun onVote(name: String, service: String) {
        if (!enabled) return
        if (services.isNotEmpty() && services.none { it.equals(service, ignoreCase = true) }) {
            log("ignored|$name|$service")
            return
        }
        log("vote|$name|$service")
        if (broadcast) Bukkit.broadcastMessage("§b[투표] §f${name}님이 서버에 투표해 주셨습니다! §7(/투표 로 보상 받기)")

        val online = Bukkit.getPlayerExact(name)
        if (online != null) {
            reward(online)
        } else {
            // 접속 중이 아니면 다음 접속 때 지급
            val key = "pending.${name.lowercase()}"
            data.set(key, data.getInt(key, 0) + 1)
            saveData()
        }
    }

    private fun reward(p: Player) {
        if (souls > 0) plugin.soulManager.addSouls(p, souls)
        if (dc > 0) plugin.dcManager.addDC(p, dc)
        if (money > 0) Bukkit.getServicesManager().getRegistration(net.milkbowl.vault.economy.Economy::class.java)
            ?.provider?.depositPlayer(p, money)
        commands.forEach { Bukkit.dispatchCommand(Bukkit.getConsoleSender(), it.replace("{player}", p.name)) }

        val totalKey = "total.${p.uniqueId}"
        data.set(totalKey, data.getInt(totalKey, 0) + 1)
        saveData()
        log("reward|${p.uniqueId}|${p.name}|souls=$souls|dc=$dc|money=$money")

        val parts = listOfNotNull(
            if (souls > 0) "영혼 $souls" else null,
            if (dc > 0) "DC $dc" else null,
            if (money > 0) "${money.toLong()}원" else null
        )
        p.sendMessage("§b[투표] §f투표해 주셔서 감사합니다! §e${parts.joinToString(", ")}§f 지급 §7(누적 ${data.getInt(totalKey)}회)")
        Sounds.success(p)
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        val key = "pending.${p.name.lowercase()}"
        val count = data.getInt(key, 0)
        if (count <= 0) return
        data.set(key, null)
        saveData()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline) return@Runnable
            repeat(count) { reward(p) }
        }, 60L)
    }

    // /투표, /투표 테스트 <닉네임> (관리자)
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.getOrNull(0) == "테스트" && sender.hasPermission("digriss.admin")) {
            val name = args.getOrNull(1) ?: return sender.sendMessage("§c/투표 테스트 <닉네임>").let { true }
            onVote(name, "test")
            sender.sendMessage("§a$name 이름으로 테스트 투표를 넣었습니다.")
            return true
        }

        sender.sendMessage("§b§l[ 서버 투표 ]")
        if (voteUrl.isNotBlank()) sender.sendMessage("§f아래 주소에서 하루 한 번 투표할 수 있어요: §e$voteUrl")
        else sender.sendMessage("§7투표 주소가 아직 준비되지 않았습니다.")
        val parts = listOfNotNull(
            if (souls > 0) "영혼 $souls" else null,
            if (dc > 0) "DC $dc" else null,
            if (money > 0) "${money.toLong()}원" else null
        )
        if (parts.isNotEmpty()) sender.sendMessage("§7투표 보상: §f${parts.joinToString(", ")} §7(접속 중이 아니면 다음 접속 때 지급)")
        if (sender is Player) sender.sendMessage("§7내 누적 투표: §f${data.getInt("total.${sender.uniqueId}", 0)}회")
        if (sender.hasPermission("digriss.admin")) {
            sender.sendMessage("§8관리자: NuVotifier ${if (hooked) "§a연결됨" else "§c없음"}§8 · /투표 테스트 <닉네임>")
        }
        return true
    }
}
