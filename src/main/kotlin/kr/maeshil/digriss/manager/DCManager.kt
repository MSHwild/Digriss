package kr.maeshil.digriss.manager

import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class DCManager(private val plugin: JavaPlugin) {

    private val file = File(plugin.dataFolder, "dc.yml")
    private lateinit var config: YamlConfiguration
    private val cache = mutableMapOf<UUID, Long>()

    // 파일 변경 감지용 (웹훅이 dc.yml을 직접 수정하는지 확인)
    private var lastModified = 0L
    private var lastLength = -1L

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
        stamp()
    }

    private fun stamp() {
        lastModified = file.lastModified()
        lastLength = file.length()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            try {
                cache[UUID.fromString(key)] = config.getLong(key)
            } catch (_: IllegalArgumentException) {
                // key가 UUID 형식이 아니면 스킵 (혹시 모를 손상 데이터 방어)
            }
        }
    }

    /** 파일을 무조건 다시 읽음 (쓰기 작업 전에 사용) */
    fun reload() {
        config = YamlConfiguration.loadConfiguration(file)
        cache.clear()
        load()
        stamp()
    }

    /** 웹훅 등으로 파일이 바뀐 경우에만 다시 읽음 (조회용, 스코어보드가 매초 호출해도 가벼움) */
    private fun reloadIfChanged() {
        if (file.lastModified() != lastModified || file.length() != lastLength) {
            reload()
        }
    }

    fun save() {
        cache.forEach { (uuid, amount) -> config.set(uuid.toString(), amount) }
        config.save(file)
        stamp()
    }

    fun getDC(player: OfflinePlayer): Long {
        reloadIfChanged()
        return cache[player.uniqueId] ?: 0L
    }

    fun addDC(player: OfflinePlayer, amount: Long) {
        reload()
        cache[player.uniqueId] = (cache[player.uniqueId] ?: 0L) + amount
        save()
    }

    fun removeDC(player: OfflinePlayer, amount: Long): Boolean {
        reload()
        val current = cache[player.uniqueId] ?: 0L
        if (current < amount) return false
        cache[player.uniqueId] = current - amount
        save()
        return true
    }

    /** 모든 유저의 DC를 초기화 (dc.yml을 비움) */
    fun resetAll() {
        cache.clear()
        config = YamlConfiguration()
        config.save(file)
        stamp()
    }
}