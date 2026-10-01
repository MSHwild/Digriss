package kr.maeshil.digriss.effect

data class KillEffect(
    val id: String,
    val displayName: String,
    val price: Long,
    val description: List<String>
)

object EffectRegistry {
    val effects = listOf(
        KillEffect("flame_burst", "&c화염 폭발", 100, listOf("&7적 처치시 화염이 터집니다")),
        KillEffect("frost_nova", "&b서리 폭발", 100, listOf("&7적 처치시 얼음 파편이 흩어집니다")),
        KillEffect("blood_burst", "&4피의 분출", 150, listOf("&7적 처치시 핏빛 파티클이 솟구칩니다")),
        KillEffect("void_collapse", "&5공허 붕괴", 200, listOf("&7적 처치시 주변이 검게 물듭니다")),
        KillEffect("soul_release", "&d영혼 해방", 250, listOf("&7적 처치시 영혼이 하늘로 승천합니다"))
    )

    fun get(id: String): KillEffect? = effects.find { it.id == id }
}