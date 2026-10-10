package kr.maeshil.digriss.weapon

import kr.maeshil.digriss.Friendly
import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.Location
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable
import org.bukkit.util.Vector

class SlashSwordSkill(private val plugin: JavaPlugin) : WeaponSkill {
    override val itemId = "weapon:blade"
    override val cooldownSeconds = 10L

    private val range = 8.0
    private val width = 1.5 // 직선 판정 폭
    private val damage = 10.0
    private val bleedDamage = 2.0
    private val bleedTicks = 5 // 5초, 1초마다 틱
    private val bleedInterval = 20L

    override fun execute(player: Player): Boolean {
        val eyeLoc = player.eyeLocation
        val direction = eyeLoc.direction.setY(0).normalize()

        SkillEffects.ironSlash(eyeLoc, direction, range)

        val hitEntities = mutableSetOf<LivingEntity>()
        val nearby = player.world.getNearbyLivingEntities(player.location, range)

        for (entity in nearby) {
            if (entity == player || Friendly.isAlly(player, entity)) continue
            if (isOnLine(player.location, direction, entity.location)) {
                hitEntities.add(entity)
            }
        }

        for (entity in hitEntities) {
            entity.damage(damage, player)
            SkillEffects.ironSlashHit(entity.location)
            applyBleed(entity, player)
        }
        return true
    }

    private fun isOnLine(origin: Location, direction: Vector, targetLoc: Location): Boolean {
        val toTarget = targetLoc.toVector().subtract(origin.toVector()).setY(0)
        val projectionLength = toTarget.dot(direction)

        if (projectionLength < 0 || projectionLength > range) return false

        val closestPoint = direction.clone().multiply(projectionLength)
        val distanceFromLine = toTarget.subtract(closestPoint).length()

        return distanceFromLine <= width
    }

    private fun applyBleed(entity: LivingEntity, source: Player) {
        var ticksLeft = bleedTicks
        object : BukkitRunnable() {
            override fun run() {
                if (!entity.isValid || entity.isDead || ticksLeft <= 0) {
                    cancel()
                    return
                }
                entity.damage(bleedDamage, source)
                SkillEffects.bleedTick(entity.location)
                ticksLeft--
            }
        }.runTaskTimer(plugin, bleedInterval, bleedInterval)
    }
}
