package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Digriss
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

class NationStorageHolder(val nationName: String) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /국가창고 + 창고를 닫을 때 저장
class NationStorage(private val plugin: Digriss) : Listener, CommandExecutor {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        plugin.nationStorageManager.open(player)
        return true
    }

    @EventHandler
    fun onClose(e: InventoryCloseEvent) {
        if (e.inventory.holder is NationStorageHolder) plugin.nationStorageManager.save()
    }
}
