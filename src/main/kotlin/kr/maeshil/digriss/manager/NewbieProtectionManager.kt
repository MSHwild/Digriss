package kr.maeshil.digriss.manager

import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.Statistic
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerJoinEvent
import java.io.File
import java.util.UUID

/**
 * 초보 보호 (protection.yml)
 * 이 월드에서 플레이한 시간이 hours 미만이면 다른 플레이어에게 공격받지 않음.
 * 보호 중에 먼저 플레이어를 공격하거나 /보호해제 를 하면 보호가 끝남.
 * 보호 중에는 적 신호기 파괴, 자원 거점 점령을 할 수 없음 (무적 상태로 전쟁에 끼어드는 것 방지).
 * 플레이 시간은 월드에 저장되므로 시즌 초기화(월드 교체) 때 모두 다시 보호받음.
 */
class NewbieProtectionManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val configFile = File(plugin.dataFolder, "protection.yml")
    private val dataFile = File(plugin.dataFolder, "protection-data.yml")
    private var enabled = true
    private var hours = 3.0
    private val forfeited = HashSet<UUID>() // 스스로 해제했거나 먼저 공격해서 보호가 끝난 사람
    private var forfeitWorld = ""          // 기록이 어느 월드 기준인지 (월드가 바뀌면 기록 초기화)

    init {
        load()
    }

    fun load() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            YamlConfiguration().apply {
                options().setHeader(listOf(
                    "초보 보호: 이 월드에서 플레이한 시간이 hours 미만이면 다른 플레이어에게 공격받지 않습니다.",
                    "보호 중에 먼저 공격하거나 /보호해제 를 하면 보호가 끝납니다. 수정 후 /디그리스 리로드"
                ))
                set("enabled", true)
                set("hours", 3)
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        enabled = c.getBoolean("enabled", true)
        hours = c.getDouble("hours", 3.0).coerceAtLeast(0.0)

        val d = YamlConfiguration.loadConfiguration(dataFile)
        forfeitWorld = d.getString("world", "") ?: ""
        forfeited.clear()
        // 월드(시즌)가 바뀌었으면 해제 기록을 버림 → 모두 다시 보호받음
        if (forfeitWorld == currentWorldId()) {
            d.getStringList("forfeited").mapNotNullTo(forfeited) { runCatching { UUID.fromString(it) }.getOrNull() }
        } else {
            forfeitWorld = currentWorldId()
            save()
        }
    }

    // 기본 월드의 고유 ID (월드 파일을 새로 깔면 바뀜)
    private fun currentWorldId(): String = Bukkit.getWorlds().firstOrNull()?.uid?.toString() ?: ""

    private fun save() {
        val d = YamlConfiguration()
        d.set("world", forfeitWorld)
        d.set("forfeited", forfeited.map { it.toString() })
        runCatching { d.save(dataFile) }.onFailure { plugin.logger.severe("[초보 보호] protection-data.yml 저장 실패: ${it.message}") }
    }

    private fun playedMs(p: Player): Long = p.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L // 틱 → ms

    /** 남은 보호 시간(ms). 보호 중이 아니면 0 */
    fun remainingMs(p: Player): Long {
        if (!enabled || p.uniqueId in forfeited) return 0
        return ((hours * 3_600_000).toLong() - playedMs(p)).coerceAtLeast(0)
    }

    fun isProtected(p: Player): Boolean = remainingMs(p) > 0

    fun remainingText(p: Player): String {
        val m = (remainingMs(p) / 60_000).coerceAtLeast(1)
        return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
    }

    /** 스코어보드용 한 줄. 보호 중이 아니면 null */
    fun scoreboardLine(p: Player): String? = if (isProtected(p)) " §a초보 보호 ${remainingText(p)}" else null

    private fun forfeit(p: Player, reason: String) {
        if (!forfeited.add(p.uniqueId)) return
        save()
        p.sendMessage("§e[초보 보호] §f$reason 보호가 끝났습니다. 이제 다른 플레이어에게 공격받을 수 있어요.")
        Sounds.play(p, org.bukkit.Sound.BLOCK_BEACON_DEACTIVATE, 0.8f, 1.2f)
    }

    // 다른 보호(연합, 같은 국가 등) 판정보다 먼저 처리해서, 막힌 공격은 전투 상태도 안 붙음 (CombatManager는 MONITOR)
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onDamage(e: EntityDamageByEntityEvent) {
        if (!enabled) return
        val victim = e.entity as? Player ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if (attacker == victim) return

        if (isProtected(victim)) {
            e.isCancelled = true
            ActionBarManager.showTemp(attacker, "§a초보 보호 중인 플레이어입니다 §7(${remainingText(victim)} 남음)", 1.5)
            return
        }
        // 보호 중인 사람이 먼저 공격하면 보호 해제 (공격은 그대로 들어감)
        if (isProtected(attacker)) forfeit(attacker, "다른 플레이어를 먼저 공격해서")
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        if (!isProtected(p)) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || !isProtected(p)) return@Runnable
            p.sendMessage("§a[초보 보호] §f앞으로 §a${remainingText(p)}§f 동안 다른 플레이어에게 공격받지 않아요.")
            p.sendMessage("§7먼저 공격하거나 /보호해제 를 하면 보호가 끝나요. 보호 중에는 적 신호기와 자원 거점을 점령할 수 없어요.")
        }, 60L)
    }

    // /보호해제 확인
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val p = sender as? Player ?: return true
        if (!isProtected(p)) return p.sendMessage("§7지금은 초보 보호 중이 아닙니다.").let { true }
        if (args.getOrNull(0) != "확인") {
            p.sendMessage("§e초보 보호가 §a${remainingText(p)}§e 남았습니다. 정말 해제하려면 §f/보호해제 확인")
            return true
        }
        forfeit(p, "직접 해제해서")
        return true
    }
}
