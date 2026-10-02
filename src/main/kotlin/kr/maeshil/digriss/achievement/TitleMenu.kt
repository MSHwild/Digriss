package kr.maeshil.digriss.achievement

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.enchantments.Enchantment
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class TitleMenuHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /칭호 (/업적): 업적 목록 + 달성한 업적의 칭호 장착
class TitleMenu(private val plugin: Digriss) : CommandExecutor, Listener {

    private val UNEQUIP_SLOT = 49
    private val achievements get() = plugin.achievementManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        open(player)
        Sounds.open(player)
        return true
    }

    fun open(player: Player) {
        val uuid = player.uniqueId
        val inv = Bukkit.createInventory(TitleMenuHolder(), 54,
            "§8업적 · 칭호 §7(${achievements.unlockedCount(uuid)}/${Achievement.entries.size})")
        val equipped = achievements.equippedOf(uuid)

        Achievement.entries.take(45).forEachIndexed { i, a ->
            val unlocked = achievements.has(uuid, a)
            val base = if (unlocked) plugin.iconManager.get("achievement.${a.name.lowercase()}", a.icon)
            else plugin.iconManager.get("achievement.locked", Material.GRAY_DYE)
            val lore = mutableListOf(
                "§7${a.description}",
                "",
                "§7칭호: ${a.title}",
                ""
            )
            lore += when {
                !unlocked -> "§8🔒 미달성"
                a == equipped -> "§a★ 장착 중"
                else -> "§e클릭: 칭호 장착"
            }
            inv.setItem(i, item(base, if (unlocked) "§6§l${a.displayName}" else "§7${a.displayName}", lore, glow = a == equipped))
        }

        val filler = item(plugin.iconManager.get("common.filler", Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(UNEQUIP_SLOT, item(plugin.iconManager.get("achievement.unequip", Material.BARRIER), "§c칭호 해제",
            listOf("§7채팅에 칭호를 표시하지 않습니다.")))
        player.openInventory(inv)
    }

    private fun item(base: ItemStack, name: String, lore: List<String>, glow: Boolean = false): ItemStack {
        val meta = base.itemMeta ?: return base
        meta.setDisplayName(name)
        meta.lore = lore
        if (glow) meta.addEnchant(Enchantment.UNBREAKING, 1, true)
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        base.itemMeta = meta
        return base
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        if (e.view.topInventory.holder !is TitleMenuHolder) return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return

        if (e.rawSlot == UNEQUIP_SLOT) {
            achievements.equip(player.uniqueId, null)
            player.sendMessage("§7칭호를 해제했습니다.")
            Sounds.click(player)
            reopen(player)
            return
        }
        val a = Achievement.entries.getOrNull(e.rawSlot) ?: return
        if (!achievements.has(player.uniqueId, a)) {
            player.sendMessage("§c아직 달성하지 않은 업적입니다. §7(${a.description})")
            Sounds.fail(player)
            return
        }
        achievements.equip(player.uniqueId, a)
        player.sendMessage("§a칭호 ${a.title} §a을(를) 장착했습니다.")
        Sounds.equip(player)
        reopen(player)
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is TitleMenuHolder) e.isCancelled = true
    }

    private fun reopen(player: Player) {
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) open(player) })
    }
}
