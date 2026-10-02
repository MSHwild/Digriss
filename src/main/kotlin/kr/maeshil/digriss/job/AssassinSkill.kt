package kr.maeshil.digriss.job

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.jobManager.AssassinStealthManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class AssassinSkill : JobSkill {
    override val baseCooldownSeconds = 30
    private val stealthDurationSeconds = 4.0

    override fun execute(player: Player): Boolean {
        val durationTicks = (stealthDurationSeconds * 20).toInt()

        AssassinStealthManager.activate(player.uniqueId)
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, durationTicks, 0, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, durationTicks, 1, false, false))
        Sounds.play(player, org.bukkit.Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 1.2f)
        player.sendMessage("§5은신 발동! ${stealthDurationSeconds}초 내 첫 공격에 기습 피해가 추가됩니다.")

        val plugin = Bukkit.getPluginManager().getPlugin("Digriss")!!
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (AssassinStealthManager.isActive(player.uniqueId)) {
                AssassinStealthManager.consume(player.uniqueId)
                player.sendMessage("§7은신이 종료되었습니다.")
            }
        }, durationTicks.toLong())
        return true
    }
}