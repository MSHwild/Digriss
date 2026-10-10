package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class MuhanjeongcheSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_4"
    override val manaCost = 60.0

    private val radius = 3.6
    private val durationTicks = 100L

    override fun execute(player: Player): Boolean {
        val center = player.location.clone() // 발밑부터 전개
        val world = center.world!!
        val barrier = SphereUtil.placeSphereShell(center, radius, world)

        SkillEffects.stasisOpen(center, radius)
        player.sendMessage("§f무한정체 §7- 결계 안의 모든 움직임이 봉인됩니다.")

        val frozen = HashMap<LivingEntity, Location>()
        world.getNearbyEntities(center, radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && !Friendly.isAlly(player, it) && it.location.distance(center) <= radius }
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
                    SkillEffects.barrierShatter(center, radius, SkillEffects.Palette.STASIS_GOLD, SkillEffects.Palette.STASIS_DEEP)
                    cancel()
                    return
                }

                SkillEffects.stasisTick(center, radius, elapsed.toInt())
                frozen.forEach { (entity, loc) ->
                    if (!entity.isDead) {
                        entity.teleport(Location(loc.world, loc.x, loc.y, loc.z, entity.location.yaw, entity.location.pitch))
                        entity.velocity = entity.velocity.zero()
                        entity.fallDistance = 0f

                        SkillEffects.stasisFrozen(entity.location, elapsed.toInt())
                    }
                }

                elapsed += 1
            }
        }.runTaskTimer(plugin, 0L, 1L)

        barrier.task = task
        return true
    }
}