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
    private val BACK_SLOT = 45
    private val achievements get() = plugin.achievementManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        // 관리자: /칭호 지급|회수 <닉네임> <칭호>
        if (args.isNotEmpty() && (args[0] == "지급" || args[0] == "회수")) {
            if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.").let { true }
            if (args.size < 3) return sender.sendMessage("§e사용법: /칭호 ${args[0]} <닉네임> <칭호> §7(예: /칭호 지급 철수 베타테스터)").let { true }
            val target = Bukkit.getOfflinePlayer(args[1])
            if (!target.hasPlayedBefore() && !target.isOnline) return sender.sendMessage("§c'${args[1]}' 플레이어를 찾을 수 없습니다.").let { true }
            val a = Achievement.find(args.drop(2).joinToString(" "))
                ?: return sender.sendMessage("§c그런 칭호가 없습니다. §7(${Achievement.entries.joinToString(", ") { it.displayName.replace(" ", "") }})").let { true }
            if (args[0] == "지급") {
                if (achievements.has(target.uniqueId, a)) return sender.sendMessage("§c${target.name}님은 이미 ${a.title}§c 칭호가 있습니다.").let { true }
                achievements.unlock(target, a)
                plugin.adminLogManager.log(sender, "칭호 지급 → ${target.name} ${a.displayName}")
                sender.sendMessage("§a${target.name}님에게 ${a.title} §a칭호를 지급했습니다.")
            } else {
                if (!achievements.revoke(target.uniqueId, a)) return sender.sendMessage("§c${target.name}님은 ${a.title}§c 칭호가 없습니다.").let { true }
                plugin.adminLogManager.log(sender, "칭호 회수 → ${target.name} ${a.displayName}")
                sender.sendMessage("§a${target.name}님의 ${a.title} §a칭호를 회수했습니다.")
            }
            return true
        }

        val player = sender as? Player ?: return true
        open(player)
        Sounds.open(player)
        return true
    }

    fun open(player: Player) {
        val uuid = player.uniqueId
        val inv = Bukkit.createInventory(TitleMenuHolder(), 54,
            "§8업적 · 칭호 §7(${achievements.unlockedCount(uuid)}/${shown(uuid).size})")
        val equipped = achievements.equippedOf(uuid)

        shown(uuid).forEachIndexed { i, a ->
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
                !unlocked -> "§8미달성"
                a == equipped -> "§a장착 중"
                else -> "§e클릭: 칭호 장착"
            }
            inv.setItem(i, item(base, if (unlocked) "§6§l${a.displayName}" else "§7${a.displayName}", lore, glow = a == equipped))
        }

        val filler = item(plugin.iconManager.get("common.filler", Material.GRAY_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(BACK_SLOT, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        inv.setItem(UNEQUIP_SLOT, item(plugin.iconManager.get("achievement.unequip", Material.BARRIER), "§c칭호 해제",
            listOf("§7채팅에 칭호를 표시하지 않습니다.")))
        player.openInventory(inv)
    }

    // 목록에 보여줄 업적: 일반 업적 전부 + 운영자 지급 칭호는 가진 경우에만
    private fun shown(uuid: java.util.UUID): List<Achievement> =
        Achievement.entries.filter { !it.manual || achievements.has(uuid, it) }.take(45)

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

        if (e.rawSlot == BACK_SLOT) return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
        if (e.rawSlot == UNEQUIP_SLOT) {
            achievements.equip(player.uniqueId, null)
            player.sendMessage("§7칭호를 해제했습니다.")
            Sounds.click(player)
            reopen(player)
            return
        }
        val a = shown(player.uniqueId).getOrNull(e.rawSlot) ?: return
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
