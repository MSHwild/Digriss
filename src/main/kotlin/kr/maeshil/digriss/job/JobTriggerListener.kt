package kr.maeshil.digriss.job

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.manager.JobSkillManager
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerQuitEvent
import kr.maeshil.digriss.jobManager.AssassinStealthManager
import kr.maeshil.digriss.jobManager.ReaperChargeManager

class JobTriggerListener(
    private val plugin: Digriss,
    private val jobManager: JobManager,
    private val skillManager: JobSkillManager
) : Listener {

    // F(양손 바꾸기)로 직업 스킬 발동. 스킬 있는 직업이면 손 바꾸기는 막힘. Shift+F는 메뉴(MainMenu)
    @EventHandler(ignoreCancelled = true)
    fun onSwap(e: PlayerSwapHandItemsEvent) {
        if (e.player.isSneaking) return
        if (JobSkillRegistry.get(jobManager.getJob(e.player.uniqueId) ?: return) == null) return
        e.isCancelled = true
        trigger(e.player)
    }

    private fun trigger(player: Player) {
        val job = jobManager.getJob(player.uniqueId) ?: return
        val skill = JobSkillRegistry.get(job) ?: return

        if (!skillManager.isReady(player.uniqueId)) {
            ActionBarManager.showTemp(player, "§c스킬 쿨타임: ${skillManager.getRemaining(player.uniqueId)}초", 1.0)
            Sounds.cooldown(player)
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
