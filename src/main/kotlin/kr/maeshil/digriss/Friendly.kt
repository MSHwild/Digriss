package kr.maeshil.digriss

import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

// 스킬 대상 판정용: 공격자 기준 아군(본인, 같은 국가, 연합국)이면 true → 피해·디버프 대상에서 제외
object Friendly {
    fun isAlly(attacker: Player, target: Entity): Boolean {
        if (target !is Player) return false
        val plugin = Bukkit.getPluginManager().getPlugin("Digriss") as? Digriss ?: return false
        return plugin.allianceManager.isFriendly(attacker, target)
    }
}
