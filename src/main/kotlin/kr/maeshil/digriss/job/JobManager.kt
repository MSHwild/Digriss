package kr.maeshil.digriss.job

import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

class JobManager(private val plugin: JavaPlugin) {

    private val jobKey = NamespacedKey(plugin, "job_type")
    private val playerJobs = HashMap<UUID, JobType>()

    fun getJob(uuid: UUID): JobType? = playerJobs[uuid]

    fun setJob(uuid: UUID, job: JobType) {
        playerJobs[uuid] = job
    }

    // 기존 코드에 추가
    fun removeJob(uuid: UUID) {
        playerJobs.remove(uuid)
    }

    fun hasJob(uuid: UUID): Boolean = playerJobs.containsKey(uuid)

    fun createJobItem(job: JobType): ItemStack {
        val item = ItemStack(job.icon)
        val meta: ItemMeta = item.itemMeta
        meta.setDisplayName("§b${job.displayName}")
        meta.lore = job.lore
        meta.persistentDataContainer.set(jobKey, PersistentDataType.STRING, job.name)
        item.itemMeta = meta
        return item
    }

    fun getJobFromItem(item: ItemStack?): JobType? {
        if (item == null || !item.hasItemMeta()) return null
        val name = item.itemMeta.persistentDataContainer.get(jobKey, PersistentDataType.STRING) ?: return null
        return runCatching { JobType.valueOf(name) }.getOrNull()
    }
}