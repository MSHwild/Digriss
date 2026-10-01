package kr.maeshil.digriss.job

import kr.maeshil.digriss.manager.SoulManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent

class JobListener(
    private val jobManager: JobManager,
    private val soulManager: SoulManager
) : Listener {

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.inventory.holder
        val player = e.whoClicked as? Player ?: return

        when (holder) {
            is JobPurchaseHolder -> {
                e.isCancelled = true
                val clicked = e.currentItem ?: return
                val job = jobManager.getJobFromItem(clicked) ?: return

                if (soulManager.getSouls(player) < JOB_PURCHASE_COST) {
                    player.sendMessage("§c영혼이 부족합니다. (필요: $JOB_PURCHASE_COST)")
                    return
                }
                soulManager.removeSouls(player, JOB_PURCHASE_COST.toLong())
                player.inventory.addItem(jobManager.createJobItem(job))
                player.sendMessage("§a${job.displayName} 직업 아이템을 구매했습니다. /직업설정 으로 장착하세요.")
                player.closeInventory()
            }

            is JobConfirmHolder -> {
                // 상단 인벤토리 클릭인데 중앙 슬롯이 아니면 막음 (테두리 등 조작 방지)
                val topClicked = e.clickedInventory == e.view.topInventory
                if (topClicked && e.rawSlot != JOB_CONFIRM_SLOT) {
                    e.isCancelled = true
                }
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder
        if (holder !is JobConfirmHolder) return

        val player = e.player as? Player ?: return
        val item = e.inventory.getItem(JOB_CONFIRM_SLOT)
        val job = jobManager.getJobFromItem(item)

        if (job != null) {
            jobManager.setJob(player.uniqueId, job)
            player.sendMessage("§a${job.displayName} 직업으로 확정되었습니다.")
        } else {
            // 중앙 슬롯이 비어있으면 직업 해제
            if (jobManager.hasJob(player.uniqueId)) {
                jobManager.removeJob(player.uniqueId)
                player.sendMessage("§c직업이 해제되었습니다.")
            }
        }
    }

    @EventHandler
    fun onBlockPlace(e: BlockPlaceEvent) {
        val item = e.itemInHand
        if (jobManager.getJobFromItem(item) != null) {
            e.isCancelled = true
            e.player.sendMessage("§c직업 아이템은 설치할 수 없습니다.")
        }
    }
}