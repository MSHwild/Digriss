package kr.maeshil.digriss.weapon

import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
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

    override fun execute(player: Player) {
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction.setY(0).normalize()

        player.world.playSound(player.location, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f)
        drawFrostCone(eyeLoc, direction)

        val nearby = player.world.getNearbyLivingEntities(player.location, range)
        for (entity in nearby) {
            if (entity == player) continue
            if (!isInFront(player.location, direction, entity.location, angleDegrees)) continue

            entity.damage(damage, player)
            entity.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, slowDurationTicks, slowAmplifier))

            entity.world.spawnParticle(
                Particle.SNOWFLAKE, entity.location.add(0.0, 1.0, 0.0), 15,
                0.3, 0.5, 0.3, 0.02
            )
        }
    }

    private fun isInFront(origin: Location, direction: Vector, targetLoc: Location, maxAngle: Double): Boolean {
        val toTarget = targetLoc.toVector().subtract(origin.toVector()).setY(0)
        if (toTarget.lengthSquared() == 0.0) return false
        toTarget.normalize()
        val angle = Math.toDegrees(direction.angle(toTarget).toDouble())
        return angle <= maxAngle / 2
    }

    private fun drawFrostCone(eyeLoc: Location, direction: Vector) {
        val world = eyeLoc.world ?: return
        val steps = 16
        for (i in 0..steps) {
            val angleOffset = Math.toRadians(-angleDegrees / 2 + (angleDegrees / steps) * i)
            val rotated = rotateY(direction, angleOffset)
            for (dist in 1..range.toInt()) {
                val point = eyeLoc.clone().add(rotated.clone().multiply(dist))
                world.spawnParticle(Particle.SNOWFLAKE, point, 1, 0.05, 0.05, 0.05, 0.0)
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