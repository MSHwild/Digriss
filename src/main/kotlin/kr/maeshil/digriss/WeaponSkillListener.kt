package kr.maeshil.digriss

import kr.maeshil.digriss.weapon.BloodHoeSkill
import kr.maeshil.digriss.weapon.FrostAxeSkill
import kr.maeshil.digriss.weapon.HellSwordSkill
import kr.maeshil.digriss.weapon.OceanSpearSkill
import kr.maeshil.digriss.weapon.SlashSwordSkill
import kr.maeshil.digriss.weapon.VampireSwordSkill
import kr.maeshil.digriss.weapon.VoidSwordSkill
import kr.maeshil.digriss.weapon.WeaponSkill
import net.md_5.bungee.api.ChatColor
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import java.util.UUID

class WeaponSkillListener(private val plugin: Digriss) : Listener {

    private val cooldowns = mutableMapOf<Pair<UUID, String>, Long>()

    init {
        // 손에 든 무기의 스킬 쿨타임을 액션바에 상시 표시 (무기를 안 들면 표시 안 함)
        ActionBarManager.addProvider { player ->
            val weapon = ItemAttributeUtil.getWeaponData(player.inventory.itemInMainHand) ?: return@addProvider null
            val remain = remainingMillis(player.uniqueId, weapon)
            if (remain > 0) "${ChatColor.RED}⏳ ${weapon.displayName} ${"%.1f".format(remain / 1000.0)}초"
            else "${ChatColor.GREEN}⚔ ${weapon.displayName} 준비 완료"
        }
    }

    private fun remainingMillis(uuid: UUID, weapon: WeaponData): Long {
        val last = cooldowns[uuid to weapon.id] ?: return 0L
        return weapon.cooldown * 1000L - (System.currentTimeMillis() - last)
    }

    // 스킬타입 -> 실제 실행 클래스 매핑
    private val skillMap: Map<String, WeaponSkill> = mapOf(
        "fire_explosion" to HellSwordSkill(),
        "piercing_slash" to SlashSwordSkill(plugin),
        "aoe_lifesteal" to BloodHoeSkill(),
        "single_lifesteal" to VampireSwordSkill(plugin),
        "cone_slow" to FrostAxeSkill(),
        "dash_strike" to OceanSpearSkill(),
        "void_cut" to VoidSwordSkill()
    )

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return

        val player = event.player
        val item = player.inventory.itemInMainHand
        val weaponData = ItemAttributeUtil.getWeaponData(item) ?: return

        val key = player.uniqueId to weaponData.id
        val now = System.currentTimeMillis()

        // 쿨타임 중이면 액션바에 이미 남은 시간이 떠 있으므로 그냥 무시
        if (remainingMillis(player.uniqueId, weaponData) > 0) return

        val skill = skillMap[weaponData.skillType] ?: return

        if (!skill.execute(player)) return
        cooldowns[key] = now
        player.sendMessage("${ChatColor.GOLD}[${weaponData.displayName}] ${ChatColor.YELLOW}스킬 발동!")
    }
}