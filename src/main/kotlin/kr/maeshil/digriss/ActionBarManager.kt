package kr.maeshil.digriss

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

// 액션바를 한 곳에서 관리해서 여러 기능이 서로 덮어쓰지 않게 함
// - 상시 표시(영토, 스킬 쿨타임 등)는 provider로 등록하면 " | "로 합쳐서 표시
// - 잠깐 띄울 알림(쿨타임 경고 등)은 showTemp로 보내면 지정 시간 동안 우선 표시
object ActionBarManager {

    private const val SEPARATOR = "  §8|  "

    private val providers = mutableListOf<(Player) -> String?>()
    private val temp = HashMap<UUID, Pair<String, Long>>() // 메시지, 만료 시각(ms)

    fun start(plugin: JavaPlugin) {
        providers.clear()
        temp.clear()
        // 액션바는 약 2초 뒤 사라지므로 0.5초마다 다시 보냄
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            Bukkit.getOnlinePlayers().forEach { render(it) }
        }, 10L, 10L)
    }

    fun addProvider(provider: (Player) -> String?) {
        providers.add(provider)
    }

    fun showTemp(player: Player, message: String, seconds: Double = 2.0) {
        temp[player.uniqueId] = message to System.currentTimeMillis() + (seconds * 1000).toLong()
        render(player)
    }

    fun remove(player: Player) {
        temp.remove(player.uniqueId)
    }

    private fun render(player: Player) {
        val t = temp[player.uniqueId]
        if (t != null) {
            if (t.second > System.currentTimeMillis()) {
                player.sendActionBar(t.first)
                return
            }
            temp.remove(player.uniqueId)
        }

        val parts = providers.mapNotNull { runCatching { it(player) }.getOrNull() }
        if (parts.isNotEmpty()) player.sendActionBar(parts.joinToString(SEPARATOR))
    }
}
