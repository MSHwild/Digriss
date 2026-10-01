package kr.maeshil.digriss.jobManager

import java.util.UUID

// 은신 상태(기습 대기) 관리
object AssassinStealthManager {
    private val active = HashSet<UUID>()

    fun activate(uuid: UUID) { active.add(uuid) }
    fun isActive(uuid: UUID): Boolean = active.contains(uuid)
    fun consume(uuid: UUID) { active.remove(uuid) }
}