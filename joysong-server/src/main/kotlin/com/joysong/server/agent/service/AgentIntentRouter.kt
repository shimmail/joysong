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
    val requiresLlmParsing: Boolean,
    val explicitIntent: Boolean = false,
    val explicitQueryTarget: Boolean = false,
    val requiresContextCompletion: Boolean = false
)

/**
 * Lightweight, deterministic routing before catalog search or model generation.
 * Audit persistence is deliberately not referenced here: traces observe a decision,
 * but never participate in making it.
 */
@Service
class AgentIntentRouter {
    fun assess(query: String, contextType: String): AgentRouteAssessment {
        val currentAssessment = assessCurrent(query, contextType)
        val decision = completeDetailContext(currentAssessment.decision, contextType)
        if (decision == currentAssessment.decision) return currentAssessment

        val reasons = if (decision.queryTarget != null) {
            currentAssessment.ambiguityReasons - "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET"
        } else {
            currentAssessment.ambiguityReasons
        }
        val confidence = (currentAssessment.confidence + 0.15 +
            if ("MULTIPLE_CONSTRAINTS_WITHOUT_TARGET" in currentAssessment.ambiguityReasons && decision.queryTarget != null) 0.20 else 0.0
            ).coerceAtMost(1.0)
        val requiresContextCompletion = "UNRESOLVED_CURRENT_REFERENCE" in reasons ||
            (currentAssessment.requiresContextCompletion && decision.queryTarget == null)
        return currentAssessment.copy(
            decision = decision,
            confidence = confidence,
            requiresLlmParsing = requiresLlmParsing(
                decision.intent,
                confidence,
                reasons,
                requiresContextCompletion
            ),
            ambiguityReasons = reasons,
            requiresContextCompletion = requiresContextCompletion
        )
    }

    fun assessCurrent(query: String, contextType: String): AgentRouteAssessment {
        val normalized = normalize(query)
        val safetySignals = matchSignals(normalized, safetyTerms)
        val detailSummarySignals = matchSignals(normalized, detailSummaryTerms)
        val comparisonSignals = matchSignals(normalized, comparisonTerms)
        val planningSignals = matchSignals(normalized, planningTerms)
        val catalogSignals = matchSignals(normalized, catalogTerms)
        val institutionProjectSignals = matchSignals(normalized, institutionProjectTerms)
        val doctorSignals = matchSignals(normalized, doctorTerms)
        val institutionSignals = matchSignals(normalized, institutionTerms)
        val projectSignals = matchSignals(normalized, projectTerms)
        val unresolvedReferenceSignals = matchSignals(normalized, unresolvedReferenceTerms)
        val reasons = mutableListOf<String>()
        var score = 0.55

        if (matchSignals(normalized, routingTerms).ambiguousNegation) {
            reasons += "AMBIGUOUS_NEGATION"
        }
        val queryTarget = when {
            institutionProjectSignals.positiveTerms.isNotEmpty() -> AgentQueryTarget.INSTITUTION_PROJECT
            doctorSignals.positiveTerms.isNotEmpty() -> AgentQueryTarget.DOCTOR
            institutionSignals.positiveTerms.isNotEmpty() -> AgentQueryTarget.INSTITUTION
            projectSignals.positiveTerms.isNotEmpty() -> AgentQueryTarget.PROJECT
            else -> null
        }
        val targetCount = listOf(
            institutionProjectSignals.positiveTerms.isNotEmpty(),
            doctorSignals.positiveTerms.isNotEmpty(),
            institutionSignals.positiveTerms.isNotEmpty(),
            projectSignals.positiveTerms.isNotEmpty()
        ).count { it }
        if (targetCount > 1 && institutionProjectSignals.positiveTerms.isEmpty()) {
            score -= 0.20
            reasons += "CONFLICTING_CURRENT_TARGETS"
        }
        if (unresolvedReferenceSignals.positiveTerms.isNotEmpty()) {
            score -= 0.20
            reasons += "UNRESOLVED_CURRENT_REFERENCE"
        }
        val constraintCount = constraintSignalGroups.count { terms ->
            matchSignals(normalized, terms).let { signals ->
                signals.positiveTerms.isNotEmpty() || signals.negatedTerms.isNotEmpty()
            }
        }
        if (constraintCount >= 2 && queryTarget == null) {
            score -= 0.20
            reasons += "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET"
        }
        val intent = when {
            safetySignals.positiveTerms.isNotEmpty() -> AgentIntent.SAFETY_SCREENING
            detailSummarySignals.positiveTerms.isNotEmpty() -> AgentIntent.DETAIL_SUMMARY
            comparisonSignals.positiveTerms.isNotEmpty() -> AgentIntent.COMPARISON
            planningSignals.positiveTerms.isNotEmpty() -> AgentIntent.PLANNING
            catalogSignals.positiveTerms.isNotEmpty() -> AgentIntent.CATALOG_QA
            else -> AgentIntent.GENERAL_CHAT
        }
        if (intent == AgentIntent.GENERAL_CHAT && matchSignals(normalized, aestheticConcernTerms).positiveTerms.isNotEmpty()) {
            score -= 0.30
            reasons += "UNCLASSIFIED_AESTHETIC_REQUEST"
        }

        val explicitIntent = when (intent) {
            AgentIntent.SAFETY_SCREENING -> safetySignals.positiveTerms.isNotEmpty()
            AgentIntent.DETAIL_SUMMARY -> detailSummarySignals.positiveTerms.isNotEmpty()
            AgentIntent.COMPARISON -> comparisonSignals.positiveTerms.isNotEmpty()
            AgentIntent.PLANNING -> planningSignals.positiveTerms.isNotEmpty()
            AgentIntent.CATALOG_QA -> catalogSignals.positiveTerms.isNotEmpty()
            AgentIntent.GENERAL_CHAT -> false
        }
        if (explicitIntent) score += 0.20
        if (queryTarget != null) score += 0.15
        if (contextType.uppercase() in detailContextTypes) score += 0.10

        val confidence = score.coerceIn(0.0, 1.0)
        val requiresContextCompletion = (intent in intentsRequiringTarget && queryTarget == null) ||
            "UNRESOLVED_CURRENT_REFERENCE" in reasons
        val needsLlm = requiresLlmParsing(intent, confidence, reasons, requiresContextCompletion)
        return AgentRouteAssessment(
            decision = validatedDecision(intent, queryTarget),
            confidence = confidence,
            ambiguityReasons = reasons.distinct(),
            requiresLlmParsing = needsLlm,
            explicitIntent = explicitIntent,
            explicitQueryTarget = queryTarget != null,
            requiresContextCompletion = requiresContextCompletion
        )
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
        val currentDecision = assessCurrent(query, contextType).decision
        return completeDetailContext(currentDecision, contextType)
    }

    private fun completeDetailContext(
        currentDecision: AgentIntentDecision,
        contextType: String
    ): AgentIntentDecision {
        val detailContext = contextType.uppercase() in detailContextTypes
        if (!detailContext || currentDecision.intent == AgentIntent.SAFETY_SCREENING) return currentDecision
        val queryTarget = currentDecision.queryTarget ?: AgentQueryTarget.valueOf(contextType.uppercase())
        val intent = if (currentDecision.intent == AgentIntent.GENERAL_CHAT) AgentIntent.CATALOG_QA else currentDecision.intent
        return validatedDecision(intent, queryTarget)
    }

    private fun requiresLlmParsing(
        intent: AgentIntent,
        confidence: Double,
        reasons: List<String>,
        requiresContextCompletion: Boolean
    ): Boolean = intent != AgentIntent.SAFETY_SCREENING && (
        confidence < 0.70 ||
            "AMBIGUOUS_NEGATION" in reasons ||
            "CONFLICTING_CURRENT_TARGETS" in reasons ||
            "UNRESOLVED_CURRENT_REFERENCE" in reasons ||
            "UNCLASSIFIED_AESTHETIC_REQUEST" in reasons ||
            requiresContextCompletion
        )

    private data class MatchedSignals(
        val positiveTerms: List<String>,
        val negatedTerms: List<String>,
        val ambiguousNegation: Boolean
    )

    private fun matchSignals(query: String, terms: List<String>): MatchedSignals {
        val normalizedQuery = normalize(query)
        val normalizedTerms = terms.map(::normalize).distinct().sortedByDescending(String::length)
        val positiveTerms = mutableListOf<String>()
        val negatedTerms = mutableListOf<String>()
        var ambiguousNegation = false

        clauseRanges(normalizedQuery).forEach { range ->
            val clause = normalizedQuery.substring(range)
            val matches = normalizedTerms.flatMap { term ->
                termIndexes(clause, term).map { index -> SignalMatch(term, index) }
            }.sortedBy { it.index }.fold(mutableListOf<SignalMatch>()) { selected, match ->
                if (selected.none { rangesOverlap(it.index, it.term.length, match.index, match.term.length) }) selected += match
                selected
            }
            val negators = negatorIndexes(clause)
            val negatedMatches = negators.mapNotNull { negator ->
                matches.minByOrNull { kotlin.math.abs(it.index - negator.index) }
            }.toSet()
            if (negatedMatches.size < negators.size) ambiguousNegation = true
            matches.forEach { match ->
                if (match in negatedMatches) negatedTerms += match.term else positiveTerms += match.term
            }
        }
        return MatchedSignals(positiveTerms.distinct(), negatedTerms.distinct(), ambiguousNegation)
    }

    private fun normalize(query: String): String = query
        .replace(Regex("[’‘ʼ]"), "'")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clauseRanges(query: String): List<IntRange> {
        val delimiter = Regex("[,.;!?，。；！？]|但是|而是|但|只|\\bbut\\b|\\binstead\\b|\\bjust\\b")
        val ranges = mutableListOf<IntRange>()
        var start = 0
        delimiter.findAll(query).forEach { match ->
            if (start < match.range.first) ranges += start until match.range.first
            start = match.range.last + 1
        }
        if (start < query.length) ranges += start until query.length
        return ranges
    }

    private fun termIndexes(text: String, term: String): List<Int> = buildList {
        var index = text.indexOf(term)
        while (index >= 0) {
            add(index)
            index = text.indexOf(term, index + term.length)
        }
    }

    private fun negatorIndexes(text: String): List<SignalMatch> {
        val negators = listOf("don't", "do not", "doesn't", "does not", "not", "without", "不要", "不是", "没有", "别", "不", "没")
        val matches = negators.sortedByDescending(String::length).flatMap { term ->
            termIndexes(text, term).map { index -> SignalMatch(term, index) }
        }.sortedBy { it.index }.fold(mutableListOf<SignalMatch>()) { selected, match ->
            if (selected.none { rangesOverlap(it.index, it.term.length, match.index, match.term.length) }) selected += match
            selected
        }
        return matches
    }

    private fun rangesOverlap(firstStart: Int, firstLength: Int, secondStart: Int, secondLength: Int): Boolean =
        firstStart < secondStart + secondLength && secondStart < firstStart + firstLength

    private data class SignalMatch(val term: String, val index: Int)

    private companion object {
        val detailContextTypes = setOf("PROJECT", "INSTITUTION", "INSTITUTION_PROJECT", "DOCTOR")
        val intentsRequiringTarget = setOf(
            AgentIntent.CATALOG_QA,
            AgentIntent.COMPARISON,
            AgentIntent.PLANNING,
            AgentIntent.DETAIL_SUMMARY
        )
        val comparisonTerms = listOf("对比", "比较", "区别", "compare", "comparison", "versus", " vs ")
        val planningTerms = listOf("规划", "方案", "怎么安排", "适合我", "plan", "planning", "suitable for me")
        val unresolvedReferenceTerms = listOf("这个", "那个", "那这个", "那它", "它呢", "这家", "那家", "这个呢", "那个呢", "this", "that", "what about it", "how about that")
        val constraintSignalGroups = listOf(
            listOf("预算", "budget"),
            listOf("恢复期", "恢复", "downtime", "recovery"),
            listOf("疼痛", "怕痛", "pain"),
            listOf("多久", "时间")
        )
        val aestheticConcernTerms = listOf("脸垮", "显老", "松弛", "下垂", "暗沉", "毛孔", "斑", "痘", "皱纹", "细纹", "凹陷", "变美", "改善", "sagging", "aging", "dull", "pores", "acne", "wrinkle", "hollow", "improve my face")
        val institutionProjectTerms = listOf("机构项目", "机构套餐", "项目套餐", "套餐", "报价", "institution project", "clinic package", "package", "offering")
        val doctorTerms = listOf("医生", "医师", "大夫", "doctor", "surgeon", "physician")
        val institutionTerms = listOf("机构", "医院", "诊所", "门诊部", "clinic", "clinics", "hospital", "hospitals", "institution")
        val projectTerms = listOf("项目", "治疗", "术式", "procedure", "treatment", "treatments")
        val detailSummaryTerms = listOf(
            "总结", "概括", "介绍", "简介", "详情", "当前页面", "当前详情", "这个页面", "这页", "简要", "简短",
            "summary", "summarize", "overview", "introduce", "about this", "current page", "this page"
        )
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
        val routingTerms = safetyTerms + detailSummaryTerms + comparisonTerms + planningTerms + catalogTerms +
            institutionProjectTerms + doctorTerms + institutionTerms + projectTerms + unresolvedReferenceTerms
    }
}
