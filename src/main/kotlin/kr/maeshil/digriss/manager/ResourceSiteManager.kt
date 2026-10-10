package kr.maeshil.digriss.manager

import kr.maeshil.digriss.ActionBarManager
import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.nation.BlueMapBridge
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.World
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

/**
 * 자원 거점 (sites.yml)
 * 실제 지구 지명 위치에 거점이 있고, 반경 안에 한 국가의 국가원만 있으면 점령 게이지가 참.
 * 다른 국가가 같이 있으면 교전(멈춤). 점령한 국가는 매일 자정 금고 돈 + 특산 자원(국가 창고) + 내실 점수를 받음.
 * 거점 주변은 건축·파괴·영토 점령이 금지됨.
 */
class ResourceSiteManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    class Site(
        val id: String,
        val name: String,
        val worldName: String,
        val x: Int,
        val z: Int,
        val rewards: List<ItemStack>,
        val rewardText: String
    ) {
        var owner: String? = null
        var capturing: String? = null   // 지금 게이지를 채우는 국가
        var progress = 0.0              // 점령 게이지 (초)
        var contested = false
        var groundY: Int? = null        // 파티클 높이 (청크가 로드됐을 때 한 번 계산)
        var bar: BossBar? = null
    }

    private val zone = ZoneId.of("Asia/Seoul")
    private val configFile = File(plugin.dataFolder, "sites.yml")
    private val dataFile = File(plugin.dataFolder, "sites-data.yml")

    private val sites = LinkedHashMap<String, Site>()
    private var captureSeconds = 300
    private var radius = 15.0
    private var protectRadius = 20.0
    private var dailyMoney = 1000.0
    private var dailyPeace = 5.0
    private var lastPayDate: String = ""
    private var tick = 0
    private val playerSite = HashMap<UUID, String>()        // 지금 서 있는 거점 (들어오고 나갈 때 안내용)
    private val lastGuide = HashMap<String, Long>()         // "uuid:거점" → 마지막으로 채팅 안내한 시각

    init {
        load()
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { update() }, 20L, 20L)
        BlueMapBridge.onReady { sites.values.forEach { drawMarker(it) } }
    }

    // ───────────────────────── 설정 ─────────────────────────

    fun load() {
        if (!configFile.exists()) {
            plugin.dataFolder.mkdirs()
            if (plugin.getResource("sites.yml") != null) plugin.saveResource("sites.yml", false)
            else YamlConfiguration().apply {
                // jar 안에 sites.yml이 없을 때(IntelliJ 아티팩트 빌드 등) 최소한의 기본값
                set("capture-seconds", 300); set("radius", 15); set("protect-radius", 20)
                set("daily-money", 1000); set("daily-peace", 5)
                set("sites.persian_gulf.name", "페르시아만 유전"); set("sites.persian_gulf.x", 2540); set("sites.persian_gulf.z", -1300)
                set("sites.persian_gulf.rewards", listOf("COAL_BLOCK:16"))
                save(configFile)
            }
        }
        val c = YamlConfiguration.loadConfiguration(configFile)
        captureSeconds = c.getInt("capture-seconds", 300).coerceAtLeast(10)
        radius = c.getDouble("radius", 15.0).coerceIn(3.0, 64.0)
        protectRadius = c.getDouble("protect-radius", 20.0).coerceAtLeast(radius)
        dailyMoney = c.getDouble("daily-money", 1000.0).coerceAtLeast(0.0)
        dailyPeace = c.getDouble("daily-peace", 5.0).coerceAtLeast(0.0)

        // 보스바 정리 후 다시 만듦 (주인 정보는 데이터 파일에서 다시 읽음)
        sites.values.forEach { it.bar?.removeAll() }
        val oldProgress = sites.mapValues { Triple(it.value.capturing, it.value.progress, it.value.owner) }
        sites.clear()

        val section = c.getConfigurationSection("sites")
        section?.getKeys(false)?.forEach { id ->
            val s = section.getConfigurationSection(id) ?: return@forEach
            val rewards = s.getStringList("rewards").mapNotNull { parseItem(it) }
            val site = Site(
                id,
                s.getString("name", id) ?: id,
                s.getString("world", "") ?: "",
                s.getInt("x"),
                s.getInt("z"),
                rewards,
                rewards.joinToString(", ") { "${koName(it.type)} ${it.amount}개" }
            )
            oldProgress[id]?.let { (cap, prog, _) -> site.capturing = cap; site.progress = prog }
            sites[id] = site
        }

        val d = YamlConfiguration.loadConfiguration(dataFile)
        lastPayDate = d.getString("last-pay-date", "") ?: ""
        sites.values.forEach { site ->
            site.owner = d.getString("owners.${site.id}")?.takeIf { Nation.nations.containsKey(it) }
        }
        sites.values.forEach { drawMarker(it) }
    }

    private fun parseItem(text: String): ItemStack? {
        val parts = text.split(":")
        val type = Material.matchMaterial(parts[0].trim()) ?: run {
            plugin.logger.warning("[거점] sites.yml의 아이템 이름이 잘못됐습니다: $text")
            return null
        }
        return ItemStack(type, (parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 1).coerceIn(1, 64 * 9))
    }

    private fun koName(type: Material): String = when (type) {
        Material.COAL_BLOCK -> "석탄 블록"; Material.DIAMOND -> "다이아몬드"; Material.OAK_LOG -> "참나무 원목"
        Material.SPRUCE_LOG -> "가문비나무 원목"; Material.COPPER_INGOT -> "구리 주괴"; Material.IRON_INGOT -> "철 주괴"
        Material.EMERALD -> "에메랄드"; Material.NETHERITE_SCRAP -> "네더라이트 조각"; Material.GOLD_INGOT -> "금 주괴"
        Material.REDSTONE -> "레드스톤"; Material.LAPIS_LAZULI -> "청금석"; Material.COAL -> "석탄"
        Material.WHEAT -> "밀"; Material.COCOA_BEANS -> "코코아 콩"; Material.MELON -> "수박"; Material.GLOWSTONE_DUST -> "발광석 가루"
        else -> type.name.lowercase()
    }

    private fun saveData() {
        val d = YamlConfiguration()
        d.set("last-pay-date", lastPayDate)
        sites.values.forEach { site -> site.owner?.let { d.set("owners.${site.id}", it) } }
        runCatching { d.save(dataFile) }.onFailure { plugin.logger.severe("[거점] sites-data.yml 저장 실패: ${it.message}") }
    }

    private fun worldOf(site: Site): World? =
        (if (site.worldName.isNotBlank()) Bukkit.getWorld(site.worldName) else null) ?: Bukkit.getWorlds().firstOrNull()

    // ───────────────────────── 조회 ─────────────────────────

    /** 이 위치가 거점 보호 구역 안인지 (건축·영토 점령·건국 금지) */
    fun siteAt(loc: Location, extra: Double = 0.0): Site? = sites.values.firstOrNull { site ->
        val w = worldOf(site) ?: return@firstOrNull false
        loc.world == w && dist2(loc.x, loc.z, site) <= (protectRadius + extra) * (protectRadius + extra)
    }

    /** 청크가 거점 보호 구역과 겹치는지 (영토 점령 금지용) */
    fun chunkTouchesSite(world: World, chunkX: Int, chunkZ: Int): Site? = sites.values.firstOrNull { site ->
        if (worldOf(site) != world) return@firstOrNull false
        val nearestX = site.x.toDouble().coerceIn(chunkX * 16.0, chunkX * 16.0 + 16)
        val nearestZ = site.z.toDouble().coerceIn(chunkZ * 16.0, chunkZ * 16.0 + 16)
        dist2(nearestX, nearestZ, site) <= protectRadius * protectRadius
    }

    fun sitesOwnedBy(nation: String): List<Site> = sites.values.filter { it.owner == nation }

    /** 모든 거점 (워프 역참 등에서 사용) */
    fun allSites(): List<Site> = sites.values.toList()

    fun worldOfSite(site: Site): World? = worldOf(site)

    fun protectRadius(): Double = protectRadius

    private fun dist2(x: Double, z: Double, site: Site): Double {
        val dx = x - (site.x + 0.5); val dz = z - (site.z + 0.5)
        return dx * dx + dz * dz
    }

    // ───────────────────────── 매초 점령 처리 ─────────────────────────

    private fun update() {
        tick++
        sites.values.forEach { updateSite(it) }
        checkEnterLeave()
        checkDailyPay()
    }

    private fun updateSite(site: Site) {
        val world = worldOf(site) ?: return
        val inside = Bukkit.getOnlinePlayers().filter { p ->
            p.world == world && !p.isDead && p.gameMode != GameMode.SPECTATOR && p.gameMode != GameMode.CREATIVE &&
                !plugin.newbieProtectionManager.isProtected(p) && // 무적 상태로 점령하는 것 방지
                dist2(p.location.x, p.location.z, site) <= radius * radius
        }
        val nations = inside.mapNotNull { plugin.nationManager.getNationName(it.uniqueId) }.toSet()

        site.contested = nations.size > 1
        when {
            site.contested -> {} // 교전 중: 게이지 멈춤
            nations.size == 1 -> {
                val nation = nations.first()
                if (nation == site.owner) {
                    // 주인이 지키고 있으면 남의 점령 게이지가 빠르게 줄어듦
                    site.progress = (site.progress - 3).coerceAtLeast(0.0)
                    if (site.progress == 0.0) site.capturing = null
                } else {
                    if (site.capturing != nation) { site.capturing = nation; site.progress = 0.0 }
                    site.progress += captureSpeed(nation)
                    if (site.progress >= captureSeconds) capture(site, nation)
                }
            }
            else -> {
                // 아무도 없으면 천천히 줄어듦
                site.progress = (site.progress - 1).coerceAtLeast(0.0)
                if (site.progress == 0.0) site.capturing = null
            }
        }

        updateBar(site, inside, world)
        if (tick % 2 == 0) drawRing(site, world)
    }

    // 거점 보호 구역에 들어오면 제목 + 안내, 나가면 액션바
    private fun checkEnterLeave() {
        Bukkit.getOnlinePlayers().forEach { p ->
            val now = siteAt(p.location)?.id
            val before = playerSite[p.uniqueId]
            if (now == before) return@forEach
            if (now == null) playerSite.remove(p.uniqueId) else playerSite[p.uniqueId] = now
            before?.let { sites[it] }?.let { ActionBarManager.showTemp(p, "§7${it.name}을(를) 벗어났습니다", 2.0) }
            now?.let { sites[it] }?.let { onEnter(p, it) }
        }
    }

    private fun onEnter(p: Player, site: Site) {
        val owner = site.owner?.let { "§a$it" } ?: "§7없음"
        p.sendTitle("§6§l자원 거점", "§f${site.name} §7· 주인 $owner", 5, 40, 10)
        Sounds.play(p, org.bukkit.Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.4f)

        // 채팅 안내는 같은 거점에 5분에 한 번만
        val key = "${p.uniqueId}:${site.id}"
        val nowMs = System.currentTimeMillis()
        if (nowMs - (lastGuide[key] ?: 0L) < 5 * 60_000L) return
        lastGuide[key] = nowMs
        p.sendMessage("§6[거점] §f${site.name}§7에 들어왔습니다. 주인: $owner")
        p.sendMessage("§7가운데 빛나는 원 안에서 §e${captureSeconds / 60}분§7 버티면 점령 · 다른 국가가 들어오면 교전(멈춤)")
        p.sendMessage("§7점령하면 매일 금고 §e${dailyMoney.toLong()}원§7 + §e${site.rewardText}§7 (절반은 기술 자재) · 거점 주변은 건축/파괴 금지")
        when {
            plugin.nationManager.getNationName(p.uniqueId) == null -> p.sendMessage("§c국가가 있어야 점령할 수 있어요.")
            plugin.newbieProtectionManager.isProtected(p) -> p.sendMessage("§c초보 보호 중에는 점령에 참여할 수 없어요. §7(/보호해제 확인)")
        }
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        playerSite.remove(e.player.uniqueId)
    }

    /** 초당 게이지 증가량. 기술 트리(군사 3단계)가 있으면 빨라짐 */
    private fun captureSpeed(nation: String): Double = 1.0 * plugin.nationTechManager.captureSpeedMultiplier(nation)

    private fun capture(site: Site, nation: String) {
        val before = site.owner
        site.owner = nation
        site.capturing = null
        site.progress = 0.0
        saveData()
        drawMarker(site)

        val text = if (before == null) "'$nation' 국가가 ${site.name}을(를) 점령했습니다!"
        else "'$nation' 국가가 '$before' 국가로부터 ${site.name}을(를) 빼앗았습니다!"
        Bukkit.broadcastMessage("§6[거점] §f$text")
        plugin.nationManager.notifyNation(nation, "§a[거점] 매일 자정 금고 ${dailyMoney.toLong()}원과 ${site.rewardText}을(를) 받습니다.")
        Sounds.all(org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f)
        plugin.discordNotifier.notify("site-capture", "거점 점령: ${site.name}",
            if (before == null) "**$nation** 국가가 **${site.name}**을(를) 점령했습니다."
            else "**$nation** 국가가 **$before** 국가로부터 **${site.name}**을(를) 빼앗았습니다.",
            DiscordNotifier.GOLD)
    }

    private fun updateBar(site: Site, inside: List<Player>, world: World) {
        val bar = site.bar ?: Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SEGMENTED_10).also { site.bar = it }
        val ownerText = site.owner ?: "없음"
        when {
            site.contested -> { bar.setTitle("§f${site.name} §7· §c교전 중 §7(주인: $ownerText)"); bar.color = BarColor.RED }
            site.capturing != null -> {
                val pct = (site.progress / captureSeconds * 100).toInt().coerceIn(0, 100)
                bar.setTitle("§f${site.name} §7· §e${site.capturing} 점령 중 $pct%"); bar.color = BarColor.YELLOW
            }
            else -> { bar.setTitle("§f${site.name} §7· 주인: §a$ownerText"); bar.color = BarColor.GREEN }
        }
        bar.progress = if (site.capturing != null) (site.progress / captureSeconds).coerceIn(0.0, 1.0) else 1.0

        // 반경보다 조금 넓은 곳까지 보스바를 보여줌
        val showRange = radius + 10
        val viewers = Bukkit.getOnlinePlayers().filter { p ->
            p.world == world && dist2(p.location.x, p.location.z, site) <= showRange * showRange
        }.toSet()
        bar.players.filter { it !in viewers }.forEach { bar.removePlayer(it) }
        viewers.filter { it !in bar.players }.forEach { bar.addPlayer(it) }
        if (inside.isEmpty() && viewers.isEmpty()) bar.removeAll()
    }

    // 점령 범위를 파티클 원으로 표시 (근처에 사람이 있을 때만)
    private fun drawRing(site: Site, world: World) {
        val near = Bukkit.getOnlinePlayers().any { it.world == world && dist2(it.location.x, it.location.z, site) <= 64.0 * 64.0 }
        if (!near) return
        val y = site.groundY ?: (world.getHighestBlockYAt(site.x, site.z) + 1).also { site.groundY = it }
        val particle = when {
            site.contested -> Particle.FLAME
            site.capturing != null -> Particle.WAX_ON
            else -> Particle.HAPPY_VILLAGER
        }
        val points = 48
        for (i in 0 until points) {
            val a = Math.PI * 2 * i / points
            world.spawnParticle(particle, site.x + 0.5 + radius * cos(a), y + 0.3, site.z + 0.5 + radius * sin(a), 1, 0.0, 0.0, 0.0, 0.0)
        }
        world.spawnParticle(Particle.END_ROD, site.x + 0.5, y + 1.0, site.z + 0.5, 3, 0.2, 1.0, 0.2, 0.01)
    }

    // ───────────────────────── 매일 자정 보상 ─────────────────────────

    private fun checkDailyPay() {
        val today = LocalDate.now(zone).toString()
        if (lastPayDate == today) return
        val first = lastPayDate.isBlank()
        lastPayDate = today
        saveData()
        if (first) return // 처음 켰을 때는 지급하지 않고 날짜만 기록

        var paid = false
        sites.values.forEach { site ->
            val owner = site.owner ?: return@forEach
            val nation = Nation.nations[owner] ?: run { site.owner = null; return@forEach }
            val mult = plugin.nationTechManager.siteRewardMultiplier(owner)
            nation.bank += dailyMoney * mult
            val items = site.rewards.map { it.clone().apply { amount = (amount * mult).toInt().coerceAtLeast(1) } }
            // 절반(올림)은 기술 자재로, 나머지는 국가 창고로 (네더라이트 조각 1개처럼 적은 건 기술 자재 우선)
            val techItems = items.map { it.clone().apply { amount = (it.amount + 1) / 2 } }
            val storageItems = items.mapNotNull { item ->
                val rest = item.amount - (item.amount + 1) / 2
                if (rest > 0) item.clone().apply { amount = rest } else null
            }
            plugin.nationTechManager.addMaterials(owner, techItems)
            val leftover = if (storageItems.isEmpty()) 0 else plugin.nationStorageManager.deposit(owner, storageItems)
            plugin.nationManager.addPeace(owner, dailyPeace)
            val techText = techItems.joinToString(", ") { "${koName(it.type)} ${it.amount}개" }
            val storageText = storageItems.joinToString(", ") { "${koName(it.type)} ${it.amount}개" }
            plugin.nationManager.notifyNation(owner,
                "§6[거점] §f${site.name} 수입: 금고 +${(dailyMoney * mult).toLong()}원, 기술 자재 $techText" +
                    (if (storageItems.isNotEmpty()) ", 국가 창고 $storageText" else "") +
                    if (leftover > 0) " §c(창고가 가득 차서 ${leftover}개는 받지 못했습니다)" else "")
            paid = true
        }
        if (paid) {
            plugin.nationManager.saveNations()
            saveData()
        }
    }

    // ───────────────────────── 국가 변화 ─────────────────────────

    fun renameNation(old: String, new: String) {
        sites.values.forEach { if (it.owner == old) it.owner = new; if (it.capturing == old) it.capturing = new }
        saveData(); sites.values.forEach { drawMarker(it) }
    }

    /** 국가 해체: 거점을 주인 없음으로 */
    fun releaseNation(name: String) {
        sites.values.forEach { if (it.owner == name) it.owner = null; if (it.capturing == name) { it.capturing = null; it.progress = 0.0 } }
        saveData(); sites.values.forEach { drawMarker(it) }
    }

    /** 국가 점령: 패배국의 거점을 승리국이 가져감 */
    fun transferNation(from: String, to: String) {
        sites.values.forEach { if (it.owner == from) it.owner = to; if (it.capturing == from) { it.capturing = null; it.progress = 0.0 } }
        saveData(); sites.values.forEach { drawMarker(it) }
    }

    /** 모든 거점을 주인 없음으로 (시즌 초기화용) */
    fun resetAll() {
        sites.values.forEach { it.owner = null; it.capturing = null; it.progress = 0.0 }
        saveData(); sites.values.forEach { drawMarker(it) }
    }

    private fun drawMarker(site: Site) {
        val world = worldOf(site) ?: return
        BlueMapBridge.setSiteMarker(site.id, "${site.name} (주인: ${site.owner ?: "없음"})", world, site.x.toDouble(), site.z.toDouble())
    }

    fun shutdown() {
        sites.values.forEach { it.bar?.removeAll() }
    }

    // ───────────────────────── 거점 보호 ─────────────────────────

    private fun bypass(p: Player) = p.hasPermission("digriss.admin") && p.gameMode == GameMode.CREATIVE

    // 블록 설치·파괴는 허용 (방벽을 쌓고 부수는 공방전). 물·용암 붓기와 폭발만 막음

    @EventHandler(ignoreCancelled = true)
    fun onBucket(e: PlayerBucketEmptyEvent) {
        if (bypass(e.player)) return
        siteAt(e.block.location)?.let { e.isCancelled = true; e.player.sendMessage("§c${it.name} 주변에서는 물·용암을 부을 수 없습니다.") }
    }

    @EventHandler(ignoreCancelled = true)
    fun onEntityExplode(e: EntityExplodeEvent) { e.blockList().removeIf { siteAt(it.location) != null } }

    @EventHandler(ignoreCancelled = true)
    fun onBlockExplode(e: BlockExplodeEvent) { e.blockList().removeIf { siteAt(it.location) != null } }

    // ───────────────────────── /거점 ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.getOrNull(0) == "주인" && sender.hasPermission("digriss.admin")) {
            val site = sites[args.getOrNull(1) ?: ""] ?: return sender.sendMessage("§c사용법: /거점 주인 <거점ID> <국가|없음>").let { true }
            val nation = args.getOrNull(2)
            site.owner = if (nation == null || nation == "없음") null else nation.takeIf { Nation.nations.containsKey(it) }
                ?: return sender.sendMessage("§c그런 국가가 없습니다.").let { true }
            site.capturing = null; site.progress = 0.0
            saveData(); drawMarker(site)
            plugin.adminLogManager.log(sender, "거점 주인 변경: ${site.id} -> ${site.owner ?: "없음"}")
            sender.sendMessage("§a${site.name}의 주인을 ${site.owner ?: "없음"}(으)로 바꿨습니다.")
            return true
        }

        val me = sender as? Player
        val myNation = me?.let { plugin.nationManager.getNationName(it.uniqueId) }
        sender.sendMessage("§6§l[ 자원 거점 ] §7반경 ${radius.toInt()}블록 안에서 ${captureSeconds / 60}분 버티면 점령")
        sites.values.forEach { site ->
            val owner = when (site.owner) { null -> "§8없음"; myNation -> "§a${site.owner}"; else -> "§c${site.owner}" }
            val distance = me?.takeIf { it.world == worldOf(site) }?.let { p ->
                " §8· ${Math.sqrt(dist2(p.location.x, p.location.z, site)).toInt()}m"
            } ?: ""
            sender.sendMessage(" §f${site.name} §7(${site.x}, ${site.z}) §7주인 $owner$distance")
            sender.sendMessage("   §8매일: 금고 ${dailyMoney.toLong()}원 + ${site.rewardText}")
        }
        return true
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (!sender.hasPermission("digriss.admin")) return emptyList()
        return when (args.size) {
            1 -> listOf("주인").filter { it.startsWith(args[0]) }
            2 -> sites.keys.filter { it.startsWith(args[1]) }
            3 -> (Nation.nations.keys + "없음").filter { it.startsWith(args[2]) }
            else -> emptyList()
        }
    }
}
