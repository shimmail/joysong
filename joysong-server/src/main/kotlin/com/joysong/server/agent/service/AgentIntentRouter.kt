package com.joysong.server.agent.service

import org.springframework.stereotype.Service

enum class AgentIntent {
    GENERAL_CHAT,
    CATALOG_QA,
    COMPARISON,
    PLANNING,
    DETAIL_SUMMARY,
    SAFETY_SCREENING
}

enum class AgentQueryTarget { INSTITUTION, DOCTOR, PROJECT, INSTITUTION_PROJECT }

enum class AgentNextAction { NONE, SHOW_CATALOG, START_PLANNING, COMPLETE_SAFETY_SCREENING }

data class AgentIntentDecision(
    val intent: AgentIntent,
    val queryTarget: AgentQueryTarget? = null,
    val searchCatalog: Boolean,
    val nextAction: AgentNextAction = AgentNextAction.NONE
)

data class AgentRouteAssessment(
    val decision: AgentIntentDecision,
    val confidence: Double,
    val ambiguityReasons: List<String>,
    val requiresLlmParsing: Boolean
)

/**
 * Lightweight, deterministic routing before catalog search or model generation.
 * Audit persistence is deliberately not referenced here: traces observe a decision,
 * but never participate in making it.
 */
@Service
class AgentIntentRouter {
    fun assess(query: String, contextType: String): AgentRouteAssessment {
        val decision = decide(query, contextType)
        val normalized = query.lowercase()
        val reasons = mutableListOf<String>()
        var score = 0.55

        if (explicitIntentTerms.any(normalized::contains)) score += 0.20
        if (decision.queryTarget != null) score += 0.15
        if (contextType.uppercase() in detailContextTypes) score += 0.10

        val targetCount = listOf(
            institutionProjectTerms.any(normalized::contains),
            doctorTerms.any(normalized::contains),
            institutionTerms.any(normalized::contains),
            projectTerms.any(normalized::contains)
        ).count { it }
        if (targetCount > 1 && !institutionProjectTerms.any(normalized::contains)) {
            score -= 0.20
            reasons += "CONFLICTING_TARGETS"
        }
        if (unresolvedReferenceTerms.any { normalized.trim() == it || normalized.startsWith("$it ") }) {
            score -= 0.20
            reasons += "UNRESOLVED_REFERENCE"
        }
        val constraintCount = constraintTerms.count(normalized::contains)
        if (constraintCount >= 2 && decision.queryTarget == null) {
            score -= 0.20
            reasons += "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET"
        }
        if (decision.intent == AgentIntent.GENERAL_CHAT && aestheticConcernTerms.any(normalized::contains)) {
            score -= 0.30
            reasons += "UNCLASSIFIED_AESTHETIC_REQUEST"
        }

        val confidence = score.coerceIn(0.0, 1.0)
        val needsLlm = decision.intent != AgentIntent.SAFETY_SCREENING && (
            confidence < 0.70 ||
                "CONFLICTING_TARGETS" in reasons ||
                "UNCLASSIFIED_AESTHETIC_REQUEST" in reasons
            )
        return AgentRouteAssessment(decision, confidence, reasons.distinct(), needsLlm)
    }

    fun validatedDecision(intent: AgentIntent, queryTarget: AgentQueryTarget?): AgentIntentDecision {
        val searchCatalog = intent in setOf(
            AgentIntent.CATALOG_QA,
            AgentIntent.COMPARISON,
            AgentIntent.DETAIL_SUMMARY
        ) || (intent == AgentIntent.PLANNING && queryTarget != null)
        return AgentIntentDecision(
            intent = intent,
            queryTarget = queryTarget,
            searchCatalog = intent != AgentIntent.SAFETY_SCREENING && searchCatalog,
            nextAction = when (intent) {
                AgentIntent.SAFETY_SCREENING -> AgentNextAction.COMPLETE_SAFETY_SCREENING
                AgentIntent.PLANNING -> AgentNextAction.START_PLANNING
                AgentIntent.CATALOG_QA, AgentIntent.COMPARISON, AgentIntent.DETAIL_SUMMARY -> AgentNextAction.SHOW_CATALOG
                AgentIntent.GENERAL_CHAT -> AgentNextAction.NONE
            }
        )
    }

    fun decide(query: String, contextType: String): AgentIntentDecision {
        val normalized = query.lowercase()
        val detailContext = contextType.uppercase() in detailContextTypes
        val catalogRelated = detailContext || catalogTerms.any(normalized::contains)
        val queryTarget = when {
            institutionProjectTerms.any(normalized::contains) -> AgentQueryTarget.INSTITUTION_PROJECT
            doctorTerms.any(normalized::contains) -> AgentQueryTarget.DOCTOR
            institutionTerms.any(normalized::contains) -> AgentQueryTarget.INSTITUTION
            projectTerms.any(normalized::contains) -> AgentQueryTarget.PROJECT
            else -> contextType.uppercase().takeIf(detailContextTypes::contains)?.let(AgentQueryTarget::valueOf)
        }
        val intent = when {
            safetyTerms.any(normalized::contains) -> AgentIntent.SAFETY_SCREENING
            detailContext -> AgentIntent.DETAIL_SUMMARY
            comparisonTerms.any(normalized::contains) -> AgentIntent.COMPARISON
            planningTerms.any(normalized::contains) -> AgentIntent.PLANNING
            catalogRelated -> AgentIntent.CATALOG_QA
            else -> AgentIntent.GENERAL_CHAT
        }
        return AgentIntentDecision(
            intent = intent,
            queryTarget = queryTarget,
            searchCatalog = intent != AgentIntent.SAFETY_SCREENING &&
                (catalogRelated || intent == AgentIntent.COMPARISON),
            nextAction = when (intent) {
                AgentIntent.SAFETY_SCREENING -> AgentNextAction.COMPLETE_SAFETY_SCREENING
                AgentIntent.PLANNING -> AgentNextAction.START_PLANNING
                AgentIntent.CATALOG_QA, AgentIntent.COMPARISON, AgentIntent.DETAIL_SUMMARY -> AgentNextAction.SHOW_CATALOG
                AgentIntent.GENERAL_CHAT -> AgentNextAction.NONE
            }
        )
    }

    private companion object {
        val detailContextTypes = setOf("PROJECT", "INSTITUTION", "INSTITUTION_PROJECT", "DOCTOR")
        val comparisonTerms = listOf("对比", "比较", "区别", "compare", "comparison", "versus", " vs ")
        val planningTerms = listOf("规划", "方案", "怎么安排", "适合我", "plan", "planning", "suitable for me")
        val explicitIntentTerms = comparisonTerms + planningTerms + listOf("查找", "搜索", "推荐", "预约", "介绍", "总结", "find", "search", "recommend", "book", "summarize")
        val unresolvedReferenceTerms = listOf("这个", "那个", "那这个", "那它", "它呢", "这家", "那家", "这个呢", "那个呢", "this", "that", "what about it", "how about that")
        val constraintTerms = listOf("预算", "恢复期", "恢复", "疼痛", "怕痛", "多久", "时间", "budget", "downtime", "pain", "recovery")
        val aestheticConcernTerms = listOf("脸垮", "显老", "松弛", "下垂", "暗沉", "毛孔", "斑", "痘", "皱纹", "细纹", "凹陷", "变美", "改善", "sagging", "aging", "dull", "pores", "acne", "wrinkle", "hollow", "improve my face")
        val institutionProjectTerms = listOf("机构项目", "机构套餐", "项目套餐", "套餐", "报价", "institution project", "clinic package", "package", "offering")
        val doctorTerms = listOf("医生", "医师", "大夫", "doctor", "surgeon", "physician")
        val institutionTerms = listOf("机构", "医院", "诊所", "门诊部", "clinic", "hospital", "institution")
        val projectTerms = listOf("项目", "治疗", "术式", "procedure", "treatment")
        val safetyTerms = listOf(
            "怀孕", "孕期", "备孕", "哺乳", "严重过敏", "过敏史", "瘢痕体质", "疤痕体质",
            "正在吃药", "正在服药", "皮肤感染", "伤口未愈合", "保证效果", "百分百有效",
            "pregnant", "pregnancy", "breastfeeding", "severe allergy", "keloid",
            "taking medication", "skin infection", "guaranteed result", "guarantee", "100% effective", "100% result"
        )
        val catalogTerms = listOf(
            "机构", "医院", "诊所", "医生", "医师", "项目", "价格", "费用", "报价", "套餐", "预约",
            "推荐", "光子", "嫩肤", "激光", "注射", "玻尿酸", "肉毒", "超声", "射频", "热玛吉",
            "皮肤", "肤质", "暗沉", "毛孔", "粗糙", "斑", "痘", "皱纹", "细纹", "松弛", "下垂", "凹陷", "显老",
            "clinic", "hospital", "doctor", "surgeon", "treatment", "procedure", "price", "cost", "package",
            "recommend", "appointment", "ipl", "aopt", "dpl", "laser", "botox", "filler", "thermage",
            "skin", "dull", "pores", "texture", "spots", "pigmentation", "acne", "wrinkle", "aging", "sagging", "hollow"
        )
    }
}
