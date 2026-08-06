package com.joysong.server.agent.service

import com.joysong.server.agent.dto.SafetyScreeningRequest
import com.joysong.server.agent.entity.AgentSafetyEventEntity
import com.joysong.server.agent.repository.AgentSafetyEventRepository
import org.springframework.stereotype.Service
import java.util.UUID

data class SafetyDecision(
    val level: String,
    val reasons: List<String>,
    val nextAction: String
)

@Service
class AgentSafetyService(
    private val repository: AgentSafetyEventRepository
) {
    fun evaluate(userId: String, assessmentId: String, screening: SafetyScreeningRequest): SafetyDecision {
        val stopReasons = mutableListOf<String>()
        val cautionReasons = mutableListOf<String>()
        if (screening.pregnantOrNursing) stopReasons += AgentText.value("处于孕期或哺乳期，需要由专业医生评估", "Pregnancy or breastfeeding requires assessment by a qualified clinician")
        if (screening.activeSkinCondition) stopReasons += AgentText.value("当前存在活动性皮肤问题，不适合直接进行项目规划", "An active skin condition should be assessed before treatment planning")
        if (screening.severeAllergyHistory) stopReasons += AgentText.value("存在严重过敏史，需要先完成医学评估", "A history of severe allergy requires medical assessment first")
        if (screening.takingRelevantMedication) stopReasons += AgentText.value("当前用药可能影响项目安全性，需要医生核对", "Current medication may affect treatment safety and needs clinician review")
        if (screening.severeDistressAboutAppearance) stopReasons += AgentText.value("当前对外貌的困扰较强，建议先暂停项目决策并寻求专业支持", "Strong appearance-related distress is a reason to pause treatment decisions and seek professional support")
        if (screening.recentProcedure) cautionReasons += AgentText.value("近期做过相关项目，需要确认间隔时间和项目冲突", "A recent related treatment requires confirmation of timing and possible conflicts")
        if (screening.expectsGuaranteedResult) cautionReasons += AgentText.value("医美效果存在个体差异，不能保证确定效果", "Aesthetic outcomes vary by person and cannot be guaranteed")

        val decision = when {
            stopReasons.isNotEmpty() -> SafetyDecision("HIGH", stopReasons + cautionReasons, "HUMAN_REVIEW")
            cautionReasons.isNotEmpty() -> SafetyDecision("MEDIUM", cautionReasons, "CLARIFY")
            else -> SafetyDecision("NONE", emptyList(), "PLAN")
        }
        if (decision.level != "NONE") {
            repository.save(
                AgentSafetyEventEntity(
                    id = UUID.randomUUID().toString(),
                    userId = userId,
                    assessmentId = assessmentId,
                    riskType = if (decision.level == "HIGH") "MEDICAL_OR_WELLBEING_RISK" else "EXPECTATION_OR_TIMING_RISK",
                    riskLevel = decision.level,
                    // Do not persist health screening details without an approved encryption key.
                    evidenceJson = "[]",
                    agentAction = decision.nextAction
                )
            )
        }
        return decision
    }
}
