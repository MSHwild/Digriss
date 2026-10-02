package kr.maeshil.digriss.manager

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import java.util.UUID

class ManaManager(private val plugin: JavaPlugin) : Listener {

    private val maxMana = 100
    private val regenAmount = 2
    private val regenIntervalTicks = 10L // 0.5초

    private val mana = mutableMapOf<UUID, Int>()
    private val bars = mutableMapOf<UUID, BossBar>()

    init {
        // 이벤트 리스너 등록
        Bukkit.getPluginManager().registerEvents(this, plugin)

        // 이전 실행에서 정리되지 못한 마나 보스바 제거 (리로드 시 중복 방지)
        Bukkit.getBossBars().asSequence()
            .filter { it.key.namespace == plugin.name.lowercase() && it.key.key.startsWith("mana_") }
            .map { it.key }
            .toList()
            .forEach { key -> Bukkit.getBossBar(key)?.removeAll(); Bukkit.removeBossBar(key) }

        // 객체 생성 시 마나 회복 루프 자동 시작
        startRegen()

        // 이미 접속 중인 플레이어가 있다면 BossBar 생성 (플러그인 리로드 대응)
        Bukkit.getOnlinePlayers().forEach { player ->
            updateBar(player, getMana(player))
        }
    }

    // 접속 처리 도중에 붙이면 클라이언트가 못 받는 경우가 있어 1초 뒤에 붙임 (회복 루프도 계속 확인함)
    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (player.isOnline) updateBar(player, getMana(player))
        }, 20L)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        removeBar(event.player)
        // 마나는 지우지 않음: 지우면 재접속 시 최대치로 차서 마나 회복 꼼수가 됨
    }

    fun startRegen() {
        object : BukkitRunnable() {
            override fun run() {
                Bukkit.getOnlinePlayers().forEach { player ->
                    val current = getMana(player)
                    if (current < maxMana) {
                        setMana(player, (current + regenAmount).coerceAtMost(maxMana))
                    } else {
                        // 마나가 가득 차 있어도 보스바가 빠져 있으면 다시 붙임 (접속 타이밍·월드 이동 등으로 빠지는 경우)
                        updateBar(player, current)
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, regenIntervalTicks)
    }

    fun getMana(player: Player): Int = mana[player.uniqueId] ?: maxMana

    fun setMana(player: Player, amount: Int) {
        val clamped = amount.coerceIn(0, maxMana)
        mana[player.uniqueId] = clamped
        updateBar(player, clamped)
    }

    fun hasEnoughMana(player: Player, cost: Int): Boolean = getMana(player) >= cost

    fun consumeMana(player: Player, cost: Int): Boolean {
        if (!hasEnoughMana(player, cost)) return false
        setMana(player, getMana(player) - cost)
        return true
    }

    fun hasEnoughMana(player: Player, cost: Double): Boolean = hasEnoughMana(player, cost.toInt())

    fun consumeMana(player: Player, cost: Double): Boolean = consumeMana(player, cost.toInt())

    private fun updateBar(player: Player, current: Int) {
        val bar = bars.getOrPut(player.uniqueId) {
            // 키가 있는 보스바라서 리로드 후에도 찾아서 지울 수 있음
            Bukkit.getBossBar(barKey(player.uniqueId))?.let { Bukkit.removeBossBar(barKey(player.uniqueId)) }
            Bukkit.createBossBar(barKey(player.uniqueId), "마나", BarColor.BLUE, BarStyle.SOLID)
        }
        if (!bar.isVisible) bar.isVisible = true
        if (player !in bar.players) bar.addPlayer(player)
        val progress = current.toDouble() / maxMana
        if (bar.progress != progress) bar.progress = progress
        val title = "마나 $current / $maxMana"
        if (bar.title != title) bar.setTitle(title)
    }

    private fun barKey(uuid: UUID) = NamespacedKey(plugin, "mana_$uuid")

    fun removeBar(player: Player) {
        bars.remove(player.uniqueId)?.removeAll()
        Bukkit.removeBossBar(barKey(player.uniqueId))
    }

    // 플러그인 종료(onDisable) 시 호출하여 잔여 BossBar 정리
    fun cleanup() {
        bars.keys.forEach { Bukkit.removeBossBar(barKey(it)) }
        bars.values.forEach { it.removeAll() }
        bars.clear()
        mana.clear()
    }
}