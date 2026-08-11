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

enum class AgentLabelPolarity { POSITIVE, NEGATIVE, UNCERTAIN }

enum class AgentLabelSource { CURRENT, CONTEXT, MODEL }

data class AgentIntentEvidence(
    val intent: AgentIntent,
    val polarity: AgentLabelPolarity,
    val explicit: Boolean,
    val locked: Boolean,
    val source: AgentLabelSource
)

data class AgentTargetEvidence(
    val target: AgentQueryTarget,
    val polarity: AgentLabelPolarity,
    val explicit: Boolean,
    val locked: Boolean,
    val source: AgentLabelSource
)

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
    val requiresContextCompletion: Boolean = false,
    val unresolvedSafetyNegation: Boolean = false,
    val intentEvidence: Set<AgentIntentEvidence> = emptySet(),
    val targetEvidence: Set<AgentTargetEvidence> = emptySet()
)

data class ParsedAgentRoute(
    val intent: AgentIntent,
    val queryTarget: AgentQueryTarget?,
    val keywords: List<String>,
    val intents: Set<AgentIntent> = emptySet()
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
        val annotatedSignals = annotateSignals(normalized)
        val safetySignals = matchSignals(annotatedSignals, safetyTerms)
        val detailSummarySignals = matchSignals(annotatedSignals, detailSummaryTerms)
        val comparisonSignals = matchSignals(annotatedSignals, comparisonTerms)
        val planningSignals = matchSignals(annotatedSignals, planningTerms)
        val catalogSignals = matchSignals(annotatedSignals, catalogTerms, includeAttachedToNegatedAction = false)
        val institutionProjectSignals = matchSignals(annotatedSignals, institutionProjectTerms)
        val doctorSignals = matchSignals(annotatedSignals, doctorTerms)
        val institutionSignals = matchSignals(annotatedSignals, institutionTerms)
        val projectSignals = matchSignals(annotatedSignals, projectTerms)
        val independentInstitutionProjectSignals = matchSignals(
            annotatedSignals,
            institutionProjectTerms,
            includeAttachedToNegatedAction = false
        )
        val independentDoctorSignals = matchSignals(annotatedSignals, doctorTerms, includeAttachedToNegatedAction = false)
        val independentInstitutionSignals = matchSignals(
            annotatedSignals,
            institutionTerms,
            includeAttachedToNegatedAction = false
        )
        val independentProjectSignals = matchSignals(annotatedSignals, projectTerms, includeAttachedToNegatedAction = false)
        val unresolvedReferenceSignals = matchSignals(annotatedSignals, unresolvedReferenceTerms)
        val specificIntentEvidence = setOfNotNull(
            intentEvidence(AgentIntent.SAFETY_SCREENING, safetySignals),
            intentEvidence(AgentIntent.DETAIL_SUMMARY, detailSummarySignals),
            intentEvidence(AgentIntent.COMPARISON, comparisonSignals),
            intentEvidence(AgentIntent.PLANNING, planningSignals)
        )
        val intentEvidence = specificIntentEvidence + if (
            specificIntentEvidence.none { it.polarity == AgentLabelPolarity.POSITIVE }
        ) {
            setOfNotNull(intentEvidence(AgentIntent.CATALOG_QA, catalogSignals))
        } else {
            emptySet()
        }
        val targetEvidence = setOfNotNull(
            targetEvidence(AgentQueryTarget.INSTITUTION_PROJECT, institutionProjectSignals),
            targetEvidence(AgentQueryTarget.DOCTOR, doctorSignals),
            targetEvidence(AgentQueryTarget.INSTITUTION, institutionSignals),
            targetEvidence(AgentQueryTarget.PROJECT, projectSignals)
        )
        val primaryTargetEvidence = setOfNotNull(
            targetEvidence(AgentQueryTarget.INSTITUTION_PROJECT, independentInstitutionProjectSignals),
            targetEvidence(AgentQueryTarget.DOCTOR, independentDoctorSignals),
            targetEvidence(AgentQueryTarget.INSTITUTION, independentInstitutionSignals),
            targetEvidence(AgentQueryTarget.PROJECT, independentProjectSignals)
        )
        val decision = selectPrimary(intentEvidence, primaryTargetEvidence, contextType)
        val intent = decision.intent
        val queryTarget = decision.queryTarget
        val reasons = mutableListOf<String>()
        var score = 0.55

        if (annotatedSignals.ambiguousNegation) {
            reasons += "AMBIGUOUS_NEGATION"
        }
        val targetCount = listOf(
            independentInstitutionProjectSignals.positiveTerms.isNotEmpty(),
            independentDoctorSignals.positiveTerms.isNotEmpty(),
            independentInstitutionSignals.positiveTerms.isNotEmpty(),
            independentProjectSignals.positiveTerms.isNotEmpty()
        ).count { it }
        if (targetCount > 1 && independentInstitutionProjectSignals.positiveTerms.isEmpty()) {
            score -= 0.20
            reasons += "CONFLICTING_CURRENT_TARGETS"
        }
        if (unresolvedReferenceSignals.positiveTerms.isNotEmpty()) {
            score -= 0.20
            reasons += "UNRESOLVED_CURRENT_REFERENCE"
        }
        val constraintCount = constraintSignalGroups.count { terms ->
            matchSignals(annotatedSignals, terms).let { signals ->
                signals.positiveTerms.isNotEmpty() || signals.negatedTerms.isNotEmpty()
            }
        }
        if (constraintCount >= 2 && queryTarget == null) {
            score -= 0.20
            reasons += "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET"
        }
        if (intent == AgentIntent.GENERAL_CHAT && matchSignals(annotatedSignals, aestheticConcernTerms).positiveTerms.isNotEmpty()) {
            score -= 0.30
            reasons += "UNCLASSIFIED_AESTHETIC_REQUEST"
        }

        val selectedIntentEvidence = intentEvidence.singleOrNull { it.intent == intent }
        val selectedTargetEvidence = primaryTargetEvidence.singleOrNull { it.target == queryTarget }
        val unresolvedSafetyNegation = intentEvidence
            .singleOrNull { it.intent == AgentIntent.SAFETY_SCREENING }
            ?.polarity == AgentLabelPolarity.UNCERTAIN
        val explicitIntent = selectedIntentEvidence?.locked == true
        val explicitQueryTarget = selectedTargetEvidence?.locked == true
        if (explicitIntent) score += 0.20
        if (queryTarget != null) score += 0.15
        if (contextType.uppercase() in detailContextTypes) score += 0.10

        val confidence = score.coerceIn(0.0, 1.0)
        val requiresContextCompletion = (intent in intentsRequiringTarget && queryTarget == null) ||
            "UNRESOLVED_CURRENT_REFERENCE" in reasons ||
            "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET" in reasons
        val needsLlm = requiresLlmParsing(intent, confidence, reasons, requiresContextCompletion)
        return AgentRouteAssessment(
            decision = decision,
            confidence = confidence,
            ambiguityReasons = reasons.distinct(),
            requiresLlmParsing = needsLlm,
            explicitIntent = explicitIntent,
            explicitQueryTarget = explicitQueryTarget,
            requiresContextCompletion = requiresContextCompletion,
            unresolvedSafetyNegation = unresolvedSafetyNegation,
            intentEvidence = intentEvidence,
            targetEvidence = targetEvidence
        )
    }

    fun supplementWithContext(
        current: AgentRouteAssessment,
        contextDecisions: List<AgentIntentDecision>,
        contextAmbiguityReasons: List<String> = emptyList()
    ): AgentRouteAssessment {
        if (current.decision.intent == AgentIntent.SAFETY_SCREENING) return current

        val usableContexts = contextDecisions.filter { candidate ->
            candidate.intent != AgentIntent.SAFETY_SCREENING && (
                candidate.queryTarget != null ||
                    (!current.explicitIntent &&
                        current.decision.intent == AgentIntent.GENERAL_CHAT &&
                        candidate.intent != AgentIntent.GENERAL_CHAT)
                )
        }
        if (usableContexts.isEmpty()) return current
        val context = usableContexts.last()
        val currentDecision = current.decision
        val targetConflict = current.explicitQueryTarget && usableContexts.any { candidate ->
            candidate.queryTarget != null && candidate.queryTarget != currentDecision.queryTarget
        }
        val contextCandidateConflict = usableContexts.mapNotNull { it.queryTarget }.distinct().size > 1 ||
            (!current.explicitIntent && currentDecision.intent == AgentIntent.GENERAL_CHAT &&
                usableContexts.map { it.intent }.filter { it != AgentIntent.GENERAL_CHAT }.distinct().size > 1)
        val uncertainContext = contextAmbiguityReasons.isNotEmpty()
        val contextIntentEvidence = usableContexts.mapTo(mutableSetOf()) { candidate ->
            AgentIntentEvidence(
                intent = candidate.intent,
                polarity = AgentLabelPolarity.POSITIVE,
                explicit = false,
                locked = false,
                source = AgentLabelSource.CONTEXT
            )
        }
        val contextTargetEvidence = usableContexts.mapNotNullTo(mutableSetOf()) { candidate ->
            candidate.queryTarget?.let { target ->
                AgentTargetEvidence(
                    target = target,
                    polarity = AgentLabelPolarity.POSITIVE,
                    explicit = false,
                    locked = false,
                    source = AgentLabelSource.CONTEXT
                )
            }
        }
        val mergedIntentEvidence = mergeIntentEvidence(currentIntentEvidence(current), contextIntentEvidence)
        val mergedTargetEvidence = mergeTargetEvidence(currentTargetEvidence(current), contextTargetEvidence)
        val resolvedDecision = selectPrimary(
            intentEvidence = mergedIntentEvidence,
            targetEvidence = mergedTargetEvidence,
            contextType = "GENERAL",
            preferredIntent = currentDecision.intent.takeIf { it != AgentIntent.GENERAL_CHAT } ?: context.intent,
            preferredTarget = currentDecision.queryTarget ?: context.queryTarget
        )
        val resolvedIntent = resolvedDecision.intent
        val resolvedTarget = resolvedDecision.queryTarget
        val contextFilled = resolvedIntent != currentDecision.intent || resolvedTarget != currentDecision.queryTarget
        val resolvedMissingTargetConstraint = "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET" in current.ambiguityReasons &&
            resolvedTarget != null
        val reasons = current.ambiguityReasons
            .filterNot { it == "MULTIPLE_CONSTRAINTS_WITHOUT_TARGET" && resolvedTarget != null }
            .let {
                if (targetConflict || contextCandidateConflict || uncertainContext) {
                    it + contextAmbiguityReasons.map { reason -> "CONTEXT_$reason" } + "CONFLICTING_CONTEXT"
                } else {
                    it
                }
            }
            .distinct()
        val confidence = (current.confidence +
            (if (contextFilled) 0.10 else 0.0) +
            (if (resolvedMissingTargetConstraint) 0.20 else 0.0) -
            (if (targetConflict || contextCandidateConflict || uncertainContext) 0.20 else 0.0))
            .coerceIn(0.0, 1.0)
        val requiresContextCompletion = (resolvedIntent in intentsRequiringTarget && resolvedTarget == null) ||
            "UNRESOLVED_CURRENT_REFERENCE" in reasons
        return current.copy(
            decision = resolvedDecision,
            confidence = confidence,
            ambiguityReasons = reasons,
            requiresLlmParsing = targetConflict || contextCandidateConflict || uncertainContext || requiresLlmParsing(
                resolvedIntent,
                confidence,
                reasons,
                requiresContextCompletion
            ),
            requiresContextCompletion = requiresContextCompletion,
            intentEvidence = mergedIntentEvidence,
            targetEvidence = mergedTargetEvidence
        )
    }

    fun mergeParsedRoute(
        local: AgentRouteAssessment,
        parsed: ParsedAgentRoute?
    ): AgentIntentDecision {
        if (parsed == null || local.decision.intent == AgentIntent.SAFETY_SCREENING) return local.decision

        val parsedIntents = parsed.intents + parsed.intent
        val modelIntentEvidence = parsedIntents.mapTo(mutableSetOf()) { intent ->
            AgentIntentEvidence(
                intent = intent,
                polarity = AgentLabelPolarity.POSITIVE,
                explicit = false,
                locked = false,
                source = AgentLabelSource.MODEL
            )
        }
        val modelTargetEvidence = parsed.queryTarget?.let { target ->
            setOf(
                AgentTargetEvidence(
                    target = target,
                    polarity = AgentLabelPolarity.POSITIVE,
                    explicit = false,
                    locked = false,
                    source = AgentLabelSource.MODEL
                )
            )
        }.orEmpty()
        val mergedIntentEvidence = mergeIntentEvidence(currentIntentEvidence(local), modelIntentEvidence)
        val mergedTargetEvidence = mergeTargetEvidence(currentTargetEvidence(local), modelTargetEvidence)
        val safetyUpgrade = local.unresolvedSafetyNegation && AgentIntent.SAFETY_SCREENING in parsedIntents
        val decision = selectPrimary(
            intentEvidence = mergedIntentEvidence,
            targetEvidence = mergedTargetEvidence,
            contextType = "GENERAL",
            preferredIntent = when {
                safetyUpgrade -> AgentIntent.SAFETY_SCREENING
                local.unresolvedSafetyNegation || local.explicitIntent -> local.decision.intent
                else -> parsed.intent
            },
            preferredTarget = if (local.explicitQueryTarget) local.decision.queryTarget else parsed.queryTarget
        )
        return if (decision.intent == AgentIntent.SAFETY_SCREENING) {
            validatedDecision(AgentIntent.SAFETY_SCREENING, null)
        } else {
            decision
        }
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

    @Suppress("UNUSED_PARAMETER")
    private fun selectPrimary(
        intentEvidence: Set<AgentIntentEvidence>,
        targetEvidence: Set<AgentTargetEvidence>,
        contextType: String,
        preferredIntent: AgentIntent? = null,
        preferredTarget: AgentQueryTarget? = null
    ): AgentIntentDecision {
        val positiveIntents = intentEvidence
            .filter { it.polarity == AgentLabelPolarity.POSITIVE }
            .mapTo(mutableSetOf()) { it.intent }
        val intent = when {
            preferredIntent in positiveIntents -> preferredIntent!!
            AgentIntent.SAFETY_SCREENING in positiveIntents -> AgentIntent.SAFETY_SCREENING
            AgentIntent.DETAIL_SUMMARY in positiveIntents -> AgentIntent.DETAIL_SUMMARY
            AgentIntent.COMPARISON in positiveIntents -> AgentIntent.COMPARISON
            AgentIntent.PLANNING in positiveIntents -> AgentIntent.PLANNING
            AgentIntent.CATALOG_QA in positiveIntents -> AgentIntent.CATALOG_QA
            else -> AgentIntent.GENERAL_CHAT
        }
        val positiveTargets = targetEvidence
            .filter { it.polarity == AgentLabelPolarity.POSITIVE }
            .mapTo(mutableSetOf()) { it.target }
        val target = when {
            preferredTarget in positiveTargets -> preferredTarget
            AgentQueryTarget.INSTITUTION_PROJECT in positiveTargets -> AgentQueryTarget.INSTITUTION_PROJECT
            AgentQueryTarget.DOCTOR in positiveTargets -> AgentQueryTarget.DOCTOR
            AgentQueryTarget.INSTITUTION in positiveTargets -> AgentQueryTarget.INSTITUTION
            AgentQueryTarget.PROJECT in positiveTargets -> AgentQueryTarget.PROJECT
            else -> null
        }
        return validatedDecision(intent, target)
    }

    private fun currentIntentEvidence(assessment: AgentRouteAssessment): Set<AgentIntentEvidence> {
        if (!assessment.explicitIntent || assessment.intentEvidence.any { it.intent == assessment.decision.intent }) {
            return assessment.intentEvidence
        }
        return assessment.intentEvidence + AgentIntentEvidence(
            intent = assessment.decision.intent,
            polarity = AgentLabelPolarity.POSITIVE,
            explicit = true,
            locked = true,
            source = AgentLabelSource.CURRENT
        )
    }

    private fun currentTargetEvidence(assessment: AgentRouteAssessment): Set<AgentTargetEvidence> {
        val target = assessment.decision.queryTarget ?: return assessment.targetEvidence
        if (!assessment.explicitQueryTarget || assessment.targetEvidence.any { it.target == target }) {
            return assessment.targetEvidence
        }
        return assessment.targetEvidence + AgentTargetEvidence(
            target = target,
            polarity = AgentLabelPolarity.POSITIVE,
            explicit = true,
            locked = true,
            source = AgentLabelSource.CURRENT
        )
    }

    private fun mergeIntentEvidence(
        current: Set<AgentIntentEvidence>,
        incoming: Set<AgentIntentEvidence>
    ): Set<AgentIntentEvidence> {
        val merged = current.associateByTo(linkedMapOf()) { it.intent }
        incoming.forEach { candidate ->
            val existing = merged[candidate.intent]
            if (existing == null || (!existing.locked && existing.polarity == AgentLabelPolarity.UNCERTAIN)) {
                merged[candidate.intent] = candidate
            }
        }
        return merged.values.toSet()
    }

    private fun mergeTargetEvidence(
        current: Set<AgentTargetEvidence>,
        incoming: Set<AgentTargetEvidence>
    ): Set<AgentTargetEvidence> {
        val merged = current.associateByTo(linkedMapOf()) { it.target }
        incoming.forEach { candidate ->
            val existing = merged[candidate.target]
            if (existing == null || (!existing.locked && existing.polarity == AgentLabelPolarity.UNCERTAIN)) {
                merged[candidate.target] = candidate
            }
        }
        return merged.values.toSet()
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

    private enum class SignalPolarity { POSITIVE, NEGATED, AMBIGUOUS }

    private data class AnnotatedSignal(
        val term: String,
        val polarity: SignalPolarity,
        val attachedToNegatedAction: Boolean
    )

    private data class AnnotatedSignals(
        val matches: List<AnnotatedSignal>,
        val ambiguousNegation: Boolean
    )

    private enum class SentenceConstraint { NEGATED, UNCERTAIN }

    private fun annotateSignals(query: String): AnnotatedSignals {
        val normalizedQuery = normalize(query)
        val normalizedTerms = routingTerms.map(::normalize).distinct().sortedByDescending(String::length)
        val actionTerms = intentActionTerms.map(::normalize).toSet()
        val stateTerms = (safetyTerms + institutionProjectTerms + doctorTerms + institutionTerms + projectTerms)
            .map(::normalize)
            .toSet()
        val targetTerms = (institutionProjectTerms + doctorTerms + institutionTerms + projectTerms)
            .map(::normalize)
            .toSet()
        val annotated = mutableListOf<AnnotatedSignal>()
        var ambiguousNegation = false

        clauseRanges(normalizedQuery).forEach { range ->
            val clause = normalizedQuery.substring(range)
            val matches = normalizedTerms.flatMap { term ->
                termIndexes(clause, term).map { index -> SignalMatch(term, index) }
            }
            val constraints = mutableMapOf<SignalMatch, SentenceConstraint>()
            val attachedToNegatedAction = mutableSetOf<SignalMatch>()
            val uncertaintyPhrases = uncertaintyPhraseIndexes(clause)
            uncertaintyPhrases.forEach { phrase ->
                applyConstraint(
                    constraints,
                    nearestMatches(phrase.index + phrase.term.length, matches, stateTerms),
                    SentenceConstraint.UNCERTAIN
                )
            }
            negatorIndexes(clause)
                .filterNot { negator -> uncertaintyPhrases.any { phrase ->
                    rangesOverlap(negator.index, negator.term.length, phrase.index, phrase.term.length)
                } }
                .forEach { negator ->
                    val constrainedMatches = nearestMatches(
                        negator.index + negator.term.length,
                        matches,
                        actionTerms + stateTerms
                    )
                    applyConstraint(
                        constraints,
                        constrainedMatches,
                        SentenceConstraint.NEGATED
                    )
                    constrainedMatches.filter { it.term in actionTerms }.forEach { action ->
                        val nextActionIndex = matches
                            .asSequence()
                            .filter { candidate ->
                                candidate.term in actionTerms && candidate.index >= action.index + action.term.length
                            }
                            .minOfOrNull { it.index }
                        attachedToNegatedAction += matches.filter { target ->
                            target.term in targetTerms &&
                                target.index >= action.index + action.term.length &&
                                (nextActionIndex == null || target.index < nextActionIndex)
                        }
                    }
                }
            matches.forEach { match ->
                val polarity = when (constraints[match]) {
                    SentenceConstraint.NEGATED -> SignalPolarity.NEGATED
                    SentenceConstraint.UNCERTAIN -> SignalPolarity.AMBIGUOUS
                    null -> SignalPolarity.POSITIVE
                }
                if (polarity == SignalPolarity.AMBIGUOUS) ambiguousNegation = true
                annotated += AnnotatedSignal(
                    term = match.term,
                    polarity = polarity,
                    attachedToNegatedAction = match in attachedToNegatedAction
                )
            }
        }
        return AnnotatedSignals(annotated, ambiguousNegation)
    }

    private fun nearestMatches(
        after: Int,
        matches: List<SignalMatch>,
        compatibleTerms: Set<String>
    ): List<SignalMatch> {
        val candidates = matches.filter { it.index >= after && it.term in compatibleTerms }
        val firstIndex = candidates.minOfOrNull { it.index } ?: return emptyList()
        return candidates.filter { it.index == firstIndex }
    }

    private fun applyConstraint(
        constraints: MutableMap<SignalMatch, SentenceConstraint>,
        matches: List<SignalMatch>,
        constraint: SentenceConstraint
    ) {
        val resolvedConstraint = if (matches.size > 1) SentenceConstraint.UNCERTAIN else constraint
        matches.forEach { match ->
            constraints[match] = if (constraints[match] == null) resolvedConstraint else SentenceConstraint.UNCERTAIN
        }
    }

    private fun matchSignals(
        annotatedSignals: AnnotatedSignals,
        terms: List<String>,
        includeAttachedToNegatedAction: Boolean = true
    ): MatchedSignals {
        val normalizedTerms = terms.map(::normalize).toSet()
        val matches = annotatedSignals.matches.filter { signal ->
            signal.term in normalizedTerms && (includeAttachedToNegatedAction || !signal.attachedToNegatedAction)
        }
        return MatchedSignals(
            positiveTerms = matches.filter { it.polarity == SignalPolarity.POSITIVE }.map { it.term }.distinct(),
            negatedTerms = matches.filter { it.polarity == SignalPolarity.NEGATED }.map { it.term }.distinct(),
            ambiguousNegation = matches.any { it.polarity == SignalPolarity.AMBIGUOUS }
        )
    }

    private fun intentEvidence(intent: AgentIntent, signals: MatchedSignals): AgentIntentEvidence? =
        signals.toPolarity()?.let { polarity ->
            AgentIntentEvidence(
                intent = intent,
                polarity = polarity,
                explicit = true,
                locked = polarity != AgentLabelPolarity.UNCERTAIN,
                source = AgentLabelSource.CURRENT
            )
        }

    private fun targetEvidence(target: AgentQueryTarget, signals: MatchedSignals): AgentTargetEvidence? =
        signals.toPolarity()?.let { polarity ->
            AgentTargetEvidence(
                target = target,
                polarity = polarity,
                explicit = true,
                locked = polarity != AgentLabelPolarity.UNCERTAIN,
                source = AgentLabelSource.CURRENT
            )
        }

    private fun MatchedSignals.toPolarity(): AgentLabelPolarity? = when {
        positiveTerms.isNotEmpty() -> AgentLabelPolarity.POSITIVE
        ambiguousNegation -> AgentLabelPolarity.UNCERTAIN
        negatedTerms.isNotEmpty() -> AgentLabelPolarity.NEGATIVE
        else -> null
    }

    private fun normalize(query: String): String = query
        .replace(Regex("[’‘ʼ]"), "'")
        .lowercase()
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun clauseRanges(query: String): List<IntRange> {
        val delimiter = Regex("[,.;!?，。；！？]|同时|但是|而是|因为|但|只|\\bwhile\\b|\\bbut\\b|\\bbecause\\b|\\binstead\\b|\\bjust\\b")
        val ranges = mutableListOf<IntRange>()
        var start = 0
        delimiter.findAll(query).forEach { match ->
            if (start < match.range.first) ranges += start until match.range.first
            start = match.range.last + 1
        }
        if (start < query.length) ranges += start until query.length
        return ranges
    }

    private fun termIndexes(text: String, term: String): List<Int> {
        val escaped = Regex.escape(term)
        val pattern = if (term.any { it in 'a'..'z' }) {
            Regex("(?<![a-z0-9])$escaped(?![a-z0-9])")
        } else {
            Regex(escaped)
        }
        return pattern.findAll(text).map { it.range.first }.toList()
    }

    private fun negatorIndexes(text: String): List<SignalMatch> {
        val negators = listOf(
            "don't", "do not", "doesn't", "does not", "isn't", "is not", "without", "not", "no",
            "不要", "不想", "不是", "没有", "无需", "别", "不", "没"
        )
        val matches = negators.sortedByDescending(String::length).flatMap { term ->
            termIndexes(text, term).map { index -> SignalMatch(term, index) }
        }.sortedBy { it.index }.fold(mutableListOf<SignalMatch>()) { selected, match ->
            if (selected.none { rangesOverlap(it.index, it.term.length, match.index, match.term.length) }) selected += match
            selected
        }
        return matches
    }

    private fun uncertaintyPhraseIndexes(text: String): List<SignalMatch> =
        uncertaintyPhrases.sortedByDescending(String::length).flatMap { phrase ->
            termIndexes(text, phrase).map { index -> SignalMatch(phrase, index) }
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
        val institutionProjectTerms = listOf(
            "机构项目", "机构套餐", "项目套餐", "套餐", "报价",
            "institution project", "institution projects", "clinic package", "clinic packages", "package", "packages", "offering", "offerings"
        )
        val doctorTerms = listOf("医生", "医师", "大夫", "doctor", "doctors", "surgeon", "surgeons", "physician", "physicians")
        val institutionTerms = listOf(
            "机构", "医院", "诊所", "门诊部", "clinic", "clinics", "hospital", "hospitals", "institution", "institutions"
        )
        val projectTerms = listOf("项目", "治疗", "术式", "procedure", "procedures", "treatment", "treatments")
        val detailSummaryTerms = listOf(
            "总结", "概括", "介绍", "简介", "详情", "当前页面", "当前详情", "这个页面", "这页", "简要", "简短",
            "summary", "summarize", "overview", "introduce", "about this", "current page", "this page"
        )
        val catalogActionTerms = listOf("推荐", "recommend")
        val intentActionTerms = comparisonTerms + planningTerms + detailSummaryTerms + catalogActionTerms
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
            "clinic", "clinics", "hospital", "hospitals", "doctor", "doctors", "surgeon", "surgeons",
            "treatment", "treatments", "procedure", "procedures", "price", "prices", "cost", "costs", "package", "packages",
            "recommend", "appointment", "ipl", "aopt", "dpl", "laser", "botox", "filler", "thermage",
            "skin", "dull", "pores", "texture", "spots", "pigmentation", "acne", "wrinkle", "aging", "sagging", "hollow"
        )
        val routingTerms = safetyTerms + detailSummaryTerms + comparisonTerms + planningTerms + catalogTerms +
            institutionProjectTerms + doctorTerms + institutionTerms + projectTerms + unresolvedReferenceTerms +
            constraintSignalGroups.flatten() + aestheticConcernTerms
        val uncertaintyPhrases = listOf(
            "don't know if", "do not know whether", "not sure if", "not sure whether", "不确定是否", "不知道是否"
        )
    }
}
