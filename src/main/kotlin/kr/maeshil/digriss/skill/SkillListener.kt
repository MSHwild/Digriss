package kr.maeshil.digriss.skill

import dev.lone.itemsadder.api.CustomStack
import kr.maeshil.digriss.manager.ManaManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.block.Action
import org.bukkit.plugin.java.JavaPlugin

class SkillListener(private val plugin: JavaPlugin, private val manaManager: ManaManager) : Listener {

    private val skillMap: Map<String, Skill> = listOf(
        SasaengyeolmokSkill(plugin),
        GaeSkill(plugin),
        HeukroeSkill(),
        MuhanjeongcheSkill(plugin),
        ChamSkill(plugin),
        InSkill(plugin),
        CheokSkill(),
        YeoksulSkill(plugin)
    ).associateBy { it.itemId }

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val item = event.item ?: return
        val customStack = CustomStack.byItemStack(item) ?: return
        val id = customStack.namespacedID
        val skill = skillMap[id] ?: return

        val player = event.player
        if (!manaManager.hasEnoughMana(player, skill.manaCost)) {
            player.sendMessage("§b마나가 부족합니다. (필요: ${skill.manaCost})")
            return
        }

        manaManager.consumeMana(player, skill.manaCost)
        skill.execute(player)
        (plugin as kr.maeshil.digriss.Digriss).questManager.addProgress(player, kr.maeshil.digriss.quest.QuestType.SKILL_USE)
    }
}