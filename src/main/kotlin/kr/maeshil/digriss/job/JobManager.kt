package kr.maeshil.digriss.job

import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.ItemMeta
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.util.UUID

class JobManager(private val plugin: JavaPlugin) {

    private val jobKey = NamespacedKey(plugin, "job_type")
    private val playerJobs = HashMap<UUID, JobType>()

    private val file = File(plugin.dataFolder, "jobs.yml")
    private val config: YamlConfiguration

    init {
        if (!plugin.dataFolder.exists()) plugin.dataFolder.mkdirs()
        if (!file.exists()) file.createNewFile()
        config = YamlConfiguration.loadConfiguration(file)
        load()
    }

    private fun load() {
        config.getKeys(false).forEach { key ->
            val job = runCatching { JobType.valueOf(config.getString(key) ?: "") }.getOrNull() ?: return@forEach
            playerJobs[UUID.fromString(key)] = job
        }
    }

    // 직업 변경은 드물어서 바뀔 때마다 바로 저장 (서버가 비정상 종료돼도 유지)
    fun save() {
        config.getKeys(false).forEach { config.set(it, null) }
        playerJobs.forEach { (uuid, job) -> config.set(uuid.toString(), job.name) }
        config.save(file)
    }

    fun getJob(uuid: UUID): JobType? = playerJobs[uuid]

    fun setJob(uuid: UUID, job: JobType) {
        playerJobs[uuid] = job
        save()
    }

    fun removeJob(uuid: UUID) {
        playerJobs.remove(uuid)
        save()
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