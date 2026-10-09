package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.Nation
import kr.maeshil.digriss.nation.NationStorageHolder
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import java.io.File

// 국가 공용 창고: 국가원 모두가 같은 인벤토리를 함께 씀 (동시에 열어도 실시간 공유)
// 크기는 국가 레벨에 따라 커짐: Lv.1 27칸, Lv.2 36칸, Lv.3 45칸, Lv.4 이상 54칸
class NationStorageManager(private val plugin: Digriss) {

    private val file = File(plugin.dataFolder, "nation-storage.yml")
    private val inventories = mutableMapOf<String, Inventory>()
    private val saved = mutableMapOf<String, List<ItemStack?>>() // 아직 열지 않은 국가의 저장된 내용

    init {
        if (file.exists()) {
            val config = YamlConfiguration.loadConfiguration(file)
            config.getKeys(false).forEach { name ->
                @Suppress("UNCHECKED_CAST")
                saved[name] = (config.getList(name) as? List<ItemStack?>) ?: emptyList()
            }
        }
    }

    // 내정 2단계 기술이 있으면 한 줄 더
    fun sizeFor(name: String) = 9 * ((Nation.nations[name]?.level ?: 1) + 2 + plugin.nationTechManager.extraStorageRows(name)).coerceIn(3, 6)

    fun open(player: Player) {
        val name = plugin.nationManager.getNationName(player.uniqueId)
        if (name == null) {
            player.sendMessage("§c소속된 국가가 없습니다.")
            return
        }
        player.openInventory(inventoryOf(name))
        Sounds.play(player, org.bukkit.Sound.BLOCK_CHEST_OPEN, 0.7f, 1.0f)
    }

    private fun inventoryOf(name: String): Inventory {
        val size = sizeFor(name)
        val current = inventories[name]
        if (current != null && current.size >= size) return current

        // 처음 열거나 레벨업으로 칸이 늘어났으면 새로 만들고 내용 옮김
        val contents = current?.contents?.toList() ?: saved.remove(name) ?: emptyList()
        val inv = Bukkit.createInventory(NationStorageHolder(name), size, "§8국가 창고 - $name")
        contents.take(size).forEachIndexed { i, item -> if (item != null) inv.setItem(i, item) }
        current?.viewers?.toList()?.forEach { it.closeInventory() }
        inventories[name] = inv
        return inv
    }

    /** 거점 보상 등을 국가 창고에 넣음. 못 넣은 아이템 개수를 돌려줌 */
    fun deposit(name: String, items: List<ItemStack>): Int {
        val leftover = inventoryOf(name).addItem(*items.map { it.clone() }.toTypedArray())
        save()
        return leftover.values.sumOf { it.amount }
    }

    /** 창고에 있는 이 재료 개수 (이름·마법이 없는 기본 아이템만 셈) */
    fun count(name: String, type: Material): Int {
        val plain = ItemStack(type)
        return inventoryOf(name).contents.filterNotNull().filter { it.isSimilar(plain) }.sumOf { it.amount }
    }

    /** 재료가 전부 있으면 빼고 true, 하나라도 모자라면 아무것도 빼지 않고 false (기술 비용용) */
    fun take(name: String, cost: Map<Material, Int>): Boolean {
        if (cost.any { (type, amount) -> count(name, type) < amount }) return false
        val inv = inventoryOf(name)
        cost.forEach { (type, amount) -> inv.removeItem(ItemStack(type, amount)) }
        save()
        return true
    }

    // 국가 해체: 창고 아이템을 지정 위치(지도자 위치)에 떨어뜨림
    fun dropAll(name: String, location: Location) {
        val items = takeAll(name)
        items.forEach { location.world?.dropItemNaturally(location, it) }
        save()
    }

    // 국가 점령: 패배국 창고를 승리국 창고로 옮기고, 넘치는 건 공격자 위치에 떨어뜨림
    fun absorb(attacker: String, defender: String, overflowAt: Location) {
        val items = takeAll(defender)
        val leftover = inventoryOf(attacker).addItem(*items.toTypedArray())
        leftover.values.forEach { overflowAt.world?.dropItemNaturally(overflowAt, it) }
        save()
    }

    private fun takeAll(name: String): List<ItemStack> {
        val inv = inventories.remove(name)
        inv?.viewers?.toList()?.forEach { it.closeInventory() }
        val items = inv?.contents?.toList() ?: saved.remove(name) ?: emptyList()
        saved.remove(name)
        return items.filterNotNull().filter { !it.type.isAir }
    }

    // 국가 이름이 바뀌면 창고 내용을 새 이름으로 옮김 (열려 있던 창은 닫힘)
    fun rename(old: String, new: String) {
        val inv = inventories.remove(old)
        val items = inv?.contents?.toList() ?: saved.remove(old)
        inv?.viewers?.toList()?.forEach { it.closeInventory() }
        saved.remove(old)
        if (items != null) saved[new] = items
        save()
    }

    fun resetAll() {
        inventories.values.forEach { inv -> inv.viewers.toList().forEach { it.closeInventory() } }
        inventories.clear()
        saved.clear()
        save()
    }

    fun save() {
        val config = YamlConfiguration()
        saved.forEach { (name, items) -> config.set(name, items) }
        inventories.forEach { (name, inv) -> config.set(name, inv.contents.toList()) }
        runCatching { config.save(file) }.onFailure { plugin.logger.severe("nation-storage.yml 저장 실패: ${it.message}") }
    }
}
