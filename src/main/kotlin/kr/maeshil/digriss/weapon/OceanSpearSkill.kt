package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
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

        // 돌진 이동
        val velocity = direction.clone().multiply(1.8).setY(0.25)
        player.velocity = velocity

        SkillEffects.abyssDash(player.location, direction) { player.takeIf { it.isOnline && !it.isDead }?.location }

        // 돌진 경로상의 적 탐색 후 데미지
        val nearby = player.world.getNearbyLivingEntities(player.location, dashDistance)
        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (isInDashPath(player, direction, entity)) {
                entity.damage(damage, player)
                SkillEffects.abyssHit(entity.location)
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
}
