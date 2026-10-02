package kr.maeshil.digriss.weapon

import org.bukkit.entity.Player

interface WeaponSkill {
    val itemId: String        // ItemsAdder namespaced id (예: "weapon:hell_sword")
    val cooldownSeconds: Long
    // 실제로 발동했으면 true (false면 쿨타임을 시작하지 않음)
    fun execute(player: Player): Boolean
}