package kr.maeshil.digriss.weapon

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.UseCooldown
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.TrackerModifier
import net.kyori.adventure.key.Key
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin

// 무기 스킬 모션: 스킬을 쓰면 BetterModel 플레이어 애니메이션 재생 + 그동안 손 아이템 쿨타임 표시
// 애니메이션은 BetterModel 플레이어 모델(limb) 이름 = MOTIONS의 limb. 본인 화면에는 안 보이고 주변 사람에게만 보임
object WeaponSkillMotion {

    data class Motion(val limb: String, val ticks: Int)

    // ItemsAdder 무기 ID → (BetterModel 플레이어 모델 이름, 재생 길이 틱)
    val MOTIONS: Map<String, Motion> = mapOf(
        "weapon:hell_sword" to Motion("hell_sword_skill", 20),
        "weapon:frost_axe" to Motion("frost_axe_skill", 18),
        "weapon:lifestealsword" to Motion("lifestealsword_skill", 21),
        "weapon:ocean_spear" to Motion("ocean_spear_skill", 17)
    )

    private fun idOf(item: ItemStack?): String? =
        if (item == null || item.isEmpty) null else CustomStack.byItemStack(item)?.namespacedID

    // 무기마다 쿨타임 그룹을 따로 둬서, 쿨타임 표시가 같은 종류 아이템 전체에 걸리지 않게
    private fun group(id: String): Key = Key.key("digriss", "skill_motion_" + id.substringAfter(':'))

    /** 그 칸의 무기에 쿨타임 그룹을 붙여 둠 (들었을 때 · 접속했을 때 미리) */
    fun prepare(player: Player, slot: Int) {
        val item = player.inventory.getItem(slot) ?: return
        val id = idOf(item) ?: return
        if (!MOTIONS.containsKey(id)) return
        val g = group(id)
        if (item.getData(DataComponentTypes.USE_COOLDOWN)?.cooldownGroup() == g) return
        item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(g).build())
        player.inventory.setItem(slot, item)
    }

    fun play(plugin: JavaPlugin, player: Player, weaponId: String) {
        val m = MOTIONS[weaponId] ?: return
        prepare(player, player.inventory.heldItemSlot)
        player.setCooldown(player.inventory.itemInMainHand, m.ticks)
        if (!plugin.server.pluginManager.isPluginEnabled("BetterModel")) return
        BetterModel.limb(m.limb).ifPresent { r ->
            val tracker = r.getOrCreate(BukkitAdapter.adapt(player), TrackerModifier.DEFAULT) { t ->
                t.pipeline.viewFilter { viewer -> viewer.uuid() != player.uniqueId } // 본인 1인칭에는 안 보이게
            }
            tracker.animate(m.limb, AnimationModifier.builder().start(1).end(1).type(AnimationIterator.Type.PLAY_ONCE).build()) {
                plugin.server.scheduler.runTask(plugin, Runnable { tracker.close() })
            }
        }
    }

    class Listener(private val plugin: JavaPlugin) : org.bukkit.event.Listener {

        @EventHandler
        fun onHeld(e: PlayerItemHeldEvent) {
            plugin.server.scheduler.runTask(plugin, Runnable { prepare(e.player, e.newSlot) })
        }

        @EventHandler
        fun onJoin(e: PlayerJoinEvent) {
            plugin.server.scheduler.runTaskLater(plugin, Runnable { prepare(e.player, e.player.inventory.heldItemSlot) }, 20L)
        }
    }
}
