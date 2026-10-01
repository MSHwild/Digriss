package kr.maeshil.digriss.job

import org.bukkit.entity.Player

interface JobSkill {
    val baseCooldownSeconds: Int
    fun execute(player: Player)
}