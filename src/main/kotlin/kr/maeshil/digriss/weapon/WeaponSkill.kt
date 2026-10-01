package kr.maeshil.digriss.weapon

import org.bukkit.entity.Player

interface WeaponSkill {
    val itemId: String        // ItemsAdder namespaced id (예: "weapon:hell_sword")
    val cooldownSeconds: Long
    fun execute(player: Player)
}