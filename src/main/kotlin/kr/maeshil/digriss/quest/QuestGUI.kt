package kr.maeshil.digriss.quest

import kr.maeshil.digriss.manager.QuestManager
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemFlag
import org.bukkit.inventory.ItemStack

// rerollMode: 리롤 버튼을 누른 뒤 교체할 퀘스트를 고르는 상태
class QuestHolder(val rerollMode: Boolean) : InventoryHolder {
    override fun getInventory(): Inventory = throw UnsupportedOperationException()
}

object QuestGUI {

    val QUEST_SLOTS = listOf(11, 13, 15) // 쉬움, 보통, 어려움 순서
    const val REROLL_SLOT = 22
    const val BACK_SLOT = 18
    private const val INFO_SLOT = 4
    private const val WEEKLY_SLOT = 20
    private const val STREAK_SLOT = 24

    fun open(player: Player, questManager: QuestManager, rerollMode: Boolean = false) {
        val pd = questManager.getData(player)
        val title = if (rerollMode) "§8일일 퀘스트 §c(교체할 퀘스트 선택)" else "§8일일 퀘스트"
        val inv = Bukkit.createInventory(QuestHolder(rerollMode), 27, title)

        val filler = item(icon("common.filler", Material.GRAY_STAINED_GLASS_PANE), " ")
        for (i in 0 until inv.size) inv.setItem(i, filler)

        inv.setItem(INFO_SLOT, item(icon("quest.info", Material.BOOK), "§f§l일일 퀘스트",
            "§7매일 오전 5시에 새 퀘스트 3개가 주어집니다.",
            "§7완료하면 보상이 자동으로 지급됩니다."))

        pd.quests.forEachIndexed { i, quest ->
            val slot = QUEST_SLOTS.getOrNull(i) ?: return@forEachIndexed
            val def = questManager.definitions[quest.id]
            inv.setItem(slot, if (def == null) item(icon("common.empty", Material.BARRIER), "§c알 수 없는 퀘스트", "§7quest.yml에서 삭제된 퀘스트입니다.", "§7리롤로 교체하세요.")
            else questItem(def, quest, rerollMode, questManager))
        }

        // 주간 보너스
        val days = pd.weekDays.size
        val required = questManager.weeklyRequired
        inv.setItem(WEEKLY_SLOT, item(icon("quest.weekly", Material.CLOCK), "§d§l주간 보너스",
            "§7이번 주 완료 일수: §f$days§7/§f${required}일",
            progressBar(days.coerceAtMost(required), required),
            "",
            if (pd.weeklyClaimed) "§a✔ 이번 주 보너스 수령 완료" else "§7월요일부터 ${required}일 이상 퀘스트를 완료하면 지급"))

        // 연속 출석
        val streak = questManager.currentStreak(pd)
        val todayDone = pd.streakLast == pd.dateKey
        val nextStreak = if (todayDone) streak else streak + 1
        inv.setItem(STREAK_SLOT, item(icon("quest.streak", Material.CAMPFIRE), "§e§l연속 출석 §f${streak}일",
            "§7하루 첫 퀘스트를 완료하면 1일씩 늘어납니다.",
            "§7하루를 놓치면 절반으로 줄어듭니다.",
            "",
            if (todayDone) "§a✔ 오늘 출석 완료"
            else "§7오늘 첫 완료 시 추가 보상: §b영혼 ${questManager.streakBonusSouls(nextStreak)} §6${questManager.formatMoney(questManager.streakBonusMoney(nextStreak))}원"))

        // 리롤 버튼
        inv.setItem(REROLL_SLOT, when {
            pd.rerolled -> item(icon("quest.reroll_used", Material.GRAY_DYE), "§7리롤 (오늘 사용함)", "§7내일 오전 5시에 다시 사용할 수 있습니다.")
            rerollMode -> item(icon("quest.reroll_cancel", Material.BARRIER), "§c리롤 취소", "§7교체할 퀘스트를 클릭하거나", "§7이 버튼을 눌러 취소하세요.")
            else -> item(icon("quest.reroll", Material.HOPPER), "§b§l퀘스트 리롤 §7(하루 1회)", "§7클릭 후 교체할 퀘스트를 고르세요.", "§7완료하지 않은 퀘스트만 교체됩니다.")
        })

        inv.setItem(BACK_SLOT, kr.maeshil.digriss.menu.MainMenu.backItem(Bukkit.getPluginManager().getPlugin("Digriss") as kr.maeshil.digriss.Digriss))
        player.openInventory(inv)
    }

    private fun questItem(def: QuestDefinition, quest: ActiveQuest, rerollMode: Boolean, questManager: QuestManager): ItemStack {
        val material = when {
            quest.done -> Material.LIME_DYE
            def.difficulty == QuestDifficulty.EASY -> Material.LIME_CONCRETE
            def.difficulty == QuestDifficulty.NORMAL -> Material.YELLOW_CONCRETE
            else -> Material.RED_CONCRETE
        }
        val lore = mutableListOf(
            "§7난이도: ${def.difficulty.color}${def.difficulty.displayName}",
            "",
            "§7진행도: §f${quest.progress}§7/§f${def.target}",
            progressBar(quest.progress, def.target),
            "",
            "§7보상: §b영혼 ${def.souls}" + (if (def.money > 0) " §6${questManager.formatMoney(def.money)}원" else "") +
                (if (def.dc > 0) " §3DC ${def.dc}" else "")
        )
        if (def.warOnly) lore.add("§4⚔ 전쟁 퀘스트")
        lore.add("")
        lore.add(when {
            quest.done -> "§a✔ 완료 (보상 지급됨)"
            rerollMode -> "§e▶ 클릭하면 이 퀘스트를 교체합니다"
            else -> "§7진행 중"
        })
        val key = when {
            quest.done -> "quest.done"
            def.difficulty == QuestDifficulty.EASY -> "quest.easy"
            def.difficulty == QuestDifficulty.NORMAL -> "quest.normal"
            else -> "quest.hard"
        }
        return item(icon(key, material), "${def.difficulty.color}§l${def.name}", *lore.toTypedArray())
    }

    private fun progressBar(current: Int, max: Int, length: Int = 20): String {
        val filled = if (max <= 0) length else (current.toDouble() / max * length).toInt().coerceIn(0, length)
        val percent = if (max <= 0) 100 else (current * 100 / max).coerceIn(0, 100)
        return "§a" + "■".repeat(filled) + "§8" + "■".repeat(length - filled) + " §f$percent%"
    }

    private fun icon(key: String, default: Material): ItemStack =
        (Bukkit.getPluginManager().getPlugin("Digriss") as kr.maeshil.digriss.Digriss).iconManager.get(key, default)

    private fun item(material: Material, name: String, vararg lore: String): ItemStack = item(ItemStack(material), name, *lore)

    private fun item(base: ItemStack, name: String, vararg lore: String): ItemStack {
        val item = base
        val meta = item.itemMeta
        meta.setDisplayName(name)
        meta.lore = lore.toList()
        meta.addItemFlags(*ItemFlag.entries.toTypedArray())
        item.itemMeta = meta
        return item
    }
}
