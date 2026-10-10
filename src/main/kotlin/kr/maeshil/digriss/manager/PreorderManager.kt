package kr.maeshil.digriss.manager

import kr.maeshil.digriss.Digriss
import kr.maeshil.digriss.Sounds
import kr.maeshil.digriss.job.JobType
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import java.io.File

// 사전예약 보상: 명단에 있는 사람은 계정당 딱 한 번, 원하는 직업 아이템 1개 + 초보자의 세트를 받음
// 접속하면 직업 선택 창이 열리고, 닫아도 /사전예약 으로 다시 열 수 있음. 받은 기록은 preorder-data.yml (시즌 초기화와 무관)
class PreorderManager(private val plugin: Digriss) : Listener, CommandExecutor {

    private val names = listOf(
        "BeeYuumi", "Hyeonmuji", "doha0521", "gd41", "hanse_025", "catlifejjjjjjjj", "RUDOLA",
        "ACO145", "FUNKEVIN0426", "LEAF_b_xdwa", "MJTT_baeghyeon", "Koreasihyun", "tarktarku"
    ).map { it.lowercase() }.toSet()

    private val file = File(plugin.dataFolder, "preorder-data.yml")
    private val data = YamlConfiguration.loadConfiguration(file)

    private class Holder : InventoryHolder {
        lateinit var inv: Inventory
        val slotJobs = mutableMapOf<Int, JobType>()
        override fun getInventory() = inv
    }

    private fun isListed(p: Player) = p.name.lowercase() in names

    // UUID와 닉네임 둘 다로 확인 (닉네임을 바꿔도, 다른 계정이 그 닉네임을 써도 한 번만)
    private fun claimed(p: Player) =
        data.contains("claimed.${p.uniqueId}") || data.getStringList("claimed-names").contains(p.name.lowercase())

    private fun canClaim(p: Player) = isListed(p) && !claimed(p)

    private fun markClaimed(p: Player, job: JobType) {
        data.set("claimed.${p.uniqueId}", "${p.name} / ${job.displayName} / ${java.time.LocalDateTime.now().withNano(0)}")
        data.set("claimed-names", data.getStringList("claimed-names") + p.name.lowercase())
        runCatching { data.save(file) }.onFailure { plugin.logger.warning("[사전예약] 저장 실패: ${it.message}") }
    }

    // 초보자의 세트
    private fun starterKit(): List<ItemStack> = listOf(
        ItemStack(Material.IRON_HELMET), ItemStack(Material.IRON_CHESTPLATE),
        ItemStack(Material.IRON_LEGGINGS), ItemStack(Material.IRON_BOOTS),
        ItemStack(Material.IRON_SWORD), ItemStack(Material.IRON_PICKAXE), ItemStack(Material.IRON_AXE),
        ItemStack(Material.IRON_SHOVEL), ItemStack(Material.SHIELD),
        ItemStack(Material.BREAD, 32), ItemStack(Material.TORCH, 32), ItemStack(Material.WHITE_BED),
        ItemStack(Material.GOLDEN_APPLE, 2)
    ).onEach { item ->
        item.itemMeta = item.itemMeta?.apply { lore = listOf("§6사전예약 보상 · 초보자의 세트") }
    }

    fun open(p: Player) {
        val holder = Holder()
        val inv = Bukkit.createInventory(holder, 27, "§8사전예약 보상 - 직업 선택")
        holder.inv = inv
        val pane = ItemStack(Material.GRAY_STAINED_GLASS_PANE).apply { itemMeta = itemMeta?.apply { setDisplayName(" ") } }
        for (i in 0 until 27) inv.setItem(i, pane)

        inv.setItem(4, ItemStack(Material.CHEST).apply {
            itemMeta = itemMeta?.apply {
                setDisplayName("§6§l사전예약 감사 보상")
                lore = listOf("§7원하는 직업을 하나 고르면", "§f직업 아이템 + 초보자의 세트§7를 받습니다.",
                    "§7(철 갑옷·도구 세트, 방패, 빵, 횃불, 침대, 황금사과)", "", "§c계정당 딱 한 번만 받을 수 있어요.")
            }
        })
        JobType.entries.forEachIndexed { i, job ->
            val slot = 10 + i
            inv.setItem(slot, plugin.jobManager.createJobItem(job).apply {
                itemMeta = itemMeta?.apply { lore = (lore ?: emptyList()) + listOf("", "§a클릭: 이 직업으로 받기") }
            })
            holder.slotJobs[slot] = job
        }
        p.openInventory(inv)
    }

    private fun claim(p: Player, job: JobType) {
        if (!canClaim(p)) return p.sendMessage("§c이미 사전예약 보상을 받았습니다.").also { Sounds.fail(p) }
        markClaimed(p, job) // 아이템 지급 전에 먼저 기록 (중복 수령 방지)
        val items = listOf(plugin.jobManager.createJobItem(job)) + starterKit()
        p.inventory.addItem(*items.toTypedArray()).values.forEach { p.world.dropItemNaturally(p.location, it) }
        p.closeInventory()
        p.sendMessage("§6[사전예약] §a${job.displayName} 직업 아이템과 초보자의 세트를 받았습니다! 사전예약해 주셔서 감사합니다.")
        p.sendMessage("§e/직업설정 §7에서 직업 아이템을 가운데 칸에 넣으면 직업이 장착됩니다.")
        Sounds.bigReward(p)
        plugin.logger.info("[사전예약] ${p.name} 보상 수령 (${job.displayName})")
    }

    @EventHandler
    fun onJoin(e: PlayerJoinEvent) {
        val p = e.player
        if (!canClaim(p)) return
        Bukkit.getScheduler().runTaskLater(plugin, Runnable {
            if (!p.isOnline || !canClaim(p)) return@Runnable
            p.sendMessage("§6[사전예약] §f사전예약 보상이 도착했습니다! 원하는 직업을 골라 주세요. §7(창을 닫았다면 /사전예약)")
            Sounds.notify(p)
            open(p)
        }, 80L)
    }

    @EventHandler
    fun onClick(e: InventoryClickEvent) {
        val holder = e.view.topInventory.holder as? Holder ?: return
        e.isCancelled = true
        if (e.clickedInventory != e.view.topInventory) return
        val p = e.whoClicked as? Player ?: return
        val job = holder.slotJobs[e.slot] ?: return
        claim(p, job)
    }

    @EventHandler
    fun onDrag(e: InventoryDragEvent) {
        if (e.view.topInventory.holder is Holder) e.isCancelled = true
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        val p = sender as? Player ?: return true
        when {
            !isListed(p) -> p.sendMessage("§7사전예약 명단에 없는 닉네임입니다.")
            claimed(p) -> p.sendMessage("§7이미 사전예약 보상을 받았습니다.")
            else -> { open(p); Sounds.open(p) }
        }
        return true
    }
}
