package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.nation.Nation
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerRespawnEvent
import java.io.File
import kotlin.random.Random

// 랜덤 스폰 (spawn.yml): 처음 접속한 유저, 그리고 국가가 없는 유저가 죽었을 때 랜덤 위치로 보냄
// 침대·리스폰 정박기를 설정해 뒀으면 그쪽이 우선. 다른 국가 영토와 물·용암 위는 피함
class RandomSpawnManager(private val plugin: Digriss) : Listener {

    private var enabled = true
    private var worldName = ""
    private var minRadius = 300
    private var maxRadius = 3000
    // area가 설정돼 있으면 반경 대신 이 직사각형 안에서 찾음 (지구 맵처럼 가로가 긴 맵용): minX, maxX, minZ, maxZ
    private var area: IntArray? = null

    // 위험하거나 서 있기 곤란한 바닥
    private val badGround = setOf(
        Material.WATER, Material.LAVA, Material.MAGMA_BLOCK, Material.CACTUS, Material.POWDER_SNOW,
        Material.FIRE, Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POINTED_DRIPSTONE
    )

    init {
        load()
    }

    fun load() {
        val file = File(plugin.dataFolder, "spawn.yml")
        if (!file.exists()) {
            // jar 안에 spawn.yml이 없어도(IntelliJ 아티팩트로 빌드 등) 플러그인이 꺼지지 않게 기본값으로 만듦
            if (plugin.getResource("spawn.yml") != null) plugin.saveResource("spawn.yml", false)
            else YamlConfiguration().apply {
                set("enabled", true); set("world", ""); set("min-radius", 300); set("max-radius", 3000)
                set("area.min-x", -9000); set("area.max-x", 9000); set("area.min-z", -4400); set("area.max-z", 4400)
                save(file)
            }
        }
        val config = YamlConfiguration.loadConfiguration(file)
        enabled = config.getBoolean("enabled", true)
        worldName = config.getString("world", "") ?: ""
        minRadius = config.getInt("min-radius", 300).coerceAtLeast(0)
        maxRadius = config.getInt("max-radius", 3000).coerceAtLeast(minRadius + 1)
        area = config.getConfigurationSection("area")?.let {
            val x1 = it.getInt("min-x"); val x2 = it.getInt("max-x")
            val z1 = it.getInt("min-z"); val z2 = it.getInt("max-z")
            if (x1 < x2 && z1 < z2) intArrayOf(x1, x2, z1, z2) else null
        }
    }

    private fun world(): World? = Bukkit.getWorld(worldName).takeIf { worldName.isNotEmpty() } ?: Bukkit.getWorlds().firstOrNull()

    // 직사각형(area) 또는 월드 스폰 중심 min~max 반경 안의 안전한 땅. 30번 시도해도 못 찾으면 null (기본 스폰 사용)
    fun findLocation(): Location? {
        val world = world() ?: return null
        val center = world.spawnLocation
        repeat(30) {
            val rect = area
            val x: Int
            val z: Int
            if (rect != null) {
                x = Random.nextInt(rect[0], rect[1] + 1)
                z = Random.nextInt(rect[2], rect[3] + 1)
            } else {
                val angle = Random.nextDouble(0.0, Math.PI * 2)
                val dist = Random.nextDouble(minRadius.toDouble(), maxRadius.toDouble())
                x = center.blockX + (Math.cos(angle) * dist).toInt()
                z = center.blockZ + (Math.sin(angle) * dist).toInt()
            }
            if (!world.worldBorder.isInside(Location(world, x + 0.5, 64.0, z + 0.5))) return@repeat

            val ground = world.getHighestBlockAt(x, z)
            if (ground.type in badGround || ground.isLiquid || ground.type.name.endsWith("LEAVES")) return@repeat
            val loc = ground.location.add(0.5, 1.0, 0.5)
            // 다른 국가 영토 안에는 내려주지 않음
            if (Nation.chunkClaims.containsKey("${world.name},${x shr 4},${z shr 4}")) return@repeat
            return loc
        }
        return null
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        if (!enabled || e.player.hasPlayedBefore()) return
        val player = e.player
        // 접속 처리 직후에 이동
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!player.isOnline) return@Runnable
            findLocation()?.let { player.teleport(it) }
        }, 5L)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(e: PlayerRespawnEvent) {
        if (!enabled || e.isBedSpawn || e.isAnchorSpawn) return
        if (plugin.nationManager.getNationName(e.player.uniqueId) != null) return
        findLocation()?.let { e.respawnLocation = it }
    }
}
