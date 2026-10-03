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

        val distance = result?.hitPosition?.distance(player.eyeLocation.toVector())?.minus(0.5) ?: maxDistance
        val teleportLoc = safeSpot(player, dir, distance.coerceAtLeast(0.0)) ?: run {
            player.sendMessage("§c이동할 공간이 없습니다.")
            return false
        }
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

    // 바라보는 방향으로 distance만큼 간 곳부터 0.5칸씩 되돌아오며, 몸(발·머리)이 블록에 안 겹치는 자리를 찾음.
    // 발이 땅에 묻히는 자리면 최대 1.5칸 위로 올려서 확인 (바닥을 보고 쓴 경우)
    private fun safeSpot(player: Player, dir: org.bukkit.util.Vector, distance: Double): org.bukkit.Location? {
        var d = distance
        while (d >= 0.0) {
            val feet = player.eyeLocation.clone().add(dir.clone().multiply(d)).subtract(0.0, player.eyeHeight, 0.0)
            for (lift in listOf(0.0, 0.5, 1.0, 1.5)) {
                val spot = feet.clone().add(0.0, lift, 0.0)
                if (fits(player, spot)) return spot
            }
            d -= 0.5
        }
        return null
    }

    private fun fits(player: Player, feet: org.bukkit.Location): Boolean {
        val box = player.boundingBox.clone().shift(feet.toVector().subtract(player.location.toVector()))
        val world = feet.world ?: return false
        for (x in Math.floor(box.minX).toInt()..Math.floor(box.maxX - 1e-6).toInt())
            for (y in Math.floor(box.minY).toInt()..Math.floor(box.maxY - 1e-6).toInt())
                for (z in Math.floor(box.minZ).toInt()..Math.floor(box.maxZ - 1e-6).toInt()) {
                    val b = world.getBlockAt(x, y, z)
                    if (b.isPassable) continue
                    if (b.collisionShape.boundingBoxes.any { it.clone().shift(x.toDouble(), y.toDouble(), z.toDouble()).overlaps(box) }) return false
                }
        return true
    }
}