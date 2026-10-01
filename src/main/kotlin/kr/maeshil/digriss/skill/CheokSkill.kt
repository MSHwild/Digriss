package kr.maeshil.digriss.skill

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class CheokSkill : Skill {
    override val itemId = "weapon:skill_7"
    override val manaCost = 30.0

    override fun execute(player: Player) {
        val maxDistance = 20.0
        val result = player.rayTraceBlocks(maxDistance)
        val endLoc = result?.hitPosition?.toLocation(player.world)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(maxDistance))

        val start = player.eyeLocation
        val dir = endLoc.toVector().subtract(start.toVector())
        val distance = dir.length()
        dir.normalize()

        player.world.playSound(start, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1f, 0.7f)

        var traveled = 0.0
        val step = 0.4
        val current = start.clone()
        val hit = HashSet<LivingEntity>()

        while (traveled < distance) {
            current.add(dir.clone().multiply(step))
            traveled += step

            current.world!!.spawnParticle(Particle.DUST, current, 4, 0.05, 0.05, 0.05, 0.0,
                Particle.DustOptions(Color.fromRGB(255, 20, 20), 1.3f))

            current.world!!.getNearbyEntities(current, 0.6, 0.6, 0.6)
                .filterIsInstance<LivingEntity>()
                .filter { it != player && it !in hit }
                .forEach { target ->
                    hit.add(target)
                    target.damage(8.0, player)
                    target.velocity = dir.clone().multiply(1.5).setY(0.35)
                }
        }

        endLoc.world!!.spawnParticle(Particle.DUST, endLoc, 30, 0.3, 0.3, 0.3, 0.0,
            Particle.DustOptions(Color.fromRGB(255, 0, 0), 1.6f))
    }
}