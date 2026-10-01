package kr.maeshil.digriss.manager

import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class SoulManager(private val plugin: JavaPlugin) {

    private val file = File(plugin.dataFolder, "souls.yml")
    private lateinit var config: YamlConfiguration
    private val cache = mutableMapOf<UUID, Long>()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            val uuid = UUID.fromString(key)
            cache[uuid] = config.getLong(key)
        }
    }

    fun save() {
        cache.forEach { (uuid, amount) ->
            config.set(uuid.toString(), amount)
        }
        config.save(file)
    }

    fun getSouls(player: OfflinePlayer): Long {
        return cache[player.uniqueId] ?: 0L
    }

    fun addSouls(player: OfflinePlayer, amount: Long) {
        val current = cache[player.uniqueId] ?: 0L
        cache[player.uniqueId] = current + amount
    }

    fun removeSouls(player: OfflinePlayer, amount: Long): Boolean {
        val current = cache[player.uniqueId] ?: 0L
        if (current < amount) return false
        cache[player.uniqueId] = current - amount
        return true
    }

    fun setSouls(player: OfflinePlayer, amount: Long) {
        cache[player.uniqueId] = amount
    }

    fun resetAll() {
        cache.clear()
        config.getKeys(false).forEach { config.set(it, null) }
        save()
    }
}