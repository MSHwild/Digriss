package kr.maeshil.digriss.skill

import org.bukkit.Color
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class InSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_6"
    override val manaCost = 30.0

    override fun execute(player: Player) {
        val maxDistance = 15.0
        val result = player.rayTraceBlocks(maxDistance)
        val center = result?.hitPosition?.toLocation(player.world)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(maxDistance))

        val radius = 6.0
        val durationTicks = 60L

        player.world.playSound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.6f)

        object : BukkitRunnable() {
            var ticks = 0L
            override fun run() {
                if (ticks >= durationTicks) {
                    center.world!!.spawnParticle(Particle.EXPLOSION, center, 1)
                    cancel()
                    return
                }

                center.world!!.spawnParticle(Particle.DUST, center, 15, 0.3, 0.3, 0.3, 0.0,
                    Particle.DustOptions(Color.fromRGB(30, 80, 255), 1.5f))
                center.world!!.spawnParticle(Particle.END_ROD, center, 3, 0.1, 0.1, 0.1, 0.0)

                center.world!!.getNearbyEntities(center, radius, radius, radius)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != player }
                    .forEach { entity ->
                        val pull = center.toVector().subtract(entity.location.toVector()).normalize().multiply(0.35)
                        entity.velocity = entity.velocity.add(pull).setY(pull.y.coerceAtLeast(0.05))
                    }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
    }
}