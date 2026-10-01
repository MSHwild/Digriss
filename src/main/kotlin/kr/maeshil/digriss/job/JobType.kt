package kr.maeshil.digriss.job

import org.bukkit.Material

enum class JobType(
    val displayName: String,
    val icon: Material,
    val lore: List<String>
) {
    SHADOW_ASSASSIN("암살자", Material.NETHERITE_SWORD, listOf("§7은신과 점멸을 다루는 암살자")),
    SHIELD_GUARDIAN("방패술사", Material.SHIELD, listOf("§7전열을 지키는 수호자")),
    LIFE_PRIEST("생명술사", Material.TOTEM_OF_UNDYING, listOf("§7생명력을 다루는 치유사")),
    TRACKER("은형자", Material.SPYGLASS, listOf("§7정찰과 추적의 달인")),
    BLOOD_WARRIOR("광전사", Material.IRON_PICKAXE, listOf("§7광전사형 근접 딜러")),
    REAPER("사신", Material.WITHER_SKELETON_SKULL, listOf("§7죽음을 다루는 자"));

    companion object {
        fun fromDisplayName(name: String): JobType? = entries.find { it.displayName == name }
    }
}