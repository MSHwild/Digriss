package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.Vector

class OceanSpearSkill : WeaponSkill {
    override val itemId = "weapon:ocean_spear"
    override val cooldownSeconds = 10L

    private val dashDistance = 6.0
    private val damage = 6.0
    private val hitRadius = 1.5

    override fun execute(player: Player): Boolean {
        val direction = player.location.direction.setY(0).normalize()

        player.world.playSound(player.location, Sound.ENTITY_DOLPHIN_JUMP, 1.2f, 1.0f)

        // 돌진 이동
        val velocity = direction.clone().multiply(1.8).setY(0.25)
        player.velocity = velocity

        drawDashTrail(player, direction)

        // 돌진 경로상의 적 탐색 후 데미지
        val nearby = player.world.getNearbyLivingEntities(player.location, dashDistance)
        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (isInDashPath(player, direction, entity)) {
                entity.damage(damage, player)
                val knockback = direction.clone().multiply(0.8).setY(0.3)
                entity.velocity = knockback
            }
        }
        return true
    }

    private fun isInDashPath(player: Player, direction: Vector, entity: LivingEntity): Boolean {
        val toTarget = entity.location.toVector().subtract(player.location.toVector()).setY(0)
        val projectionLength = toTarget.dot(direction)

        if (projectionLength < 0 || projectionLength > dashDistance) return false

        val closestPoint = direction.clone().multiply(projectionLength)
        val distanceFromLine = toTarget.subtract(closestPoint).length()

        return distanceFromLine <= hitRadius
    }

    private fun drawDashTrail(player: Player, direction: Vector) {
        val world = player.world
        val start = player.location
        val steps = (dashDistance * 3).toInt()
        for (i in 0..steps) {
            val point = start.clone().add(direction.clone().multiply(dashDistance * i / steps))
            world.spawnParticle(Particle.BUBBLE_COLUMN_UP, point, 3, 0.1, 0.1, 0.1, 0.02)
        }
    }
}