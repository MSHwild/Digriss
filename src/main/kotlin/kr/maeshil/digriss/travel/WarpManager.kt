package kr.maeshil.digriss.travel

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.World
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

class WarpHolder(val slotWarps: Map<Int, String>) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

// 워프 역참 (/워프): 지구 맵이 넓어서 자원 거점 · 정해 둔 곳으로 돈을 내고 순간이동
//  - 이동 전 대기 시간 동안 움직이거나 전투가 시작되면 취소, 한 번 쓰면 쿨타임
//  - 먼 곳일수록 비용이 조금 더 듦
class WarpManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    private enum class Kind { SITE, POINT, ADDED }

    // 역참 하나. 위치는 이동할 때 그 자리 가장 높은 블록을 찾아서 정함 (목록을 볼 때 먼 청크를 불러오지 않게)
    private data class Warp(val name: String, val kind: Kind, val worldName: String, val x: Int, val z: Int,
                            val exact: Location? = null, val siteId: String? = null)

    private val file = File(plugin.dataFolder, "travel.yml")
    private val dataFile = File(plugin.dataFolder, "warp-data.yml") // 게임에서 추가한 역참

    private var warmupSeconds = 5
    private var cooldownSeconds = 300
    private var costMin = 5000.0
    private var costPer1000 = 1000.0
    private var costMax = 15000.0
    private var includeSites = true
    private var siteOffset = 30
    private var points: List<Warp> = emptyList()
    private val added = linkedMapOf<String, Location>()

    private val cooldowns = mutableMapOf<UUID, Long>()
    private val warming = mutableSetOf<UUID>()

    init {
        if (!file.exists() && plugin.getResource("travel.yml") != null) plugin.saveResource("travel.yml", false)
        load()
        loadData()
    }

    fun load() {
        val c = YamlConfiguration.loadConfiguration(file)
        warmupSeconds = c.getInt("warp.warmup-seconds", 5).coerceAtLeast(0)
        cooldownSeconds = c.getInt("warp.cooldown-seconds", 300).coerceAtLeast(0)
        costMin = c.getDouble("warp.cost-min", 5000.0).coerceAtLeast(0.0)
        costPer1000 = c.getDouble("warp.cost-per-1000", 1000.0).coerceAtLeast(0.0)
        costMax = c.getDouble("warp.cost-max", 15000.0).coerceAtLeast(costMin)
        includeSites = c.getBoolean("warp.include-sites", true)
        siteOffset = c.getInt("warp.site-offset", 30)
        points = c.getStringList("warp.points").mapNotNull { line ->
            val p = line.split(",").map { it.trim() }
            runCatching {
                when (p.size) {
                    3 -> Warp(p[0], Kind.POINT, "", p[1].toInt(), p[2].toInt())
                    4 -> Warp(p[0], Kind.POINT, p[1], p[2].toInt(), p[3].toInt())
                    else -> null
                }
            }.getOrNull().also { if (it == null) plugin.logger.warning("[워프] 역참 '$line'을(를) 읽을 수 없습니다. (예: 서울, 6501, -1924)") }
        }
    }

    // 지금 쓸 수 있는 모든 역참 (거점 → 설정 파일 → 게임에서 추가한 곳 순)
    private fun warps(): List<Warp> {
        val list = mutableListOf<Warp>()
        if (includeSites) plugin.resourceSiteManager.allSites().forEach { s ->
            list += Warp(s.name, Kind.SITE, s.worldName, s.x + siteOffset, s.z, siteId = s.id)
        }
        list += points
        added.forEach { (n, l) -> list += Warp(n, Kind.ADDED, l.world?.name ?: "", l.blockX, l.blockZ, exact = l) }
        return list.distinctBy { it.name }
    }

    private fun find(name: String): Warp? = warps().firstOrNull { it.name == name } ?: warps().firstOrNull { it.name.replace(" ", "") == name.replace(" ", "") }

    private fun worldOf(w: Warp): World? = (if (w.worldName.isBlank()) null else Bukkit.getWorld(w.worldName)) ?: Bukkit.getWorlds().firstOrNull()

    private fun costTo(player: Player, w: Warp): Double {
        val world = worldOf(w)
        if (world != player.world) return costMax
        val dx = player.location.x - w.x; val dz = player.location.z - w.z
        val dist = Math.sqrt(dx * dx + dz * dz)
        return Math.floor(costMin + dist / 1000.0 * costPer1000).coerceAtMost(costMax)
    }

    private fun distance(player: Player, w: Warp): Int? {
        if (worldOf(w) != player.world) return null
        val dx = player.location.x - w.x; val dz = player.location.z - w.z
        return Math.sqrt(dx * dx + dz * dz).toInt()
    }

    // ───────────────────────── 명령어 ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val admin = sender.hasPermission("digriss.admin")
        when (args.getOrNull(0)) {
            null -> (sender as? Player)?.let { open(it); Sounds.open(it) }
            "추가" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                val p = sender as? Player ?: return true
                val name = args.drop(1).joinToString(" ")
                if (name.isBlank()) return true.also { p.sendMessage("§e/워프 추가 <역참 이름>") }
                if (name.contains('.')) return true.also { p.sendMessage("§c역참 이름에 . 은 쓸 수 없습니다.") }
                added[name] = p.location.clone()
                saveData()
                p.sendMessage("§a역참 '§f$name§a'을(를) 지금 위치에 추가했습니다.")
                plugin.adminLogManager.log(sender, "워프 역참 추가: $name")
            }
            "삭제" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                val name = args.drop(1).joinToString(" ")
                if (added.remove(name) == null) return true.also { sender.sendMessage("§c게임에서 추가한 역참만 지울 수 있습니다. §7(거점·travel.yml 역참은 파일에서)") }
                saveData()
                sender.sendMessage("§e역참 '$name'을(를) 지웠습니다.")
                plugin.adminLogManager.log(sender, "워프 역참 삭제: $name")
            }
            else -> {
                val p = sender as? Player ?: return true
                val w = find(args.joinToString(" ")) ?: return true.also { deny(p, "§c그런 역참이 없습니다. §7(/워프 로 목록 보기)") }
                startWarp(p, w)
            }
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val typed = args.joinToString(" ")
        val names = warps().map { it.name } + if (sender.hasPermission("digriss.admin")) listOf("추가", "삭제") else emptyList()
        if (args.size >= 2 && args[0] == "삭제") {
            val typedName = args.drop(1).joinToString(" ")
            return added.keys.filter { it.startsWith(typedName) }.map { it.split(" ").drop(args.size - 2).joinToString(" ") }
        }
        // 이름에 띄어쓰기가 있어 마지막 단어만 이어서 완성
        return names.filter { it.startsWith(typed) }.map { it.split(" ").drop(args.size - 1).joinToString(" ") }
    }

    // ───────────────────────── 이동 ─────────────────────────

    private fun startWarp(player: Player, w: Warp) {
        val now = System.currentTimeMillis()
        val until = cooldowns[player.uniqueId] ?: 0L
        if (until > now) return deny(player, "§c워프는 ${(until - now) / 1000 + 1}초 뒤에 다시 쓸 수 있습니다.")
        if (plugin.combatManager.isInCombat(player)) return deny(player, "§c전투 중에는 워프할 수 없습니다.")
        if (!warming.add(player.uniqueId)) return deny(player, "§c이미 워프 대기 중입니다.")
        val cost = costTo(player, w)
        val econ = economy()
        if (cost > 0 && (econ == null || !econ.has(player, cost))) {
            warming.remove(player.uniqueId)
            return deny(player, "§c소지금이 부족합니다. (비용 ${fmt(cost)}원)")
        }

        val start = player.location.clone()
        if (warmupSeconds > 0) {
            player.sendMessage("§e${warmupSeconds}초 뒤 §f${w.name}§e(으)로 워프합니다. §7(비용 ${fmt(cost)}원, 움직이면 취소)")
            Sounds.play(player, Sound.BLOCK_PORTAL_TRIGGER, 0.3f, 1.6f)
        }
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!warming.remove(player.uniqueId) || !player.isOnline) return@Runnable
            if (plugin.combatManager.isInCombat(player)) return@Runnable deny(player, "§c전투가 시작되어 워프가 취소되었습니다.")
            if (player.world != start.world || player.location.distanceSquared(start) > 1.0) return@Runnable deny(player, "§c움직여서 워프가 취소되었습니다.")
            resolve(w) { target ->
                if (!player.isOnline) return@resolve
                if (target == null) return@resolve deny(player, "§c역참 위치를 찾을 수 없습니다.")
                val e = economy()
                if (cost > 0 && (e == null || !e.has(player, cost))) return@resolve deny(player, "§c소지금이 부족합니다. (비용 ${fmt(cost)}원)")
                if (cost > 0) e?.withdrawPlayer(player, cost)
                cooldowns[player.uniqueId] = System.currentTimeMillis() + cooldownSeconds * 1000L
                player.world.spawnParticle(Particle.PORTAL, player.location.add(0.0, 1.0, 0.0), 40, 0.4, 0.8, 0.4, 0.2)
                player.teleportAsync(target).thenAccept { ok ->
                    if (!ok) return@thenAccept
                    Sounds.play(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f)
                    player.sendMessage("§a§f${w.name}§a(으)로 워프했습니다. §7(-${fmt(cost)}원)")
                }
            }
        }, warmupSeconds * 20L)
    }

    // 도착 위치: 게임에서 추가한 곳은 그 자리 그대로, 나머지는 청크를 비동기로 불러와 가장 높은 블록 위
    private fun resolve(w: Warp, callback: (Location?) -> Unit) {
        w.exact?.let { if (it.world != null) return callback(it.clone()) }
        val world = worldOf(w) ?: return callback(null)
        world.getChunkAtAsync(w.x shr 4, w.z shr 4).thenAccept {
            val y = world.getHighestBlockYAt(w.x, w.z) + 1
            callback(Location(world, w.x + 0.5, y.toDouble(), w.z + 0.5))
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        warming.remove(e.player.uniqueId)
    }

    // ───────────────────────── GUI ─────────────────────────

    fun open(player: Player) {
        val list = warps().take(45)
        val slotWarps = list.withIndex().associate { it.index to it.value.name }
        val inv = Bukkit.createInventory(WarpHolder(slotWarps), 54, "§8워프 역참")
        val cd = ((cooldowns[player.uniqueId] ?: 0L) - System.currentTimeMillis()).coerceAtLeast(0) / 1000
        list.forEachIndexed { i, w ->
            val (key, mat) = when (w.kind) {
                Kind.SITE -> "warp.site" to Material.LODESTONE
                else -> "warp.point" to Material.ENDER_PEARL
            }
            val lore = mutableListOf<String>()
            if (w.kind == Kind.SITE) {
                val owner = plugin.resourceSiteManager.allSites().firstOrNull { it.id == w.siteId }?.owner
                lore += "§7자원 거점 §8| §7주인: ${owner?.let { "§a$it" } ?: "§8없음"}"
            }
            lore += "§7좌표: §f${w.x}, ${w.z}"
            lore += distance(player, w)?.let { "§7거리: §f${"%,d".format(it)}블록" } ?: "§7다른 월드"
            lore += "§7비용: §6${fmt(costTo(player, w))}원"
            lore += ""
            lore += if (cd > 0) "§c쿨타임 ${cd}초" else "§e클릭: 워프 §8(${warmupSeconds}초 대기)"
            inv.setItem(i, item(icon(key, mat), "§f§l${w.name}", lore))
        }
        if (list.isEmpty()) inv.setItem(22, item(icon("common.empty", Material.BARRIER), "§7역참이 없습니다.", emptyList()))
        val filler = item(icon("common.filler", Material.BLACK_STAINED_GLASS_PANE), " ", emptyList())
        for (i in 45 until 54) inv.setItem(i, filler)
        inv.setItem(45, kr.maeshil.digriss.menu.MainMenu.backItem(plugin))
        inv.setItem(49, item(icon("warp.info", Material.BOOK), "§6§l워프 안내", listOf(
            "§7역참을 골라 돈을 내고 순간이동해요.",
            "§7먼 곳일수록 비용이 조금 더 들어요. §8(${fmt(costMin)} ~ ${fmt(costMax)}원)",
            "§7이동 전 §f${warmupSeconds}초§7 동안 움직이면 취소, 전투 중엔 못 써요.",
            "§7한 번 쓰면 §f${cooldownSeconds}초§7 쿨타임.",
            "§7가까운 곳은 §e/탈것§7 으로 빠른 말을 불러 달려 보세요.",
            "",
            "§7내 소지금: §6${fmt(economy()?.getBalance(player) ?: 0.0)}원")))
        inv.setItem(51, item(icon("warp.mount", Material.SADDLE), "§e§l탈것 부르기", listOf("§7빠른 말을 불러 타요", "", "§e클릭: /탈것")))
        player.openInventory(inv)
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? WarpHolder ?: return
        e.isCancelled = true
        val player = e.whoClicked as? Player ?: return
        if (e.clickedInventory != e.view.topInventory) return
        when (e.rawSlot) {
            45 -> return kr.maeshil.digriss.menu.MainMenu.back(plugin, player)
            51 -> {
                Sounds.click(player)
                Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) { player.closeInventory(); player.performCommand("탈것") } })
                return
            }
        }
        val name = holder.slotWarps[e.rawSlot] ?: return
        val w = find(name) ?: return
        Sounds.click(player)
        Bukkit.getScheduler().runTask(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            player.closeInventory()
            startWarp(player, w)
        })
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is WarpHolder) e.isCancelled = true
    }

    // ───────────────────────── 저장 / 공통 ─────────────────────────

    private fun loadData() {
        if (!dataFile.exists()) return
        val c = YamlConfiguration.loadConfiguration(dataFile)
        c.getConfigurationSection("points")?.getKeys(false)?.forEach { n -> c.getLocation("points.$n")?.let { added[n] = it } }
    }

    private fun saveData() {
        val c = YamlConfiguration()
        added.forEach { (n, l) -> c.set("points.$n", l) }
        runCatching { c.save(dataFile) }.onFailure { plugin.logger.severe("[워프] warp-data.yml 저장 실패: ${it.message}") }
    }

    private fun economy(): Economy? = Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider

    private fun fmt(v: Double) = "%,.0f".format(v)

    private fun deny(p: Player, message: String) {
        p.sendMessage(message)
        Sounds.fail(p)
    }

    private fun icon(key: String, default: Material): ItemStack = plugin.iconManager.get(key, default)

    private fun item(base: ItemStack, name: String, lore: List<String>): ItemStack {
        val meta = base.itemMeta ?: return base
        meta.setDisplayName(name)
        meta.lore = lore
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        base.itemMeta = meta
        return base
    }
}
