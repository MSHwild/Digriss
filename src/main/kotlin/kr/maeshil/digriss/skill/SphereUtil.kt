package kr.maeshil.digriss.skill

import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.scheduler.BukkitTask
import kotlin.math.abs
import kotlin.math.ceil

class Barrier(val touched: List<Location>) {
    var task: BukkitTask? = null
    var destroyed = false
}

object SphereUtil {
    private val activeBlocks = HashMap<Location, Pair<Material, Int>>()
    private val blockToBarrier = HashMap<Location, Barrier>()

    // 빈 공간(공기)에만 흑요석을 채움. 기존 블록을 바꾸면 상자 내용물·신호기 등이 사라지므로 건드리지 않음
    // (이미 있는 블록이 결계 벽 역할을 하므로 막는 효과는 같음)
    fun placeSphereShell(center: Location, radius: Double, world: World, shellThickness: Double = 0.75): Barrier {
        val touched = ArrayList<Location>()
        val r = ceil(radius).toInt() + 1

        for (bx in -r..r) {
            for (by in -r..r) {
                for (bz in -r..r) {
                    val blockCenter = Location(world, center.blockX + bx + 0.5, center.blockY + by + 0.5, center.blockZ + bz + 0.5)
                    val dist = blockCenter.distance(center)
                    if (abs(dist - radius) > shellThickness) continue

                    val loc = blockCenter.block.location
                    val existing = activeBlocks[loc]
                    if (existing == null) {
                        if (!loc.block.type.isAir) continue
                        activeBlocks[loc] = Pair(Material.AIR, 1)
                        loc.block.type = Material.OBSIDIAN
                    } else {
                        // 다른 결계와 겹친 칸: 둘 다 사라질 때까지 유지
                        activeBlocks[loc] = Pair(existing.first, existing.second + 1)
                    }
                    touched.add(loc)
                }
            }
        }

        val barrier = Barrier(touched)
        touched.forEach { blockToBarrier[it] = barrier }
        return barrier
    }

    fun destroyBarrier(barrier: Barrier) {
        if (barrier.destroyed) return
        barrier.destroyed = true
        barrier.task?.cancel()

        for (loc in barrier.touched) {
            blockToBarrier.remove(loc)
            val existing = activeBlocks[loc] ?: continue
            val newCount = existing.second - 1
            if (newCount <= 0) {
                loc.block.type = existing.first
                activeBlocks.remove(loc)
            } else {
                activeBlocks[loc] = Pair(existing.first, newCount)
            }
        }
    }

    // 서버 종료/리로드 시 남은 결계 흑요석을 모두 원래대로 (안 하면 흑요석이 영구히 남음)
    fun restoreAll() {
        activeBlocks.forEach { (loc, original) -> loc.block.type = original.first }
        activeBlocks.clear()
        blockToBarrier.clear()
    }

    fun getBarrier(loc: Location): Barrier? = blockToBarrier[loc]
}
