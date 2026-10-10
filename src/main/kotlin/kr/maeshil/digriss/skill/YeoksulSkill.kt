package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Friendly
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitRunnable

class YeoksulSkill(private val plugin: JavaPlugin) : Skill {
    override val itemId = "weapon:skill_8"
    override val manaCost = 50.0

    companion object {
        const val META_ABSORB = "digriss_yeoksul_absorb"
        const val META_STORED = "digriss_yeoksul_stored"
    }

    override fun execute(player: Player): Boolean {
        val durationTicks = 60L

        player.setMetadata(META_ABSORB, FixedMetadataValue(plugin, true))
        player.setMetadata(META_STORED, FixedMetadataValue(plugin, 0.0))
        SkillEffects.mirrorShell({ player.takeIf { it.isOnline && !it.isDead }?.location }, durationTicks.toInt())
        player.sendMessage("§d역술 §f- 피해 흡수 시작 (3초)")

        object : BukkitRunnable() {
            var ticks = 0L
            override fun run() {
                if (ticks >= durationTicks || player.isDead) {
                    player.removeMetadata(META_ABSORB, plugin)
                    val stored = if (player.hasMetadata(META_STORED))
                        player.getMetadata(META_STORED)[0].asDouble() else 0.0
                    player.removeMetadata(META_STORED, plugin)
                    if (stored > 0) reflect(player, stored)
                    cancel()
                    return
                }
                ticks += 5
            }
        }.runTaskTimer(plugin, 0L, 5L)
        return true
    }

    private fun reflect(player: Player, amount: Double) {
        val radius = 5.0
        val victims = player.world.getNearbyEntities(player.location, radius, radius, radius)
            .filterIsInstance<LivingEntity>()
            .filter { it != player && !Friendly.isAlly(player, it) }
        SkillEffects.mirrorReflect(player.location, radius, victims.map { it.location })
        victims.forEach { it.damage(amount, player) }

        player.sendMessage("§d역술 §f- ${amount.toInt()} 피해 반사")
    }
}

