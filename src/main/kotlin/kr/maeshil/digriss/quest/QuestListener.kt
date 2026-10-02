package kr.maeshil.digriss.quest

import org.bukkit.entity.Enemy
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerJoinEvent

class QuestListener(private val questManager: QuestManager) : Listener {

    // 접속 시 오늘 퀘스트 갱신
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        questManager.ensureToday(e.player)
    }

    // 몬스터 처치 (스포너/시련의 스포너 몹 제외)
    @EventHandler(ignoreCancelled = true)
    fun onEntityDeath(e: EntityDeathEvent) {
        val entity = e.entity
        if (entity !is Enemy) return
        val killer = entity.killer ?: return
        val reason = entity.entitySpawnReason
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) return

        questManager.addProgress(killer, QuestType.MOB_KILL)
    }
}
