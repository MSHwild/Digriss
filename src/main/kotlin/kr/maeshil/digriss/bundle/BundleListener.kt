package kr.maeshil.digriss.bundle

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.BundleManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent

class BundleListener(private val plugin: Digriss) : Listener {

    private val bundleManager get() = plugin.bundleManager

    // 목록 / 미리보기: 아이템을 가져갈 수 없게 모든 클릭 취소
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder
        if (holder !is BundleListHolder && holder !is BundlePreviewHolder) return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return

        when (holder) {
            is BundleListHolder -> {
                val bundle = holder.slots[e.rawSlot]?.let { bundleManager.get(it) } ?: return
                later { BundleGUI.openPreview(player, plugin, bundle) }
            }
            is BundlePreviewHolder -> when (e.rawSlot) {
                BundleGUI.PREVIEW_BACK_SLOT -> later { BundleGUI.openList(player, plugin) }
                BundleGUI.PREVIEW_BUY_SLOT -> {
                    val bundle = bundleManager.get(holder.name)
                    if (bundle == null) {
                        player.sendMessage("§c[번들] 삭제된 번들입니다.")
                        later { BundleGUI.openList(player, plugin) }
                        return
                    }
                    when (val result = bundleManager.buy(player, bundle)) {
                        is BundleManager.Result.Success -> {
                            player.sendMessage("§a[번들] '${bundle.name}'을(를) ${bundle.price} DC에 구매했습니다!")
                            player.playSound(player.location, Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.2f)
                            later { BundleGUI.openPreview(player, plugin, bundle) } // 보유 DC 갱신
                        }
                        is BundleManager.Result.Fail -> {
                            player.sendMessage("§c[번들] ${result.reason}")
                            player.playSound(player.location, Sound.ENTITY_VILLAGER_NO, 1f, 1f)
                        }
                    }
                }
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        val holder = e.view.topInventory.holder
        if (holder is BundleListHolder || holder is BundlePreviewHolder) e.isCancelled = true
    }

    // 편집 창을 닫으면 안에 든 아이템을 번들에 저장
    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        val holder = e.inventory.holder as? BundleEditHolder ?: return
        val player = e.player as? Player ?: return
        bundleManager.stopEditing(holder.name)

        val items = e.inventory.contents.filterNotNull().filter { it.type != Material.AIR }

        if (holder.isNew) {
            if (items.isEmpty()) {
                player.sendMessage("§c[번들] 아이템이 없어 '${holder.name}' 번들 생성을 취소했습니다.")
                return
            }
            bundleManager.create(holder.name, holder.price, holder.days, items)
            val period = if (holder.days > 0) "${holder.days}일" else "무기한"
            plugin.adminLogManager.log(player, "번들 생성 → ${holder.name} (${holder.price} DC, $period, ${items.size}칸)")
            player.sendMessage("§a[번들] '${holder.name}' 번들을 만들었습니다. §7(${holder.price} DC, 판매 기간 $period, 아이템 ${items.size}칸)")
            return
        }

        if (bundleManager.get(holder.name) == null) {
            player.sendMessage("§c[번들] 편집 중에 '${holder.name}' 번들이 삭제되어 저장하지 않았습니다.")
            return
        }
        if (items.isEmpty()) {
            player.sendMessage("§c[번들] 아이템이 없어 저장하지 않았습니다. 지우려면 /번들삭제 ${holder.name}")
            return
        }
        bundleManager.updateItems(holder.name, items)
        plugin.adminLogManager.log(player, "번들 수정 → ${holder.name} (${items.size}칸)")
        player.sendMessage("§a[번들] '${holder.name}' 번들 아이템을 저장했습니다. §7(${items.size}칸)")
    }

    // 클릭 이벤트 안에서 바로 인벤토리를 다시 열면 문제가 생길 수 있어 다음 틱에 엶
    private fun later(action: () -> Unit) {
        Bukkit.getScheduler().runTask(plugin, Runnable { action() })
    }
}
