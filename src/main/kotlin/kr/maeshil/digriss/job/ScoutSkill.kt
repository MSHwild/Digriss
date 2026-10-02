package kr.maeshil.digriss.job

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class ScoutSkill : JobSkill {
    override val baseCooldownSeconds = 30
    private val radius = 30.0
    private val glowDurationSeconds = 5.0

    override fun execute(player: Player): Boolean {
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") as Digriss
        val myNation = plugin.nationManager.getNationName(player.uniqueId)
        val durationTicks = (glowDurationSeconds * 20).toInt()

        // 같은 국가원과 연합국 국가원은 제외 (무소속이면 다른 모든 플레이어가 대상)
        val enemies = player.world.getNearbyPlayers(player.location, radius).filter {
            it != player &&
                it.gameMode != GameMode.SPECTATOR &&
                (myNation == null || plugin.nationManager.getNationName(it.uniqueId) != myNation) &&
                !plugin.allianceManager.areAllied(myNation, plugin.nationManager.getNationName(it.uniqueId))
        }

        player.playSound(player.location, Sound.BLOCK_BEACON_ACTIVATE, 1f, 1.8f)

        if (enemies.isEmpty()) {
            player.sendMessage("§7주변 ${radius.toInt()}블록 내에 적이 없습니다.")
            return false
        }

        enemies.forEach { enemy ->
            enemy.addPotionEffect(PotionEffect(PotionEffectType.GLOWING, durationTicks, 0, false, false))
            enemy.sendMessage("§c정찰병에게 위치가 노출되었습니다!")
        }
        player.sendMessage("§a적 ${enemies.size}명의 위치를 ${glowDurationSeconds}초간 표시합니다.")
        return true
    }
}
