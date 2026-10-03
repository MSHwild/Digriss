package kr.maeshil.digriss.command

import kr.maeshil.digriss.nation.Nation
import kr.maeshil.digriss.nation.Nations
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter

// /국가랭킹 [영토|금고|인원|레벨|내실]  (기본: 영토)
class NationRankCommand : CommandExecutor, TabCompleter {

    private val criteria: Map<String, Pair<(Nations) -> Double, (Nations) -> String>> = linkedMapOf(
        "영토" to Pair({ n: Nations -> n.claims.size.toDouble() }, { n: Nations -> "${n.claims.size}청크" }),
        "금고" to Pair({ n: Nations -> n.bank }, { n: Nations -> "${String.format("%,.0f", n.bank)}원" }),
        "인원" to Pair({ n: Nations -> n.members.size.toDouble() }, { n: Nations -> "${n.members.size}명" }),
        "레벨" to Pair({ n: Nations -> n.level.toDouble() }, { n: Nations -> "Lv.${n.level}" }),
        "내실" to Pair({ n: Nations -> n.peace }, { n: Nations -> "${n.peace.toInt()}점" })
    )

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val type = args.getOrNull(0) ?: "영토"
        val (score, display) = criteria[type] ?: run {
            sender.sendMessage("§e사용법: /국가랭킹 [${criteria.keys.joinToString("|")}]")
            return true
        }

        // 동점이면 영토 → 인원 순으로 정렬
        val ranked = Nation.nations.values.sortedWith(
            compareByDescending<Nations> { score(it) }.thenByDescending { it.claims.size }.thenByDescending { it.members.size }
        )
        if (ranked.isEmpty()) {
            sender.sendMessage("§7아직 국가가 없습니다.")
            return true
        }

        sender.sendMessage("§8§m          §r §6§l국가 랭킹 §7($type) §8§m          ")
        ranked.take(10).forEachIndexed { i, n ->
            val medal = when (i) { 0 -> "§6🥇"; 1 -> "§7🥈"; 2 -> "§c🥉"; else -> "§8${i + 1}." }
            sender.sendMessage(" $medal §f${n.name} §8- §e${display(n)}")
        }
        sender.sendMessage("§7다른 기준: §f/국가랭킹 ${criteria.keys.joinToString("§7|§f")}")
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1) criteria.keys.filter { it.startsWith(args[0]) } else emptyList()
}
