package kr.maeshil.digriss.alliance

import kr.maeshil.digriss.addon.GuiBg

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

// slotNations: 슬롯 번호 -> 그 칸에 표시된 국가 이름
class AllianceHolder(val slotNations: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

object AllianceGUI {

    fun open(player: Player, plugin: Digriss) {
        val myNation = plugin.nationManager.getNationName(player.uniqueId)
        if (myNation == null) {
            player.sendMessage("§c소속된 국가가 없습니다.")
            return
        }
        val alliance = plugin.allianceManager
        val isLeader = Nation.nations[myNation]?.leader == player.uniqueId

        // 연합 중 → 요청 받음 → 나머지 순으로 정렬, 최대 45개국 표시
        val others = Nation.nations.keys.filter { it != myNation }
            .sortedWith(compareBy({ !alliance.areAllied(myNation, it) }, { !alliance.hasRequest(myNation, it) }, { it }))
            .take(45)

        val slotNations = others.withIndex().associate { it.index to it.value }
        val inv = GuiBg.createInventory(AllianceHolder(slotNations), 54, "§8연합 관리 §7- $myNation")

        slotNations.forEach { (slot, name) ->
            val n = Nation.nations[name] ?: return@forEach
            val info = listOf("§7국가원 §f${n.members.size}명 §8| §7영토 §f${n.claims.size}개 §8| §7Lv.${n.level}", "")
            val item = when {
                alliance.areAllied(myNation, name) -> item(icon(plugin, "alliance.allied", Material.LIGHT_BLUE_BANNER), "§b§l$name §7(연합국)",
                    info + listOf("§b연합 중", if (isLeader) "§c쉬프트+클릭: 연합 해제" else ""))
                plugin.nationManager.isAtWarBetween(myNation, name) -> item(icon(plugin, "alliance.at_war", Material.RED_BANNER), "§4§l$name §7(전쟁 중)",
                    info + listOf("§c전쟁 중인 국가와는 연합할 수 없습니다."))
                alliance.hasRequest(myNation, name) -> item(icon(plugin, "alliance.request_received", Material.YELLOW_BANNER), "§e§l$name §7(연합 요청 받음)",
                    info + listOf("§e이 국가가 연합을 요청했습니다!", if (isLeader) "§a좌클릭: 수락 §8| §c우클릭: 거절" else ""))
                alliance.hasRequest(name, myNation) -> item(icon(plugin, "alliance.request_sent", Material.GRAY_BANNER), "§7§l$name §7(요청 보냄)",
                    info + listOf("§7상대의 수락을 기다리는 중입니다.", if (isLeader) "§c우클릭: 요청 취소" else ""))
                else -> item(icon(plugin, "alliance.neutral", Material.WHITE_BANNER), "§f§l$name",
                    info + listOf(if (isLeader) "§a클릭: 연합 요청" else "§7국가 지도자만 연합을 요청할 수 있습니다."))
            }
            inv.setItem(slot, item)
        }

        val filler = item(icon(plugin, "common.filler_dark", Material.BLACK_STAINED_GLASS_PANE), " ")
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(45, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        inv.setItem(49, item(icon(plugin, "alliance.info", Material.BOOK), "§b§l연합 안내",
            "§7연합국끼리는 서로 공격할 수 없고",
            "§7전쟁을 선포할 수 없습니다.",
            "§7연합국 국가원은 이름이 §b하늘색§7으로 보입니다.",
            "",
            "§7연합은 국가당 최대 ${kr.maeshil.digriss.manager.AllianceManager.MAX_ALLIES}개국까지 맺을 수 있습니다.",
            "",
            "§7현재 연합국 (${alliance.alliesOf(myNation).size}/${kr.maeshil.digriss.manager.AllianceManager.MAX_ALLIES}): §b${alliance.alliesOf(myNation).ifEmpty { listOf("없음") }.joinToString(", ")}"))

        player.openInventory(inv)
    }

    private fun item(material: Material, name: String, vararg lore: String): ItemStack = item(material, name, lore.toList())

    private fun icon(plugin: Digriss, key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(material: Material, name: String, lore: List<String>): ItemStack = item(ItemStack(material), name, lore)

    private fun item(base: ItemStack, name: String, vararg lore: String): ItemStack = item(base, name, lore.toList())

    private fun item(base: ItemStack, name: String, lore: List<String>): ItemStack {
        val item = base
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore.dropLastWhile { it.isEmpty() }
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        item.itemMeta = meta
        return item
    }
}
