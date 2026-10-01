package kr.maeshil.digriss.manager

import org.bukkit.Bukkit
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

        // 객체 생성 시 마나 회복 루프 자동 시작
        startRegen()

        // 이미 접속 중인 플레이어가 있다면 BossBar 생성 (플러그인 리로드 대응)
        Bukkit.getOnlinePlayers().forEach { player ->
            updateBar(player, getMana(player))
        }
    }

    @EventHandler
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        val currentMana = getMana(player)
        updateBar(player, currentMana)
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val uuid = event.player.uniqueId
        removeBar(event.player)
        mana.remove(uuid) // 메모리 누수 방지: 데이터 제거
    }

    fun startRegen() {
        object : BukkitRunnable() {
            override fun run() {
                Bukkit.getOnlinePlayers().forEach { player ->
                    val current = getMana(player)
                    if (current < maxMana) {
                        setMana(player, (current + regenAmount).coerceAtMost(maxMana))
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
            val newBar = Bukkit.createBossBar("마나", BarColor.BLUE, BarStyle.SOLID)
            newBar.addPlayer(player)
            newBar
        }
        bar.progress = current.toDouble() / maxMana
        bar.setTitle("마나 $current / $maxMana")
    }

    fun removeBar(player: Player) {
        bars[player.uniqueId]?.removeAll()
        bars.remove(player.uniqueId)
    }

    // 플러그인 종료(onDisable) 시 호출하여 잔여 BossBar 정리
    fun cleanup() {
        bars.values.forEach { it.removeAll() }
        bars.clear()
        mana.clear()
    }
}