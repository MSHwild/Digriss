package kr.maeshil.digriss.bundle

import org.bukkit.inventory.ItemStack

// 상점 패키지 하나. 가격은 DC, expiresAt이 null이면 무기한 판매
data class Bundle(
    val name: String,
    var price: Long,
    val createdAt: Long,
    var expiresAt: Long?,
    var items: List<ItemStack>
) {
    fun isOnSale(now: Long = System.currentTimeMillis()): Boolean = expiresAt == null || now < expiresAt!!

    // 남은 판매 기간 표시용 ("3일 5시간", "무기한", "판매 종료")
    fun remainingText(now: Long = System.currentTimeMillis()): String {
        val end = expiresAt ?: return "무기한"
        val left = end - now
        if (left <= 0) return "판매 종료"
        val hours = left / 3_600_000
        val days = hours / 24
        return when {
            days > 0 -> "${days}일 ${hours % 24}시간"
            hours > 0 -> "${hours}시간"
            else -> "${(left / 60_000).coerceAtLeast(1)}분"
        }
    }
}
