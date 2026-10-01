package kr.maeshil.digriss.manager

import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class KDManager(private val plugin: JavaPlugin) {

    private val file = File(plugin.dataFolder, "kd.yml")
    private lateinit var config: YamlConfiguration
    private val kills = mutableMapOf<UUID, Int>()
    private val deaths = mutableMapOf<UUID, Int>()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            val uuid = UUID.fromString(key)
            kills[uuid] = config.getInt("$key.kills")
            deaths[uuid] = config.getInt("$key.deaths")
        }
    }

    fun save() {
        kills.keys.union(deaths.keys).forEach { uuid ->
            config.set("$uuid.kills", kills[uuid] ?: 0)
            config.set("$uuid.deaths", deaths[uuid] ?: 0)
        }
        config.save(file)
    }

    fun addKill(player: OfflinePlayer) {
        kills[player.uniqueId] = (kills[player.uniqueId] ?: 0) + 1
    }

    fun addDeath(player: OfflinePlayer) {
        deaths[player.uniqueId] = (deaths[player.uniqueId] ?: 0) + 1
    }

    fun getKills(player: OfflinePlayer): Int = kills[player.uniqueId] ?: 0
    fun getDeaths(player: OfflinePlayer): Int = deaths[player.uniqueId] ?: 0

    fun getKD(player: OfflinePlayer): String {
        val k = getKills(player)
        val d = getDeaths(player)
        return if (d == 0) k.toString() else String.format("%.2f", k.toDouble() / d)
    }

    fun resetAll() {
        kills.clear()
        deaths.clear()
        config.getKeys(false).forEach { config.set(it, null) }
        save()
    }
}