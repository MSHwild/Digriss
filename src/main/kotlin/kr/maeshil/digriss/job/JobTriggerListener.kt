package kr.maeshil.digriss.job

import kr.maeshil.digriss.manager.JobSkillManager
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerQuitEvent
import kr.maeshil.digriss.jobManager.AssassinStealthManager
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
            ActionBarManager.showTemp(player, "§c스킬 쿨타임: ${skillManager.getRemaining(player.uniqueId)}초", 1.0)
            return
        }

        if (skill.execute(player)) skillManager.startCooldown(player.uniqueId, skill.baseCooldownSeconds)
    }

    // 킬 시 쿨타임 15초 감소 + 사신이면 영혼 구슬 생성
    @EventHandler
    fun onKill(e: PlayerDeathEvent) {
        val killer = e.entity.killer as? Player ?: return
        if (killer == e.entity) return
        skillManager.reduceOnKill(killer.uniqueId)

        val job = jobManager.getJob(killer.uniqueId) ?: return
        if (job == JobType.REAPER) {
            ReaperChargeManager.spawnOrb(plugin, killer, e.entity.location)
        }
    }

    // 접속 종료 시 스킬 상태 정리 (사신 무체화 중 나가도 장비/무적 상태 복구)
    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        ReaperSkill.restore(e.player)
        AssassinStealthManager.consume(e.player.uniqueId)
        ActionBarManager.remove(e.player)
    }
}
