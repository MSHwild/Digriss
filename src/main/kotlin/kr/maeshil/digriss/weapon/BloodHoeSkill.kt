package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player

class BloodHoeSkill : WeaponSkill {
    override val itemId = "weapon:blood_hoe"
    override val cooldownSeconds = 14L

    private val radius = 4.0
    private val damage = 7.0
    private val lifestealRatio = 0.3 // 입힌 피해량의 30% 흡수

    override fun execute(player: Player): Boolean {
        SkillEffects.bloodHarvest(player.location, player.location.direction, radius)
        val follow = { player.takeIf { it.isOnline && !it.isDead }?.location }

        val nearby = player.world.getNearbyLivingEntities(player.location, radius)
        var totalDamageDealt = 0.0

        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue

            entity.damage(damage, player)
            totalDamageDealt += damage

            SkillEffects.bloodHarvestHit(entity.location, follow)
        }

        if (totalDamageDealt > 0) {
            val healAmount = totalDamageDealt * lifestealRatio
            val maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
            player.health = (player.health + healAmount).coerceAtMost(maxHealth)

            SkillEffects.bloodHarvestHeal(follow)
        }
        return true
    }
}

private fun org.bukkit.World.getNearbyLivingEntities(loc: org.bukkit.Location, radius: Double): List<LivingEntity> {
    return getNearbyEntities(loc, radius, radius, radius).filterIsInstance<LivingEntity>()
}