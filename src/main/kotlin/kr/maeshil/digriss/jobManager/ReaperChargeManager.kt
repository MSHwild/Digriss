package kr.maeshil.digriss.jobManager

import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.Digriss
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Particle
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.math.cos
import kotlin.math.sin

object ReaperChargeManager {

    private val charged = HashSet<UUID>()

    fun isCharged(uuid: UUID): Boolean = charged.contains(uuid)

    fun consumeCharge(uuid: UUID) {
        charged.remove(uuid)
    }

    // 사신이 플레이어를 처치했을 때 호출 — 사망 위치에 구체 형태 파티클 5초간 표시
    fun spawnOrb(plugin: Digriss, reaper: Player, deathLocation: Location) {
        val world = deathLocation.world ?: return
        val center = deathLocation.clone().add(0.5, 1.0, 0.5)
        val radius = 0.4
        val reaperUuid = reaper.uniqueId

        // 이번 구슬을 아직 흡수 못 한 상태로 시작 (혹시 이전 충전이 남아있어도 새 구슬 기준으로 초기화)
        charged.remove(reaperUuid)

        val task = object : Runnable {
            var tick = 0
            var taskId = -1

            override fun run() {
                // 사신이 나갔거나 다른 월드로 이동하면 구슬 소멸 (거리 계산 시 월드가 다르면 오류)
                if (!reaper.isOnline || reaper.world != world) {
                    Bukkit.getScheduler().cancelTask(taskId)
                    return
                }
                if (tick >= 100) { // 5초(100틱) 경과 시 구슬 소멸
                    // 그 안에 흡수 못 했으면 확실히 미충전 상태로 고정
                    if (!charged.contains(reaperUuid)) {
                        charged.remove(reaperUuid) // 방어적으로 한번 더 보장
                    }
                    Bukkit.getScheduler().cancelTask(taskId)
                    return
                }

                val bob = sin(tick * 0.1) * 0.15
                val orbCenter = center.clone().add(0.0, bob, 0.0)

                val angle = tick * 0.2
                for (i in 0 until 12) {
                    val theta = angle + (i * Math.PI * 2 / 12)
                    val x = radius * cos(theta)
                    val z = radius * sin(theta)
                    world.spawnParticle(Particle.SOUL, orbCenter.clone().add(x, 0.0, z), 1, 0.0, 0.0, 0.0, 0.0)
                }
                for (i in 0 until 12) {
                    val theta = angle + (i * Math.PI * 2 / 12)
                    val x = radius * cos(theta)
                    val y = radius * sin(theta)
                    world.spawnParticle(Particle.SOUL, orbCenter.clone().add(x, y, 0.0), 1, 0.0, 0.0, 0.0, 0.0)
                }
                world.spawnParticle(Particle.END_ROD, orbCenter, 1, 0.02, 0.02, 0.02, 0.0)

                if (reaper.location.distance(orbCenter) <= 2.5) {
                    charged.add(reaperUuid)
                    Sounds.play(reaper, org.bukkit.Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1f, 1.3f)
                    reaper.sendMessage("§b영혼 구슬을 흡수했습니다! F키로 무체화를 발동할 수 있습니다.")
                    Bukkit.getScheduler().cancelTask(taskId)
                    return
                }

                tick += 2
            }
        }

        task.taskId = Bukkit.getScheduler().runTaskTimer(plugin, task, 0L, 2L).taskId
    }
}