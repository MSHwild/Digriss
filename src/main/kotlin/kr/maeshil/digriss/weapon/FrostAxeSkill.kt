package kr.maeshil.digriss.weapon

import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class FrostAxeSkill : WeaponSkill {
    override val itemId = "weapon:frost_axe"
    override val cooldownSeconds = 11L

    override fun execute(player: Player) {
        val eyeLoc = player.eyeLocation
        val dir = eyeLoc.direction.normalize()
        val range = 6.0
        val coneAngleDeg = 45.0

        player.world.playSound(eyeLoc, Sound.BLOCK_GLASS_BREAK, 1f, 0.6f)

        loc@ for (target in player.world.getNearbyEntities(eyeLoc, range, range, range)
            .filterIsInstance<LivingEntity>()) {
            if (target == player) continue@loc
            val toTarget = target.location.toVector().subtract(eyeLoc.toVector())
            if (toTarget.length() > range) continue@loc
            val angle = Math.toDegrees(dir.angle(toTarget).toDouble())
            if (angle > coneAngleDeg / 2) continue@loc

            target.damage(7.0, player)
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, 100, 2))
            target.world.spawnParticle(Particle.SNOWFLAKE, target.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.0)
        }

        for (i in 1..12) {
            val point = eyeLoc.clone().add(dir.clone().multiply(i * (range / 12)))
            point.world!!.spawnParticle(Particle.SNOWFLAKE, point, 4, 0.4, 0.4, 0.4, 0.0)
        }
    }
}