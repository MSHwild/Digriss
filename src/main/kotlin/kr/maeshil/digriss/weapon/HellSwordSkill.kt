package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.entity.LivingEntity
import org.bukkit.util.Vector

class HellSwordSkill : WeaponSkill {
    override val itemId = "weapon:hell_sword"
    override val cooldownSeconds = 12L

    private val range = 5.0
    private val angleDegrees = 90.0 // 부채꼴 각도
    private val damage = 8.0
    private val fireSeconds = 3

    override fun execute(player: Player): Boolean {
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction.setY(0).normalize()

        SkillEffects.hellfireCleave(eyeLoc, direction, range, angleDegrees)

        // 부채꼴 범위 내 적 탐색
        val nearby = player.world.getNearbyLivingEntities(player.location, range)
        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (!isInFront(player.location, direction, entity.location, angleDegrees)) continue

            entity.damage(damage, player)
            entity.fireTicks = fireSeconds * 20
            SkillEffects.hellfireHit(entity.location)
        }
        return true
    }

    private fun isInFront(origin: Location, direction: Vector, targetLoc: Location, maxAngle: Double): Boolean {
        val toTarget = targetLoc.toVector().subtract(origin.toVector()).setY(0)
        if (toTarget.lengthSquared() == 0.0) return false
        toTarget.normalize()
        val angle = Math.toDegrees(direction.angle(toTarget).toDouble())
        return angle <= maxAngle / 2
    }
}
