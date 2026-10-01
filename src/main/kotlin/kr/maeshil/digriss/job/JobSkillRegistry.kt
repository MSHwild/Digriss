package kr.maeshil.digriss.job

object JobSkillRegistry {
    private val skills = mapOf<JobType, JobSkill>(
        JobType.REAPER to ReaperSkill(),
        JobType.SHADOW_ASSASSIN to AssassinSkill()
    )

    fun get(job: JobType): JobSkill? = skills[job]
}