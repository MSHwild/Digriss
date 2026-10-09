package kr.maeshil.digriss.nation

import java.util.UUID

data class Nations(
    var name: String,
    var leader: UUID,
    val members: MutableList<UUID> = mutableListOf(),
    val claims: MutableList<String> = mutableListOf(),
    var bank: Double = 0.0,    // 🌟 국가 금고 잔액
    var level: Int = 1,        // 🌟 국가 레벨 (기본 1레벨, 최대 5레벨)
    var peace: Double = 0.0    // 내실 점수 (전쟁 말고 건축·퀘스트·금고·평화 유지로 쌓는 점수)
)