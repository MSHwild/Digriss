package kr.maeshil.digriss.job

import kr.maeshil.digriss.manager.JobSkillManager
import kr.maeshil.digriss.Digriss
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import kr.maeshil.digriss.jobManager.ReaperChargeManager

class JobTriggerListener(
    private val plugin: Digriss,
    private val jobManager: JobManager,
    private val skillManager: JobSkillManager
) : Listener {

    // Shift+Q 감지 (일반 Q 드랍은 그대로 동작)
    @EventHandler
    fun onDrop(e: PlayerDropItemEvent) {
        val player = e.player
        if (!player.isSneaking) return

        val job = jobManager.getJob(player.uniqueId) ?: return
        val skill = JobSkillRegistry.get(job) ?: return

        e.isCancelled = true // 아이템 드랍 취소, 스킬로 대체

        if (!skillManager.isReady(player.uniqueId)) {
            player.sendMessage("§c스킬 쿨타임: ${skillManager.getRemaining(player.uniqueId)}초")
            return
        }

        skill.execute(player)
        skillManager.startCooldown(player.uniqueId, skill.baseCooldownSeconds)
    }

    // 킬 시 쿨타임 15초 감소 + 사신이면 영혼 구슬 생성
    @EventHandler
    fun onKill(e: PlayerDeathEvent) {
        val killer = e.entity.killer as? Player ?: return
        skillManager.reduceOnKill(killer.uniqueId)

        val job = jobManager.getJob(killer.uniqueId) ?: return
        if (job == JobType.REAPER) {
            ReaperChargeManager.spawnOrb(plugin, killer, e.entity.location)
        }
    }
}