package kr.maeshil.digriss.quest

import kr.maeshil.digriss.manager.QuestManager
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

// /퀘스트
class QuestCommand(private val questManager: QuestManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        QuestGUI.open(player, questManager)
        return true
    }
}

// /퀘스트관리 초기화 <닉네임>
class QuestAdminCommand(private val questManager: QuestManager) : CommandExecutor {
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("§c권한이 없습니다.")
            return true
        }
        if (args.size < 2 || args[0] != "초기화") {
            sender.sendMessage("§e사용법: /퀘스트관리 초기화 <닉네임>")
            return true
        }

        val target = Bukkit.getPlayerExact(args[1]) ?: Bukkit.getOfflinePlayerIfCached(args[1])
        if (target == null) {
            sender.sendMessage("§c'${args[1]}' 플레이어를 찾을 수 없습니다.")
            return true
        }
        if (!questManager.resetToday(target.uniqueId)) {
            sender.sendMessage("§c${target.name}님은 퀘스트 기록이 없습니다.")
            return true
        }
        sender.sendMessage("§a${target.name}님의 오늘 퀘스트를 초기화했습니다." +
            if (target.isOnline) "" else " (다음 접속 때 새로 뽑힙니다)")
        return true
    }
}
