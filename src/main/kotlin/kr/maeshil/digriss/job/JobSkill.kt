package kr.maeshil.digriss.job

import org.bukkit.entity.Player

interface JobSkill {
    val baseCooldownSeconds: Int
    // 실제로 발동했으면 true (false면 쿨타임을 시작하지 않음)
    fun execute(player: Player): Boolean
}