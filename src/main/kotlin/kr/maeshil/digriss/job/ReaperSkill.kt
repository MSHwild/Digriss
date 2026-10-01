package kr.maeshil.digriss.job

import kr.maeshil.digriss.jobManager.ReaperChargeManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType

class ReaperSkill : JobSkill {
    override val baseCooldownSeconds = 20
    private val invulnDurationSeconds = 2.0

    override fun execute(player: Player) {
        if (!ReaperChargeManager.isCharged(player.uniqueId)) {
            player.sendMessage("§c충전된 영혼이 없습니다. 처치 후 영혼 구슬을 흡수하세요.")
            return
        }

        ReaperChargeManager.consumeCharge(player.uniqueId)

        val plugin = Bukkit.getPluginManager().getPlugin("Digriss")!!
        val durationTicks = (invulnDurationSeconds * 20).toLong()

        player.isInvulnerable = true
        player.addPotionEffect(PotionEffect(PotionEffectType.INVISIBILITY, durationTicks.toInt() + 1, 0, false, false))
        player.addPotionEffect(PotionEffect(PotionEffectType.SPEED, durationTicks.toInt() + 1, 1, false, false))
        player.sendMessage("§b무체화 발동! (${invulnDurationSeconds}초)")

        // 드랍 취소로 인한 아이템 자동 복구가 끝난 다음 틱에 캡처해야 정확함
        Bukkit.getScheduler().runTask(plugin, Runnable {
            val heldSlot = player.inventory.heldItemSlot
            val mainHand = player.inventory.getItem(heldSlot)?.clone() ?: ItemStack(Material.AIR)
            val offHand = player.inventory.itemInOffHand.clone()
            val armor = player.inventory.armorContents.map { it?.clone() }.toTypedArray()

            player.inventory.setItem(heldSlot, ItemStack(Material.AIR))
            player.inventory.setItemInOffHand(ItemStack(Material.AIR))
            player.inventory.armorContents = arrayOfNulls(4)

            Bukkit.getScheduler().runTaskLater(plugin, Runnable {
                player.isInvulnerable = false
                player.inventory.setItem(heldSlot, mainHand)
                player.inventory.setItemInOffHand(offHand)
                player.inventory.armorContents = armor
                player.sendMessage("§7무체화가 종료되었습니다.")
            }, durationTicks - 1) // 이미 1틱 지난 만큼 보정
        })
    }
}