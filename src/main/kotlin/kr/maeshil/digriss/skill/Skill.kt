package kr.maeshil.digriss.skill

import org.bukkit.entity.Player

interface Skill {
    val itemId: String
    val manaCost: Double
    fun execute(player: Player)
}