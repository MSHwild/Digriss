package kr.maeshil.digriss.skill

import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBreakEvent

class BarrierBlockListener : Listener {
    @EventHandler
    fun onBreak(event: BlockBreakEvent) {
        val barrier = SphereUtil.getBarrier(event.block.location) ?: return
        event.isDropItems = false // 결계용 흑요석이라 실제 아이템은 드랍 안 함
        SphereUtil.destroyBarrier(barrier)
    }
}