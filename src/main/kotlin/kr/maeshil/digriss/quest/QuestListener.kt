package kr.maeshil.digriss.quest

import kr.maeshil.digriss.manager.QuestManager
import org.bukkit.Bukkit
import org.bukkit.entity.Enemy
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.plugin.java.JavaPlugin

class QuestListener(private val plugin: JavaPlugin, private val questManager: QuestManager) : Listener {

    // 접속 시 오늘 퀘스트 갱신
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        questManager.ensureToday(e.player)
    }

    // 몬스터 처치 (스포너/시련의 스포너 몹 제외)
    @EventHandler(ignoreCancelled = true)
    fun onEntityDeath(e: EntityDeathEvent) {
        val entity = e.entity
        if (entity !is Enemy) return
        val killer = entity.killer ?: return
        val reason = entity.entitySpawnReason
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER || reason == CreatureSpawnEvent.SpawnReason.TRIAL_SPAWNER) return

        questManager.addProgress(killer, QuestType.MOB_KILL)
    }

    // 퀘스트 GUI: 모든 클릭 취소 (아이템을 가져갈 수 없음), 리롤만 처리
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? QuestHolder ?: return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return

        when (e.rawSlot) {
            QuestGUI.REROLL_SLOT -> {
                if (questManager.getData(player).rerolled) return
                reopen(player, !holder.rerollMode)
            }
            in QuestGUI.QUEST_SLOTS -> {
                if (!holder.rerollMode) return
                questManager.reroll(player, QuestGUI.QUEST_SLOTS.indexOf(e.rawSlot))
                reopen(player, false)
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is QuestHolder) e.isCancelled = true
    }

    // 클릭 이벤트 안에서 바로 인벤토리를 다시 열면 문제가 생길 수 있어 다음 틱에 엶
    private fun reopen(player: Player, rerollMode: Boolean) {
        Bukkit.getScheduler().runTask(plugin, Runnable { QuestGUI.open(player, questManager, rerollMode) })
    }
}
