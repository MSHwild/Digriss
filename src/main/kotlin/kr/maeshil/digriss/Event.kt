package kr.maeshil.digriss

import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class Event(private val plugin: Digriss) : Listener {

    private val killStreaks = mutableMapOf<String, Int>()

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        val player = event.player
        //plugin.scoreboardManager.setJoinTime(player)
        event.joinMessage = "${ChatColor.YELLOW}[+] ${ChatColor.GOLD}${player.name}${ChatColor.YELLOW}님이 입장했습니다."
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val player = event.player
        event.quitMessage = "${ChatColor.GRAY}[-] ${ChatColor.DARK_GRAY}${player.name}${ChatColor.GRAY}님이 퇴장했습니다."
    }

    @EventHandler
    fun onDeath(event: PlayerDeathEvent) {
        val victim = event.entity
        val killer = victim.killer

        event.deathMessage = if (killer != null) {
            "${ChatColor.RED}☠ ${ChatColor.GOLD}${victim.name}${ChatColor.RED}님이 ${ChatColor.GOLD}${killer.name}${ChatColor.RED}님에게 처치당했습니다."
        } else {
            "${ChatColor.RED}☠ ${ChatColor.GOLD}${victim.name}${ChatColor.RED}님이 사망했습니다."
        }

        val victimStreak = killStreaks[victim.name] ?: 0
        killStreaks[victim.name] = 0
        plugin.kdManager.addDeath(victim)
        plugin.rankManager.onDeath(victim)

        if (killer != null) {
            val newStreak = (killStreaks[killer.name] ?: 0) + 1
            killStreaks[killer.name] = newStreak

            plugin.soulManager.addSouls(killer, 10)

            // 5킬스트릭 이상인 상대를 잡으면 전체 공지 + 보너스 영혼 (스트릭 x 2)
            var bonus = 0L
            if (victimStreak >= 5 && killer != victim) {
                bonus = victimStreak * 2L
                plugin.soulManager.addSouls(killer, bonus)
                plugin.server.broadcastMessage(
                    "${ChatColor.RED}⚔ ${ChatColor.GOLD}${killer.name}${ChatColor.RED}님이 " +
                        "${ChatColor.GOLD}${victim.name}${ChatColor.RED}님의 ${ChatColor.YELLOW}${victimStreak}킬스트릭${ChatColor.RED}을 끊었습니다! " +
                        "${ChatColor.AQUA}(보너스 영혼 +$bonus)"
                )
            }
            plugin.kdManager.addKill(killer)
            plugin.questManager.onPlayerKill(killer, victim)
            plugin.warScoreManager.recordKill(killer, victim)
            val rankGain = plugin.rankManager.onKill(killer, victim, newStreak)
            val streakText = if (newStreak >= 2) "  §6🔥 ${newStreak}킬스트릭" else ""
            ActionBarManager.showTemp(killer, "§a+$rankGain 랭크점수  §b+${10 + bonus} 영혼$streakText", 2.0)

            // 킬이펙트 재생
            plugin.killEffectManager.getEquipped(killer)?.let { effect ->
                kr.maeshil.digriss.effect.EffectPlayer.play(effect, victim.location)
            }

            killer.sendMessage("${ChatColor.GREEN}처치 성공! 영혼 +10 | 현재 킬스트릭: ${ChatColor.YELLOW}$newStreak")

            if (newStreak % 5 == 0) {
                killer.world.players.forEach {
                    it.sendMessage("${ChatColor.LIGHT_PURPLE}${killer.name}${ChatColor.YELLOW}님이 ${newStreak}킬스트릭을 달성했습니다!")
                }
            }
        }
    }

    fun getKillStreak(playerName: String): Int = killStreaks[playerName] ?: 0


}