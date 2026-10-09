package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.bundle.Bundle
import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

// 상점 패키지 (/번들): 관리자가 GUI에 넣은 아이템을 DC로 판매
class BundleManager(private val plugin: Digriss) {

    sealed class Result {
        object Success : Result()
        data class Fail(val reason: String) : Result()
    }

    private val file = File(plugin.dataFolder, "bundles.yml")
    private val logFile = File(plugin.dataFolder, "bundle-purchase.log")
    private val pendingFile = File(plugin.dataFolder, "bundle-pending.yml") // 접속하지 않은 사람에게 지급할 번들
    private val zone = ZoneId.of("Asia/Seoul")
    private val timeFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // 이름 순서 유지 (만든 순서대로 GUI에 표시)
    private val bundles = LinkedHashMap<String, Bundle>()

    // 지금 아이템을 편집 중인 번들 (두 명이 동시에 고치면 한쪽 내용이 사라지므로 막음)
    private val editing = HashMap<String, UUID>()

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        load()
    }

    // ───────────────────────── 저장 / 불러오기 ─────────────────────────

    private fun load() {
        val config = YamlConfiguration.loadConfiguration(file)
        config.getKeys(false).forEach { name ->
            val s = config.getConfigurationSection(name) ?: return@forEach
            val items = s.getList("items")?.filterIsInstance<ItemStack>() ?: emptyList()
            bundles[name] = Bundle(
                name = name,
                price = s.getLong("price"),
                createdAt = s.getLong("created-at"),
                expiresAt = if (s.contains("expires-at")) s.getLong("expires-at") else null,
                items = items
            )
        }
    }

    // 번들은 자주 안 바뀌므로 바뀔 때마다 바로 저장
    private fun save() {
        val config = YamlConfiguration()
        bundles.values.forEach { b ->
            config.set("${b.name}.price", b.price)
            config.set("${b.name}.created-at", b.createdAt)
            config.set("${b.name}.expires-at", b.expiresAt)
            config.set("${b.name}.items", b.items)
        }
        runCatching { config.save(file) }
            .onFailure { plugin.logger.severe("[번들] bundles.yml 저장 실패: ${it.message}") }
    }

    // ───────────────────────── 조회 ─────────────────────────

    fun get(name: String): Bundle? = bundles[name]

    fun all(): List<Bundle> = bundles.values.toList()

    fun onSale(): List<Bundle> = bundles.values.filter { it.isOnSale() }

    fun names(): List<String> = bundles.keys.toList()

    // ───────────────────────── 생성 / 수정 / 삭제 ─────────────────────────

    // YAML 경로 구분자(.)가 들어가면 저장이 깨지므로 막음
    fun isValidName(name: String): Boolean = name.isNotBlank() && !name.contains('.') && name.length <= 32

    // days가 0이면 무기한
    fun create(name: String, price: Long, days: Int, items: List<ItemStack>) {
        val now = System.currentTimeMillis()
        val expires = if (days > 0) now + days * 86_400_000L else null
        bundles[name] = Bundle(name, price, now, expires, items.map { it.clone() })
        save()
    }

    fun updateItems(name: String, items: List<ItemStack>) {
        val bundle = bundles[name] ?: return
        bundle.items = items.map { it.clone() }
        save()
    }

    fun delete(name: String): Boolean {
        if (bundles.remove(name) == null) return false
        save()
        return true
    }

    fun startEditing(name: String, player: Player): Boolean {
        val current = editing[name]
        if (current != null && current != player.uniqueId && Bukkit.getPlayer(current) != null) return false
        editing[name] = player.uniqueId
        return true
    }

    fun stopEditing(name: String) {
        editing.remove(name)
    }

    // ───────────────────────── 구매 ─────────────────────────

    fun buy(player: Player, bundle: Bundle): Result {
        if (!bundle.isOnSale()) return Result.Fail("판매 기간이 끝난 번들입니다.")
        if (bundle.items.isEmpty()) return Result.Fail("아이템이 없는 번들입니다.")
        if (!hasRoom(player, bundle.items)) return Result.Fail("인벤토리 공간이 부족합니다.")
        if (plugin.dcManager.getDC(player) < bundle.price) return Result.Fail("DC가 부족합니다. (보유: ${plugin.dcManager.getDC(player)} / 필요: ${bundle.price})")
        if (bundle.price > 0 && !plugin.dcManager.removeDC(player, bundle.price)) return Result.Fail("DC가 부족합니다.")

        player.inventory.addItem(*bundle.items.map { it.clone() }.toTypedArray())
        log(player, bundle)
        return Result.Success
    }

    // ───────────────────────── 관리자·콘솔 지급 (/번들지급) ─────────────────────────

    /** DC를 받지 않고 번들 아이템을 줌. 인벤토리가 가득 차면 발밑에 떨어뜨림 */
    fun give(player: Player, bundle: Bundle, by: String) {
        val leftover = player.inventory.addItem(*bundle.items.map { it.clone() }.toTypedArray())
        leftover.values.forEach { player.world.dropItemNaturally(player.location, it) }
        val time = ZonedDateTime.now(zone).format(timeFormat)
        runCatching { logFile.appendText("$time | ${player.uniqueId} | ${player.name} | ${bundle.name} | 지급 (by $by)\n") }
            .onFailure { plugin.logger.severe("[번들] bundle-purchase.log 기록 실패: ${it.message}") }
    }

    /** 접속 중이 아닌 사람: 다음 접속 때 지급되도록 저장 */
    fun addPending(uuid: UUID, bundleName: String) {
        val c = YamlConfiguration.loadConfiguration(pendingFile)
        c.set(uuid.toString(), c.getStringList(uuid.toString()) + bundleName)
        runCatching { c.save(pendingFile) }.onFailure { plugin.logger.severe("[번들] bundle-pending.yml 저장 실패: ${it.message}") }
    }

    /** 접속했을 때 대기 중인 번들을 꺼냄 (꺼내면 파일에서 지움) */
    fun takePending(uuid: UUID): List<String> {
        if (!pendingFile.exists()) return emptyList()
        val c = YamlConfiguration.loadConfiguration(pendingFile)
        val list = c.getStringList(uuid.toString())
        if (list.isEmpty()) return emptyList()
        c.set(uuid.toString(), null)
        runCatching { c.save(pendingFile) }.onFailure { plugin.logger.severe("[번들] bundle-pending.yml 저장 실패: ${it.message}") }
        return list
    }

    // 실제 인벤토리를 건드리지 않고 복사본에 넣어 보며 공간 확인
    private fun hasRoom(player: Player, items: List<ItemStack>): Boolean {
        val test = Bukkit.createInventory(null, 36)
        test.storageContents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return test.addItem(*items.map { it.clone() }.toTypedArray()).isEmpty()
    }

    // DC는 실제 돈이 오가는 재화라 구매 기록을 남김
    private fun log(player: Player, bundle: Bundle) {
        val time = ZonedDateTime.now(zone).format(timeFormat)
        runCatching { logFile.appendText("$time | ${player.uniqueId} | ${player.name} | ${bundle.name} | ${bundle.price} DC\n") }
            .onFailure { plugin.logger.severe("[번들] bundle-purchase.log 기록 실패: ${it.message}") }
    }
}
