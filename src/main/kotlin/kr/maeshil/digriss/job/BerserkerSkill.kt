package kr.maeshil.digriss.job

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

// 광전사: 주변을 내리찍어 광역 피해 + 넉백 + 그로기(강한 둔화)
class BerserkerSkill : JobSkill {
    override val baseCooldownSeconds = 25
    private val radius = 4.0
    private val damage = 6.0
    private val knockback = 1.2
    private val groggyTicks = 30 // 1.5초

    override fun execute(player: Player): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") as Digriss
        val center = player.location

        val ground = center.block.getRelative(org.bukkit.block.BlockFace.DOWN).type.takeIf { it.isSolid }
        SkillEffects.berserkSmash(center, radius, ground)

        var hits = 0
        for (target in player.world.getNearbyLivingEntities(center, radius)) {
            if (target == player || target.isDead) continue
            if (target is Player && plugin.allianceManager.isFriendly(player, target)) continue

            target.damage(damage, player)
            pushAway(player, target)
            target.addPotionEffect(PotionEffect(PotionEffectType.SLOWNESS, groggyTicks, 3, false, true))
            SkillEffects.berserkHit(target.location)
            hits++
        }
        player.sendMessage(if (hits > 0) "§c광폭 강타! §7${hits}명에게 피해를 입혔습니다." else "§c광폭 강타! §7주변에 대상이 없습니다.")
        return true
    }

    private fun pushAway(player: Player, target: LivingEntity) {
        val dir = target.location.toVector().subtract(player.location.toVector()).setY(0)
        if (dir.lengthSquared() < 0.01) dir.setX(0.1)
        target.velocity = dir.normalize().multiply(knockback).setY(0.4)
    }
}
