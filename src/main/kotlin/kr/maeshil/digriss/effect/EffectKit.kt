package kr.maeshil.digriss.effect

import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.cos
import kotlin.math.sin

/**
 * 스킬 이펙트 공용 도구.
 * 파티클·소리·지연 실행을 전부 [sink] 하나로 모아 두었다. 서버에서는 [BukkitSink]가 그대로 Bukkit을 부르고,
 * 미리보기 렌더러는 sink만 갈아 끼워 같은 코드를 오프라인에서 돌린다.
 */
object EffectKit {

    interface Sink {
        /** viewer가 있으면 그 플레이어에게만, 없으면 월드 전체에 보인다. */
        fun particle(viewer: Player?, at: Location, type: Particle, count: Int, ox: Double, oy: Double, oz: Double, extra: Double, data: Any?)
        fun sound(viewer: Player?, at: Location, sound: Sound, volume: Float, pitch: Float)
        fun later(delayTicks: Long, task: () -> Unit)
        fun blockData(material: Material): Any?
    }

    object BukkitSink : Sink {
        private val plugin get() = Bukkit.getPluginManager().getPlugin("Digriss")!!

        override fun particle(viewer: Player?, at: Location, type: Particle, count: Int, ox: Double, oy: Double, oz: Double, extra: Double, data: Any?) {
            if (viewer != null) viewer.spawnParticle(type, at, count, ox, oy, oz, extra, data)
            else at.world?.spawnParticle(type, at, count, ox, oy, oz, extra, data)
        }

        override fun sound(viewer: Player?, at: Location, sound: Sound, volume: Float, pitch: Float) {
            if (viewer != null) viewer.playSound(at, sound, volume, pitch)
            else at.world?.playSound(at, sound, volume, pitch)
        }

        override fun later(delayTicks: Long, task: () -> Unit) {
            if (delayTicks <= 0L) task() else Bukkit.getScheduler().runTaskLater(plugin, Runnable { task() }, delayTicks)
        }

        override fun blockData(material: Material): Any? = material.createBlockData()
    }

    @JvmStatic
    var sink: Sink = BukkitSink

    // ── 파티클 ──────────────────────────────────────────────

    fun p(at: Location, type: Particle, count: Int = 1, spread: Double = 0.0, speed: Double = 0.0, viewer: Player? = null) =
        sink.particle(viewer, at, type, count, spread, spread, spread, speed, null)

    fun p3(at: Location, type: Particle, count: Int, ox: Double, oy: Double, oz: Double, speed: Double = 0.0, viewer: Player? = null) =
        sink.particle(viewer, at, type, count, ox, oy, oz, speed, null)

    /** count 0 트릭: 오프셋이 곧 속도 방향이 된다. 불꽃·연기 같은 움직이는 파티클을 원하는 방향으로 날릴 때. */
    fun fling(at: Location, type: Particle, velocity: Vector, speed: Double = 1.0, viewer: Player? = null) =
        sink.particle(viewer, at, type, 0, velocity.x, velocity.y, velocity.z, speed, null)

    fun dust(at: Location, color: Color, size: Float = 1f, count: Int = 1, spread: Double = 0.0, viewer: Player? = null) =
        sink.particle(viewer, at, Particle.DUST, count, spread, spread, spread, 0.0, Particle.DustOptions(color, size))

    /** 시간이 지나며 from → to 로 색이 바뀌는 먼지. 잔상·식어가는 불씨에 쓴다. */
    fun fade(at: Location, from: Color, to: Color, size: Float = 1f, count: Int = 1, spread: Double = 0.0, viewer: Player? = null) =
        sink.particle(viewer, at, Particle.DUST_COLOR_TRANSITION, count, spread, spread, spread, 0.0, Particle.DustTransition(from, to, size))

    /** BLOCK / FALLING_DUST / DUST_PILLAR 처럼 블록 데이터를 받는 파티클. */
    fun block(at: Location, type: Particle, material: Material, count: Int = 1, spread: Double = 0.0, speed: Double = 0.0) =
        sink.particle(null, at, type, count, spread, spread, spread, speed, sink.blockData(material))

    fun sound(at: Location, sound: Sound, volume: Float = 1f, pitch: Float = 1f, viewer: Player? = null) =
        sink.sound(viewer, at, sound, volume, pitch)

    // ── 시간 ────────────────────────────────────────────────

    fun later(ticks: Int, task: () -> Unit) = sink.later(ticks.toLong(), task)

    /** task(i)를 i = 0 until times 만큼 interval 틱마다 실행. task가 false를 돌려주면 그 자리에서 멈춘다. */
    fun every(times: Int, interval: Int = 1, delay: Int = 0, task: (Int) -> Boolean) {
        fun step(i: Int) {
            if (i >= times || !task(i)) return
            sink.later(interval.toLong()) { step(i + 1) }
        }
        sink.later(delay.toLong()) { step(0) }
    }

    // ── 기하 ────────────────────────────────────────────────

    val UP: Vector get() = Vector(0.0, 1.0, 0.0)

    /** 수평 정면 방향 (Y 제거 후 정규화). */
    fun flat(dir: Vector): Vector {
        val v = dir.clone().setY(0.0)
        return if (v.lengthSquared() < 1e-6) Vector(0.0, 0.0, 1.0) else v.normalize()
    }

    /** 수평 정면 기준 오른쪽. */
    fun right(forward: Vector): Vector = Vector(-forward.z, 0.0, forward.x).let {
        if (it.lengthSquared() < 1e-6) Vector(1.0, 0.0, 0.0) else it.normalize()
    }

    /** 수평면에서 angle(라디안)만큼 돌린 벡터. 기존 스킬 코드의 rotateY와 같은 방향. */
    fun rotY(v: Vector, angle: Double): Vector {
        val c = cos(angle)
        val s = sin(angle)
        return Vector(v.x * c - v.z * s, v.y, v.x * s + v.z * c)
    }

    /** origin + f*a + r*b + u*c */
    fun at(origin: Location, f: Vector, a: Double, r: Vector, b: Double, u: Vector = UP, c: Double = 0.0): Location =
        origin.clone().add(f.clone().multiply(a)).add(r.clone().multiply(b)).add(u.clone().multiply(c))

    /** 수평 원 위의 점들. */
    inline fun ring(center: Location, radius: Double, points: Int, phase: Double = 0.0, fn: (Location, Int) -> Unit) {
        for (i in 0 until points) {
            val a = phase + Math.PI * 2 * i / points
            fn(center.clone().add(cos(a) * radius, 0.0, sin(a) * radius), i)
        }
    }

    /** a → b 직선 위를 step 간격으로. */
    inline fun line(a: Location, b: Location, step: Double, fn: (Location, Double) -> Unit) {
        val d = b.toVector().subtract(a.toVector())
        val len = d.length()
        if (len < 1e-6) { fn(a.clone(), 0.0); return }
        val n = (len / step).toInt().coerceAtLeast(1)
        for (i in 0..n) {
            val t = i.toDouble() / n
            fn(a.clone().add(d.clone().multiply(t)), t)
        }
    }

    /** 두 색 사이 보간. */
    fun mix(a: Color, b: Color, t: Double): Color {
        val k = t.coerceIn(0.0, 1.0)
        return Color.fromRGB(
            (a.red + (b.red - a.red) * k).toInt(),
            (a.green + (b.green - a.green) * k).toInt(),
            (a.blue + (b.blue - a.blue) * k).toInt()
        )
    }

    fun rgb(hex: Int): Color = Color.fromRGB((hex shr 16) and 0xFF, (hex shr 8) and 0xFF, hex and 0xFF)
}
