package kr.maeshil.digriss.job

object JobSkillRegistry {
    private val skills = mapOf<JobType, JobSkill>(
        JobType.REAPER to ReaperSkill(),
        JobType.SHADOW_ASSASSIN to AssassinSkill(),
        JobType.TRACKER to ScoutSkill(),
        JobType.BLOOD_WARRIOR to BerserkerSkill(),
        JobType.SHIELD_GUARDIAN to GuardianSkill(),
        JobType.LIFE_PRIEST to HealerSkill()
    )

    fun get(job: JobType): JobSkill? = skills[job]
}