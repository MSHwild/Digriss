package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.NationManager
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

// 영토 관련 표시와 효과: 진입 타이틀, 액션바, 국가 레벨 효과, 적 신호기 방문(퀘스트)
class NationTerritory(private val plugin: Digriss, private val core: NationManager) {

    // 플레이어별 마지막으로 있던 영토 (""는 무소속 땅)
    private val lastTerritory = HashMap<UUID, String>()

    fun start() {
        // 영토 표시는 ActionBarManager가 다른 액션바와 합쳐서 출력
        ActionBarManager.addProvider { territoryText(it) }

        // 국가 레벨 효과 (1초마다)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                applyLevelEffects(player)
            }
        }, 20L, 20L)

        // 영토 경계를 넘으면 화면 가운데 타이틀 표시 (0.5초마다 확인)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable {
            for (player in Bukkit.getOnlinePlayers()) {
                checkTerritoryEntry(player)
                checkEnemyBeaconVisit(player)
            }
        }, 10L, 10L)
    }

    fun forget(uuid: UUID) {
        lastTerritory.remove(uuid)
    }

    // ───────────────────────── 레벨 효과 ─────────────────────────

    fun applyLevelEffects(player: Player) {
        val nationName = core.getNationName(player.uniqueId) ?: return
        val nation = Nation.nations[nationName] ?: return
        val level = nation.level
        if (level < 2) return

        val inOwnTerritory = Nation.chunkClaims[core.chunkKeyOf(player.location)] == nationName

        if (inOwnTerritory) {
            giveEffect(player, PotionEffectType.SPEED)
        }

        if (level >= 3) {
            val beacon = core.beaconOf(nationName)
            if (beacon != null && beacon.world == player.world && beacon.distanceSquared(player.location) <= 30.0 * 30.0) {
                giveEffect(player, PotionEffectType.REGENERATION)
            }
        }

        if (level >= 4 && inOwnTerritory) {
            giveEffect(player, PotionEffectType.RESISTANCE)
        }

        if (level >= 5 && inOwnTerritory) {
            giveEffect(player, PotionEffectType.HASTE)
        }
    }

    private fun giveEffect(player: Player, type: PotionEffectType) {
        player.addPotionEffect(PotionEffect(type, 100, 0, true, false, true))
    }

    // ───────────────────────── 영토 표시 ─────────────────────────

    private fun checkTerritoryEntry(player: Player) {
        val owner = Nation.chunkClaims[core.chunkKeyOf(player.location)] ?: ""
        val previous = lastTerritory.put(player.uniqueId, owner)
        // 처음 확인(접속 직후)이거나 같은 영토 안이면 표시 안 함
        if (previous == null || previous == owner) return

        val myNation = core.getNationName(player.uniqueId)
        if (owner.isNotEmpty() && owner != myNation) plugin.questManager.onEnterTerritory(player, owner)
        val (title, subtitle) = when {
            owner.isEmpty() -> "§7무소속 지역" to "§8누구의 영토도 아닙니다"
            owner == myNation -> "§a$owner" to "§2내 국가 영토"
            plugin.allianceManager.areAllied(myNation, owner) -> "§b$owner" to "§3연합국 영토"
            myNation != null && core.war.isAtWar(myNation, owner) -> "§4⚔ $owner ⚔" to "§c전쟁 중인 국가의 영토입니다"
            else -> "§c$owner" to "§7다른 국가의 영토"
        }
        player.sendTitle(title, subtitle, 5, 30, 10)
        when {
            owner.isEmpty() -> Sounds.play(player, org.bukkit.Sound.BLOCK_NOTE_BLOCK_HAT, 0.4f, 1.0f)
            owner == myNation || plugin.allianceManager.areAllied(myNation, owner) ->
                Sounds.play(player, org.bukkit.Sound.BLOCK_NOTE_BLOCK_CHIME, 0.6f, 1.4f)
            myNation != null && core.war.isAtWar(myNation, owner) ->
                Sounds.play(player, org.bukkit.Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.3f, 1.2f)
            else -> Sounds.play(player, org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 1.0f)
        }
    }

    // 전쟁 중인 국가의 신호기 15블록 이내 방문 (퀘스트용)
    private fun checkEnemyBeaconVisit(player: Player) {
        for (enemy in core.warsOfPlayer(player.uniqueId)) {
            val beacon = core.beaconOf(enemy) ?: continue
            if (beacon.world == player.world && beacon.distanceSquared(player.location) <= 15.0 * 15.0) {
                plugin.questManager.onBeaconVisit(player, enemy)
            }
        }
    }

    fun territoryText(player: Player): String {
        val ownerNation = Nation.chunkClaims[core.chunkKeyOf(player.location)]
        val myNation = core.getNationName(player.uniqueId)

        return when {
            ownerNation == null -> "§f소속 국가 : 무소속"
            ownerNation == myNation -> "§a소속 국가 : $ownerNation(내 국가)"
            plugin.allianceManager.areAllied(myNation, ownerNation) -> "§b소속 국가 : $ownerNation(연합국)"
            myNation != null && core.war.isAtWar(myNation, ownerNation) -> "§4소속 국가 : $ownerNation(전쟁 중)"
            else -> "§c소속 국가 : $ownerNation"
        }
    }
}
