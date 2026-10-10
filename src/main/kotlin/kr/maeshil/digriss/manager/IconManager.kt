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
    private var loadedStamp = -1L // 마지막으로 읽은 파일의 수정 시각

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        load()
    }

    // 파일을 못 읽었을 때(형식 오류) 기본값으로 덮어쓰지 않도록 저장을 막는 표시
    private var broken = false

    // 읽기 결과 안내 문구를 돌려줌 (/아이콘 리로드에서 보여줌)
    fun load(): String {
        loadedStamp = file.lastModified()
        if (!file.exists()) {
            broken = false
            config = YamlConfiguration()
            config.options().setHeader(listOf(
                "GUI 아이콘 설정. 바닐라 아이템(BEACON) 또는 ItemsAdder ID(digriss:menu_nation)를 적으세요.",
                "수정 후 /아이콘 리로드 로 바로 적용됩니다. 메뉴를 처음 열 때 키가 자동으로 추가됩니다."
            ))
            save()
            return "§aicons.yml을 새로 만들었습니다."
        }
        val bytes = file.readBytes()
        // 메모장이 한글을 ANSI(EUC-KR)로 저장한 경우도 읽을 수 있게: UTF-8이 아니면 MS949로 해석
        val text = runCatching {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrElse { String(bytes, java.nio.charset.Charset.forName("MS949")) }.removePrefix("﻿") // 메모장 UTF-8 BOM 제거

        return try {
            val loaded = YamlConfiguration()
            loaded.loadFromString(text)
            config = loaded
            broken = false
            "§aicons.yml을 다시 불러왔습니다. 메뉴를 다시 열면 적용됩니다."
        } catch (e: Exception) {
            broken = true
            plugin.logger.warning("[아이콘] icons.yml 형식이 잘못되어 읽지 못했습니다. 파일은 건드리지 않고 기본 아이콘으로 표시합니다: ${e.message?.lineSequence()?.firstOrNull()}")
            "§cicons.yml 형식이 잘못되어 읽지 못했습니다. §7(```같은 줄이 들어갔거나 들여쓰기가 틀렸는지 확인) 기본 아이콘으로 표시합니다."
        }
    }

    // key의 아이콘. 설정이 없으면 default로 등록하고 그걸 씀. IA ID가 잘못됐거나 IA가 없으면 default로 대신 표시
    fun get(key: String, default: Material): ItemStack {
        // 지금은 꺼 둠: icons.yml·아이콘 팩 그림 없이 바닐라 아이템만 씀. 더 나은 팩이 생기면 ENABLED = true
        if (!ENABLED) return ItemStack(default)
        // 서버가 켜진 채로 파일을 고쳤으면 먼저 다시 읽음 (안 그러면 아래 save()가 고친 내용을 예전 값으로 덮어씀)
        if (file.exists() && file.lastModified() != loadedStamp) load()
        val value = config.getString(key)
        // icons.yml에 IA ID를 직접 적은 게 아니면, 아이콘 팩(digriss:키_이름)에 그림이 있을 때 그걸 먼저 씀
        // → 서버에서 icons.yml을 고치지 않아도 팩만 넣으면 아이콘이 바뀜
        if (value == null || !value.contains(':')) packIcon(key)?.let { return it }
        if (value == null) {
            if (!broken) {
                config.set(key, default.name)
                save()
            }
            return ItemStack(default)
        }
        return create(value) ?: ItemStack(default)
    }

    // 메인 메뉴 키는 명령어 이름(한글)이라 아이콘 팩 이름으로 바꿔 줌
    private val menuPackNames = mapOf(
        "국가" to "nation", "연합" to "alliance", "국가창고" to "storage", "번들" to "bundle",
        "퀘스트" to "quest", "직업" to "job", "직업설정" to "job_set", "랭크" to "rank", "랭킹" to "ranking",
        "국가랭킹" to "nation_ranking", "칭호" to "title", "킬이펙트" to "kill_effect", "전쟁이벤트" to "war_event",
        "도움말" to "help", "상점" to "shop", "레시피" to "recipe", "이벤트" to "event", "지도" to "map",
        "거래소" to "market", "거래" to "trade", "외교" to "diplomacy", "빅이벤트" to "big_event",
        "워프" to "warp", "탈것" to "mount"
    )

    /** 아이콘 팩(ItemsAdder digriss 네임스페이스)에서 이 키의 아이콘. 없으면 null */
    private fun packIcon(key: String): ItemStack? {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null
        val group = key.substringBefore('.')
        val rest = key.substringAfter('.', "")
        val name = if (group == "menu") menuPackNames[rest]?.let { "menu_$it" } else key.replace('.', '_')
        if (name == null || !name.matches(Regex("[a-z0-9_]+"))) return null
        return runCatching { CustomStack.getInstance("digriss:$name")?.itemStack }.getOrNull()
    }

    private fun create(value: String): ItemStack? {
        val id = value.trim()
        if (id.contains(':') && !id.startsWith("minecraft:", ignoreCase = true)) {
            if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return null
            return CustomStack.getInstance(id)?.itemStack
        }
        return Material.matchMaterial(id)?.let { ItemStack(it) }
    }

    companion object {
        const val ENABLED = false
    }

    private fun save() {
        runCatching { config.save(file); loadedStamp = file.lastModified() }.onFailure { plugin.logger.warning("[아이콘] icons.yml 저장 실패: ${it.message}") }
    }
}
