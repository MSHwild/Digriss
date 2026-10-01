package kr.maeshil.digriss.command

import kr.maeshil.digriss.ItemAttributeUtil
import kr.maeshil.digriss.util.ItemAttributeStore
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class AttributeCommand(private val store: ItemAttributeStore) : CommandExecutor {

    override fun onCommand(
        sender: CommandSender,
        command: Command,
        label: String,
        args: Array<out String>
    ): Boolean {
        if (sender !is Player) {
            sender.sendMessage("${ChatColor.RED}이 명령어는 플레이어만 사용할 수 있습니다.")
            return true
        }

        val item = sender.inventory.itemInMainHand
        if (item.type == Material.AIR) {
            sender.sendMessage("${ChatColor.RED}[!] 손에 아이템을 들고 있어야 합니다.")
            return true
        }

        sender.sendMessage("${ChatColor.YELLOW}======== [ 아이템 어트리뷰트 정보 ] ========")

        val customId = ItemAttributeUtil.getCustomItemId(item)
        if (customId != null) {
            sender.sendMessage("${ChatColor.GOLD}▶ IA 커스텀 아이템: ${ChatColor.AQUA}$customId")
        } else {
            sender.sendMessage("${ChatColor.GOLD}▶ 바닐라 아이템: ${ChatColor.AQUA}${item.type.name}")
        }

        // Store의 통합 조회 함수 하나로 바닐라+커스텀 다 처리
        val attributes = store.getStoredAttributes(item)

        if (attributes.isNotEmpty()) {
            sender.sendMessage("${ChatColor.GOLD}▶ 어트리뷰트")
            attributes.forEach { (attr, value) ->
                sender.sendMessage(" ${ChatColor.GRAY}- ${ChatColor.GREEN}${attr.key.key}${ChatColor.GRAY}: ${ChatColor.WHITE}$value")
            }
        } else {
            sender.sendMessage("${ChatColor.GRAY}▶ 어트리뷰트 없음")
        }

        sender.sendMessage("${ChatColor.YELLOW}==========================================")
        return true
    }
}