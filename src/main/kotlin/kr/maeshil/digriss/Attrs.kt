package kr.maeshil.digriss

import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.attribute.AttributeInstance
import org.bukkit.entity.LivingEntity

// 속성(최대 체력 · 이동 속도 등) 찾기: 1.21.1 ~ 1.21.4 모두 동작하게
// 1.21.3부터 속성 이름이 바뀜 (GENERIC_MAX_HEALTH → MAX_HEALTH, 키 generic.max_health → max_health)
// 그래서 코드에 이름을 박지 않고 서버가 켜졌을 때 새 키 → 옛 키 순서로 찾음
object Attrs {

    private fun find(name: String): Attribute? =
        Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name))
            ?: Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.$name"))

    val MAX_HEALTH by lazy { find("max_health") }
    val SCALE by lazy { find("scale") }
    val ATTACK_DAMAGE by lazy { find("attack_damage") }
    val KNOCKBACK_RESISTANCE by lazy { find("knockback_resistance") }
    val MOVEMENT_SPEED by lazy { find("movement_speed") }
    val FOLLOW_RANGE by lazy { find("follow_range") }

    /** 모든 속성 (버전마다 다른 목록을 서버에서 그대로 가져옴) */
    fun all(): List<Attribute> = Registry.ATTRIBUTE.toList()

    /** 버전과 상관없는 속성 이름 (generic. · player. 같은 앞부분을 뺌). 아이템에 저장할 때 씀 */
    fun shortKey(attribute: Attribute): String = attribute.key.key.substringAfter('.')
}

/** 속성이 없는 버전이면 null */
fun LivingEntity.attr(attribute: Attribute?): AttributeInstance? = attribute?.let { getAttribute(it) }
