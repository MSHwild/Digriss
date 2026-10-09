package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
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
    private var date: LocalDate? = null
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
    private var bossLocation: Location? = null
    private var dataDate = ""
    private val playMinutes = mutableMapOf<UUID, Int>()
    private val playRewards = mutableMapOf<UUID, Int>()
    private val welcomed = mutableSetOf<UUID>()
    private val doneKeys = mutableSetOf<String>()     // 이미 한 예고·보스 등장·축제 시작 공지 (두 번 안 하게)

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
        date = runCatching { LocalDate.parse(c.getString("date", "")) }.getOrNull()
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
        val d = date ?: return false
        if (!enabled || at.toLocalDate() != d) return false
        val t = at.toLocalTime()
        return !t.isBefore(start) && !t.isAfter(end)
    }

    fun isRaidActive() = boss?.isValid == true

    private fun nextBossTime(at: ZonedDateTime): LocalTime? =
        bossTimes.sorted().firstOrNull { it.isAfter(at.toLocalTime()) && !it.isAfter(end) && !it.isBefore(start) }

    private fun tick() {
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
            Bukkit.broadcastMessage("§6§l[디그리스 대축제] §e오늘 하루 대축제가 열립니다! §7(/빅이벤트)")
            Bukkit.getOnlinePlayers().forEach { it.sendTitle("§6§l디그리스 대축제", "§e접속 보상 · 보스 레이드 · 환영 선물!", 10, 70, 20) }
            Sounds.all(Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f)
            plugin.discordNotifier.notify("big-event", "🎉 디그리스 대축제 시작!",
                "오늘 하루 대축제가 열려요!\n• 접속해 있으면 ${playEvery}분마다 영혼·돈 보상\n• 처음 온 친구는 환영 선물\n• 보스 레이드: ${bossTimes.joinToString(", ")}\n친구를 데려와서 함께 보스를 잡아요!", DiscordNotifier.GOLD)
        }

        if (festival || isRaidActive()) updateFestivalBar(now) else removeFestivalBar()
        if (!festival) return

        // 보스 예고 / 등장 (같은 날 같은 시간은 한 번만)
        bossTimes.forEach { t ->
            val key = t.toString()
            val untilMin = java.time.Duration.between(now.toLocalTime(), t).toMinutes()
            if (noticeMinutes > 0 && untilMin in 0 until noticeMinutes && !now.toLocalTime().isAfter(t) && doneKeys.add("notice|$key")) {
                saveData()
                Bukkit.broadcastMessage("§6[대축제] §c${untilMin + 1}분 뒤 §4보스 레이드§c가 시작됩니다! §7(접속자 모두 함께 잡아요)")
                Sounds.all(Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 0.8f)
                plugin.discordNotifier.notify("big-event", "⏰ 보스 레이드 예고", "${untilMin + 1}분 뒤 보스 레이드가 시작돼요! 지금 접속하세요.", DiscordNotifier.GOLD)
            }
            if (!now.toLocalTime().isBefore(t) && now.toLocalTime().isBefore(t.plusMinutes(5)) && doneKeys.add("boss|$key")) {
                saveData()
                if (!isRaidActive()) startRaid()
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

    private fun spawnPoint(): Location {
        bossLocation?.let { if (it.world != null) return it.clone() }
        plugin.logger.warning("[대축제] 보스 위치가 설정되지 않아 월드 스폰에 소환합니다. /빅이벤트 위치 로 설정하세요.")
        return Bukkit.getWorlds().first().spawnLocation
    }

    fun startRaid(): Boolean {
        if (isRaidActive()) return false
        val loc = spawnPoint()
        val world = loc.world ?: return false
        val online = Bukkit.getOnlinePlayers().size.coerceAtLeast(1)
        val health = (baseHealth + healthPerPlayer * online).coerceIn(50.0, 2000.0)

        val mob = world.spawnEntity(loc, EntityType.HUSK) as Mob // 허스크: 햇빛에 타지 않음
        mob.customName = bossName
        mob.isCustomNameVisible = true
        mob.removeWhenFarAway = false
        mob.isPersistent = true
        mob.canPickupItems = false
        mob.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = health
        mob.health = health
        mob.getAttribute(Attribute.GENERIC_SCALE)?.baseValue = 2.5
        mob.getAttribute(Attribute.GENERIC_ATTACK_DAMAGE)?.baseValue = bossDamage
        mob.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE)?.baseValue = 1.0
        mob.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = 0.28
        mob.getAttribute(Attribute.GENERIC_FOLLOW_RANGE)?.baseValue = 40.0
        mob.equipment?.let { eq ->
            eq.helmet = ItemStack(Material.NETHERITE_HELMET); eq.helmetDropChance = 0f
            eq.chestplate = ItemStack(Material.NETHERITE_CHESTPLATE); eq.chestplateDropChance = 0f
            eq.setItemInMainHand(ItemStack(Material.NETHERITE_AXE)); eq.itemInMainHandDropChance = 0f
        }
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
        Bukkit.broadcastMessage("§4§l[보스 레이드] §c$bossName§c이(가) 깨어났습니다! §7(${timeLimitMinutes}분 안에 쓰러뜨리세요, 체력 ${health.toInt()})")
        Bukkit.getOnlinePlayers().forEach {
            it.sendTitle("§4§l보스 레이드", "$bossName §c등장!", 10, 70, 20)
            sendJoinRaidLink(it)
        }
        Sounds.all(Sound.ENTITY_WITHER_SPAWN, 0.7f, 0.8f)
        plugin.discordNotifier.notify("big-event", "👹 보스 레이드 시작!",
            "${ChatColor.stripColor(bossName)}이(가) 깨어났어요! ${timeLimitMinutes}분 안에 함께 쓰러뜨려요.\n좌표: ${world.name} ${loc.blockX}, ${loc.blockY}, ${loc.blockZ}", DiscordNotifier.RED)
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
        b.setTitle("§6§l디그리스 대축제 §8| " + if (next != null) "§7다음 보스 레이드 §f$next" else "§7오늘 보스 레이드는 모두 끝났어요")
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
                p.teleport(at)
                Sounds.play(p, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1f)
            }
            "위치" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                val p = sender as? Player ?: return true
                bossLocation = p.location.clone()
                saveData()
                p.sendMessage("§a보스 등장 위치를 지금 위치로 저장했습니다. §7(${p.world.name} ${p.location.blockX}, ${p.location.blockY}, ${p.location.blockZ})")
                plugin.adminLogManager.log(sender, "대축제 보스 위치 설정")
            }
            "보스" -> {
                if (!admin) return true.also { sender.sendMessage("§c권한이 없습니다.") }
                if (startRaid()) plugin.adminLogManager.log(sender, "대축제 보스 레이드 직접 시작")
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
        val d = date
        sender.sendMessage(when {
            !enabled || d == null -> "§7예정된 축제가 없습니다."
            isFestival(now) -> "§a진행 중! §7(오늘 $start ~ $end)"
            now.toLocalDate().isBefore(d) -> "§e${d.monthValue}월 ${d.dayOfMonth}일 $start ~ $end §7에 열려요!"
            else -> "§7축제가 끝났습니다. 함께해 주셔서 고마워요!"
        })
        sender.sendMessage("§7- 접속 보상: §f${playEvery}분마다 §b영혼 $playSouls §6돈 ${fmt(playMoney)}원 §7(하루 ${playMax}번)")
        sender.sendMessage("§7- 보스 레이드: §f${bossTimes.joinToString(", ").ifEmpty { "없음" }} §7(참가자 전원 보상, 피해량 1~3위 추가 보상)")
        sender.sendMessage("§7- 처음 온 사람 환영 선물: §b영혼 $welcomeSouls §6돈 ${fmt(welcomeMoney)}원 §3DC $welcomeDc")
        if (sender is Player && isFestival(now)) {
            sender.sendMessage("§7- 내 접속 보상: §f${playRewards[sender.uniqueId] ?: 0}/$playMax §8(접속 ${playMinutes[sender.uniqueId] ?: 0}분)")
        }
        if (isRaidActive()) sender.sendMessage("§c지금 보스 레이드 진행 중! §e/빅이벤트 이동")
        if (admin) sender.sendMessage("§8관리자: /빅이벤트 위치 | 보스 | 종료  §7(보스 위치: ${bossLocation?.let { "${it.world?.name} ${it.blockX}, ${it.blockY}, ${it.blockZ}" } ?: "§c미설정"}§7)")
    }

    override fun onTabComplete(sender: CommandSender, command: Command, alias: String, args: Array<out String>): List<String> {
        if (args.size != 1) return emptyList()
        val options = listOf("이동") + if (sender.hasPermission("digriss.admin")) listOf("위치", "보스", "종료") else emptyList()
        return options.filter { it.startsWith(args[0]) }
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
        bossLocation = c.getLocation("boss-location")
        dataDate = c.getString("date", "") ?: ""
        c.getConfigurationSection("play-minutes")?.getKeys(false)?.forEach { k -> runCatching { playMinutes[UUID.fromString(k)] = c.getInt("play-minutes.$k") } }
        c.getConfigurationSection("play-rewards")?.getKeys(false)?.forEach { k -> runCatching { playRewards[UUID.fromString(k)] = c.getInt("play-rewards.$k") } }
        c.getStringList("welcomed").forEach { runCatching { welcomed.add(UUID.fromString(it)) } }
        doneKeys.addAll(c.getStringList("done"))
    }

    private fun saveData() {
        val c = YamlConfiguration()
        c.set("boss-location", bossLocation)
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
