package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class SasaengyeolmokSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_1"
    override val manaCost = 80.0

    private val radius = 6.0
    private val durationTicks = 200L
    private val tickInterval = 10L

    override fun execute(player: Player): Boolean {
        val center = player.location.clone() // 발밑부터 전개
        val world = center.world!!
        val barrier = SphereUtil.placeSphereShell(center, radius, world)

        SkillEffects.deathPactOpen(center, radius)
        player.sendMessage("§5사생결목 §f- 결계가 펼쳐졌습니다.")

        var elapsed = 0L
        val task = object : BukkitRunnable() {
            override fun run() {
                if (barrier.destroyed) {
                    cancel()
                    return
                }
                if (elapsed >= durationTicks) {
                    SphereUtil.destroyBarrier(barrier)
                    SkillEffects.barrierShatter(center, radius, SkillEffects.Palette.PACT, SkillEffects.Palette.PACT_DEEP)
                    cancel()
                    return
                }

                world.getNearbyEntities(center, radius, radius, radius)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != player && !Friendly.isAlly(player, it) && it.location.distance(center) <= radius }
                    .forEach {
                        it.noDamageTicks = 0
                        it.damage(1.0, player)
                        SkillEffects.deathPactDrain(it.location)
                    }

                SkillEffects.deathPactPulse(center, radius, (elapsed / tickInterval).toInt())

                elapsed += tickInterval
            }
        }.runTaskTimer(plugin, tickInterval, tickInterval)

        barrier.task = task
        return true
    }
}