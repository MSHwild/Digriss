package kr.maeshil.digriss.jobManager

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.job.JobManager
import kr.maeshil.digriss.job.JobType
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.potion.PotionEffectType

class AssassinListener(private val jobManager: JobManager) : Listener {

    private val ambushMultiplier = 1.5 // 기습 피해 배율

    @EventHandler
    fun onHit(e: EntityDamageByEntityEvent) {
        val attacker = e.damager as? Player ?: return
        if (jobManager.getJob(attacker.uniqueId) != JobType.SHADOW_ASSASSIN) return
        if (!AssassinStealthManager.isActive(attacker.uniqueId)) return

        AssassinStealthManager.consume(attacker.uniqueId)
        e.damage *= ambushMultiplier
        attacker.removePotionEffect(PotionEffectType.INVISIBILITY)
        Sounds.play(attacker, org.bukkit.Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.7f)
        attacker.sendMessage("§5기습 공격 성공! (피해 ${ambushMultiplier}배)")
    }
}