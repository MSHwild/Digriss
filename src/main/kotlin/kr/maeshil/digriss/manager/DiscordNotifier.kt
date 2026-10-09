package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * 디스코드 알림 (discord.yml)
 * 전쟁 시작/휴전/점령, 건국/해체/이름 변경, 전쟁 이벤트를 디스코드 채널 웹훅으로 보냄.
 * 채널 설정 → 연동 → 웹후크에서 주소를 만들어 webhook-url에 넣고 enabled: true. 수정 후 /디그리스 리로드
 */
class DiscordNotifier(private val plugin: Digriss) {

    companion object {
        const val RED = 0xE74C3C
        const val GREEN = 0x2ECC71
        const val GOLD = 0xF1C40F
        const val BLUE = 0x3498DB
        const val GRAY = 0x95A5A6
    }

    private val configFile = File(plugin.dataFolder, "discord.yml")
    private val client: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private var enabled = false
    private var url = ""
    private var username = "디그리스"
    private var footer = ""
    private val events = HashMap<String, Boolean>()

    init {
        load()
    }

    fun load() {
        if (!configFile.exists()) {
            // jar 안에 discord.yml이 없어도(IntelliJ 아티팩트로 빌드 등) 플러그인이 꺼지지 않게 기본값으로 만듦
            plugin.dataFolder.mkdirs()
            if (plugin.getResource("discord.yml") != null) plugin.saveResource("discord.yml", false)
            else YamlConfiguration().apply {
                set("enabled", false); set("webhook-url", ""); set("username", "디그리스"); set("footer", "puritymc.kr")
                listOf("war-start", "truce", "conquer", "nation-create", "nation-dissolve", "nation-rename", "war-event-notice", "war-event")
                    .forEach { set("events.$it", true) }
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        enabled = c.getBoolean("enabled", false)
        url = c.getString("webhook-url")?.trim() ?: ""
        username = c.getString("username", "디그리스") ?: "디그리스"
        footer = c.getString("footer", "") ?: ""
        events.clear()
        c.getConfigurationSection("events")?.getKeys(false)?.forEach { events[it] = c.getBoolean("events.$it", true) }
    }

    fun isReady(): Boolean = enabled && url.startsWith("https://")

    /** event: discord.yml의 events 이름. 꺼져 있거나 주소가 없으면 아무것도 안 함 */
    fun notify(event: String, title: String, description: String, color: Int) {
        if (!isReady() || events[event] == false) return
        val json = buildString {
            append("{\"username\":").append(quote(username)).append(",\"embeds\":[{")
            append("\"title\":").append(quote(clean(title))).append(",")
            append("\"description\":").append(quote(clean(description))).append(",")
            append("\"color\":").append(color).append(",")
            append("\"timestamp\":").append(quote(Instant.now().toString()))
            if (footer.isNotBlank()) append(",\"footer\":{\"text\":").append(quote(footer)).append("}")
            append("}]}")
        }
        val request = runCatching {
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, Charsets.UTF_8)).build()
        }.getOrElse {
            plugin.logger.warning("[디스코드] webhook-url이 올바르지 않습니다: ${it.message}")
            return
        }
        // 메인 스레드를 막지 않게 비동기로 전송
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete { res, err ->
            if (err != null) plugin.logger.warning("[디스코드] 전송 실패: ${err.message}")
            else if (res.statusCode() !in 200..299) plugin.logger.warning("[디스코드] 전송 실패 (HTTP ${res.statusCode()}). 웹훅 주소를 확인하세요.")
        }
    }

    // 마인크래프트 색 코드(§x, &x)를 지움
    private fun clean(text: String) = text.replace(Regex("[§][0-9a-fk-orA-FK-OR]"), "")

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> {}
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }
}
