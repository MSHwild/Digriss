package kr.maeshil.digriss

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import net.md_5.bungee.api.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent

class Event(private val plugin: Digriss) : Listener {

    private val killStreaks = mutableMapOf<String, Int>()

    // 같은 상대를 5분 안에 다시 처치하면 영혼 미지급 (부계정·짜고 죽여주기로 영혼 파밍 방지)
    private val recentKills = HashMap<java.util.UUID, HashMap<java.util.UUID, Long>>()
    private val soulRepeatWindowMs = 5 * 60 * 1000L

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
        val killer = victim.killer?.takeIf { it != victim } // 자기 화살·TNT로 죽은 건 킬로 치지 않음

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

            val now = System.currentTimeMillis()
            val history = recentKills.getOrPut(killer.uniqueId) { HashMap() }
            history.entries.removeIf { now - it.value >= soulRepeatWindowMs }
            val repeated = history.put(victim.uniqueId, now) != null
            val baseSouls = if (repeated) 0L else 10L
            if (baseSouls > 0) plugin.soulManager.addSouls(killer, baseSouls)

            // 5킬스트릭 이상인 상대를 잡으면 전체 공지 + 보너스 영혼 (스트릭 x 2)
            var bonus = 0L
            if (victimStreak >= 5 && !repeated) {
                bonus = victimStreak * 2L
                plugin.soulManager.addSouls(killer, bonus)
                Sounds.all(org.bukkit.Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.2f)
                plugin.server.broadcastMessage(
                    "${ChatColor.RED}⚔ ${ChatColor.GOLD}${killer.name}${ChatColor.RED}님이 " +
                        "${ChatColor.GOLD}${victim.name}${ChatColor.RED}님의 ${ChatColor.YELLOW}${victimStreak}킬스트릭${ChatColor.RED}을 끊었습니다! " +
                        "${ChatColor.AQUA}(보너스 영혼 +$bonus)"
                )
            }
            plugin.kdManager.addKill(killer)
            plugin.achievementManager.onKill(killer, plugin.kdManager.getKills(killer), newStreak, victimStreak >= 5 && !repeated)
            plugin.questManager.onPlayerKill(killer, victim)
            plugin.warScoreManager.recordKill(killer, victim)
            val rankGain = plugin.rankManager.onKill(killer, victim, newStreak)
            val streakText = if (newStreak >= 2) "  §6🔥 ${newStreak}킬스트릭" else ""
            ActionBarManager.showTemp(killer, "§a+$rankGain 랭크점수  §b+${baseSouls + bonus} 영혼$streakText", 2.0)

            // 킬이펙트 재생
            plugin.killEffectManager.getEquipped(killer)?.let { effect ->
                kr.maeshil.digriss.effect.EffectPlayer.play(effect, victim.location)
            }

            if (repeated) killer.sendMessage("${ChatColor.GRAY}같은 상대를 5분 안에 다시 처치해 영혼을 얻지 못했습니다.")
            Sounds.play(killer, org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 0.8f)
            killer.sendMessage("${ChatColor.GREEN}처치 성공! 영혼 +$baseSouls | 현재 킬스트릭: ${ChatColor.YELLOW}$newStreak")

            if (newStreak % 5 == 0) {
                killer.world.players.forEach { Sounds.play(it, org.bukkit.Sound.ENTITY_BLAZE_SHOOT, 0.6f, 0.8f) }
                killer.world.players.forEach {
                    it.sendMessage("${ChatColor.LIGHT_PURPLE}${killer.name}${ChatColor.YELLOW}님이 ${newStreak}킬스트릭을 달성했습니다!")
                }
            }
        }
    }

    fun getKillStreak(playerName: String): Int = killStreaks[playerName] ?: 0


}