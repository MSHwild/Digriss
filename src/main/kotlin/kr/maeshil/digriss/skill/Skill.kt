package kr.maeshil.digriss.skill

import org.bukkit.entity.Player

interface Skill {
    val itemId: String
    val manaCost: Double
    // 실제로 발동했으면 true (false면 마나를 쓰지 않음)
    fun execute(player: Player): Boolean
}