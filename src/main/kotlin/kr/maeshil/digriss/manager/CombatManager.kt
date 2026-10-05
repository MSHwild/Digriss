package kr.maeshil.digriss.manager

import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTeleportEvent
import java.io.File
import java.util.UUID

// 전투 중 도주 방지 (combat.yml)
// - 플레이어끼리 피해를 주고받으면 둘 다 N초간 "전투 중"
// - 전투 중에 접속을 끊으면 그 자리에서 사망 (아이템은 떨어짐)
// - 전투 중에는 tpa·home·spawn 같은 이동 명령어와 국가 스폰 이동이 막힘
// digriss.combat.bypass 권한이 있으면 전투 표시 자체가 안 붙음 (테스트용)
class CombatManager(private val plugin: Digriss) : Listener {

    private val tagged = HashMap<UUID, Long>() // 전투가 끝나는 시각(ms)

    private var enabled = true
    private var seconds = 7
    private var killOnLogout = true
    private var punishReasons = setOf("DISCONNECTED", "TIMED_OUT")
    private var blockedCommands = setOf<String>()

    private val defaultBlocked = listOf(
        "tpa", "tpahere", "tpaccept", "tpyes", "tpask", "call", "tpr",
        "tp", "etp", "tphere", "etphere", "tpo", "tppos", "tpall",
        "home", "ehome", "spawn", "espawn", "warp", "ewarp", "back", "eback", "dback",
        "rtp", "wild", "top", "jump"
    )

    fun start() {
        load()
        if (!enabled) return
        Bukkit.getPluginManager().registerEvents(this, plugin)
        ActionBarManager.addProvider { p ->
            if (isInCombat(p)) "§c⚔ 전투 중 ${remainingSeconds(p)}초 §7(도주하면 사망)" else null
        }
        // 1초마다 전투가 끝난 사람 정리
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val now = System.currentTimeMillis()
            val it = tagged.entries.iterator()
            while (it.hasNext()) {
                val (uuid, end) = it.next()
                if (end > now) continue
                it.remove()
                Bukkit.getPlayer(uuid)?.let { p ->
                    p.sendMessage("§a⚔ 전투가 끝났습니다. 이제 안전합니다.")
                    Sounds.success(p)
                }
            }
        }, 20L, 20L)
    }

    // /디그리스 리로드 에서 호출. 전투 기능을 켜거나 끄는 것(enabled)은 재시작해야 반영됨
    fun reload() = load()

    private fun load() {
        val file = File(plugin.dataFolder, "combat.yml")
        if (!file.exists()) {
            if (plugin.dataFolder.exists().not()) plugin.dataFolder.mkdirs()
            // jar에 combat.yml이 없어도 꺼지지 않게 기본값으로 만듦
            if (plugin.getResource("combat.yml") != null) plugin.saveResource("combat.yml", false)
            else YamlConfiguration().apply {
                set("enabled", true); set("seconds", 7); set("kill-on-logout", true)
                set("punish-reasons", listOf("DISCONNECTED", "TIMED_OUT"))
                set("blocked-commands", defaultBlocked)
                save(file)
            }
        }
        val config = YamlConfiguration.loadConfiguration(file)
        enabled = config.getBoolean("enabled", true)
        seconds = config.getInt("seconds", 7).coerceIn(3, 120)
        killOnLogout = config.getBoolean("kill-on-logout", true)
        punishReasons = config.getStringList("punish-reasons").map { it.uppercase() }.toSet()
            .ifEmpty { setOf("DISCONNECTED", "TIMED_OUT") }
        blockedCommands = config.getStringList("blocked-commands").map { it.lowercase().removePrefix("/") }.toSet()
            .ifEmpty { defaultBlocked.toSet() }
    }

    private fun bypass(p: Player) = p.hasPermission("digriss.combat.bypass")

    fun isInCombat(p: Player): Boolean {
        if (!enabled || bypass(p)) return false
        return (tagged[p.uniqueId] ?: return false) > System.currentTimeMillis()
    }

    fun remainingSeconds(p: Player): Long {
        val end = tagged[p.uniqueId] ?: return 0
        return ((end - System.currentTimeMillis() + 999) / 1000).coerceAtLeast(0)
    }

    private fun tag(p: Player, enemy: Player) {
        if (bypass(p)) return
        val wasTagged = isInCombat(p)
        tagged[p.uniqueId] = System.currentTimeMillis() + seconds * 1000L
        if (!wasTagged) {
            p.sendMessage("§c⚔ ${enemy.name}님과 전투가 시작됐습니다! ${seconds}초 동안 접속을 끊거나 이동 명령어를 쓰면 안 됩니다.")
            Sounds.alert(p)
        }
    }

    // 플레이어끼리 실제로 피해가 들어갔을 때 (화살·삼지창 등 투사체 포함)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDamage(e: EntityDamageByEntityEvent) {
        if (!enabled || e.finalDamage <= 0.0) return
        val victim = e.entity as? Player ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if (attacker == victim) return
        tag(victim, attacker)
        tag(attacker, victim)
    }

    // 전투 중 접속 종료 → 사망
    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val p = e.player
        val inCombat = isInCombat(p)
        tagged.remove(p.uniqueId)
        if (!inCombat || !killOnLogout || Bukkit.isStopping()) return
        if (e.reason.name !in punishReasons) return

        Bukkit.broadcastMessage("§c⚔ [전투 도주] §6${p.name}§c님이 전투 중 접속을 끊어 사망했습니다!")
        p.health = 0.0
    }

    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        tagged.remove(e.entity.uniqueId)
    }

    // 전투 중 이동 명령어 차단
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(e: PlayerCommandPreprocessEvent) {
        val p = e.player
        if (!isInCombat(p)) return
        val label = e.message.trim().removePrefix("/").substringBefore(' ').lowercase().substringAfter(':')
        if (label !in blockedCommands) return
        e.isCancelled = true
        p.sendMessage("§c전투 중에는 사용할 수 없는 명령어입니다. §7(${remainingSeconds(p)}초 남음)")
        Sounds.fail(p)
    }

    // 명령어로 시작된 텔레포트 차단 (전투 시작 전에 걸어둔 이동 포함). 스킬·플러그인 이동은 막지 않음
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(e: PlayerTeleportEvent) {
        if (e.cause != PlayerTeleportEvent.TeleportCause.COMMAND) return
        if (!isInCombat(e.player)) return
        e.isCancelled = true
        e.player.sendMessage("§c전투 중에는 이동할 수 없습니다. §7(${remainingSeconds(e.player)}초 남음)")
    }
}
