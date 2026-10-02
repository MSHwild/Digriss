package kr.maeshil.digriss.quest

class ActiveQuest(
    var id: String,
    var progress: Int = 0,
    var done: Boolean = false
)

class PlayerQuestData(
    var dateKey: String = "",                       // 오늘 퀘스트 날짜 키 (05시 기준, yyyy-MM-dd)
    val quests: MutableList<ActiveQuest> = mutableListOf(),
    var rerolled: Boolean = false,
    var weekKey: String = "",                       // 이번 주 월요일 날짜
    val weekDays: MutableSet<String> = mutableSetOf(), // 이번 주에 퀘스트를 1개 이상 완료한 날짜들
    var weeklyClaimed: Boolean = false,
    var streak: Int = 0,                            // 연속 완료 일수
    var streakLast: String = ""                     // 마지막으로 연속 완료가 기록된 날짜
)
