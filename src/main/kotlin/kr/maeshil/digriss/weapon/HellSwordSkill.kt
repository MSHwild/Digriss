package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
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

        // 이펙트 연출
        player.world.playSound(player.location, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.8f)
        drawFireArc(eyeLoc, direction)

        // 부채꼴 범위 내 적 탐색
        val nearby = player.world.getNearbyLivingEntities(player.location, range)
        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (!isInFront(player.location, direction, entity.location, angleDegrees)) continue

            entity.damage(damage, player)
            entity.fireTicks = fireSeconds * 20

            entity.world.spawnParticle(Particle.FLAME, entity.location.add(0.0, 1.0, 0.0), 20, 0.3, 0.5, 0.3, 0.02)
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

    private fun drawFireArc(eyeLoc: Location, direction: Vector) {
        val world = eyeLoc.world ?: return
        val steps = 20
        for (i in 0..steps) {
            val angleOffset = Math.toRadians(-angleDegrees / 2 + (angleDegrees / steps) * i)
            val rotated = rotateY(direction, angleOffset)
            for (dist in 1..range.toInt()) {
                val point = eyeLoc.clone().add(rotated.clone().multiply(dist))
                world.spawnParticle(Particle.FLAME, point, 1, 0.05, 0.05, 0.05, 0.0)
            }
        }
    }

    private fun rotateY(vector: Vector, angle: Double): Vector {
        val cos = Math.cos(angle)
        val sin = Math.sin(angle)
        val x = vector.x * cos - vector.z * sin
        val z = vector.x * sin + vector.z * cos
        return Vector(x, vector.y, z)
    }
}