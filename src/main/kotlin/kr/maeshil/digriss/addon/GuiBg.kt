package kr.maeshil.digriss.addon

import kr.maeshil.digriss.nation.MenuType
import kr.maeshil.digriss.nation.NationMenuHolder
import org.bukkit.Bukkit
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder

// GUI 배경: ItemsAdder 폰트 이미지 digriss:gui_<키> 가 있으면 창 제목 뒤에 배경 그림을 깔아줌
// 그림이 없거나 ItemsAdder가 없으면 원래 제목 그대로 (그래서 그림을 하나씩 추가해도 됨)
object GuiBg {

    private const val BG_SHIFT = -8
    private const val TITLE_SHIFT = -169

    /** 배경 그림을 붙인 제목. 그림이 없으면 원래 제목 */
    @JvmStatic
    fun title(key: String, title: String): String {
        if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) return title
        val bg = runCatching { IaFont.background("digriss:gui_$key", BG_SHIFT, TITLE_SHIFT) }.getOrNull() ?: return title
        return "§f$bg$title"
    }

    // 배경 위에서는 어두운 글씨(§8)가 안 보여서 흰색으로
    private fun recolor(title: String) = title.replace("§8", "§f")

    /** 창 종류 → 배경 그림 키 (gui_<키>). 배경을 안 쓰는 창은 null */
    @JvmStatic
    fun keyOf(holder: InventoryHolder?, size: Int): String? {
        if (holder is NationMenuHolder) return when (holder.type) {
            MenuType.MAIN -> "nation_main"
            MenuType.NO_NATION -> "nation_none"
            MenuType.INVITE -> "invite"
            MenuType.CONFIRM_DISSOLVE -> "dissolve"
            MenuType.WAR -> "war"
            MenuType.WAR_LOG -> "war_log"
            MenuType.BANK -> "nation_bank"
            MenuType.RECRUIT_LIST -> "recruit_list"
            MenuType.RECRUIT_MANAGE -> "recruit_manage"
        }
        // 다른 패키지의 Holder 클래스를 직접 참조하지 않게 이름으로 구분
        return when (holder?.javaClass?.name?.substringAfterLast('.')) {
            "JobPurchaseHolder" -> "job_buy"
            "HelpHolder" -> "help"
            "NationStorageHolder" -> "storage_${size / 9}"
            "TradeHolder" -> "trade"
            "JobConfirmHolder" -> "job_confirm"
            "MarketStorageHolder" -> "market_storage"
            "BundlePreviewHolder" -> "bundle_preview"
            "BundleListHolder" -> "bundle_list"
            "MarketHolder" -> "market"
            "DiplomacyListHolder" -> "diplomacy_list"
            "NationTechHolder" -> "tech"
            "AllianceHolder" -> "alliance"
            "WarpHolder" -> "warp"
            "TradePickHolder" -> "trade_pick"
            "BundleEditHolder" -> "bundle_edit"
            "PreorderManager\$Holder" -> "preorder"
            "QuestHolder" -> "quest"
            "TitleMenuHolder" -> "titles"
            "DiplomacyDetailHolder" -> "diplomacy_detail"
            else -> null
        }
    }

    /** Bukkit.createInventory 대신 사용: 배경이 있는 창이면 배경 제목 + 배경에 맞춘 크기(GuiSlots)로 만듦 */
    @JvmStatic
    fun createInventory(holder: InventoryHolder?, size: Int, title: String): Inventory {
        val key = keyOf(holder, size) ?: return Bukkit.createInventory(holder, size, title)
        return Bukkit.createInventory(holder, GuiSlots.sizeOf(holder, size), title(key, recolor(title)))
    }
}
