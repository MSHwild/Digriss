package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import java.util.UUID

class JobSkillManager(private val plugin: Digriss) {

    private val cooldowns = HashMap<UUID, Int>() // 남은 쿨타임(초)
    private val killReduction = 15

    init {
        // 1초마다 자연 감소
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            val iterator = cooldowns.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val next = entry.value - 1
                if (next <= 0) iterator.remove() else entry.setValue(next)
            }
        }, 20L, 20L)
    }

    fun getRemaining(uuid: UUID): Int = cooldowns[uuid] ?: 0

    fun isReady(uuid: UUID): Boolean = getRemaining(uuid) <= 0

    fun startCooldown(uuid: UUID, seconds: Int) {
        cooldowns[uuid] = seconds
    }

    fun resetCooldown(uuid: UUID) {
        cooldowns.remove(uuid)
    }

    fun reduceOnKill(uuid: UUID) {
        val current = cooldowns[uuid] ?: return
        cooldowns[uuid] = (current - killReduction).coerceAtLeast(0)
    }
}