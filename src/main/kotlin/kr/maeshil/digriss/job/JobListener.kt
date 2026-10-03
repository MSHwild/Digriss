package kr.maeshil.digriss.job

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.manager.SoulManager
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityResurrectEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent

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
                if (e.clickedInventory != e.view.topInventory) return
                if (e.rawSlot == JOB_BACK_SLOT) return kr.maeshil.digriss.menu.MainMenu.back(digriss(), player)
                val clicked = e.currentItem ?: return
                val job = jobManager.getJobFromItem(clicked) ?: return

                if (soulManager.getSouls(player) < JOB_PURCHASE_COST) {
                    player.sendMessage("§c영혼이 부족합니다. (필요: $JOB_PURCHASE_COST)")
                    Sounds.fail(player)
                    return
                }
                soulManager.removeSouls(player, JOB_PURCHASE_COST.toLong())
                player.inventory.addItem(jobManager.createJobItem(job))
                Sounds.purchase(player)
                player.sendMessage("§a${job.displayName} 직업 아이템을 구매했습니다. /직업설정 으로 장착하세요.")
                player.closeInventory()
            }

            is JobConfirmHolder -> {
                // 상단 인벤토리 클릭인데 중앙 슬롯이 아니면 막음 (테두리 등 조작 방지)
                val topClicked = e.clickedInventory == e.view.topInventory
                if (topClicked && e.rawSlot == JOB_BACK_SLOT) {
                    e.isCancelled = true
                    return kr.maeshil.digriss.menu.MainMenu.back(digriss(), player) // 창이 닫히면서 아래 onClose가 직업을 확정함
                }
                if (topClicked && e.rawSlot != JOB_CONFIRM_SLOT) {
                    e.isCancelled = true
                    return
                }
                // 직업 아이템이 아닌 것은 직업 칸에 넣을 수 없음 (닫을 때 사라지던 문제)
                val incoming = when {
                    topClicked -> e.cursor.takeIf { !it.type.isAir }
                        ?: if (e.click == org.bukkit.event.inventory.ClickType.NUMBER_KEY) player.inventory.getItem(e.hotbarButton) else null
                    e.isShiftClick -> e.currentItem
                    else -> null
                }
                if (incoming != null && !incoming.type.isAir && jobManager.getJobFromItem(incoming) == null) {
                    e.isCancelled = true
                    player.sendMessage("§c직업 아이템만 넣을 수 있습니다.")
                    Sounds.fail(player)
                }
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is JobPurchaseHolder) e.isCancelled = true
        // 직업 확정 창: 위쪽 칸으로 끌어 넣는 건 직업 아이템을 직업 칸에 넣을 때만 허용
        if (holder is JobConfirmHolder) {
            val topSlots = e.rawSlots.filter { it < e.view.topInventory.size }
            if (topSlots.isNotEmpty() && (topSlots.any { it != JOB_CONFIRM_SLOT } || jobManager.getJobFromItem(e.oldCursor) == null)) {
                e.isCancelled = true
            }
        }
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder
        if (holder !is JobConfirmHolder) return

        val player = e.player as? Player ?: return
        var item = e.inventory.getItem(JOB_CONFIRM_SLOT)
        // 혹시 다른 아이템이 들어 있으면 사라지지 않게 돌려줌
        if (item != null && !item.type.isAir && jobManager.getJobFromItem(item) == null) {
            giveBack(player, item)
            item = null
        }
        // 직업 아이템을 여러 개 겹쳐 넣었으면 1개만 쓰고 나머지는 돌려줌
        if (item != null && item.amount > 1) giveBack(player, item.clone().apply { amount = item.amount - 1 })
        val job = jobManager.getJobFromItem(item)

        if (job != null) {
            jobManager.setJob(player.uniqueId, job)
            player.sendMessage("§a${job.displayName} 직업으로 확정되었습니다.")
            Sounds.equip(player)
        } else {
            // 중앙 슬롯이 비어있으면 직업 해제
            if (jobManager.hasJob(player.uniqueId)) {
                jobManager.removeJob(player.uniqueId)
                player.sendMessage("§c직업이 해제되었습니다.")
                Sounds.click(player)
            }
        }
    }

    private fun giveBack(player: Player, item: ItemStack) {
        player.inventory.addItem(item).values.forEach { player.world.dropItemNaturally(player.location, it) }
    }

    private fun digriss() = org.bukkit.Bukkit.getPluginManager().getPlugin("Digriss") as kr.maeshil.digriss.Digriss

    // ───────────── 직업 아이템은 직업 장착용일 뿐, 무기/도구/방패/토템으로 쓰이지 않게 ─────────────

    // 직업 아이템으로 때리면 맨손 피해 (암살자 네더라이트 검 등)
    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    fun onJobItemAttack(e: EntityDamageByEntityEvent) {
        val attacker = e.damager as? Player ?: return
        if (jobManager.getJobFromItem(attacker.inventory.itemInMainHand) == null) return
        e.damage = 1.0
    }

    // 방패 들기, 망원경 보기 등 우클릭 사용 막기
    @EventHandler(ignoreCancelled = false)
    fun onJobItemUse(e: PlayerInteractEvent) {
        if (e.action != Action.RIGHT_CLICK_AIR && e.action != Action.RIGHT_CLICK_BLOCK) return
        if (jobManager.getJobFromItem(e.item) == null) return
        e.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
    }

    // 직업 토템으로 부활 막기
    @EventHandler(ignoreCancelled = true)
    fun onJobTotem(e: EntityResurrectEvent) {
        val player = e.entity as? Player ?: return
        val hand = e.hand ?: return
        if (jobManager.getJobFromItem(player.inventory.getItem(hand)) != null) e.isCancelled = true
    }

    // 직업 곡괭이로 블록 캐기 막기
    @EventHandler(ignoreCancelled = true)
    fun onJobItemBreak(e: BlockBreakEvent) {
        if (jobManager.getJobFromItem(e.player.inventory.itemInMainHand) == null) return
        e.isCancelled = true
        kr.maeshil.digriss.ActionBarManager.showTemp(e.player, "§c직업 아이템으로는 블록을 캘 수 없습니다.", 1.5)
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