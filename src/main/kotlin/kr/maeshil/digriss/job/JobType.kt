package kr.maeshil.digriss.job

import org.bukkit.Material

enum class JobType(
    val displayName: String,
    val icon: Material,
    val lore: List<String>
) {
    SHADOW_ASSASSIN("암살자", Material.NETHERITE_SWORD, listOf("§7은신 후 기습하는 암살자")),
    SHIELD_GUARDIAN("수호자", Material.SHIELD, listOf("§7도발과 배리어로 전열을 지키는 탱커")),
    LIFE_PRIEST("치유사", Material.TOTEM_OF_UNDYING, listOf("§7주변 아군을 계속 회복하는 힐러", "§8Shift+Q: 광역 회복")),
    TRACKER("정찰병", Material.SPYGLASS, listOf("§7은신 정찰과 위치 핑의 척후병")),
    BLOOD_WARRIOR("광전사", Material.IRON_PICKAXE, listOf("§7근접 광역 피해와 넉백의 타격대")),
    REAPER("사신", Material.WITHER_SKELETON_SKULL, listOf("§7영혼을 거둬 무체화하는 자")),
    WEAPON_SMITH("무기 기술자", Material.ANVIL, listOf("§7무기를 다루는 기술자", "§8(스킬 준비 중)"));

    companion object {
        fun fromDisplayName(name: String): JobType? = entries.find { it.displayName == name }
    }
}