package kr.maeshil.digriss.travel

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.attribute.Attribute
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.EntityType
import org.bukkit.entity.Horse
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.vehicle.VehicleEnterEvent
import org.bukkit.event.vehicle.VehicleExitEvent
import org.bukkit.inventory.ItemStack
import java.io.File
import java.util.UUID

// 탈것 (/탈것): 빠른 말을 불러 타고 다님. 내리거나 나가거나 죽으면 사라짐
//  - 주인만 탈 수 있고, 말 인벤토리(안장)는 열 수 없음
//  - 전투가 시작되면 자동으로 내림 (빠른 말로 도망 방지), 전투 중에는 부를 수 없음
class MountManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val file = File(plugin.dataFolder, "travel.yml")

    private var speed = 0.4
    private var jump = 0.9
    private var health = 30.0
    private var cooldownSeconds = 20
    private var deathCooldownSeconds = 180

    private val mounts = mutableMapOf<UUID, UUID>()   // 주인 -> 말
    private val owners = mutableMapOf<UUID, UUID>()   // 말 -> 주인
    private val cooldowns = mutableMapOf<UUID, Long>()

    init {
        load()
        // 1초마다: 전투 중이면 내리게 함
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable { checkCombat() }, 20L, 20L)
    }

    fun load() {
        val c = YamlConfiguration.loadConfiguration(file)
        speed = c.getDouble("mount.speed", 0.4).coerceIn(0.1, 1.0)
        jump = c.getDouble("mount.jump", 0.9).coerceIn(0.1, 2.0)
        health = c.getDouble("mount.health", 30.0).coerceIn(1.0, 100.0)
        cooldownSeconds = c.getInt("mount.cooldown-seconds", 20).coerceAtLeast(0)
        deathCooldownSeconds = c.getInt("mount.death-cooldown-seconds", 180).coerceAtLeast(0)
    }

    private fun isOurs(id: UUID) = owners.containsKey(id)

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val player = sender as? Player ?: return true
        // 이미 불러둔 말이 있으면 집어넣기
        mounts[player.uniqueId]?.let { id ->
            despawn(id, startCooldown = true)
            player.sendMessage("§7탈것을 돌려보냈습니다.")
            return true
        }
        val now = System.currentTimeMillis()
        val until = cooldowns[player.uniqueId] ?: 0L
        if (until > now) return true.also { deny(player, "§c탈것은 ${(until - now) / 1000 + 1}초 뒤에 부를 수 있습니다.") }
        if (plugin.combatManager.isInCombat(player)) return true.also { deny(player, "§c전투 중에는 탈것을 부를 수 없습니다.") }
        if (player.isInsideVehicle) return true.also { deny(player, "§c이미 무언가를 타고 있습니다.") }
        if (player.isFlying || player.isGliding) return true.also { deny(player, "§c땅에 내려와서 불러 주세요.") }

        val horse = player.world.spawnEntity(player.location, EntityType.HORSE) as Horse
        horse.isTamed = true
        horse.owner = player
        horse.setAdult()
        horse.ageLock = true
        horse.isPersistent = false
        horse.removeWhenFarAway = false
        horse.customName = "§e${player.name}의 탈것"
        horse.isCustomNameVisible = false
        horse.inventory.saddle = ItemStack(Material.SADDLE)
        horse.jumpStrength = jump
        horse.getAttribute(Attribute.GENERIC_MOVEMENT_SPEED)?.baseValue = speed
        horse.getAttribute(Attribute.GENERIC_MAX_HEALTH)?.baseValue = health
        horse.health = health
        mounts[player.uniqueId] = horse.uniqueId
        owners[horse.uniqueId] = player.uniqueId
        horse.addPassenger(player)
        player.world.spawnParticle(Particle.CLOUD, horse.location.add(0.0, 1.0, 0.0), 20, 0.5, 0.5, 0.5, 0.02)
        Sounds.play(player, Sound.ENTITY_HORSE_AMBIENT, 1f, 1.2f)
        player.sendMessage("§a탈것을 불렀습니다! §7(내리면 사라져요. 다시 /탈것 하면 돌려보내기)")
        return true
    }

    // 말 제거 (드롭 없음)
    private fun despawn(horseId: UUID, startCooldown: Boolean) {
        val ownerId = owners.remove(horseId) ?: return
        mounts.remove(ownerId)
        Bukkit.getEntity(horseId)?.let { h ->
            h.world.spawnParticle(Particle.CLOUD, h.location.add(0.0, 1.0, 0.0), 15, 0.5, 0.5, 0.5, 0.02)
            h.remove()
        }
        if (startCooldown) cooldowns[ownerId] = System.currentTimeMillis() + cooldownSeconds * 1000L
    }

    private fun checkCombat() {
        if (mounts.isEmpty()) return
        mounts.toList().forEach { (ownerId, horseId) ->
            val p = Bukkit.getPlayer(ownerId)
            val h = Bukkit.getEntity(horseId)
            if (p == null || h == null || !h.isValid) return@forEach despawn(horseId, startCooldown = true)
            if (plugin.combatManager.isInCombat(p) && p.vehicle?.uniqueId == horseId) {
                p.leaveVehicle() // 내리면 VehicleExitEvent에서 말이 사라짐
                p.sendMessage("§c전투가 시작되어 탈것에서 내렸습니다.")
                Sounds.fail(p)
            }
        }
    }

    // 내리면 사라짐
    @EventHandler
    fun onExit(e: VehicleExitEvent) {
        val id = e.vehicle.uniqueId
        if (!isOurs(id)) return
        Bukkit.getScheduler().runTask(plugin, Runnable { despawn(id, startCooldown = true) })
    }

    // 주인만 탈 수 있음
    @EventHandler(ignoreCancelled = true)
    fun onEnter(e: VehicleEnterEvent) {
        val ownerId = owners[e.vehicle.uniqueId] ?: return
        if (e.entered.uniqueId != ownerId) e.isCancelled = true
    }

    // 말 인벤토리(안장) 열기 · 먹이 주기 · 끈 묶기 등 상호작용 막기 (안장 복사 방지)
    @EventHandler(ignoreCancelled = true)
    fun onOpen(e: InventoryOpenEvent) {
        val holder = e.inventory.holder as? Horse ?: return
        if (isOurs(holder.uniqueId)) e.isCancelled = true
    }

    @EventHandler(ignoreCancelled = true)
    fun onInteract(e: PlayerInteractEntityEvent) {
        val ownerId = owners[e.rightClicked.uniqueId] ?: return
        // 주인이 맨손으로 우클릭해서 다시 타는 것만 허용
        if (e.player.uniqueId != ownerId || !e.player.inventory.itemInMainHand.type.isAir) e.isCancelled = true
    }

    @EventHandler
    fun onHorseDeath(e: EntityDeathEvent) {
        val ownerId = owners[e.entity.uniqueId] ?: return
        e.drops.clear()
        e.droppedExp = 0
        despawn(e.entity.uniqueId, startCooldown = false)
        cooldowns[ownerId] = System.currentTimeMillis() + deathCooldownSeconds * 1000L
        Bukkit.getPlayer(ownerId)?.sendMessage("§c탈것이 쓰러졌습니다. §7(${deathCooldownSeconds}초 뒤 다시 부를 수 있어요)")
    }

    @EventHandler
    fun onQuit(e: PlayerQuitEvent) {
        mounts[e.player.uniqueId]?.let { despawn(it, startCooldown = false) }
    }

    @EventHandler
    fun onDeath(e: PlayerDeathEvent) {
        mounts[e.entity.uniqueId]?.let { despawn(it, startCooldown = true) }
    }

    /** 서버가 꺼질 때 모든 탈것 제거 */
    fun shutdown() {
        owners.keys.toList().forEach { despawn(it, startCooldown = false) }
    }

    private fun deny(p: Player, message: String) {
        p.sendMessage(message)
        Sounds.fail(p)
    }
}
