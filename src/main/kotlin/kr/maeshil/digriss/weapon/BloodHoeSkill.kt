package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class BloodHoeSkill : WeaponSkill {
    override val itemId = "weapon:blood_hoe"
    override val cooldownSeconds = 14L

    private val radius = 4.0
    private val damage = 7.0
    private val lifestealRatio = 0.3 // 입힌 피해량의 30% 흡수

    override fun execute(player: Player): Boolean {
        player.world.playSound(player.location, Sound.ENTITY_WITHER_HURT, 1.0f, 1.4f)
        drawRadiusEffect(player)

        val nearby = player.world.getNearbyLivingEntities(player.location, radius)
        var totalDamageDealt = 0.0

        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue

            entity.damage(damage, player)
            totalDamageDealt += damage

            entity.world.spawnParticle(
                Particle.DUST,
                entity.location.add(0.0, 1.0, 0.0),
                15, 0.3, 0.5, 0.3, 0.0,
                Particle.DustOptions(org.bukkit.Color.RED, 1.2f)
            )
        }

        if (totalDamageDealt > 0) {
            val healAmount = totalDamageDealt * lifestealRatio
            val maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
            player.health = (player.health + healAmount).coerceAtMost(maxHealth)

            player.world.spawnParticle(
                Particle.DUST,
                player.location.add(0.0, 1.0, 0.0),
                40, radius / 2, 0.3, radius / 2, 0.0,
                Particle.DustOptions(org.bukkit.Color.RED, 1.5f)
            )
            player.world.playSound(player.location, Sound.ENTITY_WITCH_DRINK, 1f, 0.7f)
        }
        return true
    }

    private fun drawRadiusEffect(player: Player) {
        val loc = player.location
        val points = 36
        for (i in 0 until points) {
            val angle = 2 * Math.PI * i / points
            val x = loc.x + radius * Math.cos(angle)
            val z = loc.z + radius * Math.sin(angle)
            loc.world?.spawnParticle(
                Particle.DUST,
                x, loc.y + 0.1, z,
                1, 0.0, 0.0, 0.0, 0.0,
                Particle.DustOptions(org.bukkit.Color.RED, 1.0f)
            )
        }
    }
}

private fun org.bukkit.World.getNearbyLivingEntities(loc: org.bukkit.Location, radius: Double): List<LivingEntity> {
    return getNearbyEntities(loc, radius, radius, radius).filterIsInstance<LivingEntity>()
}