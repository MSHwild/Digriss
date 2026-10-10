package kr.maeshil.digriss.addon

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.weapon.HwandoMotion
import kr.maeshil.digriss.weapon.KatanaMotion
import kr.maeshil.digriss.weapon.WeaponSkillMotion
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin

// 공동작업자 애드온: 일본도·환도 모션, 무기 스킬 모션 (공동작업자 jar에서 디컴파일해 복원)
// Paper 1.21.4 이상(아이템 데이터 컴포넌트 API)과 ItemsAdder가 있어야 켜짐. BetterModel이 없으면 3인칭 동작만 빠짐
object Addons {

    var motions = false
        private set

    // 1.21.4 Paper에 생긴 아이템 데이터 컴포넌트 API가 있는지
    private fun hasComponentApi(): Boolean = try {
        Class.forName("io.papermc.paper.datacomponent.item.UseCooldown")
        Class.forName("io.papermc.paper.datacomponent.item.CustomModelData")
        true
    } catch (e: Throwable) {
        false
    }

    @JvmStatic
    fun enable(plugin: Digriss) {
        if (!plugin.isEnabled) return
        val log = plugin.logger
        val pm = plugin.server.pluginManager
        when {
            !hasComponentApi() -> log.warning("[모션] Paper 1.21.4 이상이 아니어서 일본도·환도·무기 스킬 모션을 끕니다.")
            pm.getPlugin("ItemsAdder") == null -> log.warning("[모션] ItemsAdder가 없어서 무기 모션을 끕니다.")
            else -> try {
                KatanaMotion(plugin).start()
                HwandoMotion(plugin).start()
                pm.registerEvents(WeaponSkillMotion.Listener(plugin), plugin)
                motions = true
                log.info("[모션] 일본도·환도·무기 스킬 모션을 켰습니다." + if (pm.getPlugin("BetterModel") == null) " (BetterModel이 없어서 3인칭 동작은 빠짐)" else "")
            } catch (e: Throwable) {
                log.warning("[모션] 무기 모션을 켜지 못했습니다: $e")
            }
        }
    }

    /** 무기 스킬을 쓸 때 (WeaponSkillListener에서 호출) */
    @JvmStatic
    fun onWeaponSkill(player: Player, weaponId: String) {
        if (!motions) return
        try {
            WeaponSkillMotion.play(JavaPlugin.getPlugin(Digriss::class.java), player, weaponId)
        } catch (e: Throwable) {
            player.server.logger.warning("[모션] $weaponId 스킬 모션 오류: $e")
        }
    }
}
