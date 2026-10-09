package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File

class NationTechHolder : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

/**
 * 국가 기술 트리: 군사 / 경제 / 내정 세 갈래, 각 3단계. 지도자가 국가 금고로 구매 (앞 단계 필요)
 * 데이터: nation-tech.yml (국가 이름 → 갈래별 단계)
 */
class NationTechManager(private val plugin: Digriss) : Listener {

    enum class Branch(val key: String, val display: String, val color: String, val icon: Material, val row: Int) {
        MILITARY("military", "군사", "§c", Material.IRON_SWORD, 1),
        ECONOMY("economy", "경제", "§6", Material.GOLD_INGOT, 2),
        INTERNAL("internal", "내정", "§a", Material.BOOKSHELF, 3)
    }

    private class Tier(val name: String, val effect: String)

    private val tiers = mapOf(
        Branch.MILITARY to listOf(
            Tier("훈련", "전쟁 상대에게 주는 피해 +5%"),
            Tier("요새화", "우리 영토에서 받는 피해 -5%"),
            Tier("강습", "자원 거점 점령 속도 +25%")
        ),
        Branch.ECONOMY to listOf(
            Tier("세제 개편", "일일 유지비 -20%"),
            Tier("교역로", "자원 거점 보상 +25%"),
            Tier("국고", "국가원 퀘스트 돈 보상의 10%가 금고에 추가")
        ),
        Branch.INTERNAL to listOf(
            Tier("개척", "영토 한도 국가원당 10 → 12청크"),
            Tier("창고 확장", "국가 창고 +1줄"),
            Tier("번영", "내실 점수 +20%")
        )
    )

    companion object {
        val COSTS = listOf(2000.0, 4000.0, 7000.0)
        val REQUIRED_LEVEL = listOf(1, 3, 5)
        private val TIER_SLOTS = listOf(3, 5, 7) // 줄 안에서의 칸 (헤더는 1번 칸)
        private const val BACK_SLOT = 36
    }

    private val file = File(plugin.dataFolder, "nation-tech.yml")
    private val levels = HashMap<String, MutableMap<Branch, Int>>()

    init {
        if (file.exists()) {
            val c = YamlConfiguration.loadConfiguration(file)
            c.getKeys(false).forEach { nation ->
                levels[nation] = Branch.entries.associateWith { c.getInt("$nation.${it.key}", 0).coerceIn(0, 3) }.toMutableMap()
            }
        }
    }

    private fun save() {
        val c = YamlConfiguration()
        levels.forEach { (nation, m) -> m.forEach { (b, lv) -> if (lv > 0) c.set("$nation.${b.key}", lv) } }
        runCatching { c.save(file) }.onFailure { plugin.logger.severe("[기술] nation-tech.yml 저장 실패: ${it.message}") }
    }

    fun level(nation: String?, branch: Branch): Int = nation?.let { levels[it]?.get(branch) } ?: 0

    // ───────────────────────── 효과 ─────────────────────────

    fun captureSpeedMultiplier(nation: String) = if (level(nation, Branch.MILITARY) >= 3) 1.25 else 1.0
    fun taxMultiplier(nation: String) = if (level(nation, Branch.ECONOMY) >= 1) 0.8 else 1.0
    fun siteRewardMultiplier(nation: String) = if (level(nation, Branch.ECONOMY) >= 2) 1.25 else 1.0
    fun questBankRate(nation: String?) = if (level(nation, Branch.ECONOMY) >= 3) 0.10 else 0.0
    fun claimsPerMember(nation: String) = if (level(nation, Branch.INTERNAL) >= 1) 12 else 10
    fun extraStorageRows(nation: String) = if (level(nation, Branch.INTERNAL) >= 2) 1 else 0
    fun peaceMultiplier(nation: String) = if (level(nation, Branch.INTERNAL) >= 3) 1.2 else 1.0

    // 군사 1단계(주는 피해 +5%, 전쟁 상대에게만) / 2단계(우리 영토에서 받는 피해 -5%)
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(e: EntityDamageByEntityEvent) {
        val victim = e.entity as? Player ?: return
        val attacker = (e.damager as? Player) ?: ((e.damager as? Projectile)?.shooter as? Player) ?: return
        if (attacker == victim) return
        val nm = plugin.nationManager
        val an = nm.getNationName(attacker.uniqueId)
        val vn = nm.getNationName(victim.uniqueId)
        var mult = 1.0
        if (an != null && vn != null && nm.isAtWarBetween(an, vn) && level(an, Branch.MILITARY) >= 1) mult *= 1.05
        if (vn != null && level(vn, Branch.MILITARY) >= 2 && nm.territoryOwnerAt(victim.location) == vn) mult *= 0.95
        if (mult != 1.0) e.damage = e.damage * mult
    }

    // ───────────────────────── 국가 변화 ─────────────────────────

    fun renameNation(old: String, new: String) { levels.remove(old)?.let { levels[new] = it; save() } }
    fun removeNation(name: String) { if (levels.remove(name) != null) save() }

    // ───────────────────────── GUI ─────────────────────────

    fun open(player: Player) {
        val nationName = plugin.nationManager.getNationName(player.uniqueId)
            ?: return player.sendMessage("§c소속된 국가가 없습니다.")
        val nation = Nation.nations[nationName] ?: return
        val isLeader = nation.leader == player.uniqueId

        val inv = Bukkit.createInventory(NationTechHolder(), 45, "§8국가 기술 - $nationName")
        val filler = item(plugin.iconManager.get("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ")
        for (i in 0 until inv.size) inv.setItem(i, filler)

        inv.setItem(4, item(ItemStack(Material.BEACON), "§f$nationName §7(Lv.${nation.level})",
            "§7금고 §f${nation.bank.toLong()}원",
            "§7지도자가 금고 돈으로 기술을 배웁니다.",
            "§7앞 단계를 배워야 다음 단계를 배울 수 있어요."))

        Branch.entries.forEach { b ->
            val row = b.row * 9
            val current = level(nationName, b)
            inv.setItem(row + 1, item(plugin.iconManager.get("tech.${b.key}", b.icon), "${b.color}${b.display}", "§7$current / 3단계"))
            tiers.getValue(b).forEachIndexed { i, tier ->
                val state = when {
                    current > i -> "done"
                    current == i -> "next"
                    else -> "locked"
                }
                val base = when (state) {
                    "done" -> plugin.iconManager.get("tech.done", Material.LIME_DYE)
                    "next" -> plugin.iconManager.get("tech.next", Material.YELLOW_DYE)
                    else -> plugin.iconManager.get("tech.locked", Material.GRAY_DYE)
                }
                val lore = mutableListOf("§7${tier.effect}", "")
                when (state) {
                    "done" -> lore += "§a배움"
                    else -> {
                        lore += "§7비용 §f${COSTS[i].toLong()}원"
                        lore += "§7필요 국가 레벨 §f${REQUIRED_LEVEL[i]}"
                        if (state == "next") lore += if (isLeader) "§e클릭해서 배우기" else "§8지도자만 배울 수 있어요"
                        else lore += "§8앞 단계를 먼저 배워야 해요"
                    }
                }
                inv.setItem(row + TIER_SLOTS[i], item(base, "${b.color}${i + 1}단계 · ${tier.name}", *lore.toTypedArray()))
            }
        }
        inv.setItem(BACK_SLOT, item(plugin.iconManager.get("common.back", Material.ARROW), "§7← 국가 메뉴로"))
        player.openInventory(inv)
    }

    private fun item(base: ItemStack, name: String, vararg lore: String): ItemStack {
        val meta = base.itemMeta ?: return base
        meta.setDisplayName(name)
        meta.lore = lore.toList()
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        base.itemMeta = meta
        return base
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        if (e.view.topInventory.holder !is NationTechHolder) return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val player = e.whoClicked as? Player ?: return

        if (e.rawSlot == BACK_SLOT) {
            Sounds.click(player)
            player.closeInventory()
            Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) player.performCommand("국가") })
            return
        }
        val branch = Branch.entries.firstOrNull { it.row == e.rawSlot / 9 } ?: return
        val tierIndex = TIER_SLOTS.indexOf(e.rawSlot % 9).takeIf { it >= 0 } ?: return
        learn(player, branch, tierIndex)
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) open(player) })
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is NationTechHolder) e.isCancelled = true
    }

    private fun learn(player: Player, branch: Branch, tierIndex: Int) {
        val nm = plugin.nationManager
        val nationName = nm.getNationName(player.uniqueId) ?: return
        val nation = Nation.nations[nationName] ?: return
        val current = level(nationName, branch)
        when {
            nation.leader != player.uniqueId -> return nm.deny(player, "§c국가 지도자만 기술을 배울 수 있습니다.")
            tierIndex < current -> return nm.deny(player, "§7이미 배운 기술입니다.")
            tierIndex > current -> return nm.deny(player, "§c앞 단계를 먼저 배워야 합니다.")
            nation.level < REQUIRED_LEVEL[tierIndex] -> return nm.deny(player, "§c국가 레벨 ${REQUIRED_LEVEL[tierIndex]} 이상이어야 합니다.")
            nation.bank < COSTS[tierIndex] -> return nm.deny(player, "§c금고가 부족합니다. (필요 ${COSTS[tierIndex].toLong()}원, 현재 ${nation.bank.toLong()}원)")
        }
        nation.bank -= COSTS[tierIndex]
        levels.getOrPut(nationName) { Branch.entries.associateWith { 0 }.toMutableMap() }[branch] = tierIndex + 1
        save()
        nm.saveNations()
        nm.addPeace(nationName, 20.0)
        val tier = tiers.getValue(branch)[tierIndex]
        Sounds.bigReward(player)
        nm.notifyNation(nationName, "§a[기술] ${branch.color}${branch.display} ${tierIndex + 1}단계 '${tier.name}'§a을(를) 배웠습니다! §7(${tier.effect})")
    }
}
