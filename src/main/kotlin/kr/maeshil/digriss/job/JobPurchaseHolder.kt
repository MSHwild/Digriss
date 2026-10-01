package kr.maeshil.digriss.job

import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta

const val JOB_PURCHASE_TITLE = "§8직업 구매"
const val JOB_CONFIRM_TITLE = "§8직업 확정"
const val JOB_PURCHASE_COST = 100
const val JOB_CONFIRM_SLOT = 13

class JobPurchaseHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

class JobConfirmHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

object JobGUI {

    private val JOB_SLOTS = listOf(10, 11, 12, 13, 14, 15, 16)

    private fun filler(): ItemStack {
        val item = ItemStack(Material.GRAY_STAINED_GLASS_PANE)
        val meta: ItemMeta = item.itemMeta
        meta.setDisplayName(" ")
        item.itemMeta = meta
        return item
    }

    private fun fillBorder(inv: Inventory) {
        for (i in 0 until inv.size) {
            if (i < 9 || i >= inv.size - 9 || i % 9 == 0 || i % 9 == 8) {
                inv.setItem(i, filler())
            }
        }
    }

    fun openPurchase(player: Player, jobManager: JobManager) {
        val inv = Bukkit.createInventory(JobPurchaseHolder(), 27, JOB_PURCHASE_TITLE)
        fillBorder(inv)
        JobType.entries.forEachIndexed { i, job ->
            if (i < JOB_SLOTS.size) {
                inv.setItem(JOB_SLOTS[i], jobManager.createJobItem(job))
            }
        }
        player.openInventory(inv)
    }

    fun openConfirm(player: Player, jobManager: JobManager) {
        val inv = Bukkit.createInventory(JobConfirmHolder(), 27, JOB_CONFIRM_TITLE)

        for (i in 0 until inv.size) {
            if (i != JOB_CONFIRM_SLOT) {
                inv.setItem(i, filler())
            }
        }

        val current = jobManager.getJob(player.uniqueId)
        if (current != null) {
            inv.setItem(JOB_CONFIRM_SLOT, jobManager.createJobItem(current))
        }
        player.openInventory(inv)
    }
}