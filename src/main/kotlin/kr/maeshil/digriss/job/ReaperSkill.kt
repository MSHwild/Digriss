package kr.maeshil.digriss.job

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.jobManager.ReaperChargeManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.util.UUID

class ReaperSkill : JobSkill {
    override val baseCooldownSeconds = 20
    private val invulnDurationSeconds = 2.0

    override fun execute(player: Player): Boolean {
        if (!ReaperChargeManager.isCharged(player.uniqueId)) {
            player.sendMessage("§c충전된 영혼이 없습니다. 처치 후 영혼 구슬을 흡수하세요.")
            return false
        }

        ReaperChargeManager.consumeCharge(player.uniqueId)

        val plugin = Bukkit.getPluginManager().getPlugin("Digriss")!!
        val durationTicks = (invulnDurationSeconds * 20).toLong()
        val uuid = player.uniqueId

        player.isInvulnerable = true
        active.add(uuid)
        endsAt[uuid] = System.currentTimeMillis() + (invulnDurationSeconds * 1000).toLong()
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, durationTicks.toInt() + 1, 0, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, durationTicks.toInt() + 1, 1, false, false))
        Sounds.play(player, org.bukkit.Sound.ENTITY_VEX_CHARGE, 1f, 0.6f)
        player.sendMessage("§b무체화 발동! (${invulnDurationSeconds}초)")

        // 드랍 취소로 인한 아이템 자동 복구가 끝난 다음 틱에 캡처해야 정확함
        Bukkit.getScheduler().runTask(plugin, Runnable {
            // 그 1틱 사이에 나갔거나 이미 복구됐으면 장비를 건드리지 않음
            if (!player.isOnline || uuid !in active) return@Runnable

            val heldSlot = player.inventory.heldItemSlot
            val stash = Stash(
                heldSlot,
                player.inventory.getItem(heldSlot)?.clone() ?: ItemStack(Material.AIR),
                player.inventory.itemInOffHand.clone(),
                player.inventory.armorContents.map { it?.clone() }.toTypedArray()
            )

            player.inventory.setItem(heldSlot, ItemStack(Material.AIR))
            player.inventory.setItemInOffHand(ItemStack(Material.AIR))
            player.inventory.armorContents = arrayOfNulls(4)

            stash.taskId = Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                restore(player)
                player.sendMessage("§7무체화가 종료되었습니다.")
                Sounds.play(player, org.bukkit.Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 1.2f)
            }, durationTicks - 1).taskId // 이미 1틱 지난 만큼 보정
            stashes[uuid] = stash
        })
        return true
    }

    private class Stash(
        val heldSlot: Int,
        val mainHand: ItemStack,
        val offHand: ItemStack,
        val armor: Array<ItemStack?>,
        var taskId: Int = -1
    )

    companion object {
        private val active = HashSet<UUID>()
        private val endsAt = HashMap<UUID, Long>()
        private val stashes = HashMap<UUID, Stash>()

        // 무체화 남은 시간(초), 무체화 중이 아니면 null
        fun remainingSeconds(uuid: UUID): Double? {
            if (uuid !in active) return null
            val remain = (endsAt[uuid] ?: return null) - System.currentTimeMillis()
            return (remain / 1000.0).coerceAtLeast(0.0)
        }

        // 무체화 해제 + 빼둔 장비 복구 (종료 타이머, 접속 종료, 플러그인 종료 시 호출)
        fun restore(player: Player) {
            if (!active.remove(player.uniqueId)) return
            endsAt.remove(player.uniqueId)
            player.isInvulnerable = false

            val stash = stashes.remove(player.uniqueId) ?: return
            if (stash.taskId != -1) Bukkit.getScheduler().cancelTask(stash.taskId)

            // 무체화 중에 그 칸에 들어온 아이템(주운 것, 새로 낀 갑옷)은 덮어쓰지 않고 인벤토리로 돌려줌
            val inv = player.inventory
            val displaced = ArrayList<ItemStack>()
            inv.getItem(stash.heldSlot)?.takeIf { !it.type.isAir }?.let { displaced += it }
            inv.itemInOffHand.takeIf { !it.type.isAir }?.let { displaced += it }
            inv.armorContents.filterNotNull().filter { !it.type.isAir }.forEach { displaced += it }

            inv.setItem(stash.heldSlot, stash.mainHand)
            inv.setItemInOffHand(stash.offHand)
            inv.armorContents = stash.armor
            inv.addItem(*displaced.toTypedArray()).values.forEach { player.world.dropItemNaturally(player.location, it) }
        }

        fun restoreAll() {
            Bukkit.getOnlinePlayers().forEach { restore(it) }
        }
    }
}
