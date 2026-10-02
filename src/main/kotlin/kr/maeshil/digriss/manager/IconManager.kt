package kr.maeshil.digriss.manager

import dev.lone.itemsadder.api.CustomStack
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import java.io.File

// GUI 아이콘 (icons.yml): 키마다 바닐라 아이템 이름 또는 ItemsAdder ID(namespace:id)를 적으면 그 아이콘으로 표시
// 처음 쓰이는 키는 기본값으로 icons.yml에 자동 추가되므로, 메뉴를 한 번씩 열어보면 바꿀 수 있는 키가 다 채워짐
class IconManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "icons.yml")
    private var config = YamlConfiguration()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        load()
    }

    fun load() {
        config = if (file.exists()) YamlConfiguration.loadConfiguration(file) else YamlConfiguration()
        if (!file.exists()) {
            config.options().setHeader(listOf(
                "GUI 아이콘 설정. 바닐라 아이템(BEACON) 또는 ItemsAdder ID(myicons:nation)를 적으세요.",
                "수정 후 /아이콘 리로드 로 바로 적용됩니다. 메뉴를 처음 열 때 키가 자동으로 추가됩니다."
            ))
            save()
        }
    }

    // key의 아이콘. 설정이 없으면 default로 등록하고 그걸 씀. IA ID가 잘못됐거나 IA가 없으면 default로 대신 표시
    fun get(key: String, default: Material): ItemStack {
        val value = config.getString(key)
        if (value == null) {
            config.set(key, default.name)
            save()
            return ItemStack(default)
        }
        return create(value) ?: ItemStack(default)
    }

    private fun create(value: String): ItemStack? {
        val id = value.trim()
        if (id.contains(':') && !id.startsWith("minecraft:", ignoreCase = true)) {
            if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null
            return CustomStack.getInstance(id)?.itemStack
        }
        return Material.matchMaterial(id)?.let { ItemStack(it) }
    }

    private fun save() {
        runCatching { config.save(file) }.onFailure { plugin.logger.warning("[아이콘] icons.yml 저장 실패: ${it.message}") }
    }
}
