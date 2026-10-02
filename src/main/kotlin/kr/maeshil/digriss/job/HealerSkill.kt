package kr.maeshil.digriss.job

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

// 치유사 궁극기: 주변 아군 광역 회복 + 재생 + 해로운 효과 제거
class HealerSkill : JobSkill {
    override val baseCooldownSeconds = 60
    private val radius = 10.0
    private val healAmount = 8.0 // 하트 4칸
    private val regenTicks = 100 // 5초

    private val cleansed = listOf(
        PotionEffectType.POISON, PotionEffectType.WITHER, PotionEffectType.SLOWNESS,
        PotionEffectType.WEAKNESS, PotionEffectType.BLINDNESS, PotionEffectType.NAUSEA
    )

    override fun execute(player: Player): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") as Digriss
        player.world.playSound(player.location, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.6f)
        player.world.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f)

        var healed = 0
        for (ally in player.world.getNearbyPlayers(player.location, radius)) {
            if (!plugin.allianceManager.isFriendly(player, ally) || ally.isDead) continue
            ally.heal(healAmount)
            ally.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, regenTicks, 1, false, true))
            cleansed.forEach { ally.removePotionEffect(it) }
            ally.world.spawnParticle(Particle.HEART, ally.location.add(0.0, 2.0, 0.0), 6, 0.4, 0.3, 0.4, 0.0)
            if (ally != player) ally.sendMessage("§d✨ ${player.name}님의 생명의 축복으로 회복되었습니다!")
            healed++
        }
        drawBloom(player)
        player.sendMessage("§d✨ 생명의 축복! §7아군 ${healed}명을 회복했습니다.")
        return true
    }

    private fun drawBloom(player: Player) {
        val world = player.world
        val center = player.location.add(0.0, 0.3, 0.0)
        for (r in listOf(2.0, 5.0, radius)) {
            val points = (r * 8).toInt()
            for (i in 0 until points) {
                val angle = 2 * Math.PI * i / points
                world.spawnParticle(Particle.HAPPY_VILLAGER, center.clone().add(Math.cos(angle) * r, 0.0, Math.sin(angle) * r), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }
}

// 치유사 지속 힐: 3초마다 주변 8블록 아군(본인 포함)을 하트 반 칸씩 회복
object HealerAura {
    private const val RADIUS = 8.0
    private const val HEAL = 1.0
    private const val INTERVAL_TICKS = 60L

    fun start(plugin: Digriss) {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            for (healer in Bukkit.getOnlinePlayers()) {
                if (healer.isDead || plugin.jobManager.getJob(healer.uniqueId) != JobType.LIFE_PRIEST) continue
                for (ally in healer.world.getNearbyPlayers(healer.location, RADIUS)) {
                    if (ally.isDead || !plugin.allianceManager.isFriendly(healer, ally)) continue
                    if (ally.health >= (ally.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH)?.value ?: 20.0)) continue
                    ally.heal(HEAL)
                    ally.world.spawnParticle(Particle.HEART, ally.location.add(0.0, 2.1, 0.0), 1, 0.2, 0.1, 0.2, 0.0)
                }
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS)
    }
}
