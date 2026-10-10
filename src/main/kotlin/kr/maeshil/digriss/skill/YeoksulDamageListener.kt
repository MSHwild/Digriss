package kr.maeshil.digriss.skill

import kr.maeshil.digriss.effect.SkillEffects
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin

class YeoksulDamageListener(private val plugin: JavaPlugin) : Listener {
    @EventHandler
    fun onDamage(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        if (!victim.hasMetadata(YeoksulSkill.META_ABSORB)) return

        val stored = if (victim.hasMetadata(YeoksulSkill.META_STORED))
            victim.getMetadata(YeoksulSkill.META_STORED)[0].asDouble() else 0.0

        victim.setMetadata(YeoksulSkill.META_STORED, FixedMetadataValue(plugin, stored + event.finalDamage))
        event.isCancelled = true
        SkillEffects.mirrorAbsorb(victim.location)
    }
}