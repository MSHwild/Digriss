package kr.maeshil.digriss.quest

import kr.maeshil.digriss.addon.GuiSlots

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.manager.QuestManager
import org.bukkit.Bukkit
import io.papermc.paper.event.player.PlayerTradeEvent
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.Ageable
import org.bukkit.entity.Enemy
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerFishEvent
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

    // 접속 시 오늘 퀘스트 갱신 + 진행 상황 안내 (다른 입장 메시지에 묻히지 않게 2초 뒤)
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        questManager.ensureToday(player)
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            val pd = questManager.getData(player)
            val done = pd.quests.count { it.done }
            val total = pd.quests.size
            val status = if (total > 0 && done == total) "§a모두 완료!" else "§f$done§7/§f$total 완료"
            player.sendMessage("§6[일일 퀘스트] §7오늘 퀘스트 $status §8| §e/퀘스트§7로 확인")
        }, 40L)
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
        if (GuiSlots.rawSlot(e) == QuestGUI.BACK_SLOT) return kr.maeshil.digriss.menu.MainMenu.back(plugin as kr.maeshil.digriss.Digriss, player)

        if (GuiSlots.rawSlot(e) == QuestGUI.REROLL_SLOT || (holder.rerollMode && GuiSlots.rawSlot(e) in QuestGUI.QUEST_SLOTS)) Sounds.click(player)
        when (GuiSlots.rawSlot(e)) {
            QuestGUI.REROLL_SLOT -> {
                if (questManager.getData(player).rerolled) return
                reopen(player, !holder.rerollMode)
            }
            in QuestGUI.QUEST_SLOTS -> {
                if (!holder.rerollMode) return
                questManager.reroll(player, QuestGUI.QUEST_SLOTS.indexOf(GuiSlots.rawSlot(e)))
                reopen(player, false)
            }
        }
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is QuestHolder) e.isCancelled = true
    }

    // ───────────────────────── 생활 퀘스트 (낚시·채굴·농사·거래·네더) ─────────────────────────

    // 직접 설치한 광석을 부수고 다시 설치하는 반복 방지 (서버를 켜 둔 동안만 기억)
    private val placedOres = HashSet<String>()

    private fun blockKey(b: org.bukkit.block.Block) = "${b.world.uid}:${b.x},${b.y},${b.z}"

    private fun isOre(type: Material) = type.name.endsWith("_ORE") || type == Material.ANCIENT_DEBRIS

    private val crops = setOf(Material.WHEAT, Material.CARROTS, Material.POTATOES, Material.BEETROOTS, Material.NETHER_WART)

    @EventHandler(ignoreCancelled = true)
    fun onPlace(e: BlockPlaceEvent) {
        if (!isOre(e.block.type)) return
        if (placedOres.size > 50000) placedOres.clear()
        placedOres.add(blockKey(e.block))
    }

    @EventHandler(ignoreCancelled = true)
    fun onBreak(e: BlockBreakEvent) {
        val player = e.player
        if (player.gameMode == GameMode.CREATIVE) return
        val block = e.block
        val type = block.type

        if (isOre(type)) {
            if (placedOres.remove(blockKey(block))) return // 직접 설치한 광석
            questManager.addProgress(player, QuestType.ORE_MINE)
            return
        }
        if (type in crops) {
            val age = block.blockData as? Ageable ?: return
            if (age.age >= age.maximumAge) questManager.addProgress(player, QuestType.CROP_HARVEST)
        }
    }

    @EventHandler(ignoreCancelled = true)
    fun onFish(e: PlayerFishEvent) {
        if (e.state == PlayerFishEvent.State.CAUGHT_FISH) questManager.addProgress(e.player, QuestType.FISHING)
    }

    @EventHandler(ignoreCancelled = true)
    fun onTrade(e: PlayerTradeEvent) {
        questManager.addProgress(e.player, QuestType.VILLAGER_TRADE)
    }

    @EventHandler
    fun onWorldChange(e: PlayerChangedWorldEvent) {
        if (e.player.world.environment == World.Environment.NETHER) questManager.addProgress(e.player, QuestType.ENTER_NETHER)
    }

    // 클릭 이벤트 안에서 바로 인벤토리를 다시 열면 문제가 생길 수 있어 다음 틱에 엶
    private fun reopen(player: Player, rerollMode: Boolean) {
        Bukkit.getScheduler().runTask(plugin, Runnable { QuestGUI.open(player, questManager, rerollMode) })
    }
}
