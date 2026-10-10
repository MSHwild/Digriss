package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class InSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_6"
    override val manaCost = 30.0

    override fun execute(player: Player): Boolean {
        val maxDistance = 15.0
        val result = player.rayTraceBlocks(maxDistance)
        val center = result?.hitPosition?.toLocation(player.world)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(maxDistance))

        val radius = 6.0
        val durationTicks = 60L

        SkillEffects.pullOpen(center, radius)

        object : BukkitRunnable() {
            var ticks = 0L
            override fun run() {
                if (ticks >= durationTicks) {
                    SkillEffects.pullCollapse(center)
                    cancel()
                    return
                }

                SkillEffects.pullTick(center, radius, ticks)

                center.world!!.getNearbyEntities(center, radius, radius, radius)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != player && !Friendly.isAlly(player, it) }
                    .forEach { entity ->
                        val toCenter = center.toVector().subtract(entity.location.toVector())
                        if (toCenter.lengthSquared() < 0.01) return@forEach // 중심에 딱 붙어 있으면 방향 계산 불가(NaN)
                        val pull = toCenter.normalize().multiply(0.35)
                        entity.velocity = entity.velocity.add(pull).setY(pull.y.coerceAtLeast(0.05))
                    }

                ticks += 2
            }
        }.runTaskTimer(plugin, 0L, 2L)
        return true
    }
}