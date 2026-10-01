package kr.maeshil.digriss.skill

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class MuhanjeongcheSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_4"
    override val manaCost = 60.0

    private val radius = 3.6
    private val durationTicks = 100L

    override fun execute(player: Player) {
        val center = player.location.clone() // 발밑부터 전개
        val world = center.world!!
        val barrier = SphereUtil.placeSphereShell(center, radius, world)

        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.8f)
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, center, 100, radius, radius, radius, 0.02)
        player.sendMessage("§f무한정체 §7- 결계 안의 모든 움직임이 봉인됩니다.")

        val frozen = HashMap<LivingEntity, Location>()
        world.getNearbyEntities(center, radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && it.location.distance(center) <= radius }
            .forEach { frozen[it] = it.location.clone() }

        var elapsed = 0L
        val task = object : BukkitRunnable() {
            override fun run() {
                if (barrier.destroyed) {
                    cancel()
                    return
                }
                if (elapsed >= durationTicks) {
                    SphereUtil.destroyBarrier(barrier)
                    world.playSound(center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.7f)
                    world.spawnParticle(Particle.SMOKE, center, 70, radius, radius, radius, 0.02)
                    cancel()
                    return
                }

                frozen.forEach { (entity, loc) ->
                    if (!entity.isDead) {
                        entity.teleport(Location(loc.world, loc.x, loc.y, loc.z, entity.location.yaw, entity.location.pitch))
                        entity.velocity = entity.velocity.zero()
                        entity.fallDistance = 0f

                        entity.world.spawnParticle(Particle.DUST, entity.location.add(0.0, 1.0, 0.0), 3,
                            0.2, 0.3, 0.2, 0.0, Particle.DustOptions(org.bukkit.Color.fromRGB(80, 0, 120), 1.0f))
                    }
                }

                elapsed += 1
            }
        }.runTaskTimer(plugin, 0L, 1L)

        barrier.task = task
    }
}