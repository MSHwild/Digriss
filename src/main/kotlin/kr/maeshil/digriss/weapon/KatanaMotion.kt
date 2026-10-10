package kr.maeshil.digriss.weapon

import dev.lone.itemsadder.api.CustomStack
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.UseCooldown
import kr.toxicity.model.api.BetterModel
import kr.toxicity.model.api.animation.AnimationIterator
import kr.toxicity.model.api.animation.AnimationModifier
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter
import kr.toxicity.model.api.tracker.EntityTracker
import kr.toxicity.model.api.tracker.TrackerModifier
import net.kyori.adventure.key.Key
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerAnimationEvent
import org.bukkit.event.player.PlayerAnimationType
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

// 일본도 모션 (weapon:katana)
//  - 들면 칼집에 든 상태(hold), 휘두르면 발도술(iai) → 베기1 → 베기2 → 찌르기 콤보
//  - 5초 동안 안 휘두르면 칼집에 넣음(sheathe)
//  - 1인칭: 손에 든 아이템 모델 상태(custom_model_data 문자열)와 왼손 칼집(saya, 칼이 들었는지 full/empty)으로 표현
//  - 3인칭: BetterModel 플레이어 모델 katana_iai 의 애니메이션 katana_<동작> (본인에게는 안 보임)
class KatanaMotion(private val plugin: JavaPlugin) : Listener {

    companion object {
        const val ITEM_ID = "weapon:katana"
        const val SAYA_ID = "weapon:katana_saya"
        const val LIMB = "katana_iai"
        const val IDLE_TICKS = 100L                // 이만큼 안 휘두르면 칼집에 넣음 (5초)
        val TICKS = mapOf("iai" to 12, "cut1" to 10, "cut2" to 10, "thrust" to 10, "sheathe" to 26) // 동작별 길이(틱)
        val COMBO = listOf("cut1", "cut2", "thrust")
        val GROUP: Key = Key.key("digriss", "katana_motion")
    }

    private val lastAction = HashMap<UUID, Long>()
    private val combo = HashMap<UUID, Int>()
    private val saya = HashMap<UUID, Boolean>()    // 왼손 칼집에 칼이 들어 있는지
    private val wasHolding = HashSet<UUID>()

    private val tick: Long get() = plugin.server.currentTick.toLong()
    private val hasBetterModel: Boolean get() = plugin.server.pluginManager.isPluginEnabled("BetterModel")

    fun start() {
        plugin.server.pluginManager.registerEvents(this, plugin)
        plugin.server.scheduler.runTaskTimer(plugin, Runnable { scan() }, 5L, 5L)
    }

    private fun isKatana(item: ItemStack?): Boolean =
        item != null && !item.isEmpty && CustomStack.byItemStack(item)?.namespacedID == ITEM_ID

    // 아이템 모델 상태 = custom_model_data 의 첫 문자열
    private fun state(item: ItemStack): String? = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)?.strings()?.firstOrNull()

    private fun withState(item: ItemStack, s: String): ItemStack {
        val old = item.getData(DataComponentTypes.CUSTOM_MODEL_DATA)
        val b = CustomModelData.customModelData().addString(s)
        old?.floats()?.let { b.addFloats(it) }
        old?.flags()?.let { b.addFlags(it) }
        old?.colors()?.let { b.addColors(it) }
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, b.build())
        item.setData(DataComponentTypes.USE_COOLDOWN, UseCooldown.useCooldown(0.05f).cooldownGroup(GROUP).build())
        return item
    }

    private fun setMain(p: Player, s: String, cooldown: Int = 0) {
        val item = withState(p.inventory.itemInMainHand, s)
        p.inventory.setItemInMainHand(item)
        if (cooldown > 0) p.setCooldown(item, cooldown)
    }

    private fun showSaya(p: Player, full: Boolean) {
        saya[p.uniqueId] = full
        sendSaya(p)
    }

    // 왼손에 칼집 모델을 보여줌 (실제 아이템은 안 바꾸고 화면에만)
    private fun sendSaya(p: Player) {
        val full = saya[p.uniqueId] ?: return
        val stack = CustomStack.getInstance(SAYA_ID)?.itemStack ?: return
        withState(stack, if (full) "full" else "empty")
        p.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, stack)
        for (viewer in p.trackedBy) viewer.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, stack)
    }

    // 왼손을 실제 아이템으로 되돌림
    private fun clearSaya(p: Player) {
        if (saya.remove(p.uniqueId) == null) return
        val real = p.inventory.itemInOffHand
        p.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, real)
        for (viewer in p.trackedBy) viewer.sendEquipmentChange(p, EquipmentSlot.OFF_HAND, real)
    }

    private fun tracker(p: Player): EntityTracker? {
        if (!hasBetterModel) return null
        return BetterModel.limb(LIMB).map { r ->
            r.getOrCreate(BukkitAdapter.adapt(p), TrackerModifier.DEFAULT) { t ->
                t.pipeline.viewFilter { viewer -> viewer.uuid() != p.uniqueId } // 본인 1인칭에는 안 보이게
                t.animate("katana_ready", AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build())
            }
        }.orElse(null)
    }

    private fun playLimb(p: Player, motion: String, then: () -> Unit = {}) {
        val t = tracker(p) ?: return
        for (m in TICKS.keys) t.stopAnimation("katana_$m")
        t.animate("katana_$motion", AnimationModifier.builder().start(1).end(1).type(AnimationIterator.Type.PLAY_ONCE).build()) { then() }
    }

    private fun closeLimb(p: Player) {
        if (!hasBetterModel) return
        BetterModel.registry(p.uniqueId).ifPresent { it.tracker(LIMB)?.close() }
    }

    // 막 들었을 때: 칼집에 든 상태
    private fun hold(p: Player) {
        setMain(p, "hold")
        showSaya(p, true)
        closeLimb(p)
        combo[p.uniqueId] = 0
    }

    private fun attack(p: Player) {
        val id = p.uniqueId
        val s = state(p.inventory.itemInMainHand)
        lastAction[id] = tick
        val motion = if (s != null && s != "hold" && s != "sheathe") {
            val k = combo[id] ?: 0
            combo[id] = (k + 1) % COMBO.size
            COMBO[k]
        } else {
            combo[id] = 1
            // 발도: 조금 뒤 칼집이 비어 보이게
            plugin.server.scheduler.runTaskLater(plugin, Runnable { if (saya.containsKey(id)) showSaya(p, false) }, 3L)
            "iai"
        }
        setMain(p, motion, TICKS.getValue(motion))
        playLimb(p, motion)
        p.world.playSound(p.location, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.5f, if (motion == "thrust") 1.9f else 1.5f)
        if (motion == "iai") {
            p.world.playSound(p.location, Sound.ITEM_ARMOR_EQUIP_CHAIN, 0.8f, 1.5f)
            p.world.spawnParticle(Particle.CHERRY_LEAVES, p.location.add(0.0, 1.2, 0.0), 14, 0.6, 0.4, 0.6, 0.0)
        }
    }

    private fun sheathe(p: Player) {
        val n = TICKS.getValue("sheathe")
        setMain(p, "sheathe", n)
        p.world.playSound(p.location, Sound.ENTITY_PLAYER_ATTACK_NODAMAGE, 0.5f, 1.8f)
        playLimb(p, "sheathe") { plugin.server.scheduler.runTask(plugin, Runnable { closeLimb(p) }) }
        // 칼이 칼집에 들어가는 순간: 칼집이 다시 차 보이고 소리
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (isKatana(p.inventory.itemInMainHand) && state(p.inventory.itemInMainHand) == "sheathe") {
                showSaya(p, true)
                p.world.playSound(p.location, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.6f, 2.0f)
            }
        }, (n - 3).toLong())
        // 동작이 끝나면 다시 칼집에 든 상태로
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            if (isKatana(p.inventory.itemInMainHand) && state(p.inventory.itemInMainHand) == "sheathe") setMain(p, "hold")
        }, n.toLong())
    }

    // 5틱마다: 들기 시작 · 내려놓음 감지, 칼집 모델 유지, 오래 안 휘두르면 칼집에 넣기
    private fun scan() {
        for (p in plugin.server.onlinePlayers) {
            val id = p.uniqueId
            val item = p.inventory.itemInMainHand
            if (!isKatana(item)) {
                if (wasHolding.remove(id)) {
                    clearSaya(p)
                    closeLimb(p)
                }
            } else if (!wasHolding.contains(id)) {
                wasHolding.add(id)
                hold(p)
            } else {
                sendSaya(p)
                val s = state(item)
                if (s != null && s != "hold" && s != "sheathe" && !p.hasCooldown(item) && tick - (lastAction[id] ?: tick) >= IDLE_TICKS) {
                    sheathe(p)
                }
            }
        }
    }

    @EventHandler
    fun onSwap(e: PlayerItemHeldEvent) {
        // 다른 칸으로 바꾸면 들고 있던 일본도는 칼집에 든 상태로 되돌림
        val old = e.player.inventory.getItem(e.previousSlot)
        if (isKatana(old) && state(old!!) != "hold") e.player.inventory.setItem(e.previousSlot, withState(old, "hold"))
        wasHolding.remove(e.player.uniqueId)
        clearSaya(e.player)
        closeLimb(e.player)
    }

    @EventHandler
    fun onSwing(e: PlayerAnimationEvent) {
        if (e.animationType != PlayerAnimationType.ARM_SWING) return
        val p = e.player
        if (!isKatana(p.inventory.itemInMainHand) || !wasHolding.contains(p.uniqueId)) return
        if (tick - (lastAction[p.uniqueId] ?: -100L) >= 3L) attack(p)
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        val id = e.player.uniqueId
        lastAction.remove(id)
        combo.remove(id)
        saya.remove(id)
        wasHolding.remove(id)
        closeLimb(e.player)
    }
}
