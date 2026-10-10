package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class CheokSkill : Skill {
    override val itemId = "weapon:skill_7"
    override val manaCost = 30.0

    override fun execute(player: Player): Boolean {
        val maxDistance = 20.0
        val result = player.rayTraceBlocks(maxDistance)
        val endLoc = result?.hitPosition?.toLocation(player.world)
            ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(maxDistance))

        val start = player.eyeLocation
        val dir = endLoc.toVector().subtract(start.toVector())
        val distance = dir.length()
        dir.normalize()

        SkillEffects.repelBeam(start, endLoc)

        var traveled = 0.0
        val step = 0.4
        val current = start.clone()
        val hit = HashSet<LivingEntity>()

        while (traveled < distance) {
            current.add(dir.clone().multiply(step))
            traveled += step

            current.world!!.getNearbyEntities(current, 0.6, 0.6, 0.6)
                .filterIsInstance<LivingEntity>()
                .filter { it != player && it !in hit && !Friendly.isAlly(player, it) }
                .forEach { target ->
                    hit.add(target)
                    target.damage(8.0, player)
                    SkillEffects.repelHit(target.location)
                    target.velocity = dir.clone().multiply(1.5).setY(0.35)
                }
        }
        return true
    }
}