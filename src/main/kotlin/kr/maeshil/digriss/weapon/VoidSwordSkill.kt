package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.util.RayTraceResult
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class VoidSwordSkill : WeaponSkill {
    override val itemId = "weapon:void_sword"
    override val cooldownSeconds = 13L

    override fun execute(player: Player): Boolean {
        val maxDistance = 8.0
        val dir = player.eyeLocation.direction.normalize()
        val result: RayTraceResult? = player.world.rayTraceBlocks(player.eyeLocation, dir, maxDistance)

        val teleportLoc = if (result != null) {
            result.hitPosition.toLocation(player.world).subtract(dir.clone().multiply(0.5))
        } else {
            player.eyeLocation.clone().add(dir.clone().multiply(maxDistance))
        }
        teleportLoc.subtract(0.0, player.eyeHeight, 0.0) // 눈높이 → 발 위치 (천장·벽에 끼임 방지)
        teleportLoc.pitch = player.location.pitch
        teleportLoc.yaw = player.location.yaw

        val originLoc = player.location.clone()
        player.teleport(teleportLoc)

        player.world.spawnParticle(Particle.PORTAL, originLoc, 60, 0.5, 1.0, 0.5, 0.5)
        player.world.spawnParticle(Particle.PORTAL, teleportLoc, 60, 0.5, 1.0, 0.5, 0.5)
        player.world.playSound(teleportLoc, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f)

        val radius = 3.5
        teleportLoc.world!!.getNearbyEntities(teleportLoc, radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && !Friendly.isAlly(player, it) }
            .forEach {
                it.damage(8.0, player)
                it.addPotionEffect(PotionEffect(PotionEffectType.BLINDNESS, 60, 0))
                it.addPotionEffect(PotionEffect(PotionEffectType.DARKNESS, 60, 0))
            }
        return true
    }
}