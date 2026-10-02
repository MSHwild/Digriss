package kr.maeshil.digriss.effect

import kr.maeshil.digriss.Digriss
import org.bukkit.OfflinePlayer
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.util.UUID

class KillEffectManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "effects.yml")
    private lateinit var config: YamlConfiguration

    // 플레이어별 보유 이펙트 목록
    private val owned = mutableMapOf<UUID, MutableSet<String>>()
    // 플레이어별 현재 장착 이펙트
    private val equipped = mutableMapOf<UUID, String>()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            val uuid = UUID.fromString(key)
            val ownedList = config.getStringList("$key.owned").toMutableSet()
            owned[uuid] = ownedList
            config.getString("$key.equipped")?.let { equipped[uuid] = it }
        }
    }

    fun save() {
        owned.forEach { (uuid, list) ->
            config.set("$uuid.owned", list.toList())
        }
        // 해제한 사람은 null로 덮어써야 재시작 후 다시 장착되지 않음
        (owned.keys + equipped.keys).forEach { uuid ->
            config.set("$uuid.equipped", equipped[uuid])
        }
        config.save(file)
    }

    fun getOwned(player: OfflinePlayer): Set<String> = owned[player.uniqueId] ?: emptySet()

    fun hasEffect(player: OfflinePlayer, effectId: String): Boolean =
        owned[player.uniqueId]?.contains(effectId) == true

    fun buyEffect(player: OfflinePlayer, effectId: String): Boolean {
        val set = owned.getOrPut(player.uniqueId) { mutableSetOf() }
        if (set.contains(effectId)) return false
        set.add(effectId)
        return true
    }

    fun equip(player: OfflinePlayer, effectId: String): Boolean {
        if (!hasEffect(player, effectId)) return false
        equipped[player.uniqueId] = effectId
        return true
    }

    fun unequip(player: OfflinePlayer) {
        equipped.remove(player.uniqueId)
    }

    fun getEquipped(player: OfflinePlayer): KillEffect? {
        val id = equipped[player.uniqueId] ?: return null
        return EffectRegistry.get(id)
    }

    fun resetAll() {
        owned.clear()
        equipped.clear()
        config.getKeys(false).forEach { config.set(it, null) }
        save()
    }
}