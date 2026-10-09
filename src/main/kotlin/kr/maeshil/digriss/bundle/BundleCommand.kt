package kr.maeshil.digriss.bundle

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player

// /번들, /번들생성 <이름> <가격(DC)> <기간(일)>, /번들수정 <이름>, /번들삭제 <이름>
// /번들지급 <닉네임> <번들> : OP·콘솔 전용, DC 없이 지급 (Tebex 등 자동 지급용). 접속 안 했으면 다음 접속 때 지급
class BundleCommand(private val plugin: Digriss) : CommandExecutor, TabCompleter, org.bukkit.event.Listener {

    private val bundleManager get() = plugin.bundleManager

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        when (command.name) {
            "번들" -> list(sender)
            "번들생성" -> create(sender, args)
            "번들수정" -> edit(sender, args)
            "번들삭제" -> delete(sender, args)
            "번들지급" -> give(sender, args)
        }
        return true
    }

    private fun list(sender: CommandSender) {
        if (sender is Player) {
            BundleGUI.openList(sender, plugin)
            Sounds.open(sender)
            return
        }
        // 콘솔: 채팅으로 목록 출력 (판매 종료 포함)
        val all = bundleManager.all()
        if (all.isEmpty()) return sender.sendMessage("등록된 번들이 없습니다.")
        all.forEach { sender.sendMessage("- ${it.name} | ${it.price} DC | ${it.remainingText()} | 아이템 ${it.items.size}칸") }
    }

    private fun create(sender: CommandSender, args: Array<out String>) {
        val player = adminPlayer(sender) ?: return
        if (args.size < 3) return player.sendMessage("§e사용법: /번들생성 <이름> <가격(DC)> <기간(일), 0이면 무기한>")

        val name = args[0]
        val price = args[1].toLongOrNull()
        val days = args[2].toIntOrNull()
        if (!bundleManager.isValidName(name)) return player.sendMessage("§c이름은 32자 이하, 점(.) 없이 지어주세요.")
        if (price == null || price < 0) return player.sendMessage("§c가격은 0 이상의 숫자여야 합니다.")
        if (days == null || days < 0) return player.sendMessage("§c기간은 0 이상의 숫자(일)여야 합니다. 0이면 무기한")
        if (bundleManager.get(name) != null) return player.sendMessage("§c'$name' 번들이 이미 있습니다. 아이템은 /번들수정 $name")
        if (!bundleManager.startEditing(name, player)) return player.sendMessage("§c다른 관리자가 '$name' 번들을 만들고 있습니다.")

        BundleGUI.openEdit(player, BundleEditHolder(name, true, price, days), emptyList())
    }

    private fun edit(sender: CommandSender, args: Array<out String>) {
        val player = adminPlayer(sender) ?: return
        if (args.isEmpty()) return player.sendMessage("§e사용법: /번들수정 <이름>")
        val bundle = bundleManager.get(args[0]) ?: return player.sendMessage("§c'${args[0]}' 번들이 없습니다.")
        if (!bundleManager.startEditing(bundle.name, player)) return player.sendMessage("§c다른 관리자가 '${bundle.name}' 번들을 수정하고 있습니다.")

        BundleGUI.openEdit(player, BundleEditHolder(bundle.name, false, bundle.price, 0), bundle.items)
    }

    private fun delete(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.")
        if (args.isEmpty()) return sender.sendMessage("§e사용법: /번들삭제 <이름>")
        if (!bundleManager.delete(args[0])) return sender.sendMessage("§c'${args[0]}' 번들이 없습니다.")
        plugin.adminLogManager.log(sender, "번들 삭제 → ${args[0]}")
        sender.sendMessage("§a'${args[0]}' 번들을 삭제했습니다.")
    }

    private fun give(sender: CommandSender, args: Array<out String>) {
        if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.")
        if (args.size < 2) return sender.sendMessage("§e사용법: /번들지급 <닉네임> <번들 이름>")
        val bundleName = args.drop(1).joinToString(" ")
        val bundle = bundleManager.get(bundleName) ?: return sender.sendMessage("§c'$bundleName' 번들이 없습니다.")
        if (bundle.items.isEmpty()) return sender.sendMessage("§c'$bundleName' 번들에 아이템이 없습니다.")

        val online = org.bukkit.Bukkit.getPlayerExact(args[0])
        if (online != null) {
            bundleManager.give(online, bundle, sender.name)
            online.sendMessage("§6[번들] §f'${bundle.name}' 번들이 지급되었습니다!")
            Sounds.bigReward(online)
            sender.sendMessage("§a${online.name}에게 '${bundle.name}' 번들을 지급했습니다.")
        } else {
            val offline = org.bukkit.Bukkit.getOfflinePlayerIfCached(args[0])
                ?: return sender.sendMessage("§c'${args[0]}' 님은 서버에 접속한 적이 없습니다.")
            bundleManager.addPending(offline.uniqueId, bundle.name)
            sender.sendMessage("§e${offline.name}님이 접속 중이 아니라 다음 접속 때 '${bundle.name}' 번들을 지급합니다.")
        }
        plugin.adminLogManager.log(sender, "번들 지급 → ${args[0]} / ${bundle.name}")
    }

    // 접속하면 대기 중이던 번들 지급 (번들이 그사이 삭제됐으면 건너뛰고 기록)
    @org.bukkit.event.EventHandler
    fun onJoin(e: org.bukkit.event.player.PlayerJoinEvent) {
        val pending = bundleManager.takePending(e.player.uniqueId)
        if (pending.isEmpty()) return
        org.bukkit.Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            val p = e.player
            if (!p.isOnline) { pending.forEach { bundleManager.addPending(p.uniqueId, it) }; return@Runnable }
            pending.forEach { name ->
                val bundle = bundleManager.get(name)
                if (bundle == null) {
                    plugin.logger.warning("[번들] ${p.name}에게 지급할 '$name' 번들이 없어졌습니다.")
                    return@forEach
                }
                bundleManager.give(p, bundle, "대기열")
                p.sendMessage("§6[번들] §f'${bundle.name}' 번들이 지급되었습니다!")
            }
            Sounds.bigReward(p)
        }, 40L)
    }

    // GUI가 필요한 관리자 명령어용
    private fun adminPlayer(sender: CommandSender): Player? {
        if (!sender.hasPermission("digriss.admin")) {
            sender.sendMessage("§c권한이 없습니다.")
            return null
        }
        if (sender !is Player) {
            sender.sendMessage("§c게임 안에서만 사용할 수 있습니다. (아이템을 넣는 창이 열림)")
            return null
        }
        return sender
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("digriss.admin")) return emptyList()
        if (command.name == "번들지급") return when (args.size) {
            1 -> org.bukkit.Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(args[0], true) }
            2 -> bundleManager.names().filter { it.startsWith(args[1]) }
            else -> emptyList()
        }
        if (args.size != 1) return emptyList()
        if (command.name != "번들수정" && command.name != "번들삭제") return emptyList()
        return bundleManager.names().filter { it.startsWith(args[0]) }
    }
}
