package kr.maeshil.digriss.event

import kr.maeshil.digriss.Digriss
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

// /전쟁이벤트 [상태|시작 <분>|종료|리로드]  — 상태는 누구나, 나머지는 관리자
class WarEventCommand(private val plugin: Digriss) : CommandExecutor, TabCompleter {

    private val event get() = plugin.warEventManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val sub = args.getOrNull(0) ?: "상태"
        if (sub == "상태") {
            sender.sendMessage("§6국가전쟁 이벤트: ${event.statusText()}")
            return true
        }
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("§c권한이 없습니다.")
            return true
        }
        when (sub) {
            "시작" -> {
                val minutes = args.getOrNull(1)?.toIntOrNull()
                if (minutes == null || minutes <= 0) return sender.sendMessage("§e사용법: /전쟁이벤트 시작 <분>").let { true }
                event.startManual(minutes)
                plugin.adminLogManager.log(sender, "전쟁 이벤트 시작 (${minutes}분)")
                sender.sendMessage("§a전쟁 이벤트를 ${minutes}분 동안 엽니다.")
            }
            "종료" -> {
                if (event.stopManual()) {
                    plugin.adminLogManager.log(sender, "전쟁 이벤트 종료")
                    sender.sendMessage("§a직접 연 전쟁 이벤트를 끝냈습니다.")
                } else {
                    sender.sendMessage("§c직접 연 이벤트가 없습니다. (시간표 이벤트는 event.yml에서 시간을 바꾸세요)")
                }
            }
            "리로드" -> {
                event.load()
                sender.sendMessage("§aevent.yml을 다시 불러왔습니다. ${event.statusText()}")
            }
            else -> sender.sendMessage("§e사용법: /전쟁이벤트 [상태|시작 <분>|종료|리로드]")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val options = if (sender.hasPermission("digriss.admin")) listOf("상태", "시작", "종료", "리로드") else listOf("상태")
        return options.filter { it.startsWith(args[0]) }
    }
}

// /아이콘 리로드  — icons.yml 다시 불러오기 (관리자)
class IconCommand(private val plugin: Digriss) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.").let { true }
        sender.sendMessage(plugin.iconManager.load())
        return true
    }
}
