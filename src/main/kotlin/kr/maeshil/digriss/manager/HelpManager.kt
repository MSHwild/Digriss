package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

data class HelpCategory(
    val name: String,        // 색코드 없는 이름 (/도움말 <이름>)
    val icon: Material,
    val slot: Int?,
    val summary: String,
    val lines: List<String>
)

// 도움말 글 (help.yml). 운영자가 글만 고치고 /도움말 리로드 하면 바로 반영
class HelpManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "help.yml")

    var categories: List<HelpCategory> = emptyList()
        private set
    var firstJoin: List<String> = emptyList()
        private set

    init {
        // 도움말은 업데이트마다 내용이 바뀌므로 플러그인이 켜질 때마다 jar 안의 최신 help.yml로 덮어씀
        // (서버에서 직접 고친 내용은 help-old.yml로 한 번 보관)
        val resource = plugin.getResource("help.yml")?.use { it.readBytes() }
        if (resource != null) {
            plugin.dataFolder.mkdirs()
            if (file.exists() && !file.readBytes().contentEquals(resource)) file.copyTo(File(plugin.dataFolder, "help-old.yml"), overwrite = true)
            file.writeBytes(resource)
        }
        load()
    }

    fun load() {
        val config = YamlConfiguration.loadConfiguration(file)
        firstJoin = config.getStringList("first-join").map { color(it) }

        val section = config.getConfigurationSection("categories")
        categories = section?.getKeys(false)?.mapNotNull { key ->
            val c = section.getConfigurationSection(key) ?: return@mapNotNull null
            val icon = Material.matchMaterial(c.getString("icon", "BOOK")!!) ?: run {
                plugin.logger.warning("[도움말] '$key'의 icon을 찾을 수 없어 BOOK으로 표시합니다.")
                Material.BOOK
            }
            HelpCategory(
                name = ChatColor.stripColor(color(c.getString("name", key)!!))!!,
                icon = icon,
                slot = if (c.contains("slot")) c.getInt("slot") else null,
                summary = color(c.getString("summary", "")!!),
                lines = c.getStringList("lines").map { color(it) }
            )
        } ?: emptyList()
    }

    fun find(name: String): HelpCategory? = categories.firstOrNull { it.name == name }

    private fun color(text: String) = ChatColor.translateAlternateColorCodes('&', text)
}
