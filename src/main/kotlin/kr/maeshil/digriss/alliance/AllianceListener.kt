package kr.maeshil.digriss.alliance

import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent

// /연합
class AllianceCommand(private val plugin: Digriss) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        AllianceGUI.open(player, plugin)
        return true
    }
}

class AllianceListener(private val plugin: Digriss) : Listener {

    // 연합국끼리 공격 차단 (근접, 투사체, 스킬 피해 모두)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(e: EntityDamageByEntityEvent) {
        val victim = e.entity as? Player ?: return
        val attacker = when (val d = e.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        if (attacker == victim) return

        val nations = plugin.nationManager
        if (plugin.allianceManager.areAllied(nations.getNationName(attacker.uniqueId), nations.getNationName(victim.uniqueId))) {
            e.isCancelled = true
            ActionBarManager.showTemp(attacker, "§b🤝 연합국 국가원은 공격할 수 없습니다.", 1.0)
        }
    }

    // GUI: 모든 클릭 취소, 국가 칸만 처리
    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? AllianceHolder ?: return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return
        val target = holder.slotNations[e.rawSlot] ?: return

        val alliance = plugin.allianceManager
        val myNation = plugin.nationManager.getNationName(player.uniqueId) ?: return
        when {
            alliance.areAllied(myNation, target) ->
                if (e.isShiftClick) alliance.breakAlliance(player, target) else return
            alliance.hasRequest(myNation, target) ->
                if (e.isRightClick) alliance.reject(player, target) else alliance.accept(player, target)
            alliance.hasRequest(target, myNation) ->
                if (e.isRightClick) alliance.cancelRequest(player, target) else return
            else -> alliance.request(player, target)
        }
        // 클릭 이벤트 안에서 바로 다시 열지 않고 다음 틱에 새로고침
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) AllianceGUI.open(player, plugin) })
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is AllianceHolder) e.isCancelled = true
    }
}
