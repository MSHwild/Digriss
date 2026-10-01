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
        val last = cooldowns[key] ?: 0L
        val remain = weaponData.cooldown * 1000L - (now - last)

        if (remain > 0) {
            player.sendActionBar("${ChatColor.RED}쿨타임: ${"%.1f".format(remain / 1000.0)}초")
            return
        }

        val skill = skillMap[weaponData.skillType] ?: return

        cooldowns[key] = now
        player.sendMessage("${ChatColor.GOLD}[${weaponData.displayName}] ${ChatColor.YELLOW}스킬 발동!")
        skill.execute(player)
    }
}