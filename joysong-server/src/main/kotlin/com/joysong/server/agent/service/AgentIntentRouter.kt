package com.joysong.server.agent.service

import org.springframework.stereotype.Service

enum class AgentIntent {
    GENERAL_CHAT,
    CATALOG_QA,
    COMPARISON,
    PLANNING,
    DETAIL_SUMMARY,
    HUMAN_CONSULTATION,
    SAFETY_SCREENING
}

enum class AgentQueryTarget { INSTITUTION, DOCTOR, PROJECT, INSTITUTION_PROJECT }

enum class AgentNextAction { NONE, SHOW_CATALOG, START_PLANNING, SELECT_INSTITUTION, COMPLETE_SAFETY_SCREENING }

enum class AgentLabelPolarity { POSITIVE, NEGATIVE, UNCERTAIN }

enum class AgentLabelSource { CURRENT, CONTEXT, MODEL }

enum class ContextCompletionMode { NONE, DETERMINISTIC_FOLLOW_UP, BARE_DEICTIC }

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
    val targetEvidence: Set<AgentTargetEvidence> = emptySet(),
    val contextType: String = "GENERAL",
    val contextResolvedQueryTarget: Boolean = false,
    val contextCompletionMode: ContextCompletionMode = ContextCompletionMode.NONE
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
        val decision = completeDetailContext(currentAssessment, contextType)
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
        val humanConsultationSignals = matchSignals(annotatedSignals, humanConsultationTerms)
        val detailSummarySignals = matchSignals(annotatedSignals, detailSummaryTerms)
        val comparisonSignals = matchSignals(annotatedSignals, comparisonTerms)
        val planningSignals = matchSignals(annotatedSignals, planningTerms)
        val catalogActionSignals = matchSignals(
            annotatedSignals,
            catalogActionTerms,
            includeAttachedToNegatedAction = false
        )
        val genericCatalogSignals = matchSignals(
            annotatedSignals,
            catalogTerms,
            includeAttachedToNegatedAction = false
        )
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
        val alternativeEntitySignals = matchSignals(annotatedSignals, alternativeEntityTerms)
        val negatedCurrentReference = hasNegatedCurrentReference(normalized)
        val alternativeEntityRequest = alternativeEntitySignals.positiveTerms.isNotEmpty() &&
            (hasAlternativeEntityRequest(normalized) || hasEntitySwitchRequest(normalized))
        val socialInterjection = isSocialInterjection(normalized)
        val explicitTopicBoundary = hasExplicitTopicBoundary(normalized)
        val specificIntentEvidence = setOfNotNull(
            intentEvidence(AgentIntent.SAFETY_SCREENING, safetySignals),
            intentEvidence(AgentIntent.HUMAN_CONSULTATION, humanConsultationSignals),
            intentEvidence(AgentIntent.DETAIL_SUMMARY, detailSummarySignals),
            intentEvidence(AgentIntent.COMPARISON, comparisonSignals),
            intentEvidence(AgentIntent.PLANNING, planningSignals)
        )
        val catalogEvidence = intentEvidence(AgentIntent.CATALOG_QA, catalogActionSignals)
            ?: genericCatalogSignals.takeIf {
                specificIntentEvidence.none { evidence -> evidence.polarity == AgentLabelPolarity.POSITIVE }
            }?.let { signals -> intentEvidence(AgentIntent.CATALOG_QA, signals) }
        val intentEvidence = specificIntentEvidence + setOfNotNull(catalogEvidence)
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
        val contextTarget = runCatching { AgentQueryTarget.valueOf(contextType.uppercase()) }.getOrNull()
        val positiveTargets = primaryTargetEvidence.filter { it.polarity == AgentLabelPolarity.POSITIVE }
            .mapTo(mutableSetOf()) { it.target }
        val alternativeTarget = alternativeRequestTarget(normalized).takeIf { alternativeEntityRequest }
        val scopedResultTarget = deicticScopedResultTarget(normalized, positiveTargets)
        val selectedDecision = selectPrimary(
            intentEvidence,
            primaryTargetEvidence,
            contextType,
            preferredTarget = alternativeTarget ?: scopedResultTarget
        )
        val decision = when {
            selectedDecision.intent == AgentIntent.SAFETY_SCREENING -> selectedDecision
            selectedDecision.intent == AgentIntent.HUMAN_CONSULTATION -> selectedDecision
            alternativeEntityRequest -> validatedDecision(
                AgentIntent.CATALOG_QA,
                alternativeTarget ?: selectedDecision.queryTarget ?: contextTarget
            )
            explicitTopicBoundary && selectedDecision.queryTarget == null &&
                (selectedDecision.intent == AgentIntent.GENERAL_CHAT ||
                    (selectedDecision.intent == AgentIntent.CATALOG_QA &&
                        catalogActionSignals.positiveTerms.isEmpty())) ->
                validatedDecision(AgentIntent.GENERAL_CHAT, null)
            scopedResultTarget != null -> validatedDecision(selectedDecision.intent, scopedResultTarget)
            else -> selectedDecision
        }
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
        if (
            targetCount > 1 && independentInstitutionProjectSignals.positiveTerms.isEmpty() &&
            alternativeTarget == null && scopedResultTarget == null
        ) {
            score -= 0.20
            reasons += "CONFLICTING_CURRENT_TARGETS"
        }
        if (negatedCurrentReference) {
            reasons += "NEGATED_CURRENT_REFERENCE"
        } else if (unresolvedReferenceSignals.positiveTerms.isNotEmpty()) {
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
        val standaloneConstraintFollowUp = constraintCount > 0 && queryTarget == null &&
            isStandaloneConstraintQuery(normalized)
        if (standaloneConstraintFollowUp) {
            reasons += "CONSTRAINT_WITHOUT_TARGET"
        }
        if (intent == AgentIntent.GENERAL_CHAT && matchSignals(annotatedSignals, aestheticConcernTerms).positiveTerms.isNotEmpty()) {
            score -= 0.30
            reasons += "UNCLASSIFIED_AESTHETIC_REQUEST"
        }
        val sameTargetQuestion = intent == AgentIntent.CATALOG_QA && queryTarget == contextTarget &&
            catalogActionSignals.positiveTerms.isEmpty() && !alternativeEntityRequest
        val detailContextFollowUp = !negatedCurrentReference && contextTarget != null &&
            ((intent == AgentIntent.GENERAL_CHAT && standaloneConstraintFollowUp) || sameTargetQuestion)
        if (detailContextFollowUp) reasons += "DETAIL_CONTEXT_FOLLOW_UP"
        if (alternativeEntityRequest) reasons += "ALTERNATIVE_ENTITY_REQUEST"
        if (intent == AgentIntent.GENERAL_CHAT && socialInterjection) reasons += "SOCIAL_INTERJECTION"
        if (explicitTopicBoundary) reasons += "EXPLICIT_TOPIC_BOUNDARY"

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
        val hasUnlockedUncertainEvidence = hasUnlockedUncertainEvidence(intentEvidence, targetEvidence)
        val detailSessionMissingTarget = intent != AgentIntent.SAFETY_SCREENING &&
            contextType.uppercase() in detailContextTypes && queryTarget == null &&
            (intent != AgentIntent.GENERAL_CHAT || detailContextFollowUp)
        val requiresContextCompletion = !explicitTopicBoundary && (
            hasUnlockedUncertainEvidence || detailSessionMissingTarget ||
                (intent in intentsRequiringTarget && queryTarget == null) ||
                ("UNRESOLVED_CURRENT_REFERENCE" in reasons && scopedResultTarget == null) ||
                "CONSTRAINT_WITHOUT_TARGET" in reasons
            )
        val parsingReasons = if (scopedResultTarget == null) {
            reasons
        } else {
            reasons.filterNot { it == "UNRESOLVED_CURRENT_REFERENCE" }
        }
        val boundaryOnlyGeneral = explicitTopicBoundary &&
            intent == AgentIntent.GENERAL_CHAT && queryTarget == null &&
            !hasUnlockedUncertainEvidence && !unresolvedSafetyNegation &&
            parsingReasons.none {
                it in setOf(
                    "AMBIGUOUS_NEGATION",
                    "CONFLICTING_CURRENT_TARGETS",
                    "UNRESOLVED_CURRENT_REFERENCE",
                    "UNCLASSIFIED_AESTHETIC_REQUEST"
                )
            }
        val needsLlm = !boundaryOnlyGeneral &&
            requiresLlmParsing(intent, confidence, parsingReasons, requiresContextCompletion)
        val contextCompletionMode = when {
            queryTarget != null -> ContextCompletionMode.NONE
            standaloneConstraintFollowUp || "DETAIL_CONTEXT_FOLLOW_UP" in reasons ->
                ContextCompletionMode.DETERMINISTIC_FOLLOW_UP
            "UNRESOLVED_CURRENT_REFERENCE" in reasons && constraintCount == 0 &&
                intentEvidence.none { it.polarity == AgentLabelPolarity.POSITIVE } &&
                primaryTargetEvidence.none { it.polarity == AgentLabelPolarity.POSITIVE } &&
                !negatedCurrentReference && !alternativeEntityRequest && !explicitTopicBoundary ->
                ContextCompletionMode.BARE_DEICTIC
            else -> ContextCompletionMode.NONE
        }
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
            targetEvidence = targetEvidence,
            contextType = contextType,
            contextCompletionMode = contextCompletionMode
        )
    }

    fun supplementWithContext(
        current: AgentRouteAssessment,
        contextDecisions: List<AgentIntentDecision>,
        contextAmbiguityReasons: List<String> = emptyList()
    ): AgentRouteAssessment {
        if (current.decision.intent == AgentIntent.SAFETY_SCREENING) return current

        val currentAllowsHumanContext = current.intentEvidence.any {
            it.intent == AgentIntent.HUMAN_CONSULTATION &&
                it.polarity != AgentLabelPolarity.NEGATIVE
        } || "UNRESOLVED_CURRENT_REFERENCE" in current.ambiguityReasons
        val usableContexts = contextDecisions.filter { candidate ->
            candidate.intent != AgentIntent.SAFETY_SCREENING &&
                (candidate.intent != AgentIntent.HUMAN_CONSULTATION || currentAllowsHumanContext) && (
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
            contextType = current.contextType,
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
        val requiresContextCompletion = hasUnlockedUncertainEvidence(mergedIntentEvidence, mergedTargetEvidence) ||
            (resolvedIntent != AgentIntent.SAFETY_SCREENING &&
                current.contextType.uppercase() in detailContextTypes && resolvedTarget == null) ||
            (resolvedIntent in intentsRequiringTarget && resolvedTarget == null) ||
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
            targetEvidence = mergedTargetEvidence,
            contextResolvedQueryTarget = current.contextResolvedQueryTarget ||
                (contextFilled && !targetConflict && !contextCandidateConflict && !uncertainContext &&
                    currentDecision.queryTarget == null && resolvedTarget != null)
        )
    }

    fun mergeParsedRoute(
        local: AgentRouteAssessment,
        parsed: ParsedAgentRoute?
    ): AgentIntentDecision {
        if (parsed == null || local.decision.intent == AgentIntent.SAFETY_SCREENING) return local.decision
        if (!isCompatibleParsedRoute(local, parsed)) return local.decision

        val parsedIntents = parsed.intents + parsed.intent
        if (AgentIntent.SAFETY_SCREENING in parsedIntents) {
            return validatedDecision(AgentIntent.SAFETY_SCREENING, null)
        }
        if (allowsHumanContextOverride(local) && AgentIntent.HUMAN_CONSULTATION in parsedIntents) {
            return validatedDecision(AgentIntent.HUMAN_CONSULTATION, AgentQueryTarget.INSTITUTION)
        }
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
            contextType = local.contextType,
            preferredIntent = when {
                safetyUpgrade -> AgentIntent.SAFETY_SCREENING
                local.unresolvedSafetyNegation || local.explicitIntent ->
                    local.decision.intent
                else -> parsed.intent
            },
            preferredTarget = if (local.explicitQueryTarget || local.contextResolvedQueryTarget) {
                local.decision.queryTarget
            } else {
                parsed.queryTarget
            }
        )
        return if (decision.intent == AgentIntent.SAFETY_SCREENING) {
            validatedDecision(AgentIntent.SAFETY_SCREENING, null)
        } else {
            decision
        }
    }

    fun allowsHumanContextOverride(local: AgentRouteAssessment): Boolean =
        local.contextResolvedQueryTarget &&
            local.contextCompletionMode == ContextCompletionMode.BARE_DEICTIC &&
            !local.explicitIntent && !local.explicitQueryTarget

    fun isCompatibleParsedRoute(local: AgentRouteAssessment, parsed: ParsedAgentRoute): Boolean {
        val parsedIntents = parsed.intents + parsed.intent
        if (
            AgentIntent.HUMAN_CONSULTATION in parsedIntents &&
            parsed.queryTarget != null && parsed.queryTarget != AgentQueryTarget.INSTITUTION
        ) return false
        if (AgentIntent.SAFETY_SCREENING in parsedIntents) return true
        if (
            local.explicitIntent && local.decision.intent != AgentIntent.HUMAN_CONSULTATION &&
            AgentIntent.HUMAN_CONSULTATION in parsedIntents
        ) return false
        if (parsed.intent in setOf(AgentIntent.GENERAL_CHAT, AgentIntent.SAFETY_SCREENING) && parsed.queryTarget != null) {
            return false
        }
        if (local.explicitIntent && local.decision.intent !in parsedIntents) return false
        val deterministicFollowUpLock = local.contextResolvedQueryTarget &&
            local.contextCompletionMode == ContextCompletionMode.DETERMINISTIC_FOLLOW_UP
        if (deterministicFollowUpLock && parsedIntents.any { it != local.decision.intent }) return false
        val humanOverride = allowsHumanContextOverride(local) &&
            AgentIntent.HUMAN_CONSULTATION in parsedIntents
        if (
            (local.explicitQueryTarget || local.contextResolvedQueryTarget) &&
            effectiveParsedTarget(parsedIntents, parsed.queryTarget) != local.decision.queryTarget &&
            !humanOverride
        ) return false
        if (local.unresolvedSafetyNegation && parsedIntents.none {
                it in setOf(local.decision.intent, AgentIntent.SAFETY_SCREENING)
            }
        ) return false
        return true
    }

    fun validatedDecision(intent: AgentIntent, queryTarget: AgentQueryTarget?): AgentIntentDecision {
        val effectiveTarget = if (intent == AgentIntent.HUMAN_CONSULTATION) {
            AgentQueryTarget.INSTITUTION
        } else {
            queryTarget
        }
        val searchCatalog = intent in setOf(
            AgentIntent.CATALOG_QA,
            AgentIntent.COMPARISON,
            AgentIntent.DETAIL_SUMMARY
        ) || (intent == AgentIntent.PLANNING && effectiveTarget != null)
        return AgentIntentDecision(
            intent = intent,
            queryTarget = effectiveTarget,
            searchCatalog = intent != AgentIntent.SAFETY_SCREENING && searchCatalog,
            nextAction = when (intent) {
                AgentIntent.SAFETY_SCREENING -> AgentNextAction.COMPLETE_SAFETY_SCREENING
                AgentIntent.HUMAN_CONSULTATION -> AgentNextAction.SELECT_INSTITUTION
                AgentIntent.PLANNING -> AgentNextAction.START_PLANNING
                AgentIntent.CATALOG_QA, AgentIntent.COMPARISON, AgentIntent.DETAIL_SUMMARY -> AgentNextAction.SHOW_CATALOG
                AgentIntent.GENERAL_CHAT -> AgentNextAction.NONE
            }
        )
    }

    fun decide(query: String, contextType: String): AgentIntentDecision {
        val currentAssessment = assessCurrent(query, contextType)
        return completeDetailContext(currentAssessment, contextType)
    }

    private fun completeDetailContext(
        current: AgentRouteAssessment,
        contextType: String
    ): AgentIntentDecision {
        val currentDecision = current.decision
        val detailContext = contextType.uppercase() in detailContextTypes
        if (!detailContext || currentDecision.intent == AgentIntent.SAFETY_SCREENING) {
            return currentDecision
        }
        if ("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons) return currentDecision
        val implicitFollowUp = currentDecision.intent == AgentIntent.GENERAL_CHAT &&
            current.ambiguityReasons.any { it in setOf("DETAIL_CONTEXT_FOLLOW_UP", "UNRESOLVED_CURRENT_REFERENCE") }
        if (currentDecision.intent == AgentIntent.GENERAL_CHAT && !implicitFollowUp) return currentDecision
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
        val detailSummaryIsPrimary = AgentIntent.DETAIL_SUMMARY in positiveIntents && (
            contextType.uppercase() in detailContextTypes ||
                positiveIntents.all { it == AgentIntent.DETAIL_SUMMARY }
            )
        val intent = when {
            AgentIntent.SAFETY_SCREENING in positiveIntents -> AgentIntent.SAFETY_SCREENING
            AgentIntent.HUMAN_CONSULTATION in positiveIntents -> AgentIntent.HUMAN_CONSULTATION
            detailSummaryIsPrimary -> AgentIntent.DETAIL_SUMMARY
            preferredIntent in positiveIntents -> preferredIntent!!
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

    private fun hasUnlockedUncertainEvidence(
        intents: Set<AgentIntentEvidence>,
        targets: Set<AgentTargetEvidence>
    ): Boolean = intents.any { !it.locked && it.polarity == AgentLabelPolarity.UNCERTAIN } ||
        targets.any { !it.locked && it.polarity == AgentLabelPolarity.UNCERTAIN }

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

    private enum class SignalFamily { SAFETY_STATE, BUSINESS_ACTION, CATALOG_ACTION, TARGET }

    private fun annotateSignals(query: String): AnnotatedSignals {
        val normalizedQuery = normalize(query)
        val normalizedTerms = routingTerms.map(::normalize).distinct().sortedByDescending(String::length)
        val actionTerms = intentActionTerms.map(::normalize).toSet()
        val stateTerms = (safetyTerms + institutionProjectTerms + doctorTerms + institutionTerms + projectTerms +
            alternativeEntityTerms)
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
                val nearest = nearestMatches(phrase.index + phrase.term.length, matches, stateTerms)
                applyConstraint(
                    constraints,
                    coordinatedMatches(clause, nearest, matches),
                    SentenceConstraint.UNCERTAIN
                )
            }
            negatorIndexes(clause)
                .filterNot { negator -> uncertaintyPhrases.any { phrase ->
                    rangesOverlap(negator.index, negator.term.length, phrase.index, phrase.term.length)
                } }
                .filterNot { negator -> isPriceConfirmationNegator(clause, negator) }
                .forEach { negator ->
                    val constrainedMatches = nearestMatches(
                        negator.index + negator.term.length,
                        matches,
                        actionTerms + stateTerms
                    ).let { nearest -> coordinatedMatches(clause, nearest, matches) }
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

    private fun coordinatedMatches(
        clause: String,
        nearest: List<SignalMatch>,
        matches: List<SignalMatch>
    ): List<SignalMatch> {
        if (nearest.isEmpty()) return emptyList()
        val family = nearest.mapNotNull { signalFamily(it.term) }.distinct().singleOrNull() ?: return nearest
        val coordinated = nearest.toMutableList()
        var previousEnd = nearest.maxOf { it.index + it.term.length }
        val laterGroups = matches
            .filter { it.index >= previousEnd }
            .groupBy { it.index }
            .toSortedMap()

        for ((index, candidates) in laterGroups) {
            val connector = clause.substring(previousEnd, index)
            if (!coordinationConnector.matches(connector)) break
            val sameFamily = candidates.filter { signalFamily(it.term) == family }
            if (sameFamily.isEmpty()) break
            coordinated += sameFamily
            previousEnd = sameFamily.maxOf { it.index + it.term.length }
        }
        return coordinated.distinct()
    }

    private fun signalFamily(term: String): SignalFamily? = when (term) {
        in safetyTerms -> SignalFamily.SAFETY_STATE
        in humanConsultationTerms, in comparisonTerms, in planningTerms, in detailSummaryTerms -> SignalFamily.BUSINESS_ACTION
        in catalogActionTerms -> SignalFamily.CATALOG_ACTION
        in institutionProjectTerms, in doctorTerms, in institutionTerms, in projectTerms -> SignalFamily.TARGET
        else -> null
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
        val overlappingMatches = matches.groupBy { it.index }.any { (_, sameIndex) -> sameIndex.size > 1 }
        val resolvedConstraint = if (overlappingMatches) SentenceConstraint.UNCERTAIN else constraint
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

    private fun hasNegatedCurrentReference(query: String): Boolean =
        negatedCurrentReferencePatterns.any { it.containsMatchIn(query) }

    private fun hasAlternativeEntityRequest(query: String): Boolean =
        alternativeEntityRequestPatterns.any { it.containsMatchIn(query) }

    private fun hasEntitySwitchRequest(query: String): Boolean =
        entitySwitchRequestPatterns.any { it.containsMatchIn(query) }

    private fun hasExplicitTopicBoundary(query: String): Boolean =
        explicitTopicBoundaryPatterns.any { it.containsMatchIn(query) }

    private fun alternativeRequestTarget(query: String): AgentQueryTarget? = when {
        alternativeInstitutionPattern.containsMatchIn(query) -> AgentQueryTarget.INSTITUTION
        alternativeDoctorPattern.containsMatchIn(query) -> AgentQueryTarget.DOCTOR
        alternativeProjectPattern.containsMatchIn(query) -> AgentQueryTarget.PROJECT
        else -> null
    }

    private fun deicticScopedResultTarget(
        query: String,
        positiveTargets: Set<AgentQueryTarget>
    ): AgentQueryTarget? {
        val scopeTarget = deicticScopeTargetPatterns.firstNotNullOfOrNull { (pattern, target) ->
            target.takeIf { pattern.containsMatchIn(query) }
        } ?: return null
        return (positiveTargets - scopeTarget).singleOrNull()
    }

    private fun effectiveParsedTarget(
        parsedIntents: Set<AgentIntent>,
        parsedTarget: AgentQueryTarget?
    ): AgentQueryTarget? = if (AgentIntent.HUMAN_CONSULTATION in parsedIntents) {
        AgentQueryTarget.INSTITUTION
    } else {
        parsedTarget
    }

    private fun isPriceConfirmationNegator(clause: String, negator: SignalMatch): Boolean {
        val tail = clause.substring(negator.index).trim()
        return when (negator.term) {
            "不是" -> priceConfirmationPattern.matches(tail)
            "isn't", "is not" -> englishPriceConfirmationPattern.matches(tail)
            else -> false
        }
    }

    private fun isStandaloneConstraintQuery(query: String): Boolean {
        val withoutConstraints = constraintSignalGroups.flatten()
            .map(::normalize)
            .distinct()
            .sortedByDescending(String::length)
            .fold(query) { remaining, term -> remaining.replace(term, " ") }
        val withoutFillers = constraintOnlyFillerWordsPattern.replace(withoutConstraints, "")
        return constraintOnlyPunctuationPattern.replace(withoutFillers, "").isBlank()
    }

    private fun isSocialInterjection(query: String): Boolean = socialInterjectionPattern.matches(query)

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
        val humanConsultationTerms = listOf(
            "真人咨询", "人工咨询", "真人客服", "人工客服",
            "转人工", "转接真人", "转接咨询师", "联系咨询师", "找咨询师",
            "找真人", "真人顾问", "人工服务",
            "human consultation", "human consultant", "human advisor",
            "real person", "live agent", "manual service",
            "speak to a person", "talk to a person",
            "speak to a specialist", "talk to a specialist",
            "connect me to a person", "transfer me to a consultant"
        )
        val unresolvedReferenceTerms = listOf("这个", "那个", "那这个", "那它", "它呢", "这家", "那家", "这个呢", "那个呢", "this", "that", "what about it", "how about that")
        val alternativeEntityTerms = listOf(
            "其他", "其它", "另外", "别的", "换一家", "换一个", "换个", "换位", "换机构", "换医生", "换项目", "改选",
            "other", "another", "different", "alternative", "alternatives"
        )
        val constraintSignalGroups = listOf(
            listOf("价格", "费用", "多少钱", "价位", "price", "cost"),
            listOf("预算", "budget"),
            listOf("恢复期", "恢复", "downtime", "recovery"),
            listOf("疼痛", "怕痛", "pain"),
            listOf("多久")
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
        val catalogActionTerms = listOf(
            "推荐", "展示", "显示", "查找", "查一下", "找一下", "看看",
            "recommend", "show", "find", "list"
        )
        val intentActionTerms = humanConsultationTerms + comparisonTerms + planningTerms + detailSummaryTerms + catalogActionTerms
        val safetyTerms = listOf(
            "怀孕", "孕期", "备孕", "哺乳", "严重过敏", "过敏史", "瘢痕体质", "疤痕体质",
            "正在吃药", "正在服药", "皮肤感染", "伤口未愈合", "保证效果", "百分百有效",
            "pregnant", "pregnancy", "breastfeeding", "severe allergy", "keloid",
            "taking medication", "skin infection", "guaranteed result", "guarantee", "100% effective", "100% result"
        )
        val catalogTerms = listOf(
            "机构", "医院", "诊所", "医生", "医师", "项目", "价格", "费用", "报价", "套餐", "预约",
            "光子", "嫩肤", "激光", "注射", "玻尿酸", "肉毒", "超声", "射频", "热玛吉",
            "皮肤", "肤质", "暗沉", "毛孔", "粗糙", "斑", "痘", "皱纹", "细纹", "松弛", "下垂", "凹陷", "显老",
            "clinic", "clinics", "hospital", "hospitals", "doctor", "doctors", "surgeon", "surgeons",
            "treatment", "treatments", "procedure", "procedures", "price", "prices", "cost", "costs", "package", "packages",
            "appointment", "ipl", "aopt", "dpl", "laser", "botox", "filler", "thermage",
            "skin", "dull", "pores", "texture", "spots", "pigmentation", "acne", "wrinkle", "aging", "sagging", "hollow"
        )
        val routingTerms = safetyTerms + humanConsultationTerms + detailSummaryTerms + comparisonTerms + planningTerms + catalogActionTerms + catalogTerms +
            institutionProjectTerms + doctorTerms + institutionTerms + projectTerms + unresolvedReferenceTerms +
            alternativeEntityTerms + constraintSignalGroups.flatten() + aestheticConcernTerms
        val uncertaintyPhrases = listOf(
            "don't know if", "do not know whether", "not sure if", "not sure whether", "不确定是否", "不知道是否"
        )
        val coordinationConnector = Regex("\\s*(?:和|或|以及|\\b(?:and|or|nor)\\b)\\s*")
        val negatedCurrentReferencePatterns = listOf(
            Regex(
                "(?:不要|不是|不用|不选|不想(?:要)?|别|跳过|排除)\\s*" +
                    "(?:(?:再\\s*)?(?:给我\\s*)?(?:推荐|展示|显示|看|用|使用|选|选择|考虑|咨询|联系)\\s*)?" +
                    "(?:再\\s*)?(?:给我\\s*)?" +
                    "(?:(?:这家|那家)(?:机构|医院|诊所)?|(?:这个|那个|它)(?:医生|医师|机构|医院|诊所|项目|治疗|术式))" +
                    "(?:了)?(?=\\s*(?:因为|由于|$|[,.;!?，。；！？]))"
            ),
            Regex(
                "(?<![a-z0-9])(?:do not|don't|not|avoid|skip|exclude)(?![a-z0-9])\\s+" +
                    "(?:(?:recommend|show|see|use|choose|select|consider|want(?:\\s+to)?)\\s+(?:me\\s+)?)?" +
                    "(?:this|that)(?:\\s+(?:one|doctor|clinic|hospital|institution|procedure|treatment|project))?" +
                    "(?:\\s+(?:anymore|any\\s+more|any\\s+longer))?" +
                    "(?=\\s*(?:because\\b|since\\b|$|[,.;!?，。；！？]))"
            ),
            Regex(
                "(?:这个|那个|这家|那家|它)(?:医生|医师|机构|医院|诊所|项目)?\\s*" +
                    "(?:我\\s*)?(?:不要了|不用了|不选了|不考虑了|算了)"
            )
        )
        val alternativeEntityRequestPatterns = listOf(
            Regex("(?:其他|其它|另外|别的)\\s*(?:机构|医院|诊所|医生|医师|项目|治疗|术式)"),
            Regex(
                "(?:推荐|展示|显示|查找|找|看看|介绍|换)\\s*(?:其他|其它|另外|别的)\\s*" +
                    "(?:(?:(?:几\\s*)?(?:家|个|位|款|种)?\\s*)?" +
                    "(?:机构|医院|诊所|医生|医师|项目|治疗|术式)|" +
                    "(?:几\\s*)?(?:家|个|位|款|种)|的?)" +
                    "(?=\\s*(?:$|[,.;!?，。；！？]))"
            ),
            Regex("(?:other|another|different|alternative)\\s+(?:doctor|doctors|clinic|clinics|hospital|hospitals|institution|institutions|procedure|procedures|treatment|treatments|project|projects)"),
            Regex(
                "(?:recommend|show|find|list|introduce|switch to)\\s+(?:me\\s+)?" +
                    "(?:other|another|different|alternative)(?:\\s+(?:doctor|doctors|clinic|clinics|hospital|hospitals|institution|institutions|procedure|procedures|treatment|treatments|project|projects))?" +
                "(?=\\s*(?:$|[,.;!?，。；！？]))"
            )
        )
        val entitySwitchRequestPatterns = listOf(
            Regex("(?:换|改选)\\s*(?:(?:一\\s*)?家\\s*(?:机构|医院|诊所)|(?:机构|医院|诊所)|(?:一\\s*)?(?:个|位)?\\s*(?:医生|医师)|(?:一\\s*)?个?\\s*(?:项目|治疗|术式))"),
            Regex("(?:换一家|换一个|换个|换位)(?=\\s*(?:$|[,.;!?，。；！？]))")
        )
        val priceConfirmationPattern = Regex("不是.+(?:价格|费用|价位|贵|便宜).*(?:吗|嘛|么)")
        val englishPriceConfirmationPattern = Regex(
            "(?:isn't|is not)\\s+(?:this|that)\\s+" +
                "(?:project|treatment|procedure|clinic|hospital|institution|doctor|surgeon)\\s+" +
                "(?:more\\s+)?(?:expensive|cheap(?:er)?|costly)" +
                "(?:\\s+than\\s+(?:(?:this|that)" +
                    "(?:\\s+(?:one|project|treatment|procedure|clinic|hospital|institution|doctor|surgeon))?" +
                    "|(?:the\\s+)?(?:other|another)(?:\\s+one)?))?" +
                "(?:\\s+(?:right|correct))?"
        )
        val constraintOnlyFillerWordsPattern = Regex(
            "能不能|可不可以|可以|大概|一般|通常|请问|需要|能|要|会|是|的|呢|吗|嘛|么|呀|啊|" +
                "\\b(?:what|is|the|about|how|much|long|does|do|it|take|can|could|roughly|approximately|usually)\\b"
        )
        val constraintOnlyPunctuationPattern = Regex("[\\s,.;!?，。；！？]+")
        val socialInterjectionPattern = Regex(
            "(?:你好|您好|嗨|谢谢|感谢|哈哈+|好的谢谢|好的|收到|明白了|hi|hello|hey|thanks|thank you|ok|okay)[\\s!！。.]*"
        )
        val explicitTopicBoundaryPatterns = listOf(
            Regex("(?:换个|换一个|切换|改变)(?:话题|主题)"),
            Regex("聊(?:点|些)?别的"),
            Regex("\\b(?:change|switch)(?:\\s+the)?\\s+(?:topic|subject)\\b"),
            Regex("\\b(?:another|different)\\s+(?:topic|subject)\\b")
        )
        val alternativeInstitutionPattern = Regex(
            "(?:其他|其它|另外|别的)\\s*(?:几\\s*)?(?:家\\s*)?(?:机构|医院|诊所)|" +
                "(?:other|another|different|alternative)\\s+(?:clinic|clinics|hospital|hospitals|institution|institutions)"
        )
        val alternativeDoctorPattern = Regex(
            "(?:其他|其它|另外|别的)\\s*(?:几\\s*)?(?:个|位)?\\s*(?:医生|医师)|" +
                "(?:other|another|different|alternative)\\s+(?:doctor|doctors|surgeon|surgeons)"
        )
        val alternativeProjectPattern = Regex(
            "(?:其他|其它|另外|别的)\\s*(?:几\\s*)?(?:个|种)?\\s*(?:项目|治疗|术式)|" +
                "(?:other|another|different|alternative)\\s+(?:project|projects|treatment|treatments|procedure|procedures)"
        )
        val deicticScopeTargetPatterns = listOf(
            Regex("(?:这家|那家)\\s*(?:机构|医院|诊所)|(?:this|that)\\s+(?:clinic|hospital|institution)") to AgentQueryTarget.INSTITUTION,
            Regex("(?:这个|那个)\\s*(?:医生|医师)|(?:this|that)\\s+(?:doctor|surgeon)") to AgentQueryTarget.DOCTOR,
            Regex("(?:这个|那个)\\s*(?:项目|治疗|术式)|(?:this|that)\\s+(?:project|treatment|procedure)") to AgentQueryTarget.PROJECT
        )
    }
}
