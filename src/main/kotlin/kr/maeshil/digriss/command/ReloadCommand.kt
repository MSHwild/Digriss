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
//   quest.yml, combat.yml, spawn.yml, event.yml, help.yml, icons.yml, links.yml, openevent.yml, discord.yml, sites.yml, season.yml, protection.yml, vote.yml
// 새 파일을 만든 것이 아니라 "수정한 내용"을 적용하는 용도. 퀘스트 목록이 바뀌어도 이미 뽑힌 오늘 퀘스트는 그대로 진행됨
class ReloadCommand(private val plugin: Digriss) : CommandExecutor, TabCompleter {

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (!sender.hasPermission("digriss.admin")) return sender.sendMessage("§c권한이 없습니다.").let { true }
        if (args.getOrNull(0) == "설정") return setValue(sender, args)
        if (args.getOrNull(0) != "리로드") return sender.sendMessage("§e사용법: /디그리스 리로드, /디그리스 설정 <디스코드|투표> <주소|끄기>").let { true }

        val results = mutableListOf<String>()
        fun step(name: String, action: () -> Unit) {
            runCatching(action)
                .onSuccess { results += "§a성공 §f$name" }
                .onFailure {
                    results += "§c실패 §f$name §7(${it.message?.lineSequence()?.firstOrNull() ?: it.javaClass.simpleName})"
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
        step("vote.yml (투표 보상)") { plugin.voteManager.load() }
        step("discord.yml (디스코드 알림)") { plugin.discordNotifier.load() }
        step("sites.yml (자원 거점)") { plugin.resourceSiteManager.load() }
        step("season.yml (시즌)") { plugin.seasonManager.load() }
        step("protection.yml (초보 보호)") { plugin.newbieProtectionManager.load() }
        step("bigevent.yml (대축제)") { plugin.bigEventManager.load() }

        plugin.adminLogManager.log(sender, "설정 리로드")
        sender.sendMessage("§6[디그리스] §f설정을 다시 불러왔습니다.")
        results.forEach { sender.sendMessage("  $it") }
        return true
    }

    // /디그리스 설정 디스코드 <웹훅 주소|끄기>, /디그리스 설정 투표 <주소>
    // 파일을 직접 못 고치는 서버용. 웹훅 주소는 비밀번호 같은 것이라 콘솔에서 입력하는 걸 권장
    private fun setValue(sender: CommandSender, args: Array<out String>): Boolean {
        val value = args.drop(2).joinToString(" ").trim()
        when (args.getOrNull(1)) {
            "디스코드" -> {
                if (value.isEmpty()) return sender.sendMessage("§e/디그리스 설정 디스코드 <웹훅 주소|끄기>").let { true }
                val file = File(plugin.dataFolder, "discord.yml")
                val c = YamlConfiguration.loadConfiguration(file)
                if (value == "끄기") c.set("enabled", false)
                else {
                    if (!value.startsWith("https://discord.com/api/webhooks/") && !value.startsWith("https://discordapp.com/api/webhooks/"))
                        return sender.sendMessage("§c디스코드 웹훅 주소가 아닙니다. (https://discord.com/api/webhooks/... 로 시작)").let { true }
                    c.set("enabled", true); c.set("webhook-url", value)
                }
                c.save(file)
                plugin.discordNotifier.load()
                plugin.adminLogManager.log(sender, "디스코드 웹훅 ${if (value == "끄기") "끔" else "설정"}")
                sender.sendMessage(if (value == "끄기") "§a디스코드 알림을 껐습니다." else "§a디스코드 알림을 켰습니다. 다음 알림부터 그 채널로 갑니다.")
            }
            "투표" -> {
                if (value.isEmpty()) return sender.sendMessage("§e/디그리스 설정 투표 <마인리스트 서버 페이지 주소>").let { true }
                val file = File(plugin.dataFolder, "vote.yml")
                val c = YamlConfiguration.loadConfiguration(file)
                c.set("vote-url", value)
                c.save(file)
                plugin.voteManager.load()
                plugin.adminLogManager.log(sender, "투표 주소 설정: $value")
                sender.sendMessage("§a/투표 주소를 바꿨습니다: §f$value")
            }
            else -> sender.sendMessage("§e/디그리스 설정 <디스코드|투표> <주소|끄기>")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("digriss.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("리로드", "설정").filter { it.startsWith(args[0]) }
            2 -> if (args[0] == "설정") listOf("디스코드", "투표").filter { it.startsWith(args[1]) } else emptyList()
            else -> emptyList()
        }
    }
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
            "지도" -> "§b실시간 지도" to map
            else -> "§9디스코드" to discord
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
