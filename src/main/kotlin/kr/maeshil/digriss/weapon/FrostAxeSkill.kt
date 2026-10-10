package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.Vector

class FrostAxeSkill : WeaponSkill {
    override val itemId = "weapon:frost_axe"
    override val cooldownSeconds = 11L

    private val range = 5.0
    private val angleDegrees = 70.0
    private val damage = 4.0
    private val slowDurationTicks = 60 // 3초
    private val slowAmplifier = 2 // 이동속도 감소 3단계

    override fun execute(player: Player): Boolean {
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction.setY(0).normalize()

        SkillEffects.frostCrescent(eyeLoc, direction, range, angleDegrees)

        val nearby = player.world.getNearbyLivingEntities(player.location, range)
        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (!isInFront(player.location, direction, entity.location, angleDegrees)) continue

            entity.damage(damage, player)
            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowDurationTicks, slowAmplifier))

            SkillEffects.frostHit(entity.location, { entity.takeIf { it.isValid && !it.isDead }?.location }, slowDurationTicks)
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
