package kr.maeshil.digriss.quest

enum class QuestType {
    MOB_KILL,                 // 몬스터 처치 (스포너 몹 제외)
    CLAIM_CHUNK,              // 국가 영토 청크 점령
    BANK_DEPOSIT,             // 국가 금고 입금 (원)
    SKILL_USE,                // 스킬 아이템 사용
    ENEMY_TERRITORY_ENTER,    // 적국 영토 입장 (청크 이동 기준)
    WAR_KILL_ENEMY_TERRITORY, // 전쟁 중 적국 영토에서 킬
    ENEMY_BEACON_VISIT,       // 적 신호기 근처 방문
    PLAYER_KILL               // 플레이어 킬 (어려움 전용)
}

enum class QuestDifficulty(val displayName: String, val color: String) {
    EASY("쉬움", "§a"),
    NORMAL("보통", "§e"),
    HARD("어려움", "§c")
}

data class QuestDefinition(
    val id: String,
    val type: QuestType,
    val difficulty: QuestDifficulty,
    val target: Int,
    val name: String,
    val warOnly: Boolean,      // 내 국가가 전쟁 중일 때만 뽑힘
    val souls: Long,
    val money: Double
)
