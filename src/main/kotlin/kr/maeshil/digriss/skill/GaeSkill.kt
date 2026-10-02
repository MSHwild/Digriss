package kr.maeshil.digriss.skill

import kr.maeshil.digriss.Friendly
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class GaeSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_2"
    override val manaCost = 20.0

    override fun execute(player: Player): Boolean {
        val dir = player.eyeLocation.direction.normalize()
        val current = player.eyeLocation.clone()
        val speed = 0.8
        val maxRange = 15.0
        var traveled = 0.0

        player.world.playSound(current, Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f)

        object : BukkitRunnable() {
            override fun run() {
                current.add(dir.clone().multiply(speed))
                traveled += speed

                current.world!!.spawnParticle(Particle.FLAME, current, 6, 0.1, 0.1, 0.1, 0.01)
                current.world!!.spawnParticle(Particle.SMOKE, current, 2, 0.05, 0.05, 0.05, 0.0)

                val blockHit = current.block.type.isSolid
                val entityHit = current.world!!.getNearbyEntities(current, 0.8, 0.8, 0.8)
                    .filterIsInstance<LivingEntity>()
                    .firstOrNull { it != player && !Friendly.isAlly(player, it) }

                if (blockHit || entityHit != null || traveled >= maxRange) {
                    explode(current, player)
                    cancel()
                }
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }

    // 시전자 본인과 아군은 제외, 피해 출처를 시전자로 지정해 처치 시 킬로 인정
    private fun explode(loc: Location, player: Player) {
        loc.world!!.spawnParticle(Particle.EXPLOSION, loc, 1)
        loc.world!!.spawnParticle(Particle.FLAME, loc, 60, 1.2, 1.2, 1.2, 0.05)
        loc.world!!.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.1f)

        loc.world!!.getNearbyEntities(loc, 2.5, 2.5, 2.5)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && !Friendly.isAlly(player, it) }
            .forEach {
                it.damage(6.0, player)
                it.fireTicks = 60
            }
    }
}