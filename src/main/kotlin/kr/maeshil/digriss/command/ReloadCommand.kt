package kr.maeshil.digriss.command

import kr.maeshil.digriss.Digriss
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

// /디그리스 리로드  — 서버를 재시작하지 않고 설정 파일을 다시 읽음 (관리자)
//   quest.yml, combat.yml, spawn.yml, event.yml, help.yml, icons.yml, links.yml, openevent.yml, discord.yml
// 새 파일을 만든 것이 아니라 "수정한 내용"을 적용하는 용도. 퀘스트 목록이 바뀌어도 이미 뽑힌 오늘 퀘스트는 그대로 진행됨
class ReloadCommand(private val plugin: Digriss) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.").let { true }
        if (args.getOrNull(0) != "리로드") return sender.sendMessage("§e사용법: /디그리스 리로드").let { true }

        val results = mutableListOf<String>()
        fun step(name: String, action: () -> Unit) {
            runCatching(action)
                .onSuccess { results += "§a✔ §f$name" }
                .onFailure {
                    results += "§c✘ §f$name §7(${it.message?.lineSequence()?.firstOrNull() ?: it.javaClass.simpleName})"
                    plugin.logger.warning("[리로드] $name 실패: ${it.message}")
                }
        }

        step("quest.yml (퀘스트)") { plugin.questManager.loadDefinitions() }
        step("combat.yml (전투)") { plugin.combatManager.reload() }
        step("spawn.yml (랜덤 스폰)") { plugin.randomSpawnManager.load() }
        step("event.yml (전쟁 이벤트)") { plugin.warEventManager.load() }
        step("help.yml (도움말)") { plugin.helpManager.load() }
        step("icons.yml (아이콘)") { plugin.iconManager.load() }
        step("links.yml (링크)") { plugin.linkCommand.load() }
        step("openevent.yml (오픈 이벤트)") { plugin.openEventManager.load() }
        step("discord.yml (디스코드 알림)") { plugin.discordNotifier.load() }

        plugin.adminLogManager.log(sender, "설정 리로드")
        sender.sendMessage("§6[디그리스] §f설정을 다시 불러왔습니다.")
        results.forEach { sender.sendMessage("  $it") }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> =
        if (args.size == 1 && sender.hasPermission("digriss.admin")) listOf("리로드").filter { it.startsWith(args[0]) } else emptyList()
}

// /지도, /디스코드  — links.yml에 적어둔 주소를 클릭 가능한 링크로 알려줌
class LinkCommand(private val plugin: Digriss) : CommandExecutor {

    private var map = ""
    private var discord = ""

    init { load() }

    fun load() {
        val file = File(plugin.dataFolder, "links.yml")
        if (!file.exists()) {
            if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
            // jar에 links.yml이 없어도 꺼지지 않게 기본값으로 만듦
            if (plugin.getResource("links.yml") != null) plugin.saveResource("links.yml", false)
            else YamlConfiguration().apply { set("map", ""); set("discord", ""); save(file) }
        }
        val config = YamlConfiguration.loadConfiguration(file)
        map = config.getString("map", "")!!.trim()
        discord = config.getString("discord", "")!!.trim()
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val (title, url) = when (command.name) {
            "지도" -> "§b🗺 실시간 지도" to map
            else -> "§9💬 디스코드" to discord
        }
        if (url.isEmpty()) {
            sender.sendMessage("$title §7아직 준비 중이에요.")
            return true
        }
        sender.sendMessage(
            Component.text("$title ").append(
                Component.text(url, NamedTextColor.AQUA).clickEvent(ClickEvent.openUrl(url))
            )
        )
        return true
    }
}
