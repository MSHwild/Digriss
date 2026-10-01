package kr.maeshil.digriss

import kr.maeshil.digriss.command.DCCommand
import kr.maeshil.digriss.command.KillEffectCommand
import kr.maeshil.digriss.command.MyRankCommand
import kr.maeshil.digriss.command.RankCommand
import kr.maeshil.digriss.command.ResetCommand
import kr.maeshil.digriss.command.SoulCommand
import kr.maeshil.digriss.effect.EffectGUI
import kr.maeshil.digriss.effect.EffectListener
import kr.maeshil.digriss.effect.KillEffectManager
import kr.maeshil.digriss.job.JobConfirmCommand
import kr.maeshil.digriss.job.JobListener
import kr.maeshil.digriss.job.JobManager
import kr.maeshil.digriss.job.JobPurchaseCommand
import kr.maeshil.digriss.job.JobSkillRegistry
import kr.maeshil.digriss.job.ReaperSkill
import kr.maeshil.digriss.job.JobTriggerListener
import kr.maeshil.digriss.jobManager.AssassinListener
import kr.maeshil.digriss.manager.DCManager
import kr.maeshil.digriss.manager.JobSkillManager
import kr.maeshil.digriss.manager.KDManager
import kr.maeshil.digriss.manager.ManaManager
import kr.maeshil.digriss.manager.RankManager
import kr.maeshil.digriss.manager.SoulManager
import kr.maeshil.digriss.nation.Nation_D
import kr.maeshil.digriss.skill.SkillListener
import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.plugin.java.JavaPlugin

class Digriss : JavaPlugin() {

    lateinit var soulManager: SoulManager
        private set
    lateinit var kdManager: KDManager
        private set
    lateinit var rankManager: RankManager
        private set
    lateinit var killEffectManager: KillEffectManager
        private set
    lateinit var jobManager: JobManager
        private set
    lateinit var scoreboardManager: ScoreboardManager
        private set
    lateinit var dcManager: DCManager
        private set
    lateinit var manaManager: ManaManager
        private set
    lateinit var jobSkillManager: JobSkillManager
        private set
    lateinit var nationManager: Nation_D
        private set

    override fun onEnable() {

        // 액션바는 provider 등록 전에 먼저 시작
        ActionBarManager.start(this)

        // 매니저 초기화 (nationManager는 scoreboardManager보다 먼저!)
        soulManager = SoulManager(this)
        kdManager = KDManager(this)
        rankManager = RankManager(this)
        killEffectManager = KillEffectManager(this)
        jobManager = JobManager(this)
        dcManager = DCManager(this)
        manaManager = ManaManager(this)
        jobSkillManager = JobSkillManager(this)

        nationManager = Nation_D(this)
        nationManager.enable() // 국가 명령어/리스너/스케줄러는 여기서 자동 등록됨

        // 직업 스킬 쿨타임 상시 표시 (스킬 있는 직업만)
        ActionBarManager.addProvider { player ->
            val job = jobManager.getJob(player.uniqueId) ?: return@addProvider null
            JobSkillRegistry.get(job) ?: return@addProvider null
            val remain = jobSkillManager.getRemaining(player.uniqueId)
            if (remain <= 0) "§a⚔ 스킬 준비 완료" else "§e⏳ 스킬 ${remain}초"
        }

        scoreboardManager = ScoreboardManager(soulManager, kdManager, rankManager, killEffectManager, jobManager, nationManager,dcManager)

        val effectGUI = EffectGUI(this)

        // 리스너 등록
        Bukkit.getPluginManager().registerEvents(JobTriggerListener(this, jobManager, jobSkillManager), this)
        Bukkit.getPluginManager().registerEvents(EffectListener(this, effectGUI), this)
        Bukkit.getPluginManager().registerEvents(Event(this), this)
        Bukkit.getPluginManager().registerEvents(JobListener(jobManager, soulManager), this)
        Bukkit.getPluginManager().registerEvents(WeaponSkillListener(this), this)
        Bukkit.getPluginManager().registerEvents(SkillListener(this, manaManager), this)
        Bukkit.getPluginManager().registerEvents(AssassinListener(jobManager), this)

        // 명령어 등록
        getCommand("영혼")?.setExecutor(SoulCommand(this))
        getCommand("직업")?.setExecutor(JobPurchaseCommand(jobManager))
        getCommand("직업설정")?.setExecutor(JobConfirmCommand(jobManager))
        getCommand("초기화")?.setExecutor(ResetCommand(this))
        getCommand("dc")?.setExecutor(DCCommand(this))
        getCommand("랭크")?.setExecutor(MyRankCommand(rankManager))
        getCommand("랭킹")?.setExecutor(RankCommand(this))
        getCommand("킬이펙트")?.setExecutor(KillEffectCommand(effectGUI))

        // 스코어보드 주기적 갱신 (1초마다)
        Bukkit.getScheduler().runTaskTimer(this, Runnable {
            Bukkit.getOnlinePlayers().forEach { scoreboardManager.update(it) }
        }, 0L, 20L)

        logger.info("Digriss 플러그인이 활성화되었습니다.")
    }

    override fun onDisable() {
        ReaperSkill.restoreAll() // 무체화 중 리로드/종료 시 장비 복구
        HandlerList.unregisterAll(this)
        if (::rankManager.isInitialized) rankManager.save()
        if (::killEffectManager.isInitialized) killEffectManager.save()
        if (::soulManager.isInitialized) soulManager.save()
        if (::jobManager.isInitialized) jobManager.save()
        if (::nationManager.isInitialized) nationManager.disable()
        if (::manaManager.isInitialized) manaManager.cleanup()
        logger.info("Digriss 플러그인이 비활성화되었습니다.")
    }
}