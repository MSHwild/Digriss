package kr.maeshil.digriss

data class WeaponData(
    val id: String,          // IA 네임스페이스 ID (weapon:xxx)
    val displayName: String,
    val tier: Int,
    val cooldown: Int,       // 초
    val skillType: String
)

object WeaponRegistry {
    private val weapons = mapOf(
        // 1티어
        "weapon:hell_sword" to WeaponData("weapon:hell_sword", "지옥불 대검", 1, 15, "fire_explosion"),
        "weapon:blade" to WeaponData("weapon:blade", "참격의 검", 1, 12, "piercing_slash"),
        "weapon:blood_hoe" to WeaponData("weapon:blood_hoe", "피의 낫", 1, 14, "aoe_lifesteal"),

        // 2티어
        "weapon:lifestealsword" to WeaponData("weapon:lifestealsword", "흡혈의 검", 2, 10, "single_lifesteal"),
        "weapon:frost_axe" to WeaponData("weapon:frost_axe", "서리왕의 도끼", 2, 9, "cone_slow"),

        // 3티어
        "weapon:ocean_spear" to WeaponData("weapon:ocean_spear", "심연의 창", 3, 7, "dash_strike"),
        "weapon:void_sword" to WeaponData("weapon:void_sword", "공허의 검", 3, 8, "void_cut") // 새 무기 자리 채움 전까지 임시 3티어 배치
    )

    fun get(id: String): WeaponData? = weapons[id]
}