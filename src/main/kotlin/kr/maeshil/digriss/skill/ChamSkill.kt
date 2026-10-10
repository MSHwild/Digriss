package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class ChamSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_5"
    override val manaCost = 25.0

    override fun execute(player: Player): Boolean {
        val dir = player.eyeLocation.direction.normalize()
        val current = player.eyeLocation.clone()
        val range = 10.0
        var traveled = 0.0
        val hit = HashSet<LivingEntity>()

        SkillEffects.chamLaunch(current)

        object : BukkitRunnable() {
            override fun run() {
                if (traveled >= range) {
                    cancel()
                    return
                }
                current.add(dir.clone().multiply(0.6))
                traveled += 0.6

                SkillEffects.chamStep(current, dir, traveled, range)

                current.world!!.getNearbyEntities(current, 1.0, 1.0, 1.0)
                    .filterIsInstance<LivingEntity>()
                    .filter { it != player && it !in hit && !Friendly.isAlly(player, it) }
                    .forEach {
                        hit.add(it)
                        it.damage(9.0, player)
                        SkillEffects.chamHit(it.location)
                    }
            }
        }.runTaskTimer(plugin, 0L, 1L)
        return true
    }
}