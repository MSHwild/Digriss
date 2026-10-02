package kr.maeshil.digriss.nation

import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.AsyncPlayerChatEvent

// 국가 채팅: "!메시지" 또는 /국가채팅 <메시지>  → 같은 국가원에게만
// 연합 채팅: "!!메시지" 또는 /연합채팅 <메시지> → 같은 국가 + 연합국 국가원에게
class NationChat(private val plugin: Digriss) : Listener, CommandExecutor {

    // 국가 건국 이름 입력(LOWEST)이 먼저 처리되도록 LOW + ignoreCancelled
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onChat(e: AsyncPlayerChatEvent) {
        val msg = e.message
        if (!msg.startsWith("!")) return
        e.isCancelled = true

        val alliance = msg.startsWith("!!")
        val text = msg.removePrefix(if (alliance) "!!" else "!").trim()
        val player = e.player
        // 채팅 이벤트는 비동기라 국가 데이터 접근은 메인 스레드에서
        Bukkit.getScheduler().runTask(plugin, Runnable { send(player, text, alliance) })
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        send(player, args.joinToString(" ").trim(), command.name == "연합채팅")
        return true
    }

    private fun send(player: Player, text: String, alliance: Boolean) {
        if (!player.isOnline) return
        val myNation = plugin.nationManager.getNationName(player.uniqueId)
        if (myNation == null) {
            player.sendMessage("§c소속된 국가가 없어 국가 채팅을 사용할 수 없습니다.")
            return
        }
        if (text.isEmpty()) {
            player.sendMessage("§7사용법: §f!메시지 §7(국가) / §f!!메시지 §7(연합)")
            return
        }

        val targets = mutableListOf(myNation)
        if (alliance) targets += plugin.allianceManager.alliesOf(myNation)

        val formatted = if (alliance) "§b[연합] §3[$myNation] §f${player.name}§7: §b$text"
        else "§a[국가] §f${player.name}§7: §a$text"

        targets.mapNotNull { Nation.nations[it] }
            .flatMap { it.members }
            .distinct()
            .mapNotNull { Bukkit.getPlayer(it) }
            .forEach { it.sendMessage(formatted) }
        plugin.logger.info(formatted.replace(Regex("§."), ""))
    }
}
