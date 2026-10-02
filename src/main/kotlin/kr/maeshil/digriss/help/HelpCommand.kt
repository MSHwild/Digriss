package kr.maeshil.digriss.help

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.HelpCategory
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

class HelpHolder(val slots: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// /도움말 (GUI), /도움말 <카테고리> (채팅), /도움말 리로드 (관리자)
class HelpCommand(private val plugin: Digriss) : CommandExecutor, TabCompleter, Listener {

    private val help get() = plugin.helpManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isNotEmpty()) {
            val arg = args.joinToString(" ")
            if (arg == "리로드" && sender.hasPermission("digriss.admin")) {
                help.load()
                sender.sendMessage("§a[도움말] help.yml을 다시 불러왔습니다. (카테고리 ${help.categories.size}개)")
                return true
            }
            val category = help.find(arg)
            if (category == null) {
                sender.sendMessage("§c'$arg' 도움말이 없습니다. §7(${help.categories.joinToString(", ") { it.name }})")
                return true
            }
            show(sender, category)
            return true
        }

        if (sender is Player) { openGUI(sender); Sounds.open(sender) }
        else help.categories.forEach { sender.sendMessage("- ${it.name}") }
        return true
    }

    private fun show(sender: CommandSender, category: HelpCategory) {
        if (sender is Player) Sounds.open(sender)
        sender.sendMessage("")
        category.lines.forEach { sender.sendMessage(it) }
    }

    // ───────────────────────── GUI ─────────────────────────

    private fun openGUI(player: Player) {
        val categories = help.categories
        val slots = HashMap<Int, String>()
        val used = categories.mapNotNull { it.slot }.toMutableSet()
        var next = 0
        categories.forEach { c ->
            // slot이 없거나 범위를 벗어나면 비어 있는 앞 칸에 배치
            val slot = c.slot?.takeIf { it in 0 until 27 } ?: run {
                while (next in used) next++
                next.also { used.add(it) }
            }
            if (slot < 27) slots[slot] = c.name
        }

        val inv = Bukkit.createInventory(HelpHolder(slots), 27, "§8도움말")
        val filler = item(ItemStack(org.bukkit.Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 0 until inv.size) inv.setItem(i, filler)
        slots.forEach { (slot, name) ->
            val c = help.find(name) ?: return@forEach
            inv.setItem(slot, item(ItemStack(c.icon), "§6§l${c.name}", listOf(c.summary, "", "§e클릭하여 보기")))
        }
        player.openInventory(inv)
    }

    private fun item(stack: ItemStack, name: String, lore: List<String>): ItemStack {
        val meta = stack.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        stack.itemMeta = meta
        return stack
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? HelpHolder ?: return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return
        val category = holder.slots[e.rawSlot]?.let { help.find(it) } ?: return
        player.closeInventory()
        show(player, category)
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is HelpHolder) e.isCancelled = true
    }

    // 처음 접속한 유저에게 안내 (다른 입장 메시지에 묻히지 않게 3초 뒤)
    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val player = e.player
        if (player.hasPlayedBefore() || help.firstJoin.isEmpty()) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            help.firstJoin.forEach { player.sendMessage(it) }
            Sounds.bigReward(player)
        }, 60L)
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val options = help.categories.map { it.name } + if (sender.hasPermission("digriss.admin")) listOf("리로드") else emptyList()
        return options.filter { it.startsWith(args[0]) }
    }
}
