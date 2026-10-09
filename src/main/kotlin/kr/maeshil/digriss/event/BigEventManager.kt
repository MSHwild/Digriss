package kr.maeshil.digriss.event

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.manager.DiscordNotifier
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.achievement.Achievement
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.milkbowl.vault.economy.Economy
import org.bukkit.Bukkit
import org.bukkit.ChatColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.boss.BarColor
import org.bukkit.boss.BarStyle
import org.bukkit.boss.BossBar
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.potion.PotionEffect
import org.bukkit.potion.PotionEffectType
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

// 디그리스 대축제 (bigevent.yml): 정해진 날 하루 동안
//  1) 접속해 있으면 30분마다 보상  2) 처음 온 사람 환영 선물  3) 정해진 시간에 보스 레이드 (협동, 피해량 순위 보상)
// 축제 중에는 화면 위에 다음 보스 시간 보스바, 보스가 나오면 보스 체력 보스바
class BigEventManager(private val plugin: Digriss) : Listener, CommandExecutor, TabCompleter {

    private val zone = ZoneId.of("Asia/Seoul")
    private val file = File(plugin.dataFolder, "bigevent.yml")
    private val dataFile = File(plugin.dataFolder, "bigevent-data.yml") // 보스 위치 · 오늘 받은 보상 (재시작해도 유지)

    // ── 설정 ──
    private var enabled = true
    private var startDate: LocalDate? = null
    private var endDate: LocalDate? = null
    private var start = LocalTime.MIN
    private var end = LocalTime.of(23, 59)
    private var playEvery = 30
    private var playMax = 6
    private var playSouls = 20L
    private var playMoney = 1000.0
    private var welcomeSouls = 100L
    private var welcomeMoney = 5000.0
    private var welcomeDc = 2L
    private var bossTimes: List<LocalTime> = emptyList()
    private var noticeMinutes = 10
    private var timeLimitMinutes = 15
    private var bossName = "§4§l고대 수호자"
    private var baseHealth = 300.0
    private var healthPerPlayer = 150.0
    private var bossDamage = 9.0
    private var joinSouls = 50L
    private var joinMoney = 3000.0
    private var joinDc = 1L
    private var topSouls: List<Long> = listOf(300, 200, 100)
    private var topMoney: List<Double> = listOf(15000.0, 10000.0, 5000.0)
    private var topDc: List<Long> = listOf(5, 3, 2)

    // ── 오늘 기록 (bigevent-data.yml) ──
    private val extraSpots = linkedMapOf<String, Location>()   // 관리자가 /빅이벤트 위치 추가 로 넣은 장소
    private var dataDate = ""
    private val playMinutes = mutableMapOf<UUID, Int>()
    private val playRewards = mutableMapOf<UUID, Int>()
    private val welcomed = mutableSetOf<UUID>()
    private val doneKeys = mutableSetOf<String>()     // 이미 한 예고·보스 등장·축제 시작 공지 (두 번 안 하게)

    // ── 보스 등장 장소 ──
    private data class BossSpot(val name: String, val world: String, val x: Int, val z: Int)
    private var spots: List<BossSpot> = emptyList()      // bigevent.yml boss.locations
    private var addedOnly = true                          // 게임에서 추가한 장소가 있으면 그것만 쓰기
    private var currentSpot: String? = null               // 지금 레이드 장소 이름
    private var lastSpot: String? = null                  // 같은 곳이 연속으로 나오지 않게
    private var plannedSpot: String? = null               // 예고할 때 정해 둔 다음 장소
    private val returnPoints = mutableMapOf<UUID, Location>() // /빅이벤트 이동 전에 있던 곳 (레이드 끝나고 귀환용)
    private var returnUntil = 0L                          // 귀환 가능한 시각 (레이드 끝나고 10분)

    // ── 보스 레이드 진행 상태 ──
    private var boss: Mob? = null
    private var bossHome: Location? = null
    private var bossEndsAt = 0L
    private var enraged = false
    private var skillCooldown = 0
    private val damageDealt = mutableMapOf<UUID, Double>()
    private val minions = mutableListOf<UUID>()

    private var festivalBar: BossBar? = null
    private var bossBar: BossBar? = null
    private var tickCount = 0

    init {
        if (!file.exists() && plugin.getResource("bigevent.yml") != null) plugin.saveResource("bigevent.yml", false)
        load()
        loadData()
        // 1초마다: 보스 기술 · 보스바. 10초마다: 시간표 확인. 1분마다: 접속 시간 보상
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 20L, 20L)
    }

    fun load() {
        val c = YamlConfiguration.loadConfiguration(file)
        enabled = c.getBoolean("enabled", true)
        // 예전 형식(date: 하루)도 읽음
        startDate = runCatching { LocalDate.parse(c.getString("start-date") ?: c.getString("date", "")) }.getOrNull()
        endDate = runCatching { LocalDate.parse(c.getString("end-date", "")) }.getOrNull() ?: startDate
        start = runCatching { LocalTime.parse(c.getString("start", "00:00")) }.getOrDefault(LocalTime.MIN)
        end = runCatching { LocalTime.parse(c.getString("end", "23:59")) }.getOrDefault(LocalTime.of(23, 59))
        playEvery = c.getInt("playtime.every-minutes", 30).coerceAtLeast(1)
        playMax = c.getInt("playtime.max-times", 6).coerceAtLeast(0)
        playSouls = c.getLong("playtime.souls", 20)
        playMoney = c.getDouble("playtime.money", 1000.0)
        welcomeSouls = c.getLong("welcome.souls", 100)
        welcomeMoney = c.getDouble("welcome.money", 5000.0)
        welcomeDc = c.getLong("welcome.dc", 2)
        bossTimes = c.getStringList("boss.times").mapNotNull { t ->
            runCatching { LocalTime.parse(t.trim()) }.getOrElse { plugin.logger.warning("[대축제] 보스 시간 '$t'을(를) 읽을 수 없습니다. (예: 19:00)"); null }
        }
        // "이름, x, z" 또는 "이름, 월드, x, z"
        spots = c.getStringList("boss.locations").mapNotNull { line ->
            val parts = line.split(",").map { it.trim() }
            runCatching {
                when (parts.size) {
                    3 -> BossSpot(parts[0], "", parts[1].toInt(), parts[2].toInt())
                    4 -> BossSpot(parts[0], parts[1], parts[2].toInt(), parts[3].toInt())
                    else -> null
                }
            }.getOrNull().also { if (it == null) plugin.logger.warning("[대축제] 보스 장소 '$line'을(를) 읽을 수 없습니다. (예: 이집트 피라미드, 1594, -1535)") }
        }
        addedOnly = c.getBoolean("boss.added-only", true)
        noticeMinutes = c.getInt("boss.notice-minutes", 10).coerceAtLeast(0)
        timeLimitMinutes = c.getInt("boss.time-limit-minutes", 15).coerceAtLeast(1)
        bossName = ChatColor.translateAlternateColorCodes('&', c.getString("boss.name", "&4&l고대 수호자") ?: "&4&l고대 수호자")
        baseHealth = c.getDouble("boss.base-health", 300.0)
        healthPerPlayer = c.getDouble("boss.health-per-player", 150.0)
        bossDamage = c.getDouble("boss.damage", 9.0)
        joinSouls = c.getLong("boss.participation.souls", 50)
        joinMoney = c.getDouble("boss.participation.money", 3000.0)
        joinDc = c.getLong("boss.participation.dc", 1)
        topSouls = c.getLongList("boss.top-souls").ifEmpty { listOf(300L, 200L, 100L) }
        topMoney = c.getDoubleList("boss.top-money").ifEmpty { listOf(15000.0, 10000.0, 5000.0) }
        topDc = c.getLongList("boss.top-dc").ifEmpty { listOf(5L, 3L, 2L) }
    }

    // ───────────────────────── 축제 시간 ─────────────────────────

    private fun now() = ZonedDateTime.now(zone)

    fun isFestival(at: ZonedDateTime = now()): Boolean {
        val from = startDate ?: return false
        val to = endDate ?: return false
        val day = at.toLocalDate()
        if (!enabled || day.isBefore(from) || day.isAfter(to)) return false
        val t = at.toLocalTime()
        return !t.isBefore(start) && !t.isAfter(end)
    }

    /** 축제 기간이 완전히 끝났거나 꺼져 있음 → 아무 작업도 하지 않음 */
    fun isOver(at: ZonedDateTime = now()): Boolean {
        val to = endDate ?: return true
        return !enabled || at.toLocalDate().isAfter(to)
    }

    fun isRaidActive() = boss?.isValid == true

    private fun nextBossTime(at: ZonedDateTime): LocalTime? =
        bossTimes.sorted().firstOrNull { it.isAfter(at.toLocalTime()) && !it.isAfter(end) && !it.isBefore(start) }

    private fun tick() {
        // 축제가 끝난 뒤에는 쉼 (관리자가 /빅이벤트 보스 로 연 레이드만 처리)
        if (boss == null && isOver()) {
            if (festivalBar != null) removeFestivalBar()
            return
        }
        tickCount++
        if (isRaidActive() || boss != null) raidTick()
        if (tickCount % 10 == 0) scheduleTick()
        if (tickCount % 60 == 0) playtimeTick()
    }

    private fun scheduleTick() {
        val now = now()
        val today = now.toLocalDate().toString()
        if (dataDate != today) resetDay(today)
        val festival = isFestival(now)

        if (festival && doneKeys.add("start")) {
            saveData()
            Bukkit.broadcastMessage("§6§l[디그리스 대축제] §e대축제가 진행 중입니다! §7(${endDate?.let { "${it.monthValue}/${it.dayOfMonth}" }}까지, /빅이벤트)")
            Bukkit.getOnlinePlayers().forEach { it.sendTitle("§6§l디그리스 대축제", "§e접속 보상 · 보스 레이드 · 환영 선물!", 10, 70, 20) }
            Sounds.all(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            plugin.discordNotifier.notify("big-event", "🎉 디그리스 대축제 시작!",
                "대축제가 진행 중이에요! (${endDate?.let { "${it.monthValue}/${it.dayOfMonth}" }}까지)\n• 접속해 있으면 ${playEvery}분마다 영혼·돈 보상\n• 처음 온 친구는 환영 선물\n• 보스 레이드: ${bossTimes.joinToString(", ")}\n친구를 데려와서 함께 보스를 잡아요!", DiscordNotifier.GOLD)
        }

        if (festival || isRaidActive()) updateFestivalBar(now) else removeFestivalBar()
        if (!festival) return

        // 보스 예고 / 등장 (같은 날 같은 시간은 한 번만)
        bossTimes.forEach { t ->
            val key = t.toString()
            val untilMin = java.time.Duration.between(now.toLocalTime(), t).toMinutes()
            if (noticeMinutes > 0 && untilMin in 0 until noticeMinutes && !now.toLocalTime().isAfter(t) && doneKeys.add("notice|$key")) {
                // 예고할 때 장소를 미리 정해서 알려줌 (미리 가서 기다릴 수 있게)
                plannedSpot = randomSpotName() ?: "월드 스폰"
                saveData()
                Bukkit.broadcastMessage("§6[대축제] §c${untilMin + 1}분 뒤 §e$plannedSpot§c에 §4보스 레이드§c가 시작됩니다! §7(시작하면 [이동]으로 바로 갈 수 있어요)")
                Sounds.all(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f)
                plugin.discordNotifier.notify("big-event", "⏰ 보스 레이드 예고", "${untilMin + 1}분 뒤 **$plannedSpot**에 보스가 나타나요! 지금 접속하세요.", DiscordNotifier.GOLD)
            }
            if (!now.toLocalTime().isBefore(t) && now.toLocalTime().isBefore(t.plusMinutes(5)) && doneKeys.add("boss|$key")) {
                saveData()
                if (!isRaidActive()) startRaid(plannedSpot)
                plannedSpot = null
            }
        }
    }

    // ───────────────────────── 접속 보상 / 환영 선물 ─────────────────────────

    private fun playtimeTick() {
        if (!isFestival() || playMax <= 0) return
        var changed = false
        Bukkit.getOnlinePlayers().forEach { p ->
            val minutes = (playMinutes[p.uniqueId] ?: 0) + 1
            playMinutes[p.uniqueId] = minutes
            changed = true
            val got = playRewards[p.uniqueId] ?: 0
            if (minutes % playEvery == 0 && got < playMax) {
                playRewards[p.uniqueId] = got + 1
                reward(p, playSouls, playMoney, 0)
                p.sendMessage("§6[대축제] §e접속 보상 (${got + 1}/$playMax): §b영혼 +$playSouls §6돈 +${fmt(playMoney)}원")
                Sounds.coin(p)
            }
        }
        if (changed) saveData()
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        val firstJoin = !p.hasPlayedBefore()
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || !isFestival()) return@Runnable
            festivalBar?.addPlayer(p)
            bossBar?.addPlayer(p)
            p.sendTitle("§6§l디그리스 대축제", "§e/빅이벤트 §7로 일정 확인!", 10, 60, 20)
            p.sendMessage("§6§l[디그리스 대축제] §e진행 중! §7접속 보상 ${playEvery}분마다 · 보스 레이드 ${bossTimes.joinToString(", ")}")
            if (isRaidActive()) sendJoinRaidLink(p)
            if (firstJoin && welcomed.add(p.uniqueId)) {
                saveData()
                reward(p, welcomeSouls, welcomeMoney, welcomeDc)
                p.sendMessage("§a[대축제] 처음 오신 걸 환영해요! 환영 선물: §b영혼 +$welcomeSouls §6돈 +${fmt(welcomeMoney)}원 §3DC +$welcomeDc")
                Sounds.bigReward(p)
                Bukkit.broadcastMessage("§a[대축제] §f${p.name}§a 님이 처음 오셨어요! 모두 환영해 주세요!")
            }
        }, 60L)
    }

    // ───────────────────────── 보스 레이드 ─────────────────────────

    /** 보스가 나올 수 있는 장소 이름. added-only 가 켜져 있고 게임에서 추가한 곳이 있으면 그곳들만 씀 */
    private fun spotNames(): List<String> =
        if (addedOnly && extraSpots.isNotEmpty()) extraSpots.keys.toList()
        else spots.map { it.name } + extraSpots.keys

    // 장소 이름 → 실제 위치 (설정 파일 장소는 그 자리 가장 높은 블록 위)
    private fun locationOf(name: String): Location? {
        extraSpots[name]?.let { if (it.world != null) return it.clone() }
        val s = spots.firstOrNull { it.name == name } ?: return null
        val world = (if (s.world.isBlank()) null else Bukkit.getWorld(s.world)) ?: Bukkit.getWorlds().first()
        val y = world.getHighestBlockYAt(s.x, s.z) + 1
        return Location(world, s.x + 0.5, y.toDouble(), s.z + 0.5)
    }

    // 직전과 다른 장소 이름 하나 (청크를 불러오지 않음)
    private fun randomSpotName(): String? = spotNames().filter { it != lastSpot }.ifEmpty { spotNames() }.randomOrNull()

    // 지정한 곳, 아니면 직전과 다른 곳 중 무작위. 장소가 하나도 없으면 월드 스폰
    private fun pickSpot(name: String?): Pair<String, Location> {
        if (name != null) locationOf(name)?.let { return name to it }
        val candidates = spotNames().filter { it != lastSpot }.ifEmpty { spotNames() }
        candidates.shuffled().forEach { n -> locationOf(n)?.let { return n to it } }
        plugin.logger.warning("[대축제] 보스 장소가 없어 월드 스폰에 소환합니다. bigevent.yml 의 boss.locations 를 확인하세요.")
        return "월드 스폰" to Bukkit.getWorlds().first().spawnLocation
    }

    fun startRaid(spotName: String? = null): Boolean {
        if (isRaidActive()) return false
        val (place, loc) = pickSpot(spotName)
        val world = loc.world ?: return false
        currentSpot = place
        lastSpot = place
        returnPoints.clear()
        val online = Bukkit.getOnlinePlayers().size.coerceAtLeast(1)
        val health = (baseHealth + healthPerPlayer * online).coerceIn(50.0, 2000.0)

        // 거대 철골렘: 원래 플레이어 편이라 raidTick에서 가까운 플레이어를 계속 공격 대상으로 지정함
        val golem = world.spawnEntity(loc, EntityType.IRON_GOLEM) as org.bukkit.entity.IronGolem
        golem.isPlayerCreated = false
        val mob: Mob = golem
        mob.customName = bossName
        mob.isCustomNameVisible = true
        mob.removeWhenFarAway = false
        mob.isPersistent = true
        mob.isGlowing = true // 리소스팩 없이도 멀리서 보이게 빛나는 테두리
        mob.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = health
        mob.health = health
        mob.getAttribute(Attribute.GENERIC_SCALE)?.baseValue = 2.0 // 약 5.4블록 높이
        mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE)?.baseValue = bossDamage
        mob.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = 0.3
        mob.getAttribute(Attribute.GENERIC_FOLLOW_RANGE)?.baseValue = 40.0
        mob.addPotionEffect(PotionEffect(PotionEffectType.FIRE_RESISTANCE, Int.MAX_VALUE, 0, false, false))

        boss = mob
        bossHome = loc.clone()
        loc.chunk.addPluginChunkTicket(plugin) // 근처에 사람이 없어도 보스가 사라지지 않게 청크 유지
        bossEndsAt = System.currentTimeMillis() + timeLimitMinutes * 60_000L
        enraged = false
        skillCooldown = 8
        damageDealt.clear()

        bossBar?.removeAll()
        bossBar = Bukkit.createBossBar("", BarColor.RED, BarStyle.SEGMENTED_20).also { b -> Bukkit.getOnlinePlayers().forEach { b.addPlayer(it) } }
        world.strikeLightningEffect(loc)
        Bukkit.broadcastMessage("§4§l[보스 레이드] §e$place§c에서 $bossName§c이(가) 깨어났습니다! §7(${timeLimitMinutes}분 안에 쓰러뜨리세요, 체력 ${health.toInt()})")
        Bukkit.broadcastMessage("§7좌표: ${loc.blockX}, ${loc.blockY}, ${loc.blockZ} §8| §7멀어도 괜찮아요, 아래 [이동]을 누르면 바로 가요. 끝나면 §e/빅이벤트 귀환")
        Bukkit.getOnlinePlayers().forEach {
            it.sendTitle("§4§l보스 레이드", "§e$place §c- $bossName", 10, 70, 20)
            sendJoinRaidLink(it)
        }
        Sounds.all(Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.8f)
        plugin.discordNotifier.notify("big-event", "👹 보스 레이드 시작! ($place)",
            "**$place**에서 ${ChatColor.stripColor(bossName)}이(가) 깨어났어요! ${timeLimitMinutes}분 안에 함께 쓰러뜨려요.\n좌표: ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}", DiscordNotifier.RED)
        return true
    }

    private fun sendJoinRaidLink(p: Player) {
        p.sendMessage(Component.text("[보스 레이드] ", NamedTextColor.DARK_RED)
            .append(Component.text("[여기를 눌러 보스에게 이동]", NamedTextColor.GOLD).clickEvent(ClickEvent.runCommand("/빅이벤트 이동"))))
    }

    private fun raidTick() {
        val mob = boss
        if (mob == null || !mob.isValid) {
            // 죽음 이벤트 없이 사라짐 (청크 언로드·명령어 제거 등)
            if (mob != null && !mob.isDead) endRaid(false, "보스가 사라졌습니다.")
            return
        }
        val home = bossHome ?: mob.location
        bossBar?.let { b ->
            Bukkit.getOnlinePlayers().forEach { if (it !in b.players) b.addPlayer(it) }
            val max = mob.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: mob.health
            val left = ((bossEndsAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            b.setTitle("$bossName §f${mob.health.toInt()} / ${max.toInt()} §8| §7남은 시간 §f${left / 60}:${"%02d".format(left % 60)}")
            b.progress = (mob.health / max).coerceIn(0.0, 1.0)
        }
        if (System.currentTimeMillis() >= bossEndsAt) return endRaid(false, "시간 안에 쓰러뜨리지 못해 보스가 도망쳤습니다...")

        // 보스가 너무 멀리 끌려가면 제자리로
        if (mob.world != home.world || mob.location.distanceSquared(home) > 30.0 * 30.0) mob.teleport(home)

        // 철골렘은 플레이어를 먼저 공격하지 않으므로 가장 가까운 플레이어를 계속 노리게 함
        val current = mob.target as? Player
        if (current == null || !current.isValid || current.isDead || current.world != mob.world ||
            current.location.distanceSquared(mob.location) > 30.0 * 30.0 || current !in nearbyPlayers(mob, 30.0)) {
            mob.target = nearbyPlayers(mob, 30.0).minByOrNull { it.location.distanceSquared(mob.location) }
        }

        // 보스 주변 영혼 불꽃 (분노하면 붉은 불꽃)
        mob.world.spawnParticle(if (enraged) Particle.FLAME else Particle.SOUL_FIRE_FLAME,
            mob.location.clone().add(0.0, 2.5, 0.0), 12, 1.2, 1.8, 1.2, 0.01)

        // 체력 30% 이하 → 분노
        val max = mob.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.value ?: mob.health
        if (!enraged && mob.health <= max * 0.3) {
            enraged = true
            mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = 0.36
            nearbyPlayers(mob, 40.0).forEach { it.sendTitle("", "§4보스가 분노했습니다!", 5, 40, 10) }
            mob.world.playSound(mob.location, Sound.ENTITY_RAVAGER_ROAR, 2f, 0.6f)
        }

        if (--skillCooldown > 0) return
        skillCooldown = if (enraged) 5 else 8
        val targets = nearbyPlayers(mob, 20.0)
        if (targets.isEmpty()) return
        when ((0..2).random()) {
            0 -> quake(mob)
            1 -> summonMinions(mob)
            else -> lightning(mob, targets)
        }
    }

    private fun nearbyPlayers(mob: LivingEntity, radius: Double): List<Player> =
        mob.world.getNearbyEntities(mob.location, radius, radius, radius).filterIsInstance<Player>()
            .filter { it.gameMode == org.bukkit.GameMode.SURVIVAL || it.gameMode == org.bukkit.GameMode.ADVENTURE }

    // 지진: 주변 플레이어를 띄우고 밀어냄
    private fun quake(mob: Mob) {
        mob.world.playSound(mob.location, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, 0.6f)
        mob.world.spawnParticle(Particle.EXPLOSION, mob.location, 8, 3.0, 0.5, 3.0)
        nearbyPlayers(mob, 10.0).forEach { p ->
            val away = p.location.toVector().subtract(mob.location.toVector()).setY(0)
            if (away.lengthSquared() > 0.01) away.normalize()
            p.velocity = away.multiply(1.4).setY(0.8)
            p.damage(6.0, mob)
            p.sendActionBar(Component.text("지진! 보스에게서 떨어지세요", NamedTextColor.RED))
        }
    }

    // 졸개 소환 (살아 있는 졸개는 최대 8마리)
    private fun summonMinions(mob: Mob) {
        minions.removeIf { Bukkit.getEntity(it)?.isValid != true }
        val count = (2 + nearbyPlayers(mob, 30.0).size / 2).coerceAtMost(8 - minions.size)
        if (count <= 0) return
        mob.world.playSound(mob.location, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 1.5f, 0.8f)
        repeat(count) {
            val at = mob.location.clone().add((-4..4).random().toDouble(), 0.0, (-4..4).random().toDouble())
            at.y = mob.world.getHighestBlockYAt(at).toDouble() + 1
            val m = mob.world.spawnEntity(at, EntityType.ZOMBIE) as Mob
            m.customName = "§7수호자의 졸개"
            m.removeWhenFarAway = true
            m.equipment?.helmet = ItemStack(Material.IRON_HELMET) // 햇빛에 타지 않게
            m.equipment?.helmetDropChance = 0f
            mob.world.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.3, 0.5, 0.3, 0.02)
            minions.add(m.uniqueId)
        }
        nearbyPlayers(mob, 30.0).forEach { it.sendActionBar(Component.text("보스가 졸개를 불러냈습니다!", NamedTextColor.GOLD)) }
    }

    // 낙뢰: 무작위 플레이어 최대 3명에게 번개
    private fun lightning(mob: Mob, targets: List<Player>) {
        targets.shuffled().take(3).forEach { p ->
            p.world.strikeLightningEffect(p.location)
            p.damage(5.0, mob)
        }
        mob.world.playSound(mob.location, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 1.5f, 1f)
    }

    // 피해량 기록 (근접 · 투사체)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBossDamaged(e: EntityDamageByEntityEvent) {
        val mob = boss ?: return
        if (e.entity.uniqueId != mob.uniqueId) return
        val attacker = when (val d = e.damager) {
            is Player -> d
            is Projectile -> d.shooter as? Player
            else -> null
        } ?: return
        val dealt = e.finalDamage.coerceAtMost(mob.health)
        if (dealt > 0) damageDealt[attacker.uniqueId] = (damageDealt[attacker.uniqueId] ?: 0.0) + dealt
    }

    // 보스는 낙하 · 질식 · 불 · 번개 등 환경 피해를 받지 않음 (플레이어가 잡아야 함)
    private val envCauses = setOf(
        EntityDamageEvent.DamageCause.FALL, EntityDamageEvent.DamageCause.SUFFOCATION, EntityDamageEvent.DamageCause.DROWNING,
        EntityDamageEvent.DamageCause.FIRE, EntityDamageEvent.DamageCause.FIRE_TICK, EntityDamageEvent.DamageCause.LAVA,
        EntityDamageEvent.DamageCause.LIGHTNING, EntityDamageEvent.DamageCause.HOT_FLOOR, EntityDamageEvent.DamageCause.CONTACT,
        EntityDamageEvent.DamageCause.CRAMMING, EntityDamageEvent.DamageCause.FREEZE
    )

    @EventHandler(ignoreCancelled = true)
    fun onBossEnvDamage(e: EntityDamageEvent) {
        if (boss?.uniqueId == e.entity.uniqueId && e.cause in envCauses) e.isCancelled = true
    }

    // 철골렘 보스와 졸개 좀비는 원래 서로 적이라, 서로 노리거나 때리지 않게 막음 (보스는 플레이어만 노림)
    @EventHandler(ignoreCancelled = true)
    fun onTarget(e: org.bukkit.event.entity.EntityTargetEvent) {
        val bossId = boss?.uniqueId ?: return
        val target = e.target ?: return
        if (e.entity.uniqueId == bossId && target !is Player) e.isCancelled = true
        if (e.entity.uniqueId in minions && target.uniqueId == bossId) e.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onBossMinionDamage(e: EntityDamageByEntityEvent) {
        val bossId = boss?.uniqueId ?: return
        val a = e.damager.uniqueId
        val v = e.entity.uniqueId
        if ((a == bossId && v in minions) || (a in minions && v == bossId)) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onBossCombust(e: EntityCombustEvent) {
        if (boss?.uniqueId == e.entity.uniqueId) e.isCancelled = true
    }

    @EventHandler
    fun onBossDeath(e: EntityDeathEvent) {
        val mob = boss ?: return
        if (e.entity.uniqueId != mob.uniqueId) return
        e.drops.clear()
        e.droppedExp = 500
        endRaid(true, null)
    }

    private fun endRaid(success: Boolean, reason: String?) {
        val mob = boss
        boss = null
        if (mob != null && mob.isValid) {
            mob.world.spawnParticle(Particle.LARGE_SMOKE, mob.location, 40, 1.0, 2.0, 1.0, 0.05)
            mob.remove()
        }
        minions.forEach { Bukkit.getEntity(it)?.remove() }
        minions.clear()
        bossBar?.removeAll()
        bossBar = null
        bossHome?.chunk?.removePluginChunkTicket(plugin)
        currentSpot = null
        returnUntil = System.currentTimeMillis() + 10 * 60_000L // 끝나고 10분 동안 /빅이벤트 귀환 가능
        if (returnPoints.isNotEmpty()) {
            returnPoints.keys.mapNotNull { Bukkit.getPlayer(it) }.forEach { it.sendMessage("§7원래 있던 곳으로 돌아가려면 §e/빅이벤트 귀환 §7(10분 안에)") }
        }

        val ranking = damageDealt.entries.filter { it.value >= 1.0 }.sortedByDescending { it.value }
        damageDealt.clear()

        if (success) {
            Bukkit.broadcastMessage("§6§l[보스 레이드] §e$bossName§e을(를) 쓰러뜨렸습니다! §7(참가 ${ranking.size}명)")
            Sounds.all(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            Bukkit.getOnlinePlayers().forEach { it.sendTitle("§6§l레이드 성공!", "§e참가자 전원 보상 지급", 10, 60, 20) }
        } else {
            Bukkit.broadcastMessage("§7[보스 레이드] ${reason ?: "보스 레이드가 끝났습니다."} §7(참가 보상만 지급)")
            Sounds.all(Sound.BLOCK_BELL_USE, 1f, 0.6f)
        }

        val medals = listOf("§6§l1위", "§f§l2위", "§c§l3위")
        ranking.forEachIndexed { i, (uuid, dmg) ->
            val op = Bukkit.getOfflinePlayer(uuid)
            if (i < 5) Bukkit.broadcastMessage("  ${medals.getOrElse(i) { "§7${i + 1}위" }} §f${op.name} §7- 피해 ${dmg.toInt()}")
            var souls = joinSouls; var money = joinMoney; var dc = joinDc
            if (success && i < 3) {
                souls += topSouls.getOrElse(i) { 0L }
                money += topMoney.getOrElse(i) { 0.0 }
                dc += topDc.getOrElse(i) { 0L }
            }
            reward(op, souls, money, dc)
            op.player?.let {
                it.sendMessage("§6[보스 레이드] §e보상: §b영혼 +$souls §6돈 +${fmt(money)}원 §3DC +$dc")
                Sounds.reward(it)
            }
            if (success && i == 0) plugin.achievementManager.unlock(op, Achievement.RAID_HERO)
        }
        plugin.discordNotifier.notify("big-event", if (success) "🏆 보스 레이드 성공!" else "💨 보스가 도망쳤어요",
            (if (success) "참가 ${ranking.size}명이 함께 ${ChatColor.stripColor(bossName)}을(를) 쓰러뜨렸어요!" else (reason ?: "")) +
                ranking.take(3).mapIndexed { i, (uuid, dmg) -> "\n${i + 1}위 ${Bukkit.getOfflinePlayer(uuid).name} (피해 ${dmg.toInt()})" }.joinToString(""),
            if (success) DiscordNotifier.GOLD else DiscordNotifier.GRAY)
    }

    // ───────────────────────── 보스바 ─────────────────────────

    private fun updateFestivalBar(now: ZonedDateTime) {
        if (isRaidActive()) { festivalBar?.isVisible = false; return }
        val b = festivalBar ?: Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID).also { festivalBar = it }
        b.isVisible = true
        Bukkit.getOnlinePlayers().forEach { if (it !in b.players) b.addPlayer(it) }
        val next = nextBossTime(now)
        b.setTitle("§6§l디그리스 대축제 §8| " + when {
            next == null -> "§7오늘 보스 레이드는 모두 끝났어요"
            plannedSpot != null -> "§7다음 보스 레이드 §f$next §e$plannedSpot"
            else -> "§7다음 보스 레이드 §f$next"
        })
        b.progress = 1.0
    }

    private fun removeFestivalBar() {
        festivalBar?.removeAll()
        festivalBar = null
    }

    // ───────────────────────── 명령어 ─────────────────────────

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val admin = sender.hasPermission("digriss.admin")
        when (args.getOrNull(0)) {
            "이동" -> {
                val p = sender as? Player ?: return true
                val mob = boss?.takeIf { it.isValid } ?: return true.also { deny(p, "§c지금 진행 중인 보스 레이드가 없습니다.") }
                if (plugin.combatManager.isInCombat(p)) return true.also { deny(p, "§c전투 중에는 이동할 수 없습니다.") }
                val home = bossHome ?: mob.location
                val at = home.clone().add((-8..8).random().toDouble(), 0.0, (-8..8).random().toDouble())
                at.y = (home.world ?: return true).getHighestBlockYAt(at).toDouble() + 1
                // 처음 이동할 때 있던 곳을 기억 (레이드 끝나고 /빅이벤트 귀환)
                returnPoints.putIfAbsent(p.uniqueId, p.location.clone())
                p.teleport(at)
                Sounds.play(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f)
                p.sendMessage("§e${currentSpot ?: "보스"}§a(으)로 이동했습니다. §7레이드가 끝나면 /빅이벤트 귀환 으로 돌아갈 수 있어요.")
            }
            "귀환" -> {
                val p = sender as? Player ?: return true
                if (isRaidActive()) return true.also { deny(p, "§c레이드가 끝난 뒤에 돌아갈 수 있습니다.") }
                val back = returnPoints[p.uniqueId]
                if (back == null || System.currentTimeMillis() > returnUntil) return true.also { deny(p, "§c돌아갈 곳이 없습니다. §7(레이드에 [이동]으로 온 사람만, 끝나고 10분 안에)") }
                if (plugin.combatManager.isInCombat(p)) return true.also { deny(p, "§c전투 중에는 이동할 수 없습니다.") }
                returnPoints.remove(p.uniqueId)
                p.teleport(back)
                Sounds.play(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f)
                p.sendMessage("§a원래 있던 곳으로 돌아왔습니다.")
            }
            "위치" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                when (args.getOrNull(1)) {
                    "추가" -> {
                        val p = sender as? Player ?: return true
                        val name = args.drop(2).joinToString(" ").ifBlank { return true.also { p.sendMessage("§e/빅이벤트 위치 추가 <장소 이름>") } }
                        if (name.contains('.')) return true.also { p.sendMessage("§c장소 이름에 . 은 쓸 수 없습니다.") }
                        extraSpots[name] = p.location.clone()
                        saveData()
                        p.sendMessage("§a보스 장소 '§f$name§a'을(를) 지금 위치로 추가했습니다. §7(${p.location.blockX}, ${p.location.blockY}, ${p.location.blockZ})")
                        plugin.adminLogManager.log(sender, "대축제 보스 장소 추가: $name")
                    }
                    "삭제" -> {
                        val name = args.drop(2).joinToString(" ")
                        if (extraSpots.remove(name) == null) return true.also { sender.sendMessage("§c직접 추가한 장소만 지울 수 있습니다. §7(bigevent.yml 장소는 파일에서)") }
                        saveData()
                        sender.sendMessage("§e보스 장소 '$name'을(를) 지웠습니다.")
                        plugin.adminLogManager.log(sender, "대축제 보스 장소 삭제: $name")
                    }
                    else -> {
                        sender.sendMessage("§6보스 장소 ${spotNames().size}곳 §7(레이드마다 직전과 다른 곳에서 무작위로 나와요)")
                        val configUnused = addedOnly && extraSpots.isNotEmpty()
                        if (configUnused) sender.sendMessage("§8게임에서 추가한 장소만 씁니다. 아래 회색 장소(bigevent.yml)는 쓰지 않아요.")
                        spots.forEach { sender.sendMessage((if (configUnused) "§8- ${it.name}" else "§7- §f${it.name}") + " §8(${it.x}, ${it.z})") }
                        extraSpots.forEach { (n, l) -> sender.sendMessage("§7- §b$n §8(${l.blockX}, ${l.blockY}, ${l.blockZ}, 직접 추가)") }
                        sender.sendMessage("§8/빅이벤트 위치 추가 <이름> · 삭제 <이름>")
                    }
                }
            }
            "보스" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                val name = args.drop(1).joinToString(" ").ifBlank { null }
                if (name != null && name !in spotNames()) return true.also { sender.sendMessage("§c그런 장소가 없습니다. §7(/빅이벤트 위치 로 목록 확인)") }
                if (startRaid(name)) plugin.adminLogManager.log(sender, "대축제 보스 레이드 직접 시작 (${currentSpot})")
                else sender.sendMessage("§c이미 보스 레이드가 진행 중입니다.")
            }
            "종료" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                if (!isRaidActive()) return true.also { sender.sendMessage("§c진행 중인 보스 레이드가 없습니다.") }
                damageDealt.clear() // 관리자가 끝내면 보상 없음
                endRaid(false, "관리자가 보스 레이드를 끝냈습니다.")
                plugin.adminLogManager.log(sender, "대축제 보스 레이드 종료")
            }
            else -> status(sender, admin)
        }
        return true
    }

    private fun status(sender: CommandSender, admin: Boolean) {
        val now = now()
        sender.sendMessage("§6§l[ 디그리스 대축제 ]")
        val from = startDate
        val to = endDate
        val period = if (from != null && to != null) "${from.monthValue}/${from.dayOfMonth} ~ ${to.monthValue}/${to.dayOfMonth}, 매일 $start ~ $end" else ""
        sender.sendMessage(when {
            !enabled || from == null || to == null -> "§7예정된 축제가 없습니다."
            isFestival(now) -> "§a진행 중! §7($period)"
            !isOver(now) -> "§e$period §7에 열려요!"
            else -> "§7축제가 끝났습니다. 함께해 주셔서 고마워요!"
        })
        sender.sendMessage("§7- 접속 보상: §f${playEvery}분마다 §b영혼 $playSouls §6돈 ${fmt(playMoney)}원 §7(하루 ${playMax}번)")
        sender.sendMessage("§7- 보스 레이드: §f${bossTimes.joinToString(", ").ifEmpty { "없음" }} §7(참가자 전원 보상, 피해량 1~3위 추가 보상)")
        sender.sendMessage("§7- 처음 온 사람 환영 선물: §b영혼 $welcomeSouls §6돈 ${fmt(welcomeMoney)}원 §3DC $welcomeDc")
        if (sender is Player && isFestival(now)) {
            sender.sendMessage("§7- 내 접속 보상: §f${playRewards[sender.uniqueId] ?: 0}/$playMax §8(접속 ${playMinutes[sender.uniqueId] ?: 0}분)")
        }
        sender.sendMessage("§7- 보스 장소: §f${spotNames().size}곳 §7(전 세계 곳곳, 레이드마다 바뀜)")
        if (isRaidActive()) sender.sendMessage("§c지금 §e$currentSpot§c에서 보스 레이드 진행 중! §e/빅이벤트 이동")
        else plannedSpot?.let { sender.sendMessage("§e다음 보스 장소: §f$it") }
        if (admin) sender.sendMessage("§8관리자: /빅이벤트 위치 [추가|삭제] | 보스 [장소] | 종료")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        val admin = sender.hasPermission("digriss.admin")
        return when {
            args.size == 1 -> (listOf("이동", "귀환") + if (admin) listOf("위치", "보스", "종료") else emptyList()).filter { it.startsWith(args[0]) }
            args.size == 2 && admin && args[0] == "위치" -> listOf("추가", "삭제").filter { it.startsWith(args[1]) }
            args.size == 2 && admin && args[0] == "보스" -> spotNames().filter { it.startsWith(args[1]) }
            args.size == 3 && admin && args[0] == "위치" && args[1] == "삭제" -> extraSpots.keys.filter { it.startsWith(args[2]) }
            else -> emptyList()
        }
    }

    // ───────────────────────── 보상 / 저장 ─────────────────────────

    private fun reward(player: OfflinePlayer, souls: Long, money: Double, dc: Long) {
        if (souls > 0) plugin.soulManager.addSouls(player, souls)
        if (dc > 0) plugin.dcManager.addDC(player, dc)
        if (money > 0) Bukkit.getServicesManager().getRegistration(Economy::class.java)?.provider?.depositPlayer(player, money)
    }

    // 날짜가 바뀌면 하루 기록을 비움 (보스 위치는 유지)
    private fun resetDay(today: String) {
        dataDate = today
        playMinutes.clear(); playRewards.clear(); welcomed.clear(); doneKeys.clear()
        saveData()
    }

    private fun loadData() {
        if (!dataFile.exists()) return
        val c = YamlConfiguration.loadConfiguration(dataFile)
        c.getConfigurationSection("extra-locations")?.getKeys(false)?.forEach { n -> c.getLocation("extra-locations.$n")?.let { extraSpots[n] = it } }
        c.getLocation("boss-location")?.let { extraSpots.putIfAbsent("관리자 지정 장소", it) } // 예전 /빅이벤트 위치 로 저장한 곳
        plannedSpot = c.getString("planned-spot")
        dataDate = c.getString("date", "") ?: ""
        c.getConfigurationSection("play-minutes")?.getKeys(false)?.forEach { k -> runCatching { playMinutes[UUID.fromString(k)] = c.getInt("play-minutes.$k") } }
        c.getConfigurationSection("play-rewards")?.getKeys(false)?.forEach { k -> runCatching { playRewards[UUID.fromString(k)] = c.getInt("play-rewards.$k") } }
        c.getStringList("welcomed").forEach { runCatching { welcomed.add(UUID.fromString(it)) } }
        doneKeys.addAll(c.getStringList("done"))
    }

    private fun saveData() {
        val c = YamlConfiguration()
        extraSpots.forEach { (n, l) -> c.set("extra-locations.$n", l) }
        c.set("planned-spot", plannedSpot)
        c.set("date", dataDate)
        playMinutes.forEach { (k, v) -> c.set("play-minutes.$k", v) }
        playRewards.forEach { (k, v) -> c.set("play-rewards.$k", v) }
        c.set("welcomed", welcomed.map { it.toString() })
        c.set("done", doneKeys.toList())
        runCatching { c.save(dataFile) }.onFailure { plugin.logger.severe("[대축제] bigevent-data.yml 저장 실패: ${it.message}") }
    }

    /** 서버가 꺼질 때: 보스·졸개 제거 (보상 없음), 보스바 정리 */
    fun shutdown() {
        boss?.takeIf { it.isValid }?.remove()
        boss = null
        minions.forEach { Bukkit.getEntity(it)?.remove() }
        minions.clear()
        bossBar?.removeAll(); bossBar = null
        bossHome?.chunk?.removePluginChunkTicket(plugin)
        removeFestivalBar()
    }

    private fun fmt(v: Double) = "%,.0f".format(v)

    private fun deny(p: Player, message: String) {
        p.sendMessage(message)
        Sounds.fail(p)
    }
}
