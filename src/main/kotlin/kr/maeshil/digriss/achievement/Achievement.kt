package kr.maeshil.digriss.achievement

import org.bukkit.Material

// 업적 목록. 달성하면 title(칭호)을 얻고, /칭호 에서 골라 채팅 이름 앞에 표시
enum class Achievement(
    val displayName: String,
    val description: String,
    val title: String,
    val icon: Material,
    val manual: Boolean = false   // 운영자가 직접 지급하는 칭호 (/초기화 해도 유지)
) {
    // ── 전투 ──
    FIRST_BLOOD("첫 사냥", "플레이어를 처음으로 처치", "§7[신참 전사]", Material.WOODEN_SWORD),
    KILLS_50("숙련된 전사", "플레이어 50명 처치", "§c[학살자]", Material.IRON_SWORD),
    KILLS_200("전장의 악몽", "플레이어 200명 처치", "§4§l[전장의 악몽]", Material.NETHERITE_SWORD),
    STREAK_5("폭주", "5킬스트릭 달성", "§6[폭주 기관차]", Material.BLAZE_POWDER),
    STREAK_10("무쌍", "10킬스트릭 달성", "§c§l[무쌍]", Material.NETHER_STAR),
    STREAK_BREAKER("흐름 차단", "5킬스트릭 이상인 적을 처치", "§b[흐름 차단자]", Material.SHIELD),

    // ── 국가 / 전쟁 ──
    FOUNDER("건국", "국가를 세움", "§a[건국자]", Material.WHITE_BANNER),
    NATION_MAX("번영", "소속 국가가 Lv.5 달성", "§e[번영의 시민]", Material.BEACON),
    DIPLOMAT("외교관", "지도자로서 연합을 맺음", "§b[외교관]", Material.LIGHT_BLUE_BANNER),
    WAR_WINNER("승전", "전쟁에서 승리한 국가 소속", "§6[승전국의 용사]", Material.GOLDEN_SWORD),
    CONQUEROR("정복자", "적국의 신호기를 부숴 국가를 점령", "§4§l[정복자]", Material.TNT),
    PEACEKEEPER("태평성대", "소속 국가의 내실 점수 1000 달성", "§a§l[태평성대]", Material.OAK_SAPLING),

    // ── 퀘스트 / 출석 ──
    QUESTS_10("성실", "일일 퀘스트 10개 완료", "§a[성실한 모험가]", Material.WRITABLE_BOOK),
    QUESTS_100("퀘스트 장인", "일일 퀘스트 100개 완료", "§d[퀘스트 장인]", Material.ENCHANTED_BOOK),
    STREAK_DAYS_7("개근", "연속 출석 7일", "§e[개근상]", Material.CLOCK),

    // ── 랭크 ──
    RANK_DIAMOND("다이아몬드", "다이아 랭크 도달", "§3[다이아의 기백]", Material.DIAMOND),
    RANK_DIGRISS("전설", "디그리스 랭크 도달", "§2§l[디그리스의 전설]", Material.DRAGON_HEAD),

    // ── 운영자 지급 ──
    BETA_TESTER("베타 테스터", "테스트 서버에 참여해 디그리스를 함께 만든 사람", "§d§l[베타 테스터]", Material.AMETHYST_SHARD, manual = true),
    SUPPORTER("후원자", "디그리스를 후원해 준 고마운 분", "§a[후원자]", Material.EMERALD, manual = true),
    SUPPORTER_VIP("VIP 후원자", "디그리스를 크게 후원해 준 분", "§b§l[VIP]", Material.DIAMOND, manual = true),
    SUPPORTER_MVP("MVP 후원자", "디그리스 최고의 후원자", "§6§l[MVP]", Material.NETHERITE_INGOT, manual = true);

    companion object {
        fun of(name: String): Achievement? = entries.firstOrNull { it.name == name }

        // 명령어 입력용: "BETA_TESTER", "베타테스터", "베타 테스터" 모두 인식
        fun find(input: String): Achievement? {
            val key = input.replace(" ", "")
            return entries.firstOrNull { it.name.equals(key, true) || it.displayName.replace(" ", "") == key }
        }
    }
}
