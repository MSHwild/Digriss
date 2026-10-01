package kr.maeshil.digriss.effect

import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player

object EffectPlayer {

    fun play(effect: KillEffect, location: Location) {
        val world = location.world ?: return
        val loc = location.clone().add(0.0, 1.0, 0.0)

        when (effect.id) {
            "flame_burst" -> {
                world.spawnParticle(Particle.FLAME, loc, 40, 0.5, 0.8, 0.5, 0.05)
                world.spawnParticle(Particle.LAVA, loc, 10, 0.3, 0.3, 0.3, 0.0)
                world.playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 1.2f)
            }
            "frost_nova" -> {
                world.spawnParticle(Particle.SNOWFLAKE, loc, 50, 0.6, 0.8, 0.6, 0.05)
                world.spawnParticle(Particle.CLOUD, loc, 15, 0.4, 0.4, 0.4, 0.02)
                world.playSound(loc, Sound.BLOCK_GLASS_BREAK, 1.0f, 0.7f)
            }
            "blood_burst" -> {
                world.spawnParticle(
                    Particle.DUST, loc, 60, 0.5, 0.8, 0.5, 0.0,
                    Particle.DustOptions(Color.RED, 1.5f)
                )
                world.playSound(loc, Sound.ENTITY_WITHER_HURT, 1.0f, 1.5f)
            }
            "void_collapse" -> {
                world.spawnParticle(Particle.SQUID_INK, loc, 40, 0.5, 0.8, 0.5, 0.02)
                world.spawnParticle(Particle.PORTAL, loc, 30, 0.4, 0.6, 0.4, 0.3)
                world.playSound(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f)
            }
            "soul_release" -> {
                world.spawnParticle(Particle.SOUL, loc, 30, 0.4, 1.0, 0.4, 0.05)
                world.spawnParticle(Particle.END_ROD, loc, 15, 0.3, 0.8, 0.3, 0.03)
                world.playSound(loc, Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8f, 1.3f)
            }
        }
    }
}