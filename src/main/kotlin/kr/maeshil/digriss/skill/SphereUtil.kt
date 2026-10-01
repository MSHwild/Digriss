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
                    touched.add(loc)

                    val existing = activeBlocks[loc]
                    if (existing == null) {
                        activeBlocks[loc] = Pair(loc.block.type, 1)
                        loc.block.type = Material.OBSIDIAN
                    } else {
                        activeBlocks[loc] = Pair(existing.first, existing.second + 1)
                    }
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

    fun getBarrier(loc: Location): Barrier? = blockToBarrier[loc]
}