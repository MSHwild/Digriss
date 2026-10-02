package kr.maeshil.digriss.skill

import kr.maeshil.digriss.Friendly
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class HeukroeSkill : Skill {
    override val itemId = "weapon:skill_3"
    override val manaCost = 35.0

    override fun execute(player: Player): Boolean {
        val target = (player.getTargetEntity(10) as? LivingEntity)?.takeIf { !Friendly.isAlly(player, it) } ?: run {
            player.sendMessage("§c대상이 없습니다.")
            return false
        }

        val critDamage = 16.0
        target.world.strikeLightningEffect(target.location)
        target.damage(critDamage, player)

        target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 40, 250, false, false))
        target.addPotionEffect(PotionEffect(PotionEffectType.JUMP_BOOST, 40, 128, false, false))
        target.addPotionEffect(PotionEffect(PotionEffectType.WEAKNESS, 40, 10, false, false))

        target.world.spawnParticle(Particle.ELECTRIC_SPARK, target.location.add(0.0, 1.0, 0.0), 40, 0.4, 0.6, 0.4, 0.1)
        target.world.playSound(target.location, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 1.4f)
        player.sendMessage("§b흑뢰 §f- 치명타 ${critDamage.toInt()} 피해 + 기절")
        return true
    }
}