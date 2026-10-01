package kr.maeshil.digriss.nation

import java.util.UUID

data class Nations(
    val name: String,
    var leader: UUID,
    val members: MutableList<UUID> = mutableListOf(),
    val claims: MutableList<String> = mutableListOf(),
    var bank: Double = 0.0,    // 🌟 국가 금고 잔액
    var level: Int = 1         // 🌟 국가 레벨 (기본 1레벨, 최대 5레벨)
)