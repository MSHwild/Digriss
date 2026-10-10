package kr.maeshil.digriss.job

import kr.maeshil.digriss.effect.SkillEffects
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
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

        var healed = 0
        for (ally in player.world.getNearbyPlayers(player.location, radius)) {
            if (!plugin.allianceManager.isFriendly(player, ally) || ally.isDead) continue
            ally.heal(healAmount)
            ally.addPotionEffect(PotionEffect(PotionEffectType.REGENERATION, regenTicks, 1, false, true))
            cleansed.forEach { ally.removePotionEffect(it) }
            SkillEffects.lifeBloomAlly { ally.takeIf { it.isOnline && !it.isDead }?.location }
            if (ally != player) ally.sendMessage("§d${player.name}님의 생명의 축복으로 회복되었습니다!")
            healed++
        }
        SkillEffects.lifeBloom(player.location, radius)
        player.sendMessage("§d생명의 축복! §7아군 ${healed}명을 회복했습니다.")
        return true
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
                    SkillEffects.healAuraTick(ally.location)
                }
            }
        }, INTERVAL_TICKS, INTERVAL_TICKS)
    }
}
