package kr.maeshil.digriss.effect

import kr.maeshil.digriss.effect.EffectKit.at
import kr.maeshil.digriss.effect.EffectKit.block
import kr.maeshil.digriss.effect.EffectKit.dust
import kr.maeshil.digriss.effect.EffectKit.every
import kr.maeshil.digriss.effect.EffectKit.fade
import kr.maeshil.digriss.effect.EffectKit.flat
import kr.maeshil.digriss.effect.EffectKit.fling
import kr.maeshil.digriss.effect.EffectKit.later
import kr.maeshil.digriss.effect.EffectKit.line
import kr.maeshil.digriss.effect.EffectKit.mix
import kr.maeshil.digriss.effect.EffectKit.p
import kr.maeshil.digriss.effect.EffectKit.p3
import kr.maeshil.digriss.effect.EffectKit.rgb
import kr.maeshil.digriss.effect.EffectKit.right
import kr.maeshil.digriss.effect.EffectKit.ring
import kr.maeshil.digriss.effect.EffectKit.rotY
import kr.maeshil.digriss.effect.EffectKit.sound
import org.bukkit.Color
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Particle
import org.bukkit.Sound
import org.bukkit.entity.Player
import org.bukkit.util.Vector
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 디그리스 스킬 이펙트 모음 (보이는 것과 소리만 담당).
 * 피해·범위·쿨타임·상태이상 같은 수치는 각 스킬 클래스에 그대로 두고, 그 클래스의 파티클/소리 줄만 여기 함수 호출로 바꾼다.
 * 위치를 계속 따라가야 하는 이펙트는 follow 람다를 받는다. 람다가 null을 주면 (사망·접속 종료) 그 자리에서 멈춘다.
 */
object SkillEffects {

    /** 팔레트. 무기 4종은 weapons/<무기>/design.md 의 텍스처 색에서 가져왔다. */
    object Palette {
        // 지옥불 대검: 용암 균열
        val HELL_CORE = rgb(0xFFD24A); val HELL = rgb(0xE0520E); val HELL_DEEP = rgb(0x6A1405); val ASH = rgb(0x2A2220)
        // 서리달의 도끼: 달빛 은색 + 서리
        val MOON = rgb(0xF2F8FF); val FROST = rgb(0x9FD8FF); val FROST_DEEP = rgb(0x3E7BD6)
        // 흡혈의 검 / 피의 낫: 진홍
        val BLOOD_HI = rgb(0xFF5A6A); val BLOOD = rgb(0xE5233D); val BLOOD_DEEP = rgb(0x6A0D1C); val BLOOD_DARK = rgb(0x3E000C)
        // 심연의 창: 청록 생물발광 + 녹슨 사슬
        val ABYSS_GLOW = rgb(0x5CFFE0); val ABYSS = rgb(0x1FA6A0); val ABYSS_DEEP = rgb(0x0B2A3F); val RUST = rgb(0x8A4A22)
        // 공허의 검
        val VOID_HI = rgb(0xE59BFF); val VOID = rgb(0x8A2BE2); val VOID_DEEP = rgb(0x1A0026)
        // 참격의 검 / 참: 벼린 강철과 바람
        val STEEL = rgb(0xF5FBFF); val WIND = rgb(0x9EE6FF); val STEEL_DIM = rgb(0x6C7884)
        // 주문서
        val PACT = rgb(0x9B2BD0); val PACT_DEEP = rgb(0x24002F)
        val THUNDER_CORE = rgb(0xE8D9FF); val THUNDER = rgb(0x6A35B8); val THUNDER_BLACK = rgb(0x0A0610)
        val STASIS_GOLD = rgb(0xF4DFA0); val STASIS = rgb(0xA9B8CC); val STASIS_DEEP = rgb(0x3D4A5C)
        val PULL_HI = rgb(0xBFE3FF); val PULL = rgb(0x2E6BFF); val PULL_DEEP = rgb(0x0A1A5C)
        val PUSH_HI = rgb(0xFFE0D0); val PUSH = rgb(0xFF2A2A); val PUSH_DEEP = rgb(0x5C0000)
        val MIRROR_HI = rgb(0xFFE6FA); val MIRROR = rgb(0xE040C0); val MIRROR_DEEP = rgb(0x4A0A40)
        // 직업
        val SHADOW_HI = rgb(0x9E6BD6); val SHADOW = rgb(0x2B1B3D)
        val GUARD = rgb(0xFFF4CC); val GUARD_GOLD = rgb(0xFFD66B); val GUARD_DEEP = rgb(0xB8860B)
        val LIFE_HI = rgb(0xF0FFE8); val LIFE = rgb(0x7CFF9B); val LIFE_PINK = rgb(0xFFB7D5)
        val SCOUT = rgb(0x5CFF7A); val SCOUT_DIM = rgb(0x0F4A1C); val SCOUT_ENEMY = rgb(0xFF3B3B)
        val RAGE = rgb(0xFF3B1F); val RAGE_DEEP = rgb(0x4A0A00)
        val REAPER = rgb(0x46E0D0); val REAPER_DEEP = rgb(0x0B1A1A)
    }

    private val C = Palette
    private val rnd = Random

    private fun chest(feet: Location) = feet.clone().add(0.0, 1.0, 0.0)
    private fun jitter(r: Double) = (rnd.nextDouble() * 2 - 1) * r

    /** 수평 부채꼴을 frames 틱에 걸쳐 from → to 각도로 훑는다. draw(각도 오프셋(rad), 진행도 0..1) */
    private fun sweep(frames: Int, fromDeg: Double, toDeg: Double, samplesPerFrame: Int, draw: (Double, Double) -> Unit) {
        every(frames) { f ->
            for (s in 0 until samplesPerFrame) {
                val t = (f * samplesPerFrame + s).toDouble() / (frames * samplesPerFrame - 1)
                draw(Math.toRadians(fromDeg + (toDeg - fromDeg) * t), t)
            }
            true
        }
    }

    // ════════════════════════════════════════════════════════
    //  무기 스킬
    // ════════════════════════════════════════════════════════

    /** 지옥불 대검 · 업화 참격: 왼쪽 위에서 오른쪽 아래로 4틱에 걸쳐 내려긋는 불꽃 호, 이어서 땅에서 마그마가 솟는다. */
    fun hellfireCleave(eye: Location, dir: Vector, range: Double, angleDeg: Double) {
        val f = flat(dir)
        val origin = eye.clone().add(0.0, -0.45, 0.0)
        val feet = eye.clone().add(0.0, -1.55, 0.0)
        val half = angleDeg / 2

        sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.6f)
        sound(eye, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.7f)
        later(2) { sound(eye, Sound.ENTITY_BLAZE_SHOOT, 0.8f, 0.6f) }

        sweep(4, -half, half, 6) { a, t ->
            val d = rotY(f, a)
            val tilt = 0.7 - 1.1 * t // 높은 곳에서 낮은 곳으로 내려긋기
            val tip = origin.clone().add(d.clone().multiply(range)).add(0.0, tilt, 0.0)
            fade(tip, C.HELL_CORE, C.HELL_DEEP, 1.7f)
            for (k in 1..3) {
                val r = range - k * 0.65
                fade(origin.clone().add(d.clone().multiply(r)).add(0.0, tilt * r / range, 0.0), C.HELL, C.ASH, 1.4f - k * 0.25f)
            }
            if (rnd.nextInt(2) == 0) fling(tip, Particle.FLAME, d.clone().add(Vector(0.0, 0.3, 0.0)), 0.12)
        }

        // 마그마 분출: 부채꼴 안쪽 5곳
        later(4) {
            sound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.7f, 0.9f)
            sound(feet, Sound.BLOCK_LAVA_POP, 1.2f, 0.7f)
            for (i in 0 until 5) {
                val d = rotY(f, Math.toRadians(-half + angleDeg * (i + 0.5) / 5))
                val spot = feet.clone().add(d.clone().multiply(range * (0.55 + 0.15 * (i % 2))))
                line(feet.clone().add(0.0, 0.05, 0.0), spot.clone().add(0.0, 0.05, 0.0), 0.5) { l, _ -> fade(l, C.HELL, C.HELL_DEEP, 0.9f) }
                block(spot.clone().add(0.0, 0.1, 0.0), Particle.DUST_PILLAR, Material.MAGMA_BLOCK, 6, 0.2)
                p(spot.clone().add(0.0, 0.3, 0.0), Particle.LAVA, 2, 0.2)
                fling(spot.clone().add(0.0, 0.2, 0.0), Particle.FLAME, Vector(jitter(0.1), 1.0, jitter(0.1)), 0.18)
                p(spot.clone().add(0.0, 0.5, 0.0), Particle.LARGE_SMOKE, 1, 0.15, 0.02)
            }
        }
        // 식어가는 불씨
        every(4, interval = 3, delay = 6) {
            val d = rotY(f, Math.toRadians(jitter(half)))
            fling(feet.clone().add(d.multiply(1.0 + rnd.nextDouble() * (range - 1))).add(0.0, 0.3, 0.0), Particle.SMALL_FLAME, Vector(0.0, 1.0, 0.0), 0.06)
            true
        }
    }

    fun hellfireHit(feet: Location) {
        val c = chest(feet)
        p(c, Particle.LAVA, 3, 0.3)
        fade(c, C.HELL_CORE, C.ASH, 1.4f, 8, 0.35)
        repeat(6) { fling(c, Particle.FLAME, Vector(jitter(1.0), rnd.nextDouble() * 0.6, jitter(1.0)), 0.12) }
    }

    /** 참격의 검 · 일섬: 가는 은빛 선이 먼저 그어지고, 2틱 뒤 그 선을 따라 베임이 터진다. */
    fun ironSlash(eye: Location, dir: Vector, range: Double) {
        val f = flat(dir)
        val origin = eye.clone().add(0.0, -0.35, 0.0)
        val end = origin.clone().add(f.clone().multiply(range))
        sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.6f)
        sound(eye, Sound.ITEM_TRIDENT_RIPTIDE_3, 0.6f, 1.8f)
        line(origin, end, 0.25) { l, _ -> dust(l, C.STEEL, 0.45f) }

        later(2) {
            sound(eye, Sound.BLOCK_ANVIL_LAND, 0.35f, 2.0f)
            sound(eye, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.2f)
            line(origin, end, 1.0) { l, _ -> p(l, Particle.SWEEP_ATTACK) }
            line(origin, end, 0.5) { l, _ -> p(l, Particle.ENCHANTED_HIT, 2, 0.1, 0.05) }
            // 끝에서 세로로 번뜩이는 빛
            val r = right(f)
            for (k in -4..4) fade(at(end, f, -0.04 * k * k, r, 0.0, EffectKit.UP, k * 0.22), C.STEEL, C.WIND, 1.1f - kotlin.math.abs(k) * 0.08f)
        }
        later(4) { line(origin, end, 0.5) { l, _ -> fade(l, C.STEEL, C.STEEL_DIM, 0.4f) } }
    }

    fun ironSlashHit(feet: Location) = later(2) {
        val c = chest(feet)
        block(c, Particle.BLOCK, Material.REDSTONE_BLOCK, 10, 0.25, 0.1)
        dust(c, C.BLOOD, 1.2f, 6, 0.3)
    }

    /** 출혈 1회분: 붉은 가루가 뚝뚝 떨어진다. */
    fun bleedTick(feet: Location) {
        val c = chest(feet)
        block(c, Particle.FALLING_DUST, Material.REDSTONE_BLOCK, 4, 0.25)
        dust(c, C.BLOOD_DEEP, 0.8f, 2, 0.2)
    }

    /** 피의 낫 · 피의 수확: 낫이 6틱에 걸쳐 한 바퀴 돈다. */
    fun bloodHarvest(feet: Location, dir: Vector, radius: Double) {
        val f = flat(dir)
        sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.5f)
        sound(feet, Sound.ENTITY_WITHER_HURT, 0.5f, 1.4f)
        later(3) { sound(feet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 0.7f) }
        sweep(6, 0.0, 360.0, 8) { a, _ ->
            val d = rotY(f, a)
            val base = feet.clone().add(0.0, 0.9, 0.0)
            fade(base.clone().add(d.clone().multiply(radius * 0.95)), C.BLOOD_HI, C.BLOOD_DARK, 1.6f)
            fade(base.clone().add(d.clone().multiply(radius * 0.75)).add(0.0, -0.1, 0.0), C.BLOOD, C.BLOOD_DARK, 1.1f)
            fade(base.clone().add(d.clone().multiply(radius * 0.55)).add(0.0, -0.2, 0.0), C.BLOOD_DEEP, C.BLOOD_DARK, 0.8f)
        }
        later(6) { ring(feet.clone().add(0.0, 0.08, 0.0), radius, 48) { l, _ -> dust(l, C.BLOOD_DEEP, 1.1f) } }
    }

    /** 맞은 적에게서 피 구슬이 곡선을 그리며 시전자에게 돌아온다. */
    fun bloodHarvestHit(feet: Location, follow: () -> Location?) {
        val start = chest(feet)
        block(start, Particle.BLOCK, Material.REDSTONE_BLOCK, 8, 0.25, 0.1)
        val lift = 1.2 + rnd.nextDouble()
        every(10, delay = 2) { i ->
            val to = follow()?.let { chest(it) } ?: return@every false
            val t = (i + 1) / 10.0
            val pos = start.clone().add(to.toVector().subtract(start.toVector()).multiply(t)).add(0.0, sin(PI * t) * lift, 0.0)
            dust(pos, C.BLOOD, 1.4f)
            fade(pos, C.BLOOD_DEEP, C.BLOOD_DARK, 0.8f, 2, 0.06)
            if (i == 9) dust(to, C.BLOOD_HI, 1.0f, 6, 0.3)
            true
        }
    }

    /** 흡혈 성공 시: 구슬이 다 돌아온 뒤 시전자 몸을 감고 올라가는 핏줄. */
    fun bloodHarvestHeal(follow: () -> Location?) {
        later(12) { follow()?.let { sound(it, Sound.ENTITY_WITCH_DRINK, 1.0f, 0.7f) } }
        every(8, delay = 12) { i ->
            val c = follow() ?: return@every false
            for (s in 0..1) {
                val a = i * 0.8 + s * PI
                dust(c.clone().add(cos(a) * 0.6, 0.2 + i * 0.25, sin(a) * 0.6), if (s == 0) C.BLOOD_HI else C.BLOOD, 1.0f)
            }
            true
        }
    }

    /** 흡혈의 검 · 혈류 흡수: 대상에게 X자 베임, 이어서 두 가닥 핏줄이 나선을 그리며 시전자에게 빨려 온다. */
    fun vampireDrain(targetChest: Location, follow: () -> Location?) {
        val me = follow() ?: return
        sound(me, Sound.ENTITY_PLAYER_ATTACK_STRONG, 1.2f, 0.8f)
        sound(targetChest, Sound.ENTITY_PHANTOM_BITE, 1.0f, 0.7f)
        val toMe = flat(me.toVector().subtract(targetChest.toVector()))
        val r = right(toMe)
        for (sgn in listOf(-1.0, 1.0)) {
            for (k in -5..5) dust(at(targetChest, toMe, 0.3, r, k * 0.16, EffectKit.UP, sgn * k * 0.16), C.BLOOD_HI, 0.9f)
        }
        block(targetChest, Particle.BLOCK, Material.REDSTONE_BLOCK, 10, 0.25, 0.1)
        p(targetChest, Particle.SWEEP_ATTACK)

        val start = targetChest.clone()
        every(10, delay = 2) { i ->
            val end = follow()?.let { chest(it) } ?: return@every false
            val axis = end.toVector().subtract(start.toVector())
            val fa = axis.clone().normalize()
            val ra = right(flat(fa))
            val ua = ra.clone().crossProduct(fa)
            for (j in 0..2) { // 머리 부분 3점씩 두 가닥
                val t = ((i + 1) - j * 0.3) / 10.0
                if (t <= 0) continue
                val base = start.clone().add(axis.clone().multiply(t))
                for (s in 0..1) {
                    val a = t * 4 * PI + s * PI
                    val off = ra.clone().multiply(cos(a) * 0.3).add(ua.clone().multiply(sin(a) * 0.3))
                    dust(base.clone().add(off), if (s == 0) C.BLOOD else C.BLOOD_HI, 1.2f - j * 0.25f)
                }
            }
            if (i == 9) {
                ring(end, 0.8, 16) { l, _ -> dust(l, C.BLOOD_HI, 1.0f) }
                p(end.clone().add(0.0, 0.6, 0.0), Particle.HEART)
                sound(end, Sound.ENTITY_GENERIC_DRINK, 0.7f, 0.6f)
            }
            true
        }
    }

    /** 서리달의 도끼 · 초승달 베기: 가운데가 두껍고 끝이 가는 초승달이 3틱에 걸쳐 그려지고, 바깥 테두리에 얼음 가시가 솟았다 부서진다. */
    fun frostCrescent(eye: Location, dir: Vector, range: Double, angleDeg: Double) {
        val f = flat(dir)
        val origin = eye.clone().add(0.0, -0.5, 0.0)
        val feet = eye.clone().add(0.0, -1.55, 0.0)
        val half = angleDeg / 2
        sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.3f)
        sound(eye, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.7f)

        sweep(3, half, -half, 8) { a, t ->
            val d = rotY(f, a)
            val bulge = sin(PI * t)
            val outer = range * (0.8 + 0.2 * bulge)
            val thick = 0.2 + 1.3 * bulge
            fade(origin.clone().add(d.clone().multiply(outer)), C.MOON, C.FROST, 1.5f)
            fade(origin.clone().add(d.clone().multiply(outer - thick * 0.5)), C.FROST, C.FROST_DEEP, 1.1f)
            if (thick > 0.8) fade(origin.clone().add(d.clone().multiply(outer - thick)), C.FROST_DEEP, C.FROST_DEEP, 0.8f)
            if (rnd.nextInt(2) == 0) fling(origin.clone().add(d.clone().multiply(outer)), Particle.SNOWFLAKE, d, 0.08)
        }

        val spikes = (0 until 7).map { i ->
            val d = rotY(f, Math.toRadians(-half + angleDeg * i / 6.0))
            feet.clone().add(d.multiply(range * 0.9))
        }
        later(3) {
            sound(eye, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.6f)
            for (s in spikes) {
                for (h in 0..3) dust(s.clone().add(0.0, 0.1 + h * 0.3, 0.0), C.MOON, 1.3f - h * 0.25f)
                block(s.clone().add(0.0, 0.3, 0.0), Particle.BLOCK, Material.PACKED_ICE, 4, 0.15)
            }
        }
        later(8) {
            sound(feet, Sound.BLOCK_GLASS_BREAK, 0.8f, 1.4f)
            for (s in spikes) {
                block(s.clone().add(0.0, 0.5, 0.0), Particle.BLOCK, Material.ICE, 6, 0.2, 0.1)
                p(s.clone().add(0.0, 0.5, 0.0), Particle.ITEM_SNOWBALL, 3, 0.2)
            }
        }
    }

    /** 맞은 적: 얼음 파편이 튀고, 구속이 걸려 있는 동안 발밑에 서리 고리가 돈다. */
    fun frostHit(feet: Location, follow: () -> Location?, slowTicks: Int) {
        val c = chest(feet)
        block(c, Particle.BLOCK, Material.BLUE_ICE, 10, 0.3, 0.1)
        p(c, Particle.SNOWFLAKE, 10, 0.3, 0.03)
        every(slowTicks / 5, interval = 5, delay = 5) { i ->
            val at = follow() ?: return@every false
            ring(at.clone().add(0.0, 0.1, 0.0), 0.55, 6, phase = i * 0.5) { l, _ -> dust(l, C.FROST, 0.7f) }
            if (i % 2 == 0) p(at.clone().add(0.0, 0.3, 0.0), Particle.SNOWFLAKE, 1, 0.3)
            true
        }
    }

    /** 심연의 창 · 심연 돌진: 출발점 물보라, 돌진하는 동안 몸을 감는 청록 소용돌이, 지나간 자리에 녹슨 사슬 자국. */
    fun abyssDash(start: Location, dir: Vector, follow: () -> Location?) {
        val f = flat(dir)
        sound(start, Sound.ITEM_TRIDENT_RIPTIDE_2, 1.0f, 1.1f)
        sound(start, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.8f, 1.2f)
        sound(start, Sound.ENTITY_DOLPHIN_JUMP, 0.6f, 1.0f)
        p3(start.clone().add(0.0, 0.2, 0.0), Particle.SPLASH, 30, 0.5, 0.1, 0.5)
        ring(start.clone().add(0.0, 0.1, 0.0), 1.0, 16) { l, _ -> fade(l, C.ABYSS_GLOW, C.ABYSS_DEEP, 1.1f) }

        val r = right(f)
        every(8) { i ->
            val at = follow() ?: return@every false
            val body = at.clone().add(0.0, 1.0, 0.0)
            for (s in 0..2) {
                val a = i * 0.9 + s * 2 * PI / 3
                val pos = at(body, f, -0.3, r, cos(a) * 0.75, EffectKit.UP, sin(a) * 0.75)
                if (s == 0) dust(pos, C.ABYSS_GLOW, 1.0f) else fade(pos, C.ABYSS, C.ABYSS_DEEP, 1.2f)
            }
            p(body, Particle.BUBBLE_POP, 2, 0.3) // BUBBLE 은 물 밖에서 바로 사라져서 안 씀
            p(body, Particle.FALLING_WATER, 2, 0.3)
            p(body, Particle.NAUTILUS, 2, 0.4)
            true
        }
        later(8) {
            val end = follow() ?: return@later
            var n = 0
            line(start.clone().add(0.0, 0.15, 0.0), end.clone().add(0.0, 0.15, 0.0), 0.35) { l, _ ->
                val side = if (n++ % 2 == 0) 0.08 else -0.08
                fade(l.clone().add(r.clone().multiply(side)), C.RUST, C.ABYSS_DEEP, if (side > 0) 0.9f else 0.6f)
            }
        }
    }

    fun abyssHit(feet: Location) {
        val c = chest(feet)
        sound(c, Sound.ITEM_TRIDENT_HIT, 0.9f, 0.8f)
        p(c, Particle.SPLASH, 20, 0.3)
        p(c, Particle.BUBBLE_POP, 10, 0.3, 0.05)
        dust(c, C.ABYSS_GLOW, 1.2f, 8, 0.35)
    }

    /** 공허의 검 · 공허 절단: 출발점엔 사라지는 잔상과 빨려드는 포털 입자, 경로엔 보랏빛 칼금, 도착점엔 바닥에 번지는 균열. */
    fun voidRift(from: Location, to: Location, radius: Double) {
        sound(from, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1.0f, 0.8f)
        sound(to, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.6f)
        sound(to, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.8f, 1.4f)

        // 잔상
        var y = 0.2
        while (y <= 1.8) {
            fade(from.clone().add(0.0, y, 0.0), C.VOID, C.VOID_DEEP, 1.3f, 3, 0.12)
            y += 0.2
        }
        p(from.clone().add(0.0, 1.0, 0.0), Particle.PORTAL, 40, 0.6, 0.5)
        // 칼금
        line(from.clone().add(0.0, 1.0, 0.0), to.clone().add(0.0, 1.0, 0.0), 0.3) { l, _ -> fade(l, C.VOID_HI, C.VOID_DEEP, 0.8f) }
        // 도착
        p(to.clone().add(0.0, 1.0, 0.0), Particle.REVERSE_PORTAL, 60, 0.3, 0.4)
        p(to.clone().add(0.0, 1.0, 0.0), Particle.SQUID_INK, 20, 0.4, 0.05)
        every(5) { i ->
            val rr = radius * (i + 1) / 5
            ring(to.clone().add(0.0, 0.1, 0.0), rr, max(10, (rr * 7).toInt()), phase = i * 0.3) { l, k ->
                if (k % 2 == 0) dust(l, C.VOID, 1.3f) else fade(l, C.VOID_HI, C.VOID_DEEP, 1.0f)
            }
            true
        }
    }

    fun voidHit(feet: Location) {
        val eye = feet.clone().add(0.0, 1.5, 0.0)
        p(eye, Particle.SQUID_INK, 6, 0.2, 0.02)
        dust(chest(feet), C.VOID_DEEP, 1.5f, 6, 0.3)
        p(chest(feet), Particle.PORTAL, 10, 0.3, 0.5)
    }

    // ════════════════════════════════════════════════════════
    //  주문서 스킬
    // ════════════════════════════════════════════════════════

    /** 결계형 주문서(사생결목·무한정체)가 끝날 때: 흑요석 구가 부서지며 파편이 흩어진다. */
    fun barrierShatter(center: Location, radius: Double, hi: Color, deep: Color) {
        sound(center, Sound.BLOCK_GLASS_BREAK, 1.2f, 0.7f)
        sound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.2f, 0.8f)
        repeat(40) {
            val u = rnd.nextDouble() * 2 - 1
            val a = rnd.nextDouble() * 2 * PI
            val s = kotlin.math.sqrt(1 - u * u)
            val pos = center.clone().add(cos(a) * s * radius, u * radius, sin(a) * s * radius)
            block(pos, Particle.BLOCK, Material.OBSIDIAN, 2, 0.1)
            fade(pos, hi, deep, 1.0f)
        }
        later(2) { p(center, Particle.SMOKE, 30, radius * 0.6, 0.02) }
    }

    /** skill_1 사생결목: 바닥에 마법진이 10틱에 걸쳐 그려지고 영혼불 장막이 결계 벽을 따라 솟는다. */
    fun deathPactOpen(center: Location, radius: Double) {
        sound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.6f)
        sound(center, Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0f, 0.6f)
        val ground = center.clone().add(0.0, 0.1, 0.0)
        val outerPts = (radius * 8).toInt()
        val inner = radius * 0.6
        every(10) { i ->
            // 바깥 원: 10틱 동안 한 바퀴
            for (k in 0 until outerPts / 10 + 1) {
                val a = 2 * PI * (i * (outerPts / 10 + 1) + k) / outerPts
                fade(ground.clone().add(cos(a) * radius, 0.0, sin(a) * radius), C.PACT, C.PACT_DEEP, 1.2f)
            }
            // 안쪽 육망성: 뒤 5틱
            if (i >= 5) {
                val tri = if (i < 8) 0 else 1
                for (e in 0..2) {
                    val a0 = PI / 2 + tri * PI / 3 + e * 2 * PI / 3
                    val a1 = a0 + 2 * PI / 3
                    line(ground.clone().add(cos(a0) * inner, 0.0, sin(a0) * inner), ground.clone().add(cos(a1) * inner, 0.0, sin(a1) * inner), 0.45) { l, _ ->
                        dust(l, C.PACT, 0.9f)
                    }
                }
            }
            true
        }
        // 영혼불 장막: 결계 벽을 따라 위로
        ring(ground, radius * 0.95, 24) { l, _ -> fling(l, Particle.SOUL_FIRE_FLAME, Vector(0.0, 1.0, 0.0), 0.15) }
        later(10) {
            sound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 1.2f, 0.8f)
            p(center, Particle.SCULK_SOUL, 20, radius / 2, 0.02)
        }
    }

    /** 사생결목 지속 중 0.5초마다: 결계 바닥을 훑는 심장박동 파동. n = 몇 번째 박동인지. */
    fun deathPactPulse(center: Location, radius: Double, n: Int) {
        if (n % 2 == 0) sound(center, Sound.ENTITY_WARDEN_HEARTBEAT, 0.8f, 1.0f)
        val ground = center.clone().add(0.0, 0.15, 0.0)
        every(4) { i ->
            val rr = radius * (i + 1) / 4
            ring(ground, rr, max(8, (rr * 5).toInt()), phase = n * 0.4) { l, _ -> dust(l, C.PACT, 1.0f) }
            true
        }
    }

    /** 사생결목에 피해를 입는 적: 영혼이 빠져나간다. */
    fun deathPactDrain(feet: Location) {
        fling(chest(feet), Particle.SOUL, Vector(0.0, 1.0, 0.0), 0.05)
        dust(chest(feet), C.PACT_DEEP, 0.9f, 2, 0.2)
    }

    /** skill_2 개: 발사. */
    fun fireballLaunch(eye: Location, dir: Vector) {
        sound(eye, Sound.ENTITY_BLAZE_SHOOT, 1.0f, 0.8f)
        sound(eye, Sound.ITEM_FIRECHARGE_USE, 1.0f, 0.8f)
        repeat(6) { fling(eye.clone().add(dir.clone().multiply(0.8)), Particle.FLAME, dir.clone().add(Vector(jitter(0.25), jitter(0.25), jitter(0.25))), 0.2) }
    }

    /** 개: 화염구 한 틱분. 둘레를 도는 고리 3점 + 핵 + 연기 꼬리. */
    fun fireballTrail(cur: Location, dir: Vector, step: Int) {
        val f = dir.clone().normalize()
        val r = right(flat(f))
        val u = r.clone().crossProduct(f)
        p(cur, Particle.FLAME, 4, 0.08, 0.01)
        for (s in 0..2) {
            val a = step * 0.9 + s * 2 * PI / 3
            fade(cur.clone().add(r.clone().multiply(cos(a) * 0.35)).add(u.clone().multiply(sin(a) * 0.35)), C.HELL_CORE, C.HELL, 0.9f)
        }
        val tail = cur.clone().subtract(f.clone().multiply(0.5))
        p(tail, Particle.LARGE_SMOKE, 1, 0.05)
        p(tail, Particle.SMALL_FLAME, 2, 0.15)
        if (step % 3 == 0) p(cur, Particle.LAVA, 1)
    }

    /** 개: 폭발. 섬광 + 사방으로 튀는 불꽃 껍질 + 바닥 충격파. */
    fun fireballExplode(at: Location) {
        sound(at, Sound.ENTITY_GENERIC_EXPLODE, 1.2f, 1.1f)
        later(2) { sound(at, Sound.BLOCK_LAVA_POP, 1.2f, 0.6f) }
        p(at, Particle.EXPLOSION)
        p(at, Particle.FLASH)
        p(at, Particle.LAVA, 10, 0.8)
        repeat(24) {
            val u = rnd.nextDouble() * 2 - 1
            val a = rnd.nextDouble() * 2 * PI
            val s = kotlin.math.sqrt(1 - u * u)
            fling(at, Particle.FLAME, Vector(cos(a) * s, u, sin(a) * s), 0.25)
        }
        every(4) { i ->
            val rr = 0.5 + 2.0 * (i + 1) / 4
            ring(at, rr, (rr * 8).toInt()) { l, k ->
                fade(l, C.HELL, C.ASH, 1.3f)
                if (k % 3 == 0) p(l, Particle.LARGE_SMOKE, 1, 0.0, 0.01)
            }
            true
        }
    }

    /** skill_3 흑뢰: 하늘에서 검은 번개가 꺾이며 내려꽂히고, 기절하는 동안 머리 위에 전기 고리가 돈다. */
    fun blackLightning(targetFeet: Location, follow: () -> Location?, stunTicks: Int) {
        sound(targetFeet, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.9f, 1.5f)
        sound(targetFeet, Sound.ENTITY_LIGHTNING_BOLT_IMPACT, 1.2f, 1.4f)

        // 번개 경로: 위에서 아래로 7구간, 아래로 갈수록 덜 흔들림
        val pts = ArrayList<Location>()
        for (k in 0..7) {
            val t = k / 7.0
            val sway = if (k == 7) 0.0 else 0.9 * (1 - t)
            pts.add(targetFeet.clone().add(jitter(sway), 14.0 * (1 - t), jitter(sway)))
        }
        val branches = listOf(2, 4).map { k ->
            val s = pts[k]
            s to s.clone().add(jitter(1.8), -1.5 - rnd.nextDouble(), jitter(1.8))
        }
        // 먼지는 크기에 비례해 오래 남으므로 1.1 이하로 둔다: 번쩍인 뒤 금방 검게 식는다
        fun bolt(from: Color, size: Float) {
            for (k in 0 until pts.size - 1) {
                line(pts[k], pts[k + 1], 0.25) { l, _ ->
                    fade(l, from, C.THUNDER_BLACK, size)
                    dust(l, C.THUNDER, 0.6f, 1, 0.08)
                }
                p(pts[k], Particle.ELECTRIC_SPARK, 3, 0.1, 0.1)
            }
            for ((a, b) in branches) line(a, b, 0.3) { l, _ -> fade(l, from, C.THUNDER_BLACK, size * 0.7f) }
        }
        bolt(C.THUNDER_CORE, 1.1f)
        later(2) { bolt(C.THUNDER, 0.9f) } // 한 번 더 번쩍

        val ground = targetFeet.clone().add(0.0, 0.1, 0.0)
        p(ground.clone().add(0.0, 0.5, 0.0), Particle.FLASH)
        ring(ground, 1.5, 16) { l, _ -> dust(l, C.THUNDER_BLACK, 1.4f) }
        p(ground, Particle.SQUID_INK, 12, 0.6, 0.1)
        p(chest(targetFeet), Particle.ELECTRIC_SPARK, 25, 0.5, 0.4)

        every(stunTicks / 2, interval = 2, delay = 3) { i ->
            val at = follow() ?: return@every false
            ring(at.clone().add(0.0, 2.15, 0.0), 0.45, 3, phase = i * 0.7) { l, _ ->
                p(l, Particle.ELECTRIC_SPARK)
                dust(l, C.THUNDER, 0.6f)
            }
            true
        }
    }

    /** skill_4 무한정체: 바닥에 시계판이 새겨지고 결계 안 공기가 멈춘 듯 먼지가 떠 있다. */
    fun stasisOpen(center: Location, radius: Double) {
        sound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, 0.8f)
        sound(center, Sound.BLOCK_BEACON_DEACTIVATE, 1.0f, 0.5f)
        sound(center, Sound.BLOCK_BELL_USE, 1.2f, 0.5f)
        drawClock(center, radius, 0)
        dust(center, C.STASIS, 0.8f, 60, radius * 0.5)
    }

    private fun drawClock(center: Location, radius: Double, elapsed: Int) {
        val g = center.clone().add(0.0, 0.1, 0.0)
        ring(g, radius, 40) { l, _ -> dust(l, C.STASIS_GOLD, 1.1f) }
        ring(g, radius * 0.25, 10) { l, _ -> fade(l, C.STASIS, C.STASIS_DEEP, 0.9f) }
        for (h in 0 until 12) {
            val a = 2 * PI * h / 12
            for (k in 0..2) {
                val rr = radius * (0.82 + 0.09 * k)
                dust(g.clone().add(cos(a) * rr, 0.0, sin(a) * rr), C.STASIS_GOLD, 1.3f)
            }
        }
        // 초침: 100틱(5초) 동안 한 바퀴. 짧은 바늘은 멈춰 있다.
        val sec = -PI / 2 + 2 * PI * elapsed / 100.0
        line(g, g.clone().add(cos(sec) * radius * 0.9, 0.0, sin(sec) * radius * 0.9), 0.35) { l, _ -> dust(l, C.STASIS_GOLD, 1.0f) }
        line(g, g.clone().add(radius * 0.5, 0.0, 0.0), 0.35) { l, _ -> fade(l, C.STASIS, C.STASIS_DEEP, 1.2f) }
    }

    /** 무한정체 결계 지속 중 매 틱. 10틱마다 시계판을 다시 그리고 1초마다 째깍. */
    fun stasisTick(center: Location, radius: Double, elapsed: Int) {
        if (elapsed % 10 == 0) drawClock(center, radius, elapsed)
        if (elapsed % 20 == 0) sound(center, Sound.BLOCK_NOTE_BLOCK_HAT, 1.0f, 0.6f)
    }

    /** 무한정체에 붙잡힌 적: 몸을 서로 반대로 도는 금빛 고리 두 개가 감싼다. */
    fun stasisFrozen(feet: Location, elapsed: Int) {
        if (elapsed % 4 != 0) return
        ring(feet.clone().add(0.0, 0.5, 0.0), 0.6, 6, phase = elapsed * 0.08) { l, _ -> dust(l, C.STASIS_GOLD, 0.7f) }
        ring(feet.clone().add(0.0, 1.4, 0.0), 0.6, 6, phase = -elapsed * 0.08) { l, _ -> dust(l, C.STASIS, 0.7f) }
        if (elapsed % 20 == 0) p(chest(feet), Particle.ENCHANT, 4, 0.4, 0.5)
    }

    /** skill_5 참: 발사. */
    fun chamLaunch(eye: Location) {
        sound(eye, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.0f, 1.3f)
        sound(eye, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 0.9f)
        sound(eye, Sound.ENTITY_BREEZE_SHOOT, 0.6f, 1.4f)
    }

    /** 참: 날아가는 초승달 칼날 한 칸분. 나아갈수록 조금씩 넓어진다. */
    fun chamStep(cur: Location, dir: Vector, traveled: Double, range: Double) {
        val f = dir.clone().normalize()
        val r = right(flat(f))
        val grow = 1.0 + traveled / range * 0.6
        for (k in -3..3) {
            val pos = cur.clone().add(f.clone().multiply(-0.07 * k * k)).add(r.clone().multiply(k * 0.35 * grow))
            fade(pos, C.STEEL, C.WIND, 1.2f - kotlin.math.abs(k) * 0.15f)
        }
        p(cur, Particle.SWEEP_ATTACK)
        val tail = cur.clone().subtract(f.clone().multiply(0.6))
        p(tail, Particle.ENCHANTED_HIT, 2, 0.15)
    }

    fun chamHit(feet: Location) {
        sound(feet, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 1.1f)
        p(chest(feet), Particle.CRIT, 12, 0.3, 0.3)
        p(chest(feet), Particle.SWEEP_ATTACK)
    }

    /** skill_6 인: 펼칠 때. */
    fun pullOpen(center: Location, radius: Double) {
        sound(center, Sound.ENTITY_ENDER_DRAGON_FLAP, 0.8f, 1.6f)
        sound(center, Sound.ENTITY_BREEZE_INHALE, 1.0f, 0.6f)
        sound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f)
        ring(center, radius, 48) { l, _ -> dust(l, C.PULL, 1.2f) }
    }

    /** 인 지속 중(2틱마다): 빛나는 핵, 안쪽으로 감겨드는 나선 팔 3개, 테두리에서 핵으로 빨려드는 줄기. */
    fun pullTick(center: Location, radius: Double, ticks: Long) {
        dust(center, C.PULL_HI, 2.0f)
        dust(center, C.PULL, 1.4f, 4, 0.2)
        // 테두리에서 핵으로 12틱마다 조여드는 고리
        val rr0 = radius * (1 - (ticks % 12) / 12.0)
        ring(center, rr0, max(8, (rr0 * 4).toInt()), phase = ticks * 0.1) { l, _ -> dust(l, C.PULL_HI, 0.7f) }
        for (arm in 0..2) {
            for (j in 0..6) {
                val rr = radius * (1 - j / 7.0)
                val a = -ticks * 0.25 + arm * 2 * PI / 3 + j * 0.45
                fade(center.clone().add(cos(a) * rr, 0.0, sin(a) * rr), C.PULL, C.PULL_DEEP, 0.8f)
            }
        }
        // PORTAL 은 count 0으로 쏘면 (위치 + 속도)에서 출발해 위치로 빨려 들어간다
        repeat(4) {
            val a = rnd.nextDouble() * 2 * PI
            fling(center, Particle.PORTAL, Vector(cos(a) * radius, jitter(0.6), sin(a) * radius), 1.0)
        }
        if (ticks % 10L == 0L) sound(center, Sound.ENTITY_BREEZE_INHALE, 0.5f, 0.6f)
    }

    /** 인 종료: 안으로 쪼그라들었다가 바람과 함께 터진다. */
    fun pullCollapse(center: Location) {
        sound(center, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 0.7f)
        sound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f)
        p(center, Particle.EXPLOSION)
        p(center, Particle.FLASH)
        p(center, Particle.GUST)
        every(4) { i ->
            val rr = 0.5 + 3.5 * (i + 1) / 4
            ring(center, rr, (rr * 7).toInt()) { l, _ -> fade(l, C.PULL_HI, C.PULL, 1.1f) }
            true
        }
    }

    /** skill_7 척: 굵은 붉은 광선. 핵 + 회전하는 두 가닥 나선 + 3칸마다 고리, 4틱에 걸쳐 식는다. */
    fun repelBeam(start: Location, end: Location) {
        sound(start, Sound.ENTITY_ENDER_DRAGON_SHOOT, 1.0f, 0.7f)
        sound(start, Sound.ENTITY_WIND_CHARGE_WIND_BURST, 1.0f, 1.2f)
        val axis = end.toVector().subtract(start.toVector())
        val len = axis.length()
        if (len < 0.1) return
        val f = axis.clone().normalize()
        val r = right(flat(f))
        val u = r.clone().crossProduct(f)
        p(start.clone().add(f.clone().multiply(0.8)), Particle.FLASH)

        every(4) { frame ->
            val heat = frame / 3.0
            var s = 0.0
            while (s <= len) {
                val base = start.clone().add(f.clone().multiply(s))
                if (frame == 0) {
                    dust(base, C.PUSH_HI, 1.0f)
                    dust(base, C.PUSH, 1.6f)
                } else if ((s * 10).toInt() % 6 == 0) dust(base, mix(C.PUSH, C.PUSH_DEEP, heat), 1.3f)
                for (k in 0..1) {
                    val a = s * 2.5 + frame * 1.2 + k * PI
                    dust(base.clone().add(r.clone().multiply(cos(a) * 0.35)).add(u.clone().multiply(sin(a) * 0.35)), mix(C.PUSH_HI, C.PUSH, heat), 0.8f)
                }
                s += 0.4
            }
            if (frame == 0) {
                var d = 3.0
                while (d < len) {
                    val c = start.clone().add(f.clone().multiply(d))
                    for (k in 0 until 10) {
                        val a = 2 * PI * k / 10
                        fade(c.clone().add(r.clone().multiply(cos(a) * 0.55)).add(u.clone().multiply(sin(a) * 0.55)), C.PUSH_HI, C.PUSH_DEEP, 0.9f)
                    }
                    d += 3.0
                }
            }
            true
        }
        dust(end, C.PUSH, 1.6f, 30, 0.3)
        p(end, Particle.GUST)
        p(end, Particle.CRIT, 15, 0.2, 0.5)
    }

    fun repelHit(feet: Location) {
        p(chest(feet), Particle.SMALL_GUST)
        p(chest(feet), Particle.CRIT, 8, 0.3, 0.3)
    }

    /** skill_8 역술: 흡수하는 동안 몸을 도는 두 개의 거울 고리. 3초 지속 연출을 이 함수가 혼자 돌린다. */
    fun mirrorShell(follow: () -> Location?, durationTicks: Int) {
        follow()?.let {
            sound(it, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0f, 1.2f)
            sound(it, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0f, 1.2f)
        }
        every(durationTicks / 2, interval = 2) { i ->
            val at = follow() ?: return@every false
            val c = chest(at)
            val spin = i * 0.35
            for (k in 0 until 8) {
                val a = 2 * PI * k / 8
                // 수평 고리
                dust(c.clone().add(cos(a + spin) * 0.9, 0.0, sin(a + spin) * 0.9), C.MIRROR_HI, 0.8f)
                // 60도 기운 고리 (반대 방향 회전)
                val v = Vector(cos(a) * 0.9, sin(a) * 0.9 * cos(PI / 3), sin(a) * 0.9 * sin(PI / 3)).rotateAroundY(-spin)
                dust(c.clone().add(v), C.MIRROR, 0.8f)
            }
            if (i % 5 == 0) p(c, Particle.WAX_OFF, 3, 0.6)
            true
        }
    }

    /** 역술 흡수 순간: 몸 표면에 파문. */
    fun mirrorAbsorb(feet: Location) {
        sound(feet, Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, 1.5f)
        ring(chest(feet), 0.7, 10) { l, _ -> dust(l, C.MIRROR_HI, 1.0f) }
        p(chest(feet), Particle.ENCHANTED_HIT, 6, 0.3, 0.2)
    }

    /** 역술 반사: 음파 + 퍼지는 구형 파동 + 맞는 적마다 자홍 광선. */
    fun mirrorReflect(feet: Location, radius: Double, victims: List<Location>) {
        sound(feet, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, 1.3f)
        sound(feet, Sound.BLOCK_AMETHYST_BLOCK_BREAK, 1.0f, 0.8f)
        p(feet, Particle.SONIC_BOOM)
        every(5) { i ->
            val rr = radius * (i + 1) / 5
            for (h in listOf(0.2, 1.0, 1.8)) {
                ring(feet.clone().add(0.0, h, 0.0), rr, max(8, (rr * 6).toInt()), phase = i * 0.2) { l, _ -> fade(l, C.MIRROR_HI, C.MIRROR_DEEP, 1.2f) }
            }
            true
        }
        val c = chest(feet)
        for (v in victims) {
            line(c, chest(v), 0.3) { l, _ -> dust(l, C.MIRROR, 1.0f) }
            p(chest(v), Particle.ENCHANTED_HIT, 8, 0.3, 0.2)
        }
    }

    // ════════════════════════════════════════════════════════
    //  직업 스킬
    // ════════════════════════════════════════════════════════

    /** 암살자 · 은신: 연막탄. 주변 사람도 "누가 숨었다"는 걸 알 수 있게 월드에 보이고 들린다. */
    fun shadowVanish(feet: Location) {
        sound(feet, Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8f, 1.2f)
        sound(feet, Sound.BLOCK_FIRE_EXTINGUISH, 0.6f, 1.6f)
        val c = chest(feet)
        p(c, Particle.LARGE_SMOKE, 25, 0.45, 0.02)
        p(c, Particle.SQUID_INK, 15, 0.4, 0.03)
        dust(c, C.SHADOW, 1.6f, 20, 0.5)
        p(c, Particle.WITCH, 6, 0.4)
    }

    /** 암살자 · 기습 성공: 대상에게 X자 그림자 베임. attackerDir 은 공격자가 바라보는 방향. */
    fun shadowAmbush(targetFeet: Location, attackerDir: Vector) {
        sound(targetFeet, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.0f, 0.7f)
        sound(targetFeet, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8f, 1.8f)
        val c = chest(targetFeet)
        val f = flat(attackerDir)
        val r = right(f)
        for ((delay, sgn) in listOf(0 to 1.0, 1 to -1.0)) {
            later(delay) {
                for (k in -6..6) fade(at(c, f, -0.3, r, k * 0.15, EffectKit.UP, sgn * k * 0.15), C.SHADOW_HI, C.SHADOW, 1.2f)
            }
        }
        p(c, Particle.SQUID_INK, 6, 0.2, 0.05)
        p(c, Particle.CRIT, 10, 0.3, 0.4)
    }

    /** 수호자 · 수호 태세: 땅을 찍는 금빛 파동(도발 범위)과 아래에서 위로 쌓이는 반구 방벽(배리어 범위). */
    fun guardianBulwark(feet: Location, barrierRadius: Double, tauntRadius: Double) {
        sound(feet, Sound.ITEM_SHIELD_BLOCK, 1.0f, 0.6f)
        sound(feet, Sound.ENTITY_RAVAGER_ROAR, 0.6f, 1.2f)
        sound(feet, Sound.BLOCK_ANVIL_LAND, 0.5f, 0.6f)
        later(4) { sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 0.8f) }
        val ground = feet.clone().add(0.0, 0.1, 0.0)
        every(6) { i ->
            val rr = tauntRadius * (i + 1) / 6
            ring(ground, rr, (rr * 5).toInt()) { l, k ->
                dust(l, C.GUARD_GOLD, 1.2f)
                if (k % 4 == 0) p(l, Particle.POOF, 1, 0.0, 0.02)
            }
            true
        }
        every(8) { i ->
            val th = Math.toRadians(80.0 * i / 7)
            val rr = barrierRadius * cos(th)
            ring(feet.clone().add(0.0, barrierRadius * sin(th), 0.0), rr, max(8, (rr * 4).toInt()), phase = i * 0.2) { l, _ ->
                fade(l, C.GUARD, C.GUARD_DEEP, 1.0f)
            }
            if (i == 7) p(feet.clone().add(0.0, barrierRadius, 0.0), Particle.END_ROD, 6, 0.3, 0.02)
            true
        }
    }

    /** 수호자 · 도발된 대상마다: 수호자 가슴에서 대상 머리로 금빛 사슬. */
    fun guardianTaunt(guardianFeet: Location, targetEye: Location) {
        var n = 0
        line(chest(guardianFeet), targetEye, 0.3) { l, _ ->
            if (n++ % 2 == 0) dust(l, C.GUARD_GOLD, 1.0f) else dust(l, C.GUARD_DEEP, 0.6f)
        }
        val head = targetEye.clone().add(0.0, 0.5, 0.0)
        p(head, Particle.ANGRY_VILLAGER, 3, 0.3)
        dust(head, C.GUARD_GOLD, 1.2f, 6, 0.25)
    }

    /** 치유사 · 생명의 축복: 발밑에 꽃이 피고, 초록 파동이 회복 범위 끝까지 퍼지며, 빛기둥과 벚꽃잎이 흩날린다. */
    fun lifeBloom(feet: Location, radius: Double) {
        sound(feet, Sound.BLOCK_BEACON_POWER_SELECT, 1.0f, 1.6f)
        sound(feet, Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f)
        sound(feet, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.2f)
        val g = feet.clone().add(0.0, 0.15, 0.0)
        // 장미 곡선 r = 1.6·sin(3θ): 꽃잎 6장
        for (k in 0 until 48) {
            val a = 2 * PI * k / 48
            val rr = 1.6 * kotlin.math.abs(sin(3 * a))
            fade(g.clone().add(cos(a) * rr, 0.0, sin(a) * rr), C.LIFE_PINK, C.LIFE_HI, 0.9f)
        }
        p(feet.clone().add(0.0, 1.0, 0.0), Particle.TOTEM_OF_UNDYING, 30, 0.3, 0.3)
        p(feet.clone().add(0.0, 2.5, 0.0), Particle.CHERRY_LEAVES, 15, 1.5)
        every(8) { i ->
            val rr = 1.0 + (radius - 1.0) * (i + 1) / 8
            ring(g, rr, min(60, (rr * 4).toInt() + 6)) { l, k ->
                dust(l, C.LIFE, 1.1f)
                if (k % 5 == 0) p(l.clone().add(0.0, 0.2, 0.0), Particle.HAPPY_VILLAGER)
            }
            true
        }
    }

    /** 치유사 · 회복된 아군마다: 몸을 감고 올라가는 초록 나선, 끝에 하트. */
    fun lifeBloomAlly(follow: () -> Location?) {
        every(10, delay = 2) { i ->
            val at = follow() ?: return@every false
            for (s in 0..1) {
                val a = i * 0.9 + s * PI
                dust(at.clone().add(cos(a) * 0.55, i * 0.21, sin(a) * 0.55), if (s == 0) C.LIFE else C.LIFE_HI, 0.9f)
            }
            if (i == 9) {
                p(at.clone().add(0.0, 2.1, 0.0), Particle.HEART, 3, 0.3)
                p(at.clone().add(0.0, 2.0, 0.0), Particle.TOTEM_OF_UNDYING, 6, 0.2, 0.15)
            }
            true
        }
    }

    /** 치유사 패시브(3초마다 1 회복): 머리 위 작은 초록 십자. 어느 각도에서 봐도 + 로 보이게 3축으로 찍는다. */
    fun healAuraTick(feet: Location) {
        val c = feet.clone().add(0.0, 2.2, 0.0)
        dust(c, C.LIFE, 0.7f)
        for (v in listOf(Vector(0.15, 0.0, 0.0), Vector(-0.15, 0.0, 0.0), Vector(0.0, 0.15, 0.0), Vector(0.0, -0.15, 0.0), Vector(0.0, 0.0, 0.15), Vector(0.0, 0.0, -0.15))) {
            dust(c.clone().add(v), C.LIFE, 0.6f)
        }
    }

    /** 정찰병 · 탐지: 정찰병 본인에게만 보이는 레이더 파동. */
    fun scoutPing(scout: Player) {
        val o = scout.location.clone().add(0.0, 0.2, 0.0)
        sound(o, Sound.BLOCK_BEACON_ACTIVATE, 1.0f, 1.8f, viewer = scout)
        sound(o, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 2.0f, viewer = scout)
        every(3, interval = 2) { i ->
            val rr = listOf(2.0, 5.0, 8.0)[i]
            ring(o, rr, min(48, (rr * 6).toInt())) { l, _ -> fade(l, C.SCOUT, C.SCOUT_DIM, 0.9f, viewer = scout) }
            true
        }
    }

    /** 정찰병 · 들킨 적마다: 정찰병에게만 보이는 빨간 표식 기둥과 아래를 가리키는 화살표. 0.5초 뒤 한 번 더. */
    fun scoutMark(scout: Player, enemyFeet: Location) {
        for (d in listOf(0, 10)) later(d) {
            var y = 2.3
            while (y <= 5.0) { dust(enemyFeet.clone().add(0.0, y, 0.0), C.SCOUT_ENEMY, 1.2f, viewer = scout); y += 0.3 }
            for (k in 1..3) {
                dust(enemyFeet.clone().add(k * 0.18, 2.3 + k * 0.18, 0.0), C.SCOUT_ENEMY, 1.0f, viewer = scout)
                dust(enemyFeet.clone().add(-k * 0.18, 2.3 + k * 0.18, 0.0), C.SCOUT_ENEMY, 1.0f, viewer = scout)
            }
        }
    }

    /** 광전사 · 광폭 강타: 바닥이 갈라지고 흙기둥이 솟으며 붉은 충격파가 퍼진다. ground 는 발밑 블록(공기면 null). */
    fun berserkSmash(feet: Location, radius: Double, ground: Material?) {
        sound(feet, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 0.7f)
        sound(feet, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.6f)
        sound(feet, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0f, 0.8f)
        val g = feet.clone().add(0.0, 0.1, 0.0)
        p(g, Particle.EXPLOSION)
        if (ground != null) {
            block(g.clone().add(0.0, 0.3, 0.0), Particle.BLOCK, ground, 40, radius * 0.35, 0.2)
            ring(g, 1.5, 8) { l, _ -> block(l, Particle.DUST_PILLAR, ground, 8, 0.1) }
        }
        // 갈라짐 8줄 (2틱에 나눠)
        for (half in 0..1) later(half) {
            for (k in 0 until 4) {
                val a = 2 * PI * (k * 2 + half) / 8 + jitter(0.2)
                var s = 0.6
                var side = 0.0
                while (s <= radius) {
                    side += jitter(0.12)
                    fade(g.clone().add(cos(a) * s - sin(a) * side, 0.0, sin(a) * s + cos(a) * side), C.RAGE, C.RAGE_DEEP, 0.9f)
                    s += 0.3
                }
            }
        }
        every(4) { i ->
            val rr = radius * (i + 1) / 4
            ring(g.clone().add(0.0, 0.2, 0.0), rr, (rr * 8).toInt()) { l, k ->
                dust(l, C.RAGE, 1.3f)
                if (k % 3 == 0) p(l, Particle.POOF, 1, 0.0, 0.03)
            }
            true
        }
    }

    fun berserkHit(feet: Location) {
        p(chest(feet), Particle.CRIT, 15, 0.35, 0.1)
        dust(chest(feet), C.RAGE, 1.2f, 5, 0.3)
    }

    /** 사신 · 무체화 시작: 영혼이 터져 나가며 몸이 흐려진다. 지속 중엔 4틱마다 발밑에 영혼 한 점만 남는다. */
    fun reaperPhaseOut(feet: Location, follow: () -> Location?, durationTicks: Int) {
        sound(feet, Sound.ENTITY_VEX_CHARGE, 1.0f, 0.6f)
        sound(feet, Sound.PARTICLE_SOUL_ESCAPE, 1.2f, 0.8f)
        val c = chest(feet)
        p(c, Particle.SOUL, 15, 0.4, 0.05)
        p(c, Particle.SCULK_SOUL, 10, 0.4, 0.03)
        fade(c, C.REAPER, C.REAPER_DEEP, 1.4f, 25, 0.45)
        // 몸을 한 바퀴 휘감는 낫자국
        for (k in 0 until 20) {
            val a = 2 * PI * k / 20
            val rr = 1.2 * (0.6 + 0.4 * sin(a / 2))
            fade(c.clone().add(cos(a) * rr, (k - 10) * 0.04, sin(a) * rr), C.REAPER, C.REAPER_DEEP, 1.1f)
        }
        every(durationTicks / 4, interval = 4, delay = 4) {
            val at = follow() ?: return@every false
            p(at.clone().add(0.0, 0.1, 0.0), Particle.SOUL)
            true
        }
    }

    /** 사신 · 무체화 종료: 영혼이 몸으로 모여든다. */
    fun reaperPhaseIn(feet: Location) {
        sound(feet, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.6f, 1.2f)
        sound(feet, Sound.PARTICLE_SOUL_ESCAPE, 1.0f, 1.2f)
        val c = chest(feet)
        p(c, Particle.SCULK_SOUL, 12, 0.4, 0.02)
        fade(c, C.REAPER_DEEP, C.REAPER, 1.2f, 15, 0.4)
    }
}
