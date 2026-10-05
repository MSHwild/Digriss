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
import kr.maeshil.digriss.job.GuardianSkill
import kr.maeshil.digriss.job.HealerAura
import kr.maeshil.digriss.job.JobSkillRegistry
import kr.maeshil.digriss.job.JobType
import kr.maeshil.digriss.jobManager.AssassinStealthManager
import kr.maeshil.digriss.jobManager.ReaperChargeManager
import kr.maeshil.digriss.job.ReaperSkill
import kr.maeshil.digriss.job.JobTriggerListener
import kr.maeshil.digriss.jobManager.AssassinListener
import kr.maeshil.digriss.alliance.AllianceCommand
import kr.maeshil.digriss.alliance.AllianceListener
import kr.maeshil.digriss.command.NationRankCommand
import kr.maeshil.digriss.bundle.BundleCommand
import kr.maeshil.digriss.bundle.BundleEditHolder
import kr.maeshil.digriss.bundle.BundleListener
import kr.maeshil.digriss.help.HelpCommand
import kr.maeshil.digriss.menu.MainMenu
import kr.maeshil.digriss.achievement.TitleMenu
import kr.maeshil.digriss.command.IconCommand
import kr.maeshil.digriss.command.WarEventCommand
import kr.maeshil.digriss.manager.AchievementManager
import kr.maeshil.digriss.manager.IconManager
import kr.maeshil.digriss.manager.WarEventManager
import kr.maeshil.digriss.manager.AdminLogManager
import kr.maeshil.digriss.manager.AllianceManager
import kr.maeshil.digriss.manager.BundleManager
import kr.maeshil.digriss.manager.HelpManager
import kr.maeshil.digriss.manager.DCManager
import kr.maeshil.digriss.manager.JobSkillManager
import kr.maeshil.digriss.manager.KDManager
import kr.maeshil.digriss.manager.ManaManager
import kr.maeshil.digriss.manager.QuestManager
import kr.maeshil.digriss.manager.RankManager
import kr.maeshil.digriss.manager.NationStorageManager
import kr.maeshil.digriss.manager.SoulManager
import kr.maeshil.digriss.manager.WarScoreManager
import kr.maeshil.digriss.nation.NationChat
import kr.maeshil.digriss.nation.NationStorage
import kr.maeshil.digriss.manager.NationManager
import kr.maeshil.digriss.quest.QuestAdminCommand
import kr.maeshil.digriss.quest.QuestCommand
import kr.maeshil.digriss.quest.QuestListener
import kr.maeshil.digriss.skill.BarrierBlockListener
import kr.maeshil.digriss.skill.SkillListener
import kr.maeshil.digriss.skill.SphereUtil
import kr.maeshil.digriss.skill.YeoksulDamageListener
import kr.maeshil.digriss.command.AttributeCommand
import kr.maeshil.digriss.util.ItemAttributeStore
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
    lateinit var nationManager: NationManager
        private set
    lateinit var questManager: QuestManager
        private set
    lateinit var allianceManager: AllianceManager
        private set
    lateinit var warScoreManager: WarScoreManager
        private set
    lateinit var nationStorageManager: NationStorageManager
        private set
    lateinit var adminLogManager: AdminLogManager
        private set
    lateinit var bundleManager: BundleManager
        private set
    lateinit var helpManager: HelpManager
        private set
    lateinit var iconManager: IconManager
        private set
    lateinit var warEventManager: WarEventManager
        private set
    lateinit var achievementManager: AchievementManager
        private set
    lateinit var mainMenu: MainMenu
        private set
    lateinit var combatManager: kr.maeshil.digriss.manager.CombatManager
        private set
    lateinit var randomSpawnManager: kr.maeshil.digriss.manager.RandomSpawnManager
        private set
    lateinit var linkCommand: kr.maeshil.digriss.command.LinkCommand
        private set

    override fun onEnable() {

        // 액션바는 provider 등록 전에 먼저 시작
        ActionBarManager.start(this)

        // 매니저 초기화 (nationManager는 scoreboardManager보다 먼저!)
        adminLogManager = AdminLogManager(this)
        iconManager = IconManager(this)
        achievementManager = AchievementManager(this)
        soulManager = SoulManager(this)
        kdManager = KDManager(this)
        rankManager = RankManager(this)
        killEffectManager = KillEffectManager(this)
        jobManager = JobManager(this)
        dcManager = DCManager(this)
        manaManager = ManaManager(this)
        jobSkillManager = JobSkillManager(this)

        allianceManager = AllianceManager(this) // NationManager가 사용하므로 먼저 생성
        warScoreManager = WarScoreManager(this)
        nationStorageManager = NationStorageManager(this)
        nationManager = NationManager(this)
        nationManager.enable() // 국가 명령어/리스너/스케줄러는 여기서 자동 등록됨

        questManager = QuestManager(this) // soul/dc/nation 매니저 이후에 생성
        bundleManager = BundleManager(this)
        helpManager = HelpManager(this)
        warEventManager = WarEventManager(this)
        combatManager = kr.maeshil.digriss.manager.CombatManager(this) // 전투 중 도주(로그아웃·텔레포트) 방지
        combatManager.start()
        randomSpawnManager = kr.maeshil.digriss.manager.RandomSpawnManager(this)
        Bukkit.getPluginManager().registerEvents(randomSpawnManager, this) // 첫 접속·무소속 부활 랜덤 스폰

        // 직업 스킬(F) 쿨타임 상시 표시 (스킬이 있는 직업만)
        ActionBarManager.addProvider { player ->
            val job = jobManager.getJob(player.uniqueId) ?: return@addProvider null
            JobSkillRegistry.get(job) ?: return@addProvider null
            val uuid = player.uniqueId
            val remain = jobSkillManager.getRemaining(uuid)
            val reaperActive = ReaperSkill.remainingSeconds(uuid)
            when {
                reaperActive != null -> "§b👻 무체화 중 ${"%.1f".format(reaperActive)}초"
                job == JobType.SHADOW_ASSASSIN && AssassinStealthManager.isActive(uuid) -> "§5👁 은신 중 (기습 대기)"
                remain > 0 -> "§e⏳ ${job.displayName} ${remain}초"
                job == JobType.REAPER && !ReaperChargeManager.isCharged(uuid) -> "§7☠ 사신 · 영혼 없음"
                job == JobType.REAPER -> "§b☠ 사신 · 영혼 충전됨"
                else -> "§b✦ ${job.displayName} 준비 완료"
            }
        }

        scoreboardManager = ScoreboardManager(soulManager, kdManager, rankManager, killEffectManager, jobManager, nationManager, dcManager, allianceManager)

        val effectGUI = EffectGUI(this)

        // 리스너 등록
        Bukkit.getPluginManager().registerEvents(JobTriggerListener(this, jobManager, jobSkillManager), this)
        Bukkit.getPluginManager().registerEvents(EffectListener(this, effectGUI), this)
        Bukkit.getPluginManager().registerEvents(Event(this), this)
        Bukkit.getPluginManager().registerEvents(ChatEvent(this), this)
        Bukkit.getPluginManager().registerEvents(JobListener(jobManager, soulManager), this)
        Bukkit.getPluginManager().registerEvents(WeaponSkillListener(this), this)
        Bukkit.getPluginManager().registerEvents(SkillListener(this, manaManager), this)
        Bukkit.getPluginManager().registerEvents(AssassinListener(jobManager), this)
        Bukkit.getPluginManager().registerEvents(QuestListener(this, questManager), this)
        Bukkit.getPluginManager().registerEvents(AllianceListener(this), this)
        Bukkit.getPluginManager().registerEvents(BundleListener(this), this)
        mainMenu = MainMenu(this)
        Bukkit.getPluginManager().registerEvents(mainMenu, this) // Shift+F 메뉴
        val titleMenu = TitleMenu(this)
        Bukkit.getPluginManager().registerEvents(titleMenu, this)
        val nationStorage = NationStorage(this)
        Bukkit.getPluginManager().registerEvents(nationStorage, this)
        val nationChat = NationChat(this)
        Bukkit.getPluginManager().registerEvents(nationChat, this)
        Bukkit.getPluginManager().registerEvents(GuardianSkill.TauntListener(), this)
        Bukkit.getPluginManager().registerEvents(BarrierBlockListener(), this)       // 결계 흑요석을 부수면 결계 해제 (드랍 없음)
        Bukkit.getPluginManager().registerEvents(YeoksulDamageListener(this), this)  // 역술 피해 흡수
        HealerAura.start(this)

        // 명령어 등록
        getCommand("영혼")?.setExecutor(SoulCommand(this))
        getCommand("어트리뷰트")?.setExecutor(AttributeCommand(ItemAttributeStore(this)))
        getCommand("직업")?.setExecutor(JobPurchaseCommand(jobManager))
        getCommand("직업설정")?.setExecutor(JobConfirmCommand(jobManager))
        getCommand("초기화")?.setExecutor(ResetCommand(this))
        getCommand("dc")?.setExecutor(DCCommand(this))
        getCommand("랭크")?.setExecutor(MyRankCommand(rankManager, adminLogManager))
        getCommand("랭킹")?.setExecutor(RankCommand(this))
        getCommand("킬이펙트")?.setExecutor(KillEffectCommand(effectGUI))
        getCommand("퀘스트")?.setExecutor(QuestCommand(questManager))
        getCommand("퀘스트관리")?.setExecutor(QuestAdminCommand(questManager, adminLogManager))
        getCommand("연합")?.setExecutor(AllianceCommand(this))
        getCommand("국가채팅")?.setExecutor(nationChat)
        getCommand("국가창고")?.setExecutor(nationStorage)
        getCommand("연합채팅")?.setExecutor(nationChat)
        HelpCommand(this).let { cmd ->
            getCommand("도움말")?.apply { setExecutor(cmd); tabCompleter = cmd }
            Bukkit.getPluginManager().registerEvents(cmd, this)
        }
        BundleCommand(this).let { cmd ->
            listOf("번들", "번들생성", "번들수정", "번들삭제").forEach { getCommand(it)?.apply { setExecutor(cmd); tabCompleter = cmd } }
        }
        getCommand("칭호")?.setExecutor(titleMenu)
        WarEventCommand(this).let { getCommand("전쟁이벤트")?.apply { setExecutor(it); tabCompleter = it } }
        getCommand("아이콘")?.setExecutor(IconCommand(this))
        kr.maeshil.digriss.command.ReloadCommand(this).let { getCommand("디그리스")?.apply { setExecutor(it); tabCompleter = it } }
        linkCommand = kr.maeshil.digriss.command.LinkCommand(this)
        listOf("지도", "디스코드").forEach { getCommand(it)?.setExecutor(linkCommand) }
        NationRankCommand().let { getCommand("국가랭킹")?.apply { setExecutor(it); tabCompleter = it } }

        // 5분마다 자동 저장 (서버가 비정상 종료돼도 최대 5분치만 손실)
        Bukkit.getScheduler().runTaskTimer(this, Runnable { saveAll() }, 6000L, 6000L)

        // 스코어보드 주기적 갱신 (1초마다)
        Bukkit.getScheduler().runTaskTimer(this, Runnable {
            Bukkit.getOnlinePlayers().forEach { scoreboardManager.update(it) }
        }, 0L, 20L)

        logger.info("Digriss 플러그인이 활성화되었습니다.")
    }

    override fun onDisable() {
        ReaperSkill.restoreAll() // 무체화 중 리로드/종료 시 장비 복구
        SphereUtil.restoreAll()  // 결계 흑요석이 남지 않게 원래대로
        if (::warEventManager.isInitialized) warEventManager.shutdown()
        // 번들 편집 창이 열린 채로 꺼지면 넣은 아이템이 저장되지 않으므로 리스너 해제 전에 닫아서 저장
        Bukkit.getOnlinePlayers().filter { it.openInventory.topInventory.holder is BundleEditHolder }.forEach { it.closeInventory() }
        HandlerList.unregisterAll(this)
        saveAll()
        if (::nationManager.isInitialized) nationManager.disable()
        if (::manaManager.isInitialized) manaManager.cleanup()
        logger.info("Digriss 플러그인이 비활성화되었습니다.")
    }

    // DC는 변경 즉시 저장되고 웹훅이 dc.yml을 직접 수정하므로 여기서 저장하지 않음 (덮어쓰기 방지)
    private fun saveAll() {
        if (::soulManager.isInitialized) soulManager.save()
        if (::rankManager.isInitialized) rankManager.save()
        if (::kdManager.isInitialized) kdManager.save()
        if (::killEffectManager.isInitialized) killEffectManager.save()
        if (::jobManager.isInitialized) jobManager.save()
        if (::questManager.isInitialized) questManager.saveAll()
        if (::nationStorageManager.isInitialized) nationStorageManager.save()
    }
}