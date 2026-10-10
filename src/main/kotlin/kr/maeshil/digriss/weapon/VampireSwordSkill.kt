package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.attribute.Attribute
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import org.bukkit.util.RayTraceResult

class VampireSwordSkill(private val plugin: JavaPlugin) : WeaponSkill {
    override val itemId = "weapon:lifestealsword"
    override val cooldownSeconds = 9L

    private val range = 5.0
    private val damage = 9.0
    private val lifestealRatio = 0.5 // 단일 대상이라 흡혈 비율 높게
    private val speedDurationTicks = 40 // 2초
    private val speedAmplifier = 1 // 이속 2단계

    override fun execute(player: Player): Boolean {
        val target = getTargetEntity(player) ?: run {
            player.sendMessage("${net.md_5.bungee.api.ChatColor.GRAY}대상이 없습니다.")
            return false
        }

        target.damage(damage, player)

        val healAmount = damage * lifestealRatio
        val maxHealth = player.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0
        player.health = (player.health + healAmount).coerceAtMost(maxHealth)

        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, speedDurationTicks, speedAmplifier))

        SkillEffects.vampireDrain(target.location.add(0.0, 1.0, 0.0)) { player.takeIf { it.isOnline && !it.isDead }?.location }

        player.sendMessage("${net.md_5.bungee.api.ChatColor.RED}흡혈 +${"%.1f".format(healAmount)}")
        return true
    }

    private fun getTargetEntity(player: Player): LivingEntity? {
        val result: RayTraceResult? = player.world.rayTraceEntities(
            player.eyeLocation,
            player.eyeLocation.direction,
            range
        ) { entity -> entity is LivingEntity && entity != player && !Friendly.isAlly(player, entity) }

        return result?.hitEntity as? LivingEntity
    }
}
