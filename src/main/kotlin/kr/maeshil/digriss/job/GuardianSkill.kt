package kr.maeshil.digriss.job

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

// 수호자: 주변 적 도발 + 주변 아군에게 피해감소 배리어
class GuardianSkill : JobSkill {
    override val baseCooldownSeconds = 30
    private val tauntRadius = 8.0
    private val barrierRadius = 6.0
    private val durationSeconds = 4.0

    override fun execute(player: Player): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") as Digriss
        val ticks = (durationSeconds * 20).toInt()
        val until = System.currentTimeMillis() + (durationSeconds * 1000).toLong()

        player.world.playSound(player.location, Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f)
        player.world.playSound(player.location, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.2f)

        // 배리어: 본인 저항 II, 주변 아군 저항 I
        player.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, ticks, 1, false, true))
        var shielded = 0
        for (ally in player.world.getNearbyPlayers(player.location, barrierRadius)) {
            if (ally == player || !plugin.allianceManager.isFriendly(player, ally)) continue
            ally.addPotionEffect(PotionEffect(PotionEffectType.RESISTANCE, ticks, 0, false, true))
            ally.sendMessage("§9🛡 ${player.name}님의 배리어가 피해를 줄여줍니다.")
            shielded++
        }
        drawBarrier(player)

        // 도발: 몹은 수호자를 노리고, 적 플레이어는 수호자 외 대상에게 주는 피해 50% 감소
        var taunted = 0
        for (target in player.world.getNearbyLivingEntities(player.location, tauntRadius)) {
            if (target == player || target.isDead) continue
            when (target) {
                is Player -> {
                    if (plugin.allianceManager.isFriendly(player, target)) continue
                    taunts[target.uniqueId] = Taunt(player.uniqueId, until)
                    target.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false))
                    Sounds.alert(target)
                    target.sendMessage("§c🛡 ${player.name}님에게 도발당했습니다! §7${durationSeconds}초간 다른 대상에게 주는 피해가 절반이 됩니다.")
                }
                is Mob -> target.target = player
                else -> continue
            }
            target.world.spawnParticle(Particle.ANGRY_VILLAGER, target.eyeLocation.add(0.0, 0.5, 0.0), 3, 0.3, 0.2, 0.3, 0.0)
            taunted++
        }

        player.sendMessage("§9🛡 수호 태세! §7도발 ${taunted}명, 배리어 아군 ${shielded}명")
        return true
    }

    private fun drawBarrier(player: Player) {
        val world = player.world
        val center = player.location
        for (i in 0 until 40) {
            val angle = 2 * Math.PI * i / 40
            for (y in listOf(0.2, 1.0, 1.8)) {
                world.spawnParticle(Particle.END_ROD,
                    center.clone().add(Math.cos(angle) * barrierRadius, y, Math.sin(angle) * barrierRadius), 1, 0.0, 0.0, 0.0, 0.0)
            }
        }
    }

    private class Taunt(val guardian: UUID, val until: Long)

    companion object {
        private val taunts = HashMap<UUID, Taunt>()
        private const val TAUNT_DAMAGE_MULTIPLIER = 0.5
    }

    // 도발당한 플레이어가 수호자 외 대상을 때리면 피해 감소
    class TauntListener : Listener {
        @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
        fun onDamage(e: EntityDamageByEntityEvent) {
            val attacker = when (val d = e.damager) {
                is Player -> d
                is Projectile -> d.shooter as? Player
                else -> null
            } ?: return
            val taunt = taunts[attacker.uniqueId] ?: return
            if (System.currentTimeMillis() > taunt.until) {
                taunts.remove(attacker.uniqueId)
                return
            }
            if (e.entity.uniqueId == taunt.guardian) return

            e.damage *= TAUNT_DAMAGE_MULTIPLIER
            ActionBarManager.showTemp(attacker, "§c🛡 도발 중: 수호자 외 대상 피해 감소", 1.0)
        }

        @EventHandler
        fun onQuit(e: PlayerQuitEvent) {
            taunts.remove(e.player.uniqueId)
        }
    }
}
