package com.joysong.server.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.provider.AgentProviderRequestFactory
import com.joysong.server.agent.provider.AgentRequestPurpose
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.context.AgentSessionSummary
import com.joysong.server.agent.context.ConversationFocusUpdate
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.agent.orchestration.AiAgentAvailabilityGuard
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.IdempotencyKeyConflictException
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.diagnostics.AgentOperationLogger
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.agent.service.AgentText
import com.joysong.server.agent.service.PlanningCatalogProjection
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.agent.service.AgentCatalogService
import com.joysong.server.agent.service.AgentIntent
import com.joysong.server.agent.service.AgentIntentDecision
import com.joysong.server.agent.service.AgentIntentRouter
import com.joysong.server.agent.service.AgentLabelPolarity
import com.joysong.server.agent.service.AgentNextAction
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.ComparisonRequest
import com.joysong.server.agent.service.ComparisonOperand
import com.joysong.server.agent.service.ComparisonMissingField
import com.joysong.server.agent.service.ComparisonRequestBuilder
import com.joysong.server.agent.service.AgentPromptEvidence
import com.joysong.server.agent.service.AgentRouteAssessment
import com.joysong.server.agent.service.ParsedAgentRoute
import com.joysong.server.config.AiAgentProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestClientResponseException
import java.net.URI
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import com.joysong.server.user.service.AccountLifecycleGuard

private val hanScriptRegex = Regex("\\p{IsHan}")
private val latinWordRegex = Regex("[A-Za-z]+(?:['’-][A-Za-z]+)?")
private val englishLeadRegex = Regex(
    "(?i)^\\s*(?:what|why|how|which|where|when|who|is|are|can|could|should|would|do|does|please|compare|tell|explain)\\b"
)
private const val MAX_FUZZY_COMPARISON_CANDIDATES = 4

private data class PromptBuildResult(
    val prompt: String,
    val groundingPrompt: String,
    val evidence: AgentPromptEvidence
)

data class LlmCallResult(
    val content: String,
    val fallbackUsed: Boolean
)

private data class ProviderCallContext(
    val traceId: String,
    val turnId: String
)

data class GeneratedTurn(
    val content: String,
    val intentDecision: AgentIntentDecision,
    val llmResult: LlmCallResult,
    val catalogReport: AgentCatalogReportResponse?,
    val catalogItems: List<AgentCatalogItemResponse>,
    val comparisonRequest: ComparisonRequest? = null,
    val answerModelRequired: Boolean = true,
    val releaseDetailContext: Boolean = false
)

private data class BoundedRouteContext(
    val decisions: List<AgentIntentDecision>,
    val ambiguityReasons: List<String>,
    val detailContextSuperseded: Boolean
)

private data class PersistedFocusEvent(
    val update: ConversationFocusUpdate,
    val decision: AgentIntentDecision? = null
)

private data class CompleteLegacyDetailHistory(
    val entryActive: Boolean,
    val sawLegacySocial: Boolean
)

private data class DetailContextIdentity(
    val entryType: String,
    val entryId: String,
    val institutionId: String? = null,
    val projectId: String? = null
) {
    fun hasRequiredRelation(target: AgentQueryTarget?): Boolean = when {
        entryType != "INSTITUTION_PROJECT" -> true
        target == AgentQueryTarget.INSTITUTION -> !institutionId.isNullOrBlank()
        target == AgentQueryTarget.PROJECT -> !projectId.isNullOrBlank()
        else -> true
    }
}

private data class DetailScopeResolution(
    val requested: Boolean,
    val scope: String? = null
) {
    val unavailable: Boolean
        get() = requested && scope.isNullOrBlank()
}

private data class CatalogExclusionResult(
    val evidence: AgentPromptEvidence,
    val verified: Boolean
)

private data class GenerationLabelSummary(
    val requestedActions: List<AgentIntent>,
    val requestedTargets: List<AgentQueryTarget>,
    val uncertainActions: List<AgentIntent>,
    val uncertainTargets: List<AgentQueryTarget>,
    val prohibitedActions: List<AgentIntent>,
    val prohibitedTargets: List<AgentQueryTarget>
) {
    fun promptConstraint(): String = listOfNotNull(
        requestedActions.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "请求动作：$it" },
        requestedTargets.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "请求对象：$it" },
        uncertainActions.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "待澄清动作：$it" },
        uncertainTargets.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "待澄清对象：$it" },
        prohibitedActions.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "禁止动作：$it" },
        prohibitedTargets.takeIf { it.isNotEmpty() }?.joinToString(",") { it.name }
            ?.let { "禁止对象：$it" }
    ).takeIf { it.isNotEmpty() }?.joinToString("\n") { it }
        ?.let {
            """
                【本轮结构化诉求标签】
                $it
                禁止动作或对象只表示限制，不得作为用户请求执行；待澄清标签只用于提出必要的澄清问题。
            """.trimIndent()
        }.orEmpty()
}

private fun generationLabelSummary(
    local: AgentRouteAssessment,
    parsed: ParsedAgentRoute?,
    decision: AgentIntentDecision
): GenerationLabelSummary {
    val parsedActions = parsed?.let { it.intents + it.intent }.orEmpty()
    val parsedTargets = parsed?.queryTarget?.let(::setOf).orEmpty()
    val prohibitedActions = local.intentEvidence
        .filter { it.polarity == AgentLabelPolarity.NEGATIVE }
        .mapTo(linkedSetOf()) { it.intent }
    val prohibitedTargets = local.targetEvidence
        .filter { it.polarity == AgentLabelPolarity.NEGATIVE }
        .mapTo(linkedSetOf()) { it.target }
    val uncertainActions = local.intentEvidence
        .filter { it.polarity == AgentLabelPolarity.UNCERTAIN }
        .mapTo(linkedSetOf()) { it.intent }
        .apply { removeAll(parsedActions) }
    val uncertainTargets = local.targetEvidence
        .filter { it.polarity == AgentLabelPolarity.UNCERTAIN }
        .mapTo(linkedSetOf()) { it.target }
        .apply { removeAll(parsedTargets) }
    val requestedActions = local.intentEvidence
        .filter { it.polarity == AgentLabelPolarity.POSITIVE }
        .mapTo(linkedSetOf()) { it.intent }
        .apply {
            add(decision.intent)
            addAll(parsedActions)
            removeAll(prohibitedActions + uncertainActions)
        }
    val requestedTargets = local.targetEvidence
        .filter { it.polarity == AgentLabelPolarity.POSITIVE }
        .mapTo(linkedSetOf()) { it.target }
        .apply {
            decision.queryTarget?.let(::add)
            addAll(parsedTargets)
            removeAll(prohibitedTargets + uncertainTargets)
        }
    return GenerationLabelSummary(
        requestedActions = orderedActions(requestedActions, decision.intent),
        requestedTargets = orderedTargets(requestedTargets, decision.queryTarget),
        uncertainActions = orderedActions(uncertainActions),
        uncertainTargets = orderedTargets(uncertainTargets),
        prohibitedActions = orderedActions(prohibitedActions),
        prohibitedTargets = orderedTargets(prohibitedTargets)
    )
}

private fun orderedActions(
    labels: Set<AgentIntent>,
    primary: AgentIntent? = null
): List<AgentIntent> = listOfNotNull(primary?.takeIf(labels::contains)) +
    AgentIntent.entries.filter { it != primary && it in labels }

private fun orderedTargets(
    labels: Set<AgentQueryTarget>,
    primary: AgentQueryTarget? = null
): List<AgentQueryTarget> = listOfNotNull(primary?.takeIf(labels::contains)) +
    AgentQueryTarget.entries.filter { it != primary && it in labels }

internal fun summaryContextDecision(
    topic: String,
    agentIntentRouter: AgentIntentRouter
): AgentIntentDecision? {
    val parts = topic.trim().uppercase().split(":")
    if (parts.size !in 1..3 || parts.any(String::isBlank)) return null
    val intent = runCatching { AgentIntent.valueOf(parts.first()) }.getOrNull() ?: return null
    val target = parts.getOrNull(1)?.let { runCatching { AgentQueryTarget.valueOf(it) }.getOrNull() }
    if (parts.size == 3 && target == null) return null
    val action = when {
        target != null -> parts.getOrNull(2)
        else -> parts.getOrNull(1)
    }
    val decision = agentIntentRouter.validatedDecision(intent, target)
    val expectedAction = decision.nextAction.takeUnless { it.name == "NONE" }?.name
    if (action != null && action != expectedAction) return null
    return decision.takeIf {
        when (intent) {
            AgentIntent.GENERAL_CHAT -> target == null && action == null
            AgentIntent.SAFETY_SCREENING -> target == null
            AgentIntent.HUMAN_CONSULTATION -> target == AgentQueryTarget.INSTITUTION
            AgentIntent.PLANNING,
            AgentIntent.CATALOG_QA,
            AgentIntent.COMPARISON,
            AgentIntent.DETAIL_SUMMARY -> true
        }
    }
}

data class ChatTurnResult(
    val message: ChatMessageEntity,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE",
    val traceId: String? = null,
    val comparisonRequest: ComparisonRequest? = null
)

sealed interface PreparedChatTurn {
    data class Replayed(
        val traceId: String,
        val turnId: String,
        val userMessage: ChatMessageEntity,
        val turn: ChatTurnResult
    ) : PreparedChatTurn

    data class Completed(
        val traceId: String,
        val turnId: String,
        val userMessage: ChatMessageEntity,
        val turn: ChatTurnResult
    ) : PreparedChatTurn

    data class Started(
        val traceId: String,
        val turnId: String,
        val userMessage: ChatMessageEntity,
        val messages: List<Map<String, String>>,
        val maxOutputTokens: Int,
        val planning: Boolean,
        internal val sessionId: String = userMessage.sessionId,
        internal val startedAt: Long = System.nanoTime(),
        val generated: GeneratedTurn? = null,
        internal val conversationFocusUpdate: ConversationFocusUpdate = ConversationFocusUpdate.SET
    ) : PreparedChatTurn
}

@Service
class ChatService(
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: ChatMessageRepository,
    private val projectRepository: ProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
      private val doctorRepository: DoctorRepository,
      private val doctorInstitutionService: DoctorInstitutionService,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val agentCatalogService: AgentCatalogService,
    private val agentIntentRouter: AgentIntentRouter,
    private val comparisonRequestBuilder: ComparisonRequestBuilder,
    private val turnLifecycleService: TurnLifecycleService,
    private val agentOperationLogger: AgentOperationLogger,
    private val agentContextBuilder: AgentContextBuilder,
    private val aiAgentAvailabilityGuard: AiAgentAvailabilityGuard,
    private val objectMapper: ObjectMapper,
    @Qualifier("agentLlmRestTemplate") private val restTemplate: RestTemplate,
    @Qualifier("agentIntentParserRestTemplate") private val intentParserRestTemplate: RestTemplate,
    private val aiAgentProperties: AiAgentProperties,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {
    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    /**
     * 创建新的聊天会话
     */
    @Transactional
    fun createSession(userId: String, request: CreateSessionRequest): ChatSessionEntity {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val persona = request.persona.trim().uppercase()
        val contextType = request.contextType.trim().uppercase()
        require(persona in setOf("BESTIE", "CONSULTANT")) { "不支持的 AI 角色" }
        require(contextType in setOf("GENERAL", "DOCTOR", "PROJECT", "INSTITUTION", "INSTITUTION_PROJECT")) {
            "不支持的会话上下文"
        }
        require(contextType == "GENERAL" || request.contextId.isNotBlank()) { "该会话上下文必须提供 contextId" }
        require(request.contextId.length <= 100) { "contextId 过长" }
        require(request.title.length <= 100) { "会话标题不能超过 100 字" }
        val session = ChatSessionEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            persona = persona,
            contextType = contextType,
            contextId = request.contextId.trim(),
            title = request.title.trim().ifBlank { "${getPersonaName(persona)}对话" },
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        return sessionRepository.save(session)
    }

    /**
     * 获取用户的会话列表
     */
    fun getSessions(userId: String, persona: String?): List<ChatSessionEntity> {
        val sessions = if (persona.isNullOrBlank()) {
            sessionRepository.findByUserIdAndDeletedAtIsNull(userId)
        } else {
            sessionRepository.findByUserIdAndPersonaAndDeletedAtIsNull(userId, normalizePersona(persona))
        }
        return sessions.sortedByDescending { it.updatedAt }
    }

    fun getLastMessage(sessionId: String): String =
        messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)
            ?.let { PlanningCatalogProjection.projectStoredMessage(it, objectMapper).content }
            .orEmpty()

    /**
     * 获取单个会话
     */
    fun getSession(sessionId: String, userId: String): ChatSessionEntity? {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
    }

    /**
     * 发送消息并获取 AI 响应
     */
    fun sendMessage(
        sessionId: String,
        userId: String,
        request: SendMessageRequest
    ): ChatTurnResult {
        aiAgentAvailabilityGuard.requireGenerationEnabled()
        return sendMessageInternal(sessionId, userId, request, ::callLLM)
    }

    fun prepareStreamingMessage(
        sessionId: String,
        userId: String,
        request: SendMessageRequest
    ): PreparedChatTurn {
        aiAgentAvailabilityGuard.requireGenerationEnabled()
        val content = canonicalMessageContent(request)
        val begin = beginTurn(sessionId, userId, content, request.idempotencyKey)
        if (begin is BeginTurnResult.Replayed) return PreparedChatTurn.Replayed(
            traceId = begin.traceId,
            turnId = begin.turnId,
            userMessage = begin.userMessage,
            turn = begin.turn
        )
        begin as BeginTurnResult.Started
        val startedAt = System.nanoTime()
        try {
            val context = agentContextBuilder.load(userId, sessionId, 20, 4_000)
            val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
                ?: throw AgentChatException("SESSION_NOT_FOUND", begin.traceId)
            var capturedMessages: List<Map<String, String>> = emptyList()
            var capturedProfile: GenerationProfile? = null
            val generated = withCurrentTurnLanguage(content) {
                generateTurn(
                    session,
                    content,
                    context.messages,
                    context.summary,
                    true,
                    ProviderCallContext(begin.traceId, begin.turnId)
                ) { messages, profile, _ ->
                    capturedMessages = messages
                    capturedProfile = profile
                    LlmCallResult("", false)
                }
            }
            val focusUpdate = conversationFocusUpdate(session, content, generated)
            val userMessage = messageRepository.findByTurnIdAndRole(begin.turnId, "USER")
                ?: ChatMessageEntity(sessionId = sessionId, turnId = begin.turnId, role = "USER", content = content)
            if (!generated.answerModelRequired) {
                return PreparedChatTurn.Completed(
                    traceId = begin.traceId,
                    turnId = begin.turnId,
                    userMessage = userMessage,
                    turn = completeGeneratedTurn(
                        turnId = begin.turnId,
                        traceId = begin.traceId,
                        sessionId = sessionId,
                        startedAt = startedAt,
                        generated = generated,
                        conversationFocusUpdate = focusUpdate
                    )
                )
            }
            return PreparedChatTurn.Started(
                traceId = begin.traceId,
                turnId = begin.turnId,
                userMessage = userMessage,
                messages = capturedMessages,
                maxOutputTokens = requireNotNull(capturedProfile).maxOutputTokens,
                planning = generated.intentDecision.intent == AgentIntent.PLANNING,
                sessionId = sessionId,
                startedAt = startedAt,
                generated = generated,
                conversationFocusUpdate = focusUpdate
            )
        } catch (error: Exception) {
            val code = if (error is AgentChatException) error.code else "AGENT_INTERNAL_ERROR"
            val durationMs = elapsedMs(startedAt)
            turnLifecycleService.failTurn(begin.turnId, code, durationMs)
            agentOperationLogger.failed(begin.traceId, begin.turnId, sessionId, durationMs, code)
            throw if (error is AgentChatException) error else AgentChatException(code, begin.traceId)
        }
    }

    fun completeStreamingMessage(prepared: PreparedChatTurn.Started, providerContent: String): ChatTurnResult =
        withCurrentTurnLanguage(prepared.userMessage.content) {
            val generated = requireNotNull(prepared.generated).copy(
                content = enforcePlanningBoundary(
                    prepared.generated.intentDecision.intent,
                    naturalizeUserFacingLanguage(providerContent)
                ),
                llmResult = LlmCallResult(providerContent, false)
            )
            completeGeneratedTurn(
                turnId = prepared.turnId,
                traceId = prepared.traceId,
                sessionId = prepared.sessionId,
                startedAt = prepared.startedAt,
                generated = generated,
                conversationFocusUpdate = prepared.conversationFocusUpdate
            )
        }

    fun failStreamingMessage(prepared: PreparedChatTurn.Started, error: Throwable): String {
        val code = if (isProviderTimeout(error)) "AI_PROVIDER_TIMEOUT" else "AI_PROVIDER_UNAVAILABLE"
        val durationMs = elapsedMs(prepared.startedAt)
        turnLifecycleService.failTurn(prepared.turnId, code, durationMs)
        agentOperationLogger.failed(prepared.traceId, prepared.turnId, prepared.sessionId, durationMs, code)
        return code
    }

    fun cancelStreamingMessage(prepared: PreparedChatTurn.Started, code: String) {
        val durationMs = elapsedMs(prepared.startedAt)
        turnLifecycleService.failTurn(prepared.turnId, code, durationMs)
        agentOperationLogger.failed(prepared.traceId, prepared.turnId, prepared.sessionId, durationMs, code)
    }

    private fun canonicalMessageContent(request: SendMessageRequest): String = request.content.trim().also {
        require(it.isNotEmpty()) { "消息内容不能为空" }
        require(it.length <= 5000) { "消息内容不能超过 5000 字" }
    }

    private fun beginTurn(
        sessionId: String,
        userId: String,
        content: String,
        rawIdempotencyKey: String?
    ): BeginTurnResult {
        val idempotencyKey = rawIdempotencyKey?.trim()?.also {
            require(it.isNotEmpty() && it.length <= 100) { "INVALID_IDEMPOTENCY_KEY" }
        } ?: UUID.randomUUID().toString()
        return try {
            when (val begin = turnLifecycleService.beginTurn(sessionId, userId, content, idempotencyKey)) {
                BeginTurnResult.InProgress -> throw AgentChatException.turnInProgress()
                BeginTurnResult.IdempotencyExpired -> throw AgentChatException.idempotencyExpired()
                else -> begin
            }
        } catch (_: IdempotencyKeyConflictException) {
            throw AgentChatException.idempotencyConflict()
        } catch (error: IllegalArgumentException) {
            throw AgentChatException.sessionNotFound()
        }
    }

    private fun sendMessageInternal(
        sessionId: String,
        userId: String,
        request: SendMessageRequest,
        llmCaller: (List<Map<String, String>>, GenerationProfile, ProviderCallContext) -> LlmCallResult
    ): ChatTurnResult {
        val content = canonicalMessageContent(request)
        return when (val begin = beginTurn(sessionId, userId, content, request.idempotencyKey)) {
            is BeginTurnResult.Replayed -> begin.turn
            is BeginTurnResult.Started -> executeStartedTurn(begin, sessionId, userId, content, llmCaller)
            else -> error("Unreachable begin turn state")
        }
    }

    private fun executeStartedTurn(
        begin: BeginTurnResult.Started,
        sessionId: String,
        userId: String,
        content: String,
        llmCaller: (List<Map<String, String>>, GenerationProfile, ProviderCallContext) -> LlmCallResult
    ): ChatTurnResult {
        val totalStartedAt = System.nanoTime()
        return try {
            runStartedTurn(begin, sessionId, userId, content, totalStartedAt, llmCaller)
        } catch (error: Exception) {
            val code = when {
                error is AgentChatException -> error.code
                error.message == "AI_PROVIDER_TIMEOUT" -> "AI_PROVIDER_TIMEOUT"
                error.message == "AI_PROVIDER_UNAVAILABLE" -> "AI_PROVIDER_UNAVAILABLE"
                else -> "AGENT_INTERNAL_ERROR"
            }
            val durationMs = elapsedMs(totalStartedAt)
            runCatching {
                turnLifecycleService.failTurn(begin.turnId, code, durationMs)
                agentOperationLogger.failed(begin.traceId, begin.turnId, sessionId, durationMs, code)
            }.onFailure {
                logger.error("Agent turn failure finalization failed turnId={}", begin.turnId)
            }
            throw AgentChatException(code, begin.traceId)
        }
    }

    private fun runStartedTurn(
        begin: BeginTurnResult.Started,
        sessionId: String,
        userId: String,
        content: String,
        totalStartedAt: Long,
        llmCaller: (List<Map<String, String>>, GenerationProfile, ProviderCallContext) -> LlmCallResult
    ): ChatTurnResult {
        val context = agentContextBuilder.load(userId, sessionId, 20, 4_000)
        val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw AgentChatException("SESSION_NOT_FOUND", begin.traceId)
        return withCurrentTurnLanguage(content) {
            val generated = generateTurn(
                session = session,
                content = content,
                historyMessages = context.messages,
                summary = context.summary,
                appendCurrentUser = true,
                providerCallContext = ProviderCallContext(begin.traceId, begin.turnId),
                llmCaller = llmCaller
            )
            completeGeneratedTurn(
                turnId = begin.turnId,
                traceId = begin.traceId,
                sessionId = sessionId,
                startedAt = totalStartedAt,
                generated = generated,
                conversationFocusUpdate = conversationFocusUpdate(session, content, generated)
            )
        }
    }

    private fun completeGeneratedTurn(
        turnId: String,
        traceId: String,
        sessionId: String,
        startedAt: Long,
        generated: GeneratedTurn,
        conversationFocusUpdate: ConversationFocusUpdate
    ): ChatTurnResult {
        val durationMs = elapsedMs(startedAt)
        val modelName = aiAgentProperties.model.takeIf { generated.answerModelRequired }.orEmpty()
        val completed = turnLifecycleService.completeTurn(
            CompleteTurnCommand(
                turnId = turnId,
                content = generated.content,
                intent = generated.intentDecision.intent.name,
                queryTarget = generated.intentDecision.queryTarget?.name,
                nextAction = generated.intentDecision.nextAction.name,
                catalogReport = generated.catalogReport,
                catalogItems = generated.catalogItems,
                durationMs = durationMs,
                fallbackUsed = generated.llmResult.fallbackUsed,
                modelName = modelName,
                comparisonRequest = generated.comparisonRequest,
                releaseDetailContext = generated.releaseDetailContext,
                conversationFocusUpdate = conversationFocusUpdate
            )
        )
        agentOperationLogger.completed(traceId, turnId, sessionId, durationMs, modelName)
        return completed
    }

    private fun conversationFocusUpdate(
        session: ChatSessionEntity,
        content: String,
        generated: GeneratedTurn
    ): ConversationFocusUpdate {
        val current = agentIntentRouter.assessCurrent(content, session.contextType)
        val explicitTopicBoundary = "EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons
        val finalIntent = generated.intentDecision.intent
        val finalTarget = generated.intentDecision.queryTarget
        if (explicitTopicBoundary) {
            return if (
                finalTarget != null &&
                finalIntent !in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)
            ) {
                ConversationFocusUpdate.SET
            } else {
                ConversationFocusUpdate.CLEAR
            }
        }
        if (isDetailContext(session.contextType)) {
            if (!generated.releaseDetailContext) return ConversationFocusUpdate.PRESERVE
            return if (
                finalTarget != null &&
                finalIntent !in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)
            ) {
                ConversationFocusUpdate.SET
            } else {
                ConversationFocusUpdate.CLEAR
            }
        }
        if (
            "SOCIAL_INTERJECTION" in current.ambiguityReasons ||
            finalIntent in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)
        ) {
            return ConversationFocusUpdate.PRESERVE
        }
        return if (finalTarget != null) ConversationFocusUpdate.SET else ConversationFocusUpdate.CLEAR
    }

    private fun generateTurn(
        session: ChatSessionEntity,
        content: String,
        historyMessages: List<ChatMessageEntity>,
        summary: AgentSessionSummary?,
        appendCurrentUser: Boolean,
        providerCallContext: ProviderCallContext,
        llmCaller: (List<Map<String, String>>, GenerationProfile, ProviderCallContext) -> LlmCallResult
    ): GeneratedTurn {
        val llmMessages = mutableListOf<Map<String, String>>()
        val currentRouteAssessment = agentIntentRouter.assessCurrent(content, session.contextType)
        val boundedContext by lazy {
            boundedRouteContext(historyMessages, summary, session.contextType)
        }
        val targetClarificationCandidate = currentRouteAssessment.decision.queryTarget != null &&
            isComparisonTargetClarification(content, currentRouteAssessment.decision.queryTarget)
        val pendingComparison = if (targetClarificationCandidate) {
            historyMessages.asReversed()
                .asSequence()
                .filter { it.role.equals("ASSISTANT", ignoreCase = true) }
                .mapNotNull { turnLifecycleService.projectMessage(it).comparisonRequest }
                .firstOrNull { !it.isComplete }
        } else null
        val comparisonTargetClarification = pendingComparison != null &&
            ComparisonMissingField.TARGET_TYPE in pendingComparison.missingFields &&
            targetClarificationCandidate
        val localRouteAssessment = if (comparisonTargetClarification) {
            currentRouteAssessment.copy(
                decision = agentIntentRouter.validatedDecision(
                    AgentIntent.COMPARISON,
                    currentRouteAssessment.decision.queryTarget
                ),
                confidence = 1.0,
                requiresLlmParsing = false,
                requiresContextCompletion = false
            )
        } else if (currentRouteAssessment.requiresContextCompletion) {
            agentIntentRouter.supplementWithContext(
                currentRouteAssessment,
                boundedContext.decisions,
                boundedContext.ambiguityReasons
            )
        } else {
            currentRouteAssessment
        }
        // A custom institution-project name may not contain generic words such as
        // “项目” or “套餐”. Resolve effective inherited details before deciding that
        // the message is general chat, otherwise the database search is skipped.
        val routeAssessment = if (
            localRouteAssessment.decision.intent == AgentIntent.GENERAL_CHAT &&
            agentCatalogService.hasInstitutionProjectMatch(content)
        ) {
            localRouteAssessment.copy(
                decision = agentIntentRouter.validatedDecision(
                    AgentIntent.CATALOG_QA,
                    AgentQueryTarget.INSTITUTION_PROJECT
                ),
                confidence = 0.95,
                ambiguityReasons = emptyList(),
                requiresLlmParsing = false
            )
        } else localRouteAssessment
        val parsedRoute = if (aiAgentProperties.intentParserEnabled && routeAssessment.requiresLlmParsing) {
            val parserNeedsContext = currentRouteAssessment.requiresContextCompletion ||
                routeAssessment.ambiguityReasons.any {
                    it == "CONFLICTING_CONTEXT" || it.startsWith("CONTEXT_")
                }
            parseAmbiguousRoute(
                content,
                routeAssessment,
                if (parserNeedsContext) boundedContext.decisions else emptyList(),
                providerCallContext
            )
        } else null
        val intentDecision = agentIntentRouter.mergeParsedRoute(routeAssessment, parsedRoute)
        val negatedCurrentReference = "NEGATED_CURRENT_REFERENCE" in currentRouteAssessment.ambiguityReasons
        val alternativeEntityRequest = "ALTERNATIVE_ENTITY_REQUEST" in currentRouteAssessment.ambiguityReasons
        val explicitTopicBoundary = "EXPLICIT_TOPIC_BOUNDARY" in currentRouteAssessment.ambiguityReasons
        val detailContextActive = !boundedContext.detailContextSuperseded
        val detailIdentity by lazy { detailContextIdentity(session) }
        if (intentDecision.intent == AgentIntent.HUMAN_CONSULTATION) {
            val releaseDetailContext = shouldReleaseDetailContext(
                contextType = session.contextType,
                current = currentRouteAssessment,
                resolved = intentDecision,
                useDetailContext = false,
                alreadySuperseded = !detailContextActive
            )
            return generateHumanConsultationTurn(
                userId = session.userId,
                content = content,
                decision = intentDecision,
                useContextInstitution = detailContextActive &&
                    !negatedCurrentReference &&
                    !alternativeEntityRequest &&
                    !explicitTopicBoundary,
                releaseDetailContext = releaseDetailContext,
                detailIdentity = detailIdentity
            )
        }
        val detailScopeResolution = detailContextSearchScope(
            session = session,
            current = currentRouteAssessment,
            resolved = intentDecision,
            detailContextActive = detailContextActive
        )
        if (detailScopeResolution.unavailable) {
            return unreliableDetailExclusionTurn(intentDecision, releaseDetailContext = true)
        }
        val detailScope = detailScopeResolution.scope
        val useDetailContext = shouldUseDetailContext(
            contextType = session.contextType,
            current = currentRouteAssessment,
            resolved = intentDecision,
            detailContextActive = detailContextActive
        )
        val releaseDetailContext = shouldReleaseDetailContext(
            contextType = session.contextType,
            current = currentRouteAssessment,
            resolved = intentDecision,
            useDetailContext = useDetailContext,
            alreadySuperseded = !detailContextActive,
            preserveDetailScope = detailScope != null
        )
        val labelSummary = generationLabelSummary(routeAssessment, parsedRoute, intentDecision)
        val previousComparison = if (
            intentDecision.intent == AgentIntent.COMPARISON && !explicitTopicBoundary
        ) {
            historyMessages.asReversed()
                .asSequence()
                .filter { it.role.equals("ASSISTANT", ignoreCase = true) }
                .map { turnLifecycleService.projectMessage(it).comparisonRequest }
                .firstOrNull { it != null }
        } else null
        val previousUserQueries = if (explicitTopicBoundary) {
            emptyList()
        } else {
            historyMessages.filter { it.role.equals("USER", true) }
                .map { it.content }
                .takeLast(4)
        }
        val contextualQuery = agentCatalogService.contextualSearchQuery(content, previousUserQueries)
        val generationProfile = generationProfile(intentDecision.intent)
        val catalogSearchQuery = listOf(
            contextualQuery,
            detailScope.orEmpty(),
            parsedRoute?.keywords.orEmpty().joinToString(" ")
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val currentContextItems = if (useDetailContext) {
            currentContextCatalogItems(session.contextType, session.contextId)
        } else {
            emptyList()
        }
        val comparisonSearchQuery = if (intentDecision.intent == AgentIntent.COMPARISON) {
            (
                listOf(catalogSearchQuery) +
                    previousUserQueries.takeIf { comparisonTargetClarification }.orEmpty() +
                    currentContextItems.map { it.name } +
                    previousComparison?.operands.orEmpty().map { it.displayName }
                )
                .filter(String::isNotBlank)
                .distinct()
                .joinToString(" ")
        } else catalogSearchQuery
        val excludedDetailIdentity = if (
            negatedCurrentReference || alternativeEntityRequest || !detailContextActive ||
            (releaseDetailContext && !useDetailContext)
        ) {
            detailIdentity
        } else {
            null
        }
        val exclusion = catalogEvidence(
            content = content,
            searchQuery = comparisonSearchQuery,
            intentDecision = intentDecision
        ).withoutCatalogItems(excludedDetailIdentity, intentDecision.queryTarget)
        if (!exclusion.verified) {
            return unreliableDetailExclusionTurn(intentDecision, releaseDetailContext = true)
        }
        val rawEvidence = exclusion.evidence
        val initialComparisonRequest = if (intentDecision.intent == AgentIntent.COMPARISON) {
            comparisonRequestBuilder.build(
                content = content,
                operandContent = comparisonSearchQuery.takeIf { comparisonTargetClarification } ?: content,
                targetType = intentDecision.queryTarget,
                candidates = rawEvidence.report?.items.orEmpty(),
                contextCandidates = currentContextItems,
                previous = previousComparison,
                detectedCities = rawEvidence.detectedCities
            )
        } else null
        val comparisonCandidates = comparisonCandidateItems(rawEvidence, intentDecision.queryTarget)
        val comparisonRequest = initialComparisonRequest?.let { request ->
            if (
                request.missingFields == setOf(ComparisonMissingField.OPERANDS) &&
                request.operands.isEmpty() &&
                comparisonCandidates.size in 2..MAX_FUZZY_COMPARISON_CANDIDATES &&
                (rawEvidence.report?.totalMatched ?: comparisonCandidates.size) <= MAX_FUZZY_COMPARISON_CANDIDATES
            ) {
                comparisonRequestBuilder.normalize(request.copy(
                    operands = comparisonCandidates.map { candidate ->
                        ComparisonOperand(
                            entityType = requireNotNull(intentDecision.queryTarget),
                            entityId = candidate.id,
                            displayName = candidate.name
                        )
                    }
                ))
            } else request
        }
        if (comparisonRequest != null && !comparisonRequest.isComplete) {
            return GeneratedTurn(
                content = comparisonClarification(
                    request = comparisonRequest,
                    candidates = comparisonCandidates,
                    totalMatched = rawEvidence.report?.totalMatched ?: comparisonCandidates.size
                ),
                intentDecision = intentDecision,
                llmResult = LlmCallResult(content = "", fallbackUsed = false),
                catalogReport = rawEvidence.report,
                catalogItems = comparisonCandidates.take(MAX_FUZZY_COMPARISON_CANDIDATES),
                comparisonRequest = comparisonRequest,
                answerModelRequired = false,
                releaseDetailContext = releaseDetailContext
            )
        }
        val evidence = comparisonRequest?.let {
            agentCatalogService.filterComparisonEvidence(rawEvidence, it)
        } ?: rawEvidence
        val promptBuild = getSystemPrompt(
            persona = session.persona,
            contextType = session.contextType,
            contextId = session.contextId,
            intentDecision = intentDecision,
            labelSummary = labelSummary,
            evidence = evidence,
            useDetailContext = useDetailContext
        )
        llmMessages.add(mapOf("role" to "system", "content" to promptBuild.prompt))
        if (summary != null && summary != AgentSessionSummary()) {
            llmMessages.add(mapOf("role" to "system", "content" to agentContextBuilder.serializeSummary(summary)))
        }
        historyMessages.takeLast(generationProfile.historyMessageLimit).forEach { msg ->
            llmMessages.add(mapOf(
                "role" to msg.role.lowercase(),
                "content" to msg.content
            ))
        }
        if (appendCurrentUser) llmMessages.add(mapOf("role" to "user", "content" to content))
        // Keep the completed catalog search closest to generation. This prevents older
        // conversation content from overriding the same result set used by UI cards.
        if (promptBuild.groundingPrompt.isNotBlank()) {
            llmMessages.add(mapOf("role" to "system", "content" to promptBuild.groundingPrompt))
        }
        llmMessages.add(mapOf("role" to "system", "content" to currentTurnLanguagePolicy(content)))

        val llmResult = llmCaller(llmMessages, generationProfile, providerCallContext)
        val aiContent = enforcePlanningBoundary(
            intent = intentDecision.intent,
            content = naturalizeUserFacingLanguage(llmResult.content)
        )
        val visibleReport = promptBuild.evidence.report?.takeIf {
            intentDecision.intent == AgentIntent.COMPARISON && it.items.isNotEmpty()
        }
        val visibleItems = if (visibleReport == null) {
            when {
                useDetailContext && currentContextItems.isNotEmpty() ->
                    currentContextItems
                else -> promptBuild.evidence.report?.items.orEmpty().take(4)
            }
        } else emptyList()
        return GeneratedTurn(
            content = aiContent,
            intentDecision = intentDecision,
            llmResult = llmResult,
            catalogReport = visibleReport,
            catalogItems = visibleItems,
            comparisonRequest = comparisonRequest,
            releaseDetailContext = releaseDetailContext
        )
    }

    private fun generateHumanConsultationTurn(
        userId: String,
        content: String,
        decision: AgentIntentDecision,
        useContextInstitution: Boolean,
        releaseDetailContext: Boolean,
        detailIdentity: DetailContextIdentity?
    ): GeneratedTurn {
        val contextInstitutionId = when (detailIdentity?.entryType) {
            "INSTITUTION", "INSTITUTION_PROJECT" -> detailIdentity.institutionId
            else -> null
        }
        val mustVerifyExcludedInstitution = !useContextInstitution && releaseDetailContext &&
            detailIdentity?.entryType == "INSTITUTION_PROJECT"
        if (mustVerifyExcludedInstitution && contextInstitutionId.isNullOrBlank()) {
            return unreliableDetailExclusionTurn(decision, releaseDetailContext = true)
        }
        val selection = agentCatalogService.selectConsultableInstitutions(
            userId = userId,
            query = content,
            contextInstitutionId = contextInstitutionId.takeIf { useContextInstitution }
        )
        val excludedInstitutionId = contextInstitutionId.takeIf {
            !useContextInstitution && releaseDetailContext
        }
        val items = selection.items.filter {
            it.type.equals("INSTITUTION", ignoreCase = true) &&
                it.id.isNotBlank() &&
                !it.institutionId.isNullOrBlank() &&
                it.canChatWithHuman &&
                it.id != excludedInstitutionId &&
                it.institutionId != excludedInstitutionId
        }.take(4)
        val resolvedDecision = decision.copy(
            queryTarget = AgentQueryTarget.INSTITUTION,
            searchCatalog = false,
            nextAction = if (items.isEmpty()) AgentNextAction.NONE else AgentNextAction.SELECT_INSTITUTION
        )
        val response = when {
            items.isEmpty() -> AgentText.value(
                "目前没有可转接真人咨询的机构，请稍后再试。",
                "No institutions are currently available for a human-consultation handoff. Please try again later."
            )
            selection.requestedInstitutionUnavailable -> AgentText.value(
                "你提到的机构目前无法提供真人转接。你可以选择下方其他机构，查看其当前可联系的咨询师。",
                "The institution you mentioned cannot currently provide a handoff. Choose another institution below to see its currently available consultants."
            )
            else -> AgentText.value(
                "我可以为你转接真人咨询。请选择希望咨询的机构，随后可查看该机构当前可联系的咨询师。",
                "I can help connect you with a real consultant. Choose an institution to see its currently available consultants."
            )
        }
        return GeneratedTurn(
            content = response,
            intentDecision = resolvedDecision,
            llmResult = LlmCallResult("", fallbackUsed = false),
            catalogReport = null,
            catalogItems = items,
            answerModelRequired = false,
            releaseDetailContext = releaseDetailContext
        )
    }

    private fun unreliableDetailExclusionTurn(
        decision: AgentIntentDecision,
        releaseDetailContext: Boolean
    ) = GeneratedTurn(
        content = AgentText.value(
            "当前详情信息暂时不可用，无法为你可靠筛选其他选项，请稍后再试。",
            "The current detail information is temporarily unavailable, so I can't reliably filter alternatives right now. Please try again later."
        ),
        intentDecision = decision.copy(
            searchCatalog = false,
            nextAction = AgentNextAction.NONE
        ),
        llmResult = LlmCallResult(content = "", fallbackUsed = false),
        catalogReport = null,
        catalogItems = emptyList(),
        answerModelRequired = false,
        releaseDetailContext = releaseDetailContext
    )

    private fun catalogEvidence(
        content: String,
        searchQuery: String,
        intentDecision: AgentIntentDecision
    ): AgentPromptEvidence {
        if (!intentDecision.searchCatalog || intentDecision.intent == AgentIntent.DETAIL_SUMMARY) {
            return AgentPromptEvidence()
        }
        return agentCatalogService.promptEvidence(
            query = content,
            searchQuery = searchQuery,
            targetQuery = searchQuery,
            priorityQuery = content,
            queryTarget = intentDecision.queryTarget,
            reportMode = if (intentDecision.intent == AgentIntent.COMPARISON) "COMPARISON" else "AUTO"
        )
    }

    private fun comparisonCandidateItems(
        evidence: AgentPromptEvidence,
        target: AgentQueryTarget?
    ): List<AgentCatalogItemResponse> {
        if (target == null) return emptyList()
        return evidence.report?.items.orEmpty()
            .filter { it.type.equals(target.name, ignoreCase = true) }
            .distinctBy { "${it.type.uppercase()}:${it.id}" }
    }

    private fun comparisonClarification(
        request: ComparisonRequest,
        candidates: List<AgentCatalogItemResponse>,
        totalMatched: Int
    ): String = buildList {
        if (ComparisonMissingField.OPERANDS in request.missingFields) {
            val names = candidates.take(MAX_FUZZY_COMPARISON_CANDIDATES).joinToString(AgentText.value("、", ", ")) { it.name }
            when {
                totalMatched > MAX_FUZZY_COMPARISON_CANDIDATES -> add(AgentText.value(
                    "关键词搜索找到较多结果（共 $totalMatched 条），例如：$names。请补充机构、城市、价格范围、部位或更具体的项目名称来缩小范围",
                    "The keyword search found many results ($totalMatched), including: $names. Narrow the scope by clinic, city, price range, treatment area, or a more specific treatment name"
                ))
                candidates.size == 1 -> add(AgentText.value(
                    "关键词搜索找到：$names。请再提供一个要比较的对象",
                    "The keyword search found: $names. Provide one more item to compare"
                ))
                else -> add(AgentText.value(
                    "暂未搜索到足够的对比对象，请补充名称或更具体的关键词",
                    "The keyword search did not find enough items to compare. Provide a name or a more specific keyword"
                ))
            }
        }
        if (ComparisonMissingField.TARGET_TYPE in request.missingFields) {
            add(AgentText.value(
                "请明确要比较机构、医生、项目还是机构项目",
                "Specify whether to compare clinics, doctors, treatments, or clinic treatments"
            ))
        }
    }.joinToString(AgentText.value("；", "; "))

    private fun isComparisonTargetClarification(content: String, target: AgentQueryTarget?): Boolean {
        val normalized = content.trim().lowercase().trim('，', ',', '。', '.', '！', '!', '？', '?')
        val accepted = when (target) {
            AgentQueryTarget.INSTITUTION -> setOf("机构", "医院", "诊所", "clinic", "institution", "hospital")
            AgentQueryTarget.DOCTOR -> setOf("医生", "医师", "doctor", "surgeon", "physician")
            AgentQueryTarget.PROJECT -> setOf("项目", "治疗", "术式", "project", "treatment", "procedure")
            AgentQueryTarget.INSTITUTION_PROJECT -> setOf(
                "机构项目", "机构套餐", "项目套餐", "套餐", "报价",
                "institution project", "clinic treatment", "clinic package", "offering", "package"
            )
            null -> emptySet()
        }
        return normalized in accepted
    }

    /**
     * 删除消息（验证会话归属后删除）
     */
    fun deleteMessage(messageId: String, userId: String) = hideMissingResource {
        turnLifecycleService.deleteTurn(messageId, userId)
    }

    /**
     * 获取会话的所有消息
     */
    fun getMessages(
        sessionId: String,
        userId: String,
        limit: Int = 100,
        before: LocalDateTime? = null
    ): List<ChatMessageEntity> = hideMissingResource {
        agentContextBuilder.load(userId, sessionId, minOf(limit, 20), 4_000).messages
    }

    fun clearMessages(sessionId: String, userId: String) = hideMissingResource {
        turnLifecycleService.clearHistory(sessionId, userId)
    }

    fun deleteSession(sessionId: String, userId: String) = hideMissingResource {
        turnLifecycleService.deleteSession(sessionId, userId)
    }

    fun clearSessions(userId: String, persona: String) = turnLifecycleService.clearSessions(userId, persona)

    /**
     * 调用 OpenAI Chat Completions API
     */
    private fun callLLM(
        messages: List<Map<String, String>>,
        profile: GenerationProfile = generationProfile(AgentIntent.GENERAL_CHAT),
        providerCallContext: ProviderCallContext
    ): LlmCallResult {
        // Demo replies are opt-in for local development. Production must not
        // persist a fabricated assistant answer as if it came from a model.
        if (aiAgentProperties.apiKey.isBlank()) {
            if (!aiAgentProperties.demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            return LlmCallResult(
                "你好！我是娇颜颂的AI助手，目前处于演示模式。配置 AI Agent 凭据后即可使用完整的AI对话功能。",
                true
            )
        }

        val startedAt = System.nanoTime()
        return try {
            val url = providerChatCompletionsUrl()
            val headers = HttpHeaders().apply {
                setBearerAuth(aiAgentProperties.apiKey)
                contentType = MediaType.APPLICATION_JSON
            }
            val body = AgentProviderRequestFactory.build(
                provider = aiAgentProperties.provider ?: error("AI provider is required"),
                model = aiAgentProperties.model,
                messages = messages,
                maxOutputTokens = profile.maxOutputTokens,
                purpose = AgentRequestPurpose.CHAT
            )
            val response = restTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
            val responseBody = response.body
            val choices = responseBody?.get("choices") as? List<*>
            val firstChoice = choices?.firstOrNull() as? Map<*, *>
            val message = firstChoice?.get("message") as? Map<*, *>
            val content = message?.get("content") as? String
            if (content.isNullOrBlank() && !aiAgentProperties.demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            LlmCallResult(
                content = content ?: "抱歉，我暂时无法回答这个问题。",
                fallbackUsed = content == null
            )
        } catch (e: Exception) {
            logProviderFailure(
                providerCallContext,
                "MODEL_COMPLETION",
                messages,
                e,
                startedAt
            )
            if (!aiAgentProperties.demoFallbackEnabled) {
                throw IllegalStateException(if (isProviderTimeout(e)) "AI_PROVIDER_TIMEOUT" else "AI_PROVIDER_UNAVAILABLE")
            }
            // 判断是否为代理/连接层面的错误（代理未配置、代理不可达、无法连接到目标服务器）
            val cause = e.cause
            val isProxyOrConnectionIssue = e is org.springframework.web.client.ResourceAccessException &&
                (cause is java.net.ConnectException ||
                 cause is java.net.UnknownHostException ||
                 (cause is java.io.IOException && cause !is java.net.SocketTimeoutException))
            if (isProxyOrConnectionIssue) {
                logger.warn("Agent provider call failed; fallback enabled code=AI_PROVIDER_UNAVAILABLE")
                // 代理/网络问题：返回友好提示
                LlmCallResult("你好！我是娇颜颂的AI助手，目前网络暂时无法连接AI服务。请稍后再试。", true)
            } else {
                logger.warn("Agent provider call failed; fallback enabled code=AI_PROVIDER_UNAVAILABLE")
                LlmCallResult("抱歉，AI服务暂时不可用，请稍后再试。", true)
            }
        }
    }

    private fun normalizePersona(persona: String): String {
        val normalized = persona.trim().uppercase()
        require(normalized in setOf("BESTIE", "CONSULTANT")) { "不支持的 AI 角色" }
        return normalized
    }

    private fun boundedRouteContext(
        historyMessages: List<ChatMessageEntity>,
        summary: AgentSessionSummary?,
        contextType: String
    ): BoundedRouteContext {
        val allSummaryDecisions = summary?.unresolvedTopics.orEmpty().mapNotNull {
            summaryContextDecision(it, agentIntentRouter)
        }
        val summaryDecisions = allSummaryDecisions.filterNot {
            it.intent in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)
        }
        val recentUserMessages = historyMessages.filter { it.role.equals("USER", true) }.takeLast(4)
        val historyAssessments = recentUserMessages.map { agentIntentRouter.assessCurrent(it.content, "GENERAL") }
        val assistantMessagesByTurnId = historyMessages.asSequence()
            .filter { it.role.equals("ASSISTANT", ignoreCase = true) }
            .mapNotNull { message ->
                val turnId = message.turnId?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                turnId to message
            }
            .toMap()
        val sessionTarget = runCatching { AgentQueryTarget.valueOf(contextType.trim().uppercase()) }.getOrNull()
        val sessionDecision = sessionTarget
            ?.let { agentIntentRouter.validatedDecision(AgentIntent.CATALOG_QA, it) }
        val detailAssessments = if (sessionTarget == null) {
            historyAssessments
        } else {
            recentUserMessages.map { agentIntentRouter.assessCurrent(it.content, contextType) }
        }
        val persistedFocusEvents = recentUserMessages.mapIndexed { index, message ->
            val assistant = message.turnId?.let(assistantMessagesByTurnId::get) ?: return@mapIndexed null
            persistedFocusEvent(assistant, historyAssessments[index])
        }
        val completeLegacyHistory = sessionTarget?.let {
            completeLegacyDetailHistory(historyMessages, summary, it, assistantMessagesByTurnId)
        }
        val rawSummaryFocus = summaryDecisions.lastOrNull()
        val incompleteLegacyHistory = completeLegacyHistory == null && (
            (summary?.lastSummarizedSequence ?: 0) > 0 || historyMessages.isNotEmpty()
            )
        val legacyDetailMustFailClosed = summary?.focusSemanticsVersion == 0 &&
            sessionTarget != null && (
                completeLegacyHistory?.entryActive == false ||
                    incompleteLegacyHistory ||
                    retainedHistoryProvesDetailSuperseded(
                        historyMessages,
                        sessionTarget,
                        assistantMessagesByTurnId
                    )
                )
        val ambiguousLegacyOverlay = summary?.focusSemanticsVersion == 0 &&
            allSummaryDecisions.lastOrNull()?.intent in setOf(
                AgentIntent.SAFETY_SCREENING,
                AgentIntent.HUMAN_CONSULTATION
            ) &&
            (sessionTarget == null || completeLegacyHistory?.entryActive != true)
        val legacyTrailingSocial = sessionTarget != null &&
            rawSummaryFocus?.intent == AgentIntent.GENERAL_CHAT &&
            rawSummaryFocus.queryTarget == null &&
            completeLegacyHistory?.entryActive == true &&
            completeLegacyHistory.sawLegacySocial
        val summaryFocus = when {
            legacyDetailMustFailClosed -> null
            ambiguousLegacyOverlay -> null
            legacyTrailingSocial -> summaryDecisions.dropLast(1).lastOrNull()
            else -> rawSummaryFocus
        }
        val summarySupersedesDetail = sessionTarget != null && (
            legacyDetailMustFailClosed ||
                ambiguousLegacyOverlay ||
                (summaryFocus != null && summaryFocus.queryTarget != sessionTarget)
            )
        var focus = when {
            summarySupersedesDetail -> summaryFocus
            sessionDecision != null -> sessionDecision
            else -> summaryFocus
        }
        var focusReasons = emptyList<String>()
        var detailContextSuperseded = summarySupersedesDetail
        historyAssessments.forEachIndexed { index, assessment ->
            val persistedFocus = persistedFocusEvents[index]
            if (persistedFocus != null) {
                when (persistedFocus.update) {
                    ConversationFocusUpdate.PRESERVE -> Unit
                    ConversationFocusUpdate.CLEAR -> {
                        focus = null
                        focusReasons = emptyList()
                        detailContextSuperseded = detailContextSuperseded || sessionTarget != null
                    }
                    ConversationFocusUpdate.SET -> {
                        focus = persistedFocus.decision
                        focusReasons = emptyList()
                        detailContextSuperseded = detailContextSuperseded || sessionTarget != null
                    }
                }
                return@forEachIndexed
            }
            if (sessionTarget != null && supersedesEntryDetail(detailAssessments[index], sessionTarget)) {
                detailContextSuperseded = true
            }
            if (assessment.decision.intent in setOf(
                    AgentIntent.SAFETY_SCREENING,
                    AgentIntent.HUMAN_CONSULTATION
                ) || "SOCIAL_INTERJECTION" in assessment.ambiguityReasons
            ) {
                return@forEachIndexed
            }
            if (isConversationTopicBoundary(assessment)) {
                focus = null
                focusReasons = emptyList()
                return@forEachIndexed
            }
            if (assessment.decision.queryTarget != null) {
                focus = assessment.decision
                focusReasons = assessment.ambiguityReasons
            }
        }
        return BoundedRouteContext(
            decisions = listOfNotNull(focus),
            ambiguityReasons = focusReasons
                .filterNot {
                    it in setOf(
                        "DETAIL_CONTEXT_FOLLOW_UP",
                        "NEGATED_CURRENT_REFERENCE",
                        "ALTERNATIVE_ENTITY_REQUEST"
                    )
                }
                .distinct(),
            detailContextSuperseded = detailContextSuperseded
        )
    }

    private fun completeLegacyDetailHistory(
        historyMessages: List<ChatMessageEntity>,
        summary: AgentSessionSummary?,
        sessionTarget: AgentQueryTarget,
        assistantMessagesByTurnId: Map<String, ChatMessageEntity>
    ): CompleteLegacyDetailHistory? {
        val summarizedMessageSequence = summary?.lastSummarizedSequence
            ?.takeIf { it > 0 }
            ?.let { sequence -> runCatching { Math.multiplyExact(sequence, 2L) }.getOrNull() }
            ?: return null
        val completeHistory = historyMessages
            .filter { it.sequenceNo <= summarizedMessageSequence }
            .sortedBy { it.sequenceNo }
        if (completeHistory.size.toLong() != summarizedMessageSequence) return null
        if (completeHistory.withIndex().any { (index, message) -> message.sequenceNo != index + 1L }) return null

        var entryActive = true
        var sawLegacySocial = false
        completeHistory.filter { it.role.equals("USER", ignoreCase = true) }.forEach { userMessage ->
            val assistant = userMessage.turnId?.let(assistantMessagesByTurnId::get) ?: return null
            val generalAssessment = agentIntentRouter.assessCurrent(userMessage.content, "GENERAL")
            when (persistedFocusEvent(assistant, generalAssessment)?.update) {
                ConversationFocusUpdate.PRESERVE -> Unit
                ConversationFocusUpdate.CLEAR,
                ConversationFocusUpdate.SET -> entryActive = false
                null -> {
                    if ("SOCIAL_INTERJECTION" in generalAssessment.ambiguityReasons) {
                        sawLegacySocial = true
                    } else {
                        val detailAssessment = agentIntentRouter.assessCurrent(userMessage.content, sessionTarget.name)
                        if (
                            supersedesEntryDetail(detailAssessment, sessionTarget) ||
                            isConversationTopicBoundary(generalAssessment)
                        ) {
                            entryActive = false
                        }
                    }
                }
            }
        }
        return CompleteLegacyDetailHistory(entryActive, sawLegacySocial)
    }

    private fun retainedHistoryProvesDetailSuperseded(
        historyMessages: List<ChatMessageEntity>,
        sessionTarget: AgentQueryTarget,
        assistantMessagesByTurnId: Map<String, ChatMessageEntity>
    ): Boolean = historyMessages.asSequence()
        .filter { it.role.equals("USER", ignoreCase = true) }
        .sortedBy { it.sequenceNo }
        .any { userMessage ->
            val generalAssessment = agentIntentRouter.assessCurrent(userMessage.content, "GENERAL")
            val persistedFocus = userMessage.turnId
                ?.let(assistantMessagesByTurnId::get)
                ?.let { persistedFocusEvent(it, generalAssessment) }
            when (persistedFocus?.update) {
                ConversationFocusUpdate.CLEAR,
                ConversationFocusUpdate.SET -> true
                ConversationFocusUpdate.PRESERVE -> false
                null -> {
                    val detailAssessment = agentIntentRouter.assessCurrent(userMessage.content, sessionTarget.name)
                    supersedesEntryDetail(detailAssessment, sessionTarget) ||
                        isConversationTopicBoundary(generalAssessment)
                }
            }
        }

    private fun persistedFocusEvent(
        message: ChatMessageEntity,
        pairedUserAssessment: AgentRouteAssessment
    ): PersistedFocusEvent? {
        val metadata = runCatching { objectMapper.readTree(message.metadataJson.ifBlank { "{}" }) }.getOrNull()
            ?: return null
        val focusNode = metadata.get("conversationFocusUpdate")
        val update = if (focusNode == null || focusNode.isNull) {
            val legacyIntent = metadata.path("intent").asText("").trim().uppercase()
                .let { value -> runCatching { AgentIntent.valueOf(value) }.getOrNull() }
            if (legacyIntent !in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)) return null
            if ("EXPLICIT_TOPIC_BOUNDARY" in pairedUserAssessment.ambiguityReasons) {
                ConversationFocusUpdate.CLEAR
            } else {
                ConversationFocusUpdate.PRESERVE
            }
        } else {
            focusNode.asText()
                .let { value -> runCatching { ConversationFocusUpdate.valueOf(value) }.getOrNull() }
                ?: return null
        }
        if (update != ConversationFocusUpdate.SET) return PersistedFocusEvent(update)
        val intent = metadata.path("intent").asText("").trim().uppercase()
            .let { value -> runCatching { AgentIntent.valueOf(value) }.getOrNull() }
            ?: return null
        val target = metadata.get("queryTarget")
            ?.takeUnless { it.isNull }
            ?.asText()
            ?.trim()
            ?.uppercase()
            ?.let { value -> runCatching { AgentQueryTarget.valueOf(value) }.getOrNull() }
        return PersistedFocusEvent(update, agentIntentRouter.validatedDecision(intent, target))
    }

    private fun isConversationTopicBoundary(assessment: AgentRouteAssessment): Boolean =
        assessment.decision.intent == AgentIntent.GENERAL_CHAT &&
            assessment.decision.queryTarget == null &&
            !assessment.requiresContextCompletion &&
            assessment.ambiguityReasons.none {
                it in setOf(
                    "DETAIL_CONTEXT_FOLLOW_UP",
                    "UNRESOLVED_CURRENT_REFERENCE",
                    "SOCIAL_INTERJECTION"
                )
            }

    private fun supersedesEntryDetail(
        assessment: AgentRouteAssessment,
        entryTarget: AgentQueryTarget
    ): Boolean {
        if (assessment.ambiguityReasons.any {
                it in setOf("NEGATED_CURRENT_REFERENCE", "ALTERNATIVE_ENTITY_REQUEST")
            }) {
            return true
        }
        if (assessment.decision.intent in setOf(AgentIntent.SAFETY_SCREENING, AgentIntent.HUMAN_CONSULTATION)) {
            return false
        }
        if (assessment.ambiguityReasons.any {
                it in setOf("DETAIL_CONTEXT_FOLLOW_UP", "UNRESOLVED_CURRENT_REFERENCE")
            }) {
            return false
        }
        if ("SOCIAL_INTERJECTION" in assessment.ambiguityReasons) return false
        val target = assessment.decision.queryTarget
        if (assessment.explicitQueryTarget && target != null) {
            return target != entryTarget || assessment.decision.intent != AgentIntent.DETAIL_SUMMARY
        }
        return assessment.decision.intent == AgentIntent.GENERAL_CHAT
    }

    private fun parseAmbiguousRoute(
        rawQuery: String,
        local: AgentRouteAssessment,
        boundedContext: List<AgentIntentDecision>,
        providerCallContext: ProviderCallContext
    ): ParsedAgentRoute? {
        if (aiAgentProperties.apiKey.isBlank()) return null
        val startedAt = System.nanoTime()
        return try {
            val url = providerChatCompletionsUrl()
            val headers = HttpHeaders().apply {
                setBearerAuth(aiAgentProperties.apiKey)
                contentType = MediaType.APPLICATION_JSON
            }
            val context = boundedContext.takeLast(4).joinToString(", ") {
                "${it.intent}/${it.queryTarget ?: "NONE"}"
            }.ifBlank { "NONE" }
            val humanOverrideAllowed = agentIntentRouter.allowsHumanContextOverride(local)
            val ordinaryIntentLocked = local.explicitIntent ||
                (local.contextResolvedQueryTarget && !humanOverrideAllowed)
            val ordinaryTargetLocked = local.explicitQueryTarget || local.contextResolvedQueryTarget
            val instruction = """
                Classify one medical-aesthetic chat request. Return JSON only:
                {"intent":"GENERAL_CHAT|CATALOG_QA|COMPARISON|PLANNING|DETAIL_SUMMARY|HUMAN_CONSULTATION|SAFETY_SCREENING","intents":["optional additional intent labels"],"queryTarget":"INSTITUTION|DOCTOR|PROJECT|INSTITUTION_PROJECT|null","keywords":["..."]}
                Use SAFETY_SCREENING for possible contraindications or health risks. Use PLANNING for goals with budget, downtime or personal constraints.
                Use HUMAN_CONSULTATION only when the user asks to speak with or transfer to a real person or consultant.
                For HUMAN_CONSULTATION, queryTarget must be INSTITUTION or null.
                Never return a consultant user ID or invent an institution.
                Keywords may contain only useful cities, treatments, categories, tags, clinic names or doctor names from the text. Maximum 8 items. Do not invent IDs or facts.
                Local decision: ${local.decision.intent}/${local.decision.queryTarget ?: "NONE"}.
                Route policy: ordinaryIntentLocked=$ordinaryIntentLocked, ordinaryTargetLocked=$ordinaryTargetLocked, humanOverrideAllowed=$humanOverrideAllowed.
                A context-inherited target remains locked for ordinary intents. Only when humanOverrideAllowed=true and the current request is HUMAN_CONSULTATION may the inherited target become INSTITUTION or null. SAFETY_SCREENING may always clear the target.
                You may fill only fields allowed by this route policy.
            """.trimIndent()
            val parserMessages = listOf(
                mapOf("role" to "system", "content" to instruction),
                mapOf("role" to "user", "content" to "Current: $rawQuery\nBounded route context: $context")
            )
            val body = AgentProviderRequestFactory.build(
                provider = aiAgentProperties.provider ?: error("AI provider is required"),
                model = aiAgentProperties.resolvedIntentModel(),
                messages = parserMessages,
                maxOutputTokens = 180,
                purpose = AgentRequestPurpose.INTENT
            )
            val response = intentParserRestTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
            val choices = response.body?.get("choices") as? List<*>
                ?: throw IntentParserRouteException("MALFORMED_PAYLOAD")
            val choice = choices.firstOrNull() as? Map<*, *>
                ?: throw IntentParserRouteException("MALFORMED_PAYLOAD")
            val message = choice["message"] as? Map<*, *>
                ?: throw IntentParserRouteException("MALFORMED_PAYLOAD")
            val content = message["content"] as? String
                ?: throw IntentParserRouteException("MALFORMED_PAYLOAD")
            val node = objectMapper.factory.createParser(content.trim()).use { parser ->
                val parsed: com.fasterxml.jackson.databind.JsonNode = objectMapper.readTree(parser)
                    ?: throw IntentParserRouteException("MALFORMED_PAYLOAD")
                if (parser.nextToken() != null) throw IntentParserRouteException("INVALID_SCHEMA")
                parsed
            }
            if (!node.isObject) throw IntentParserRouteException("INVALID_SCHEMA")
            val requiredFields = setOf("intent", "queryTarget", "keywords")
            val allowedFields = requiredFields + "intents"
            val actualFields = node.fieldNames().asSequence().toSet()
            if (!actualFields.containsAll(requiredFields) || !allowedFields.containsAll(actualFields)) {
                throw IntentParserRouteException("INVALID_SCHEMA")
            }
            val intentNode = node.get("intent")
                ?.takeIf { it.isTextual }
                ?: throw IntentParserRouteException("INVALID_SCHEMA")
            val intent = AgentIntent.entries.firstOrNull { it.name == intentNode.asText().trim() }
                ?: throw IntentParserRouteException("INVALID_ENUM")
            val intents = node.get("intents")?.let { intentsNode ->
                if (!intentsNode.isArray || intentsNode.size() > AgentIntent.entries.size) {
                    throw IntentParserRouteException("INVALID_SCHEMA")
                }
                if (intentsNode.any { !it.isTextual }) throw IntentParserRouteException("INVALID_SCHEMA")
                intentsNode.mapTo(mutableSetOf()) { member ->
                    AgentIntent.entries.firstOrNull { it.name == member.asText().trim() }
                        ?: throw IntentParserRouteException("INVALID_ENUM")
                }
            }.orEmpty()
            val targetNode = node.get("queryTarget")
                ?: throw IntentParserRouteException("INVALID_SCHEMA")
            val target = when {
                targetNode.isNull -> null
                !targetNode.isTextual -> throw IntentParserRouteException("INVALID_SCHEMA")
                else -> AgentQueryTarget.entries.firstOrNull { it.name == targetNode.asText().trim() }
                    ?: throw IntentParserRouteException("INVALID_ENUM")
            }
            val keywordsNode = node.get("keywords")
                ?.takeIf { it.isArray && it.size() <= 8 }
                ?: throw IntentParserRouteException("INVALID_SCHEMA")
            if (keywordsNode.any { !it.isTextual }) throw IntentParserRouteException("INVALID_SCHEMA")
            val keywords = keywordsNode.map { it.asText().trim() }
            if (keywords.any { it.length !in 2..40 }) throw IntentParserRouteException("INVALID_SCHEMA")
            val parsedRoute = ParsedAgentRoute(intent, target, keywords, intents)
            if (!agentIntentRouter.isCompatibleParsedRoute(local, parsedRoute)) {
                throw IntentParserRouteException("INCOMPATIBLE_ROUTE")
            }
            parsedRoute
        } catch (error: Exception) {
            runCatching {
                logProviderFailure(
                    providerCallContext,
                    "INTENT_CLASSIFICATION",
                    emptyList(),
                    error,
                    startedAt,
                    aiAgentProperties.resolvedIntentModel()
                )
            }.onFailure { diagnosticError ->
                logger.warn("Agent intent parser diagnostics failed category={}", diagnosticError.javaClass.simpleName)
            }
            logger.info(
                "Agent intent parser fallback category={} durationMs={}",
                intentParserFailureCategory(error),
                elapsedMs(startedAt)
            )
            null
        }
    }

    private fun intentParserFailureCategory(error: Exception): String = when {
        error is IntentParserRouteException -> error.category
        isProviderTimeout(error) -> "TIMEOUT"
        error is org.springframework.web.client.HttpStatusCodeException -> "HTTP_ERROR"
        else -> "PROVIDER_ERROR"
    }

    private class IntentParserRouteException(val category: String) : IllegalArgumentException(category)

    /** Reserved SSE path. Streaming remains disabled by fixed runtime policy. */
    /**
     * 根据 persona 获取角色名称
     */
    private fun getPersonaName(persona: String): String {
        return when (persona) {
            "BESTIE" -> "AI闺蜜小颜"
            "CONSULTANT" -> "AI美学咨询师娇娇"
            "CS" -> "平台客服小娇"
            else -> "AI助手"
        }
    }

    /**
     * 根据 persona 获取系统提示词（支持上下文感知注入）
     */
    private fun getSystemPrompt(
        persona: String,
        contextType: String,
        contextId: String,
        intentDecision: AgentIntentDecision,
        labelSummary: GenerationLabelSummary,
        evidence: AgentPromptEvidence,
        useDetailContext: Boolean
    ): PromptBuildResult {
        val basePrompt = when (persona) {
            "BESTIE" -> "你是娇颜颂的AI闺蜜「小颜」。你性格活泼开朗、善解人意，像一个贴心的好朋友。你关心用户的日常状态，会适时提醒术后护理、鼓励记录变美日记。聊天语气轻松友好，偶尔用可爱的表情。当用户问到专业医美问题时，温柔地建议咨询专业美学咨询师。"
            "CONSULTANT" -> "你是娇颜颂的专业AI医美助手。像一位有经验、克制且真诚的真人咨询师一样交流：自然、温和、具体，不端着说话，不使用客服腔或模板腔。你能解释项目原理、适合人群、风险、恢复期和价格，但不会夸大效果。必要时用一句自然的话提醒用户面诊确认。"
            "CS" -> "你是娇颜颂平台的官方客服「小娇」。你负责解答用户关于平台使用、订单问题、售后服务、投诉建议等方面的问题。请用专业、友善、耐心的语气回答。遇到无法解答的具体技术问题，建议用户拨打客服热线或通过帮助中心提交工单。"
            else -> "你是娇颜颂的AI助手。"
        }

        // 注入上下文信息
        val responsePolicy = """
            回答规则（按优先级执行）：
            1. 使用用户当前语言回答，不展示思维过程，不复述问题。开头直接回应用户，像真人接话，不要写“结论：”“分析如下：”“根据你的问题：”“综上所述：”或英文的“Conclusion:”“Analysis:”。
            2. 默认控制在60至180字、1至3个短段；简单问题用1至3句。只回答当前问题最重要的部分，不主动把原理、适合人群、风险、恢复期、价格和护理全部讲完。仅当用户明确要求详细方案、完整对比或逐项说明时才展开。
            3. 平台数据库是机构、医生、项目、机构项目、价格和评价数据的首选事实来源。数据库已有结果时直接作答，不进行外部联网搜索，也不要求用户重复提供名称。
            4. 仅在平台数据库没有相关记录，或用户明确询问实时政策、最新研究、平台外机构时，才考虑外部信息；必须说明信息来源和可能的时效性。不得用外部内容覆盖平台内部事实。
            5. 数据库有结果时，不要机械复述记录、逐家报名称或重复罗列价格、评分、认证和销量。优先用2至4句话概括最相关的1至2个发现；结构化报告已负责展示明细。只有用户继续追问某家机构、医生、项目或具体维度时才展开。
            6. 对比默认只说最明显的差异和选择方向，不逐项念表格。用户明确要求完整对比时，最多呈现3至5个关键维度，并避免长篇通用科普、重复免责声明和营销措辞。
            7. 不保证疗效，不作诊断；仅在存在禁忌证、明显风险或必须面诊时给出一句必要提醒。
            8. 用户诉求模糊时，先根据最可能的含义给出简短、可执行的初步答案；确有必要时结尾最多追问一个关键问题。不得只回复“请提供更多信息”。
            9. 简单问答保持自然段，不强行加标题。只有用户要求详细回答，或内容确实包含多个独立部分时，才使用2至3个简短小标题。
            10. 中文小标题使用“怎么选”“价格与恢复”“需要留意”等自然短语，不加“结论”“分析”“第一部分”等标签；英文使用“Best fit”“Price and downtime”“What to watch”等自然短语。
            11. 不要重复用户称呼、风险提示或“建议咨询医生”。回答当前问题后自然收住，把次要信息留给用户继续追问。
            12. 面向用户时不要使用“命中、命中集、检索结果、字段、记录、数据库返回、召回、实体”等系统或AI术语。自然地说“平台上查到”“平台资料显示”“目前可以看到”“资料中暂未注明”。英文避免使用 hit、retrieval result、database record、field 等内部表达，改用 “I found on the platform”“the profile shows”“the platform does not currently list”。
        """.trimIndent()
        val contextInfo = if (useDetailContext) buildContextInfo(contextType, contextId) else ""
        val detailSessionPolicy = if (useDetailContext) """
            当前处于详情会话。回答时优先围绕当前页面实体，不要跳出到泛泛科普；如果用户追问价格、恢复期、风险、医生或机构，直接基于当前页面给出简短回答。
        """.trimIndent() else ""
        val summaryMode = intentDecision.intent == AgentIntent.DETAIL_SUMMARY
        val databaseContext = evidence.context
        val evidenceInstruction = when {
            summaryMode -> """
                【本轮详情页总结约束】
                这是当前页面的简短概括任务。只概括 1 至 2 点，不要逐条罗列价格、评分、标签、机构或医生列表。
                页面下方会单独展示当前实体卡片，正文不要重复卡片字段。
            """.trimIndent()
            intentDecision.intent == AgentIntent.PLANNING -> planningGroundingPrompt(evidence)
            databaseContext.isBlank() -> ""
            else -> """
                【本轮最终平台数据库检索结果】
                $databaseContext
                这是本轮搜索完成后的最终命中集，也是回复气泡下方卡片的数据来源，优先级高于此前对话中的任何数据库描述。
                只能依据这些记录陈述具体机构、医生、项目、城市与价格，不得补造字段。
                命中集中出现某城市的记录时，禁止声称该城市没有相关记录；没有得到明确的反向检索证据时，也不要主动断言其他城市没有记录。
                正文只简要总结最相关的1至2点，不逐条复述卡片内容，等待用户继续追问后再展开。
                上述“命中集、记录、字段”等词只用于内部约束，绝对不要原样写给用户；对外改用自然、生活化的说法。
            """.trimIndent()
        }
        val intentPolicy = when (intentDecision.intent) {
            AgentIntent.SAFETY_SCREENING -> """
                本轮涉及潜在安全风险。先明确建议暂停自行决策，不推荐具体项目、机构或套餐；
                简短说明需要向合格医生确认的原因，并最多追问一个会影响安全判断的问题。
                不作诊断，不弱化孕期、哺乳期、感染、严重过敏、用药或瘢痕风险。
            """.trimIndent()
            AgentIntent.DETAIL_SUMMARY -> """
                本轮是详情页总结。正文只用 1 至 2 句概括当前页面最重要的信息和一个需要留意的点，不要逐条罗列价格、评分、标签、机构或医生列表；页面下方会单独展示可跳转卡片。
            """.trimIndent()
            AgentIntent.PLANNING -> """
                本轮是规划诉求，仅作为信息参考，不构成诊断或治疗建议。
                不得声称“最适合”“为你制定”或已结合恢复期、疼痛偏好完成排序。
                不得从简介、宣传语或详情自由文本推断恢复期、疼痛、禁忌或风险事实；
                平台没有对应结构化数据时，明确回答“需向机构确认”。
                缺少健康筛查、预算、恢复期或疼痛偏好时，引导用户进入需求档案和安全筛查，
                不在普通聊天中伪造完整治疗计划。
            """.trimIndent()
            else -> ""
        }
        val labelPolicy = labelSummary.promptConstraint()
        return PromptBuildResult(
            prompt = listOf(
                basePrompt,
                responsePolicy,
                contextInfo,
                detailSessionPolicy,
                intentPolicy,
                labelPolicy
            ).filter(String::isNotBlank).joinToString("\n\n"),
            groundingPrompt = evidenceInstruction,
            evidence = evidence
        )
    }

    private fun elapsedMs(startedAt: Long): Long = (System.nanoTime() - startedAt) / 1_000_000

    private fun logProviderFailure(
        context: ProviderCallContext,
        phase: String,
        messages: List<Map<String, String>>,
        error: Throwable,
        startedAt: Long,
        modelName: String = aiAgentProperties.model
    ) {
        val responseError = generateSequence<Throwable>(error) { it.cause }
            .filterIsInstance<RestClientResponseException>()
            .firstOrNull()
        val rootCause = generateSequence<Throwable>(error) { it.cause }.last()
        val httpStatus = responseError?.statusCode?.value()
        val category = when {
            httpStatus in setOf(401, 403) -> "AUTH"
            httpStatus == 429 -> "RATE_LIMIT"
            httpStatus == 404 -> "MODEL_NOT_FOUND"
            httpStatus == 400 -> "INVALID_REQUEST"
            httpStatus != null && httpStatus in 500..599 -> "UPSTREAM_5XX"
            rootCause is java.net.SocketTimeoutException -> "READ_TIMEOUT"
            rootCause is java.net.ConnectException -> "CONNECT_TIMEOUT"
            error is ResourceAccessException -> "NETWORK"
            responseError == null -> "INVALID_RESPONSE"
            else -> "UNKNOWN"
        }
        val providerHost = runCatching { URI(aiAgentProperties.baseUrl.trim()).host }.getOrNull().orEmpty()
        agentOperationLogger.providerFailed(
            traceId = context.traceId,
            turnId = context.turnId,
            providerPhase = phase,
            providerHost = providerHost,
            modelName = modelName,
            httpStatus = httpStatus,
            providerCategory = category,
            providerErrorCode = stableProviderErrorCode(category),
            exceptionType = (responseError ?: rootCause).javaClass.simpleName,
            durationMs = elapsedMs(startedAt),
            messageCount = messages.size,
            systemMessageCount = messages.count { it["role"].equals("system", ignoreCase = true) },
            totalCharacterCount = messages.sumOf { it["content"].orEmpty().length }
        )
    }

    private fun stableProviderErrorCode(category: String): String? = when (category) {
        "AUTH" -> "authentication_failed"
        "RATE_LIMIT" -> "rate_limit_exceeded"
        "MODEL_NOT_FOUND" -> "model_not_found"
        "INVALID_REQUEST" -> "invalid_request"
        "UPSTREAM_5XX" -> "upstream_error"
        else -> null
    }

    private fun naturalizeUserFacingLanguage(content: String): String = content
        .replace("目前命中到", "目前可以看到")
        .replace("命中到", "查到")
        .replace("最终命中集", "平台上的相关信息")
        .replace("命中集", "相关信息")
        .replace("命中", "找到")
        .replace("这条记录里", "现有资料中")
        .replace("记录里", "资料中")
        .replace("检索结果", "平台信息")
        .replace("平台数据库", "平台资料")
        .replace("数据库", "平台资料")
        .replace("字段", "资料")

    private inline fun <T> withCurrentTurnLanguage(content: String, block: () -> T): T {
        val previousLocale = LocaleContextHolder.getLocale()
        LocaleContextHolder.setLocale(currentTurnLocale(content, previousLocale))
        return try {
            block()
        } finally {
            LocaleContextHolder.setLocale(previousLocale)
        }
    }

    private fun currentTurnLocale(content: String, fallback: Locale): Locale {
        val hasHan = hanScriptRegex.containsMatchIn(content)
        val latinWordCount = latinWordRegex.findAll(content).count()
        return when {
            !hasHan && latinWordCount > 0 -> Locale.ENGLISH
            hasHan && latinWordCount == 0 -> Locale.SIMPLIFIED_CHINESE
            hasHan && englishLeadRegex.containsMatchIn(content) -> Locale.ENGLISH
            hasHan && latinWordCount >= 3 -> Locale.ENGLISH
            hasHan -> Locale.SIMPLIFIED_CHINESE
            fallback.language.equals("zh", ignoreCase = true) -> Locale.SIMPLIFIED_CHINESE
            else -> Locale.ENGLISH
        }
    }

    private fun currentTurnLanguagePolicy(content: String): String =
        if (currentTurnLocale(content, LocaleContextHolder.getLocale()).language == "zh") {
            "本轮语言要求：当前用户消息使用中文。只用中文回答，不要附加英文翻译，即使历史消息使用英文。"
        } else {
            "Turn language requirement: The current user message is in English. Answer in English only. " +
                "Do not add a Chinese translation, even if earlier messages are in Chinese."
        }

    private fun planningGroundingPrompt(evidence: AgentPromptEvidence): String {
        val items = PlanningCatalogProjection.projectItems(evidence.report?.items.orEmpty()).take(4).map { item ->
            linkedMapOf<String, Any?>(
                "type" to item.type,
                "id" to item.id,
                "name" to item.name,
                "institutionId" to item.institutionId,
                "projectId" to item.projectId,
                "attributes" to item.attributes
            ).filterValues { value -> value != null && value != "" && value != emptyMap<String, String>() }
        }
        val safeCatalogJson = objectMapper.writeValueAsString(items)
        return """
            【本轮规划信息参考】
            以下 JSON 仅包含平台目录标识、名称和可安全引用的结构化属性：$safeCatalogJson
            不得使用卡片摘要、简介、宣传语、详情文本或历史消息补充恢复期、疼痛、禁忌、风险、疗效或个人适用性。
            缺少结构化资料时统一说明“需向机构确认”。这些项目只作为信息参考，不构成诊断或治疗建议。
        """.trimIndent()
    }

    private fun enforcePlanningBoundary(intent: AgentIntent, content: String): String {
        if (intent != AgentIntent.PLANNING) return content
        return PlanningCatalogProjection.safeContent()
    }

    private fun isProviderTimeout(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            if (current is java.net.SocketTimeoutException) return true
            current = current.cause
        }
        return false
    }

    private fun providerChatCompletionsUrl(): String =
        "${aiAgentProperties.baseUrl.trim().trimEnd('/')}/chat/completions"

    private fun <T> hideMissingResource(block: () -> T): T = try {
        block()
    } catch (_: IllegalArgumentException) {
        throw AgentChatException.sessionNotFound()
    }

    /**
     * 根据上下文类型和ID查询实体信息，构建上下文提示段落
     */
    private fun buildContextInfo(contextType: String, contextId: String): String {
        if (contextId.isBlank() || contextType == "GENERAL") return ""
        return try {
            when (contextType.uppercase()) {
                "PROJECT" -> {
                    projectRepository.findById(contextId).orElse(null)?.let { project ->
                        val priceText = if (project.referencePrice > java.math.BigDecimal.ZERO) "$${project.referencePrice}" else "未设定"
                        "【当前上下文】用户正在了解医美项目「${project.name}」。\n" +
                            "项目简介：${project.description.ifBlank { "暂无描述" }}\n" +
                            "参考价格：$priceText\n" +
                            "项目标语：${project.slogan.ifBlank { "无" }}\n" +
                            "请围绕该项目提供专业解答。"
                    } ?: ""
                }
                "INSTITUTION" -> {
                    institutionRepository.findById(contextId).orElse(null)?.let { inst ->
                        "【当前上下文】用户正在了解医美机构「${inst.name}」。\n" +
                            "机构简介：${inst.description.ifBlank { "暂无描述" }}\n" +
                            "特色项目：${inst.specialties.ifBlank { "未设定" }}\n" +
                            "地址：${inst.address.ifBlank { "未提供" }}\n" +
                            "请围绕该机构提供专业解答。"
                    } ?: ""
                }
                "INSTITUTION_PROJECT" -> {
                    institutionProjectRepository.findById(contextId).orElse(null)?.takeIf { it.isActive }?.let { offering ->
                        val project = projectRepository.findById(offering.projectId).orElse(null)
                        val institution = institutionRepository.findById(offering.institutionId).orElse(null)
                        val effective = project?.let { institutionProjectDetailResolver.resolve(offering, it) }
                        "【当前上下文】用户正在了解机构项目「${effective?.name ?: contextId}」。\n" +
                            "机构：${institution?.name ?: "未提供"}\n" +
                            "所在城市：${institution?.city ?: "未提供"}\n" +
                            "项目简介：${effective?.description?.ifBlank { "暂无描述" } ?: "暂无描述"}\n" +
                            "分类：${effective?.category?.ifBlank { "未提供" } ?: "未提供"}\n" +
                            "标签：${effective?.tags?.ifBlank { "未提供" } ?: "未提供"}\n" +
                            "宣传语：${effective?.slogan?.ifBlank { "未提供" } ?: "未提供"}\n" +
                            "评分：${effective?.rating ?: "未提供"}\n" +
                            "评价数：${effective?.reviewCount ?: "未提供"}\n" +
                            "详情内容：${contextDetailSummary(effective?.detailContent)}\n" +
                            "机构价格：$${offering.price}\n" +
                            "原价：${offering.originalPrice?.let { "$$it" } ?: "未提供"}\n" +
                            "销量：${offering.salesCount}\n" +
                            "请仅依据以上平台信息给出简短总结，并说明仍需确认的医生、设备或完整费用。"
                    }.orEmpty()
                }
                "DOCTOR" -> {
                    doctorRepository.findPublicById(contextId).orElse(null)?.let { doctor ->
                        val institutionNames = doctorInstitutionService.institutionsFor(doctor.id)
                            .joinToString("、") { it.name }
                        "【当前上下文】用户正在咨询关于「${doctor.name}」医生的问题。\n" +
                            "医生职称：${doctor.title.ifBlank { "未提供" }}\n" +
                            "医生专长：${doctor.specialties.ifBlank { "未设定" }}\n" +
                            "医生简介：${doctor.bio.ifBlank { "暂无简介" }}\n" +
                            "出诊机构：${institutionNames.ifBlank { doctor.institutionName.ifBlank { "未提供" } }}\n" +
                            "请围绕该医生提供专业解答。"
                    } ?: ""
                }
                "JOURNEY" -> {
                    // 医美旅程上下文暂未实现后端实体，静默降级
                    ""
                }
                else -> ""
            }
        } catch (_: Exception) {
            logger.warn("Agent context lookup failed contextType={}", contextType)
            ""
        }
    }

    data class GenerationProfile(
        val historyMessageLimit: Int,
        val maxOutputTokens: Int
    )

    private fun generationProfile(intent: AgentIntent): GenerationProfile = when (intent) {
        AgentIntent.GENERAL_CHAT -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 280
        )
        AgentIntent.HUMAN_CONSULTATION -> GenerationProfile(
            historyMessageLimit = 0,
            maxOutputTokens = 0
        )
        AgentIntent.CATALOG_QA -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 420
        )
        AgentIntent.DETAIL_SUMMARY -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 260
        )
        AgentIntent.COMPARISON, AgentIntent.PLANNING, AgentIntent.SAFETY_SCREENING -> GenerationProfile(
            historyMessageLimit = 6,
            maxOutputTokens = 600
        )
    }

    private fun contextDetailSummary(content: String?): String {
        val plainText = plainTextContent(content)
        return plainText.take(800).ifBlank { "未提供" }
    }

    private fun currentContextCatalogItems(
        contextType: String,
        contextId: String
    ): List<AgentCatalogItemResponse> {
        val normalizedType = contextType.trim().uppercase()
        val normalizedId = contextId.trim()
        if (!isDetailContext(normalizedType) || normalizedId.isBlank()) return emptyList()
        return try {
            when (normalizedType) {
                "PROJECT" -> projectRepository.findById(normalizedId).orElse(null)?.let { project ->
                    listOf(
                        AgentCatalogItemResponse(
                            type = "PROJECT",
                            id = project.id,
                            name = project.name,
                            subtitle = project.category,
                            summary = project.description.ifBlank { project.slogan },
                            attributes = linkedMapOf(
                                AgentText.value("参考价", "Reference price") to "$${project.referencePrice.toPlainString()}",
                                AgentText.value("评分", "Rating") to project.rating.toPlainString(),
                                AgentText.value("评价数", "Reviews") to project.reviewCount.toString(),
                                AgentText.value("标签", "Tags") to project.tags,
                                AgentText.value("宣传语", "Slogan") to project.slogan
                            ).filterValues { it.isNotBlank() },
                            projectId = project.id
                        )
                    )
                }.orEmpty()

                "INSTITUTION" -> institutionRepository.findById(normalizedId).orElse(null)?.let { institution ->
                    listOf(
                        AgentCatalogItemResponse(
                            type = "INSTITUTION",
                            id = institution.id,
                            name = institution.name,
                            subtitle = institution.city,
                            summary = institution.description,
                            attributes = linkedMapOf(
                                AgentText.value("评分", "Rating") to institution.rating.toPlainString(),
                                AgentText.value("评价数", "Reviews") to institution.reviewCount.toString(),
                                AgentText.value("认证", "Verified") to AgentText.value(
                                    if (institution.isVerified) "已认证" else "未认证",
                                    if (institution.isVerified) "Verified" else "Not verified"
                                ),
                                AgentText.value("医生数", "Doctors") to institution.doctorCount.toString(),
                                AgentText.value("项目数", "Projects") to institution.projectCount.toString(),
                                AgentText.value("特色", "Specialties") to institution.specialties,
                                AgentText.value("地址", "Address") to institution.address
                            ).filterValues { it.isNotBlank() },
                            institutionId = institution.id,
                            canChatWithHuman = true
                        )
                    )
                }.orEmpty()

                "DOCTOR" -> doctorRepository.findPublicById(normalizedId).orElse(null)?.let { doctor ->
                    val institutions = doctorInstitutionService.institutionsFor(doctor.id)
                    val selectedInstitution = institutions.firstOrNull { it.id == doctor.institutionId }
                        ?: institutions.firstOrNull()
                    val institutionLabel = institutions.take(2).joinToString("、") { it.name } +
                        if (institutions.size > 2) AgentText.value("等${institutions.size}家", " +${institutions.size - 2}") else ""
                    listOf(
                        AgentCatalogItemResponse(
                            type = "DOCTOR",
                            id = doctor.id,
                            name = doctor.name,
                            subtitle = listOf(doctor.title, institutionLabel.ifBlank { doctor.institutionName })
                                .filter { it.isNotBlank() }
                                .joinToString(" · "),
                            summary = doctor.bio,
                            attributes = linkedMapOf(
                                AgentText.value("评分", "Rating") to doctor.rating.toPlainString(),
                                AgentText.value("评价数", "Reviews") to doctor.reviewCount.toString(),
                                AgentText.value("认证", "Verified") to AgentText.value(
                                    if (doctor.isVerified) "已认证" else "未认证",
                                    if (doctor.isVerified) "Verified" else "Not verified"
                                ),
                                AgentText.value("专长", "Specialties") to doctor.specialties,
                                AgentText.value("资质", "Credentials") to doctor.credentials,
                                AgentText.value("出诊机构", "Clinics") to institutions.joinToString("、") { it.name }
                            ).filterValues { it.isNotBlank() },
                            institutionId = selectedInstitution?.id,
                            canChatWithHuman = selectedInstitution != null
                        )
                    )
                }.orEmpty()

                "INSTITUTION_PROJECT" -> {
                    val offering = institutionProjectRepository.findById(normalizedId).orElse(null)?.takeIf { it.isActive }
                    if (offering == null) {
                        emptyList()
                    } else {
                        val project = projectRepository.findById(offering.projectId).orElse(null)
                        val institution = institutionRepository.findById(offering.institutionId).orElse(null)
                        if (project == null || institution == null) {
                            emptyList()
                        } else {
                            val effective = institutionProjectDetailResolver.resolve(offering, project)
                            listOf(
                                AgentCatalogItemResponse(
                                    type = "INSTITUTION_PROJECT",
                                    id = offering.id,
                                    name = "${institution.name} · ${effective.name}",
                                    subtitle = listOf(institution.city, effective.category)
                                        .filter { it.isNotBlank() }
                                        .joinToString(" · "),
                                    summary = effective.description,
                                    attributes = linkedMapOf(
                                        AgentText.value("机构价格", "Clinic price") to "$${offering.price.toPlainString()}",
                                        AgentText.value("项目参考价", "Reference price") to "$${project.referencePrice.toPlainString()}",
                                        AgentText.value("评分", "Rating") to effective.rating.toPlainString(),
                                        AgentText.value("评价数", "Review count") to effective.reviewCount.toString(),
                                        AgentText.value("标签", "Tags") to effective.tags,
                                        AgentText.value("宣传语", "Slogan") to effective.slogan,
                                        AgentText.value("详情摘要", "Detail summary") to cardDetailSummary(effective.detailContent),
                                        AgentText.value("销量", "Sales") to offering.salesCount.toString(),
                                        AgentText.value("机构认证", "Clinic verified") to AgentText.value(
                                            if (institution.isVerified) "已认证" else "未认证",
                                            if (institution.isVerified) "Verified" else "Not verified"
                                        )
                                    ).filterValues { it.isNotBlank() },
                                    institutionId = institution.id,
                                    projectId = project.id,
                                    canChatWithHuman = true
                                )
                            )
                        }
                    }
                }

                else -> emptyList()
            }
        } catch (_: Exception) {
            logger.warn("Agent context card build failed contextType={}", normalizedType)
            emptyList()
        }
    }

    private fun detailContextIdentity(session: ChatSessionEntity): DetailContextIdentity? {
        val entryType = session.contextType.trim().uppercase()
        val entryId = session.contextId.trim()
        if (!isDetailContext(entryType) || entryId.isBlank()) return null
        return when (entryType) {
            "INSTITUTION" -> DetailContextIdentity(entryType, entryId, institutionId = entryId)
            "PROJECT" -> DetailContextIdentity(entryType, entryId, projectId = entryId)
            "DOCTOR" -> DetailContextIdentity(entryType, entryId)
            "INSTITUTION_PROJECT" -> {
                val base = DetailContextIdentity(entryType, entryId)
                runCatching { institutionProjectRepository.findById(entryId).orElse(null) }
                    .onFailure { error ->
                        logger.warn(
                            "Agent detail identity lookup failed contextType=INSTITUTION_PROJECT category={}",
                            error.javaClass.simpleName
                        )
                    }
                    .getOrNull()
                    ?.let { offering ->
                        base.copy(
                            institutionId = offering.institutionId,
                            projectId = offering.projectId
                        )
                    } ?: base
            }
            else -> null
        }
    }

    private fun detailContextSearchScope(
        session: ChatSessionEntity,
        current: AgentRouteAssessment,
        resolved: AgentIntentDecision,
        detailContextActive: Boolean
    ): DetailScopeResolution {
        if (!detailContextActive || "UNRESOLVED_CURRENT_REFERENCE" !in current.ambiguityReasons) {
            return DetailScopeResolution(requested = false)
        }
        if (current.ambiguityReasons.any {
                it in setOf("NEGATED_CURRENT_REFERENCE", "EXPLICIT_TOPIC_BOUNDARY")
            }) {
            return DetailScopeResolution(requested = false)
        }
        val resolvedTarget = resolved.queryTarget ?: return DetailScopeResolution(requested = false)
        val positiveTargets = current.targetEvidence.filter {
            it.polarity == AgentLabelPolarity.POSITIVE
        }.mapTo(mutableSetOf()) { it.target }
        val contextId = session.contextId.trim()
        val contextType = session.contextType.trim().uppercase()
        val scopeTarget = when (contextType) {
            "INSTITUTION" -> AgentQueryTarget.INSTITUTION.takeIf {
                resolvedTarget != it && it in positiveTargets
            }
            "DOCTOR" -> AgentQueryTarget.DOCTOR.takeIf {
                resolvedTarget != it && it in positiveTargets
            }
            "PROJECT" -> AgentQueryTarget.PROJECT.takeIf {
                resolvedTarget != it && it in positiveTargets
            }
            "INSTITUTION_PROJECT" -> when {
                resolvedTarget != AgentQueryTarget.INSTITUTION &&
                    AgentQueryTarget.INSTITUTION in positiveTargets -> AgentQueryTarget.INSTITUTION
                resolvedTarget != AgentQueryTarget.PROJECT &&
                    AgentQueryTarget.PROJECT in positiveTargets -> AgentQueryTarget.PROJECT
                else -> null
            }
            else -> null
        } ?: return DetailScopeResolution(requested = false)
        val scope = runCatching {
            when (contextType) {
                "INSTITUTION" -> institutionRepository.findById(contextId).orElse(null)?.name
                "DOCTOR" -> doctorRepository.findPublicById(contextId).orElse(null)?.name
                "PROJECT" -> projectRepository.findById(contextId).orElse(null)?.name
                "INSTITUTION_PROJECT" -> institutionProjectRepository.findById(contextId).orElse(null)?.let { offering ->
                    when (scopeTarget) {
                        AgentQueryTarget.INSTITUTION ->
                            institutionRepository.findById(offering.institutionId).orElse(null)?.name
                        AgentQueryTarget.PROJECT ->
                            projectRepository.findById(offering.projectId).orElse(null)?.name
                        else -> null
                    }
                }
                else -> null
            }
        }.onFailure { error ->
            logger.warn(
                "Agent detail scope lookup failed contextType={} category={}",
                session.contextType.trim().uppercase(),
                error.javaClass.simpleName
            )
        }.getOrNull()
        return DetailScopeResolution(
            requested = true,
            scope = scope?.trim()?.takeIf(String::isNotBlank)
        )
    }

    private fun shouldUseDetailContext(
        contextType: String,
        current: AgentRouteAssessment,
        resolved: AgentIntentDecision,
        detailContextActive: Boolean
    ): Boolean {
        if (!detailContextActive) return false
        val contextTarget = runCatching { AgentQueryTarget.valueOf(contextType.trim().uppercase()) }.getOrNull()
            ?: return false
        if ("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons) return false
        if (resolved.intent in setOf(
                AgentIntent.GENERAL_CHAT,
                AgentIntent.HUMAN_CONSULTATION,
                AgentIntent.SAFETY_SCREENING
            )) {
            return false
        }
        if (current.ambiguityReasons.any {
                it in setOf(
                    "AMBIGUOUS_NEGATION",
                    "CONFLICTING_CURRENT_TARGETS",
                    "NEGATED_CURRENT_REFERENCE",
                    "ALTERNATIVE_ENTITY_REQUEST"
                )
            }) {
            return false
        }
        if (current.targetEvidence.any {
                it.target == contextTarget && it.polarity != AgentLabelPolarity.POSITIVE
            }) {
            return false
        }
        val currentTarget = current.decision.queryTarget
        if (currentTarget != null && currentTarget != contextTarget) return false
        if (resolved.queryTarget != null && resolved.queryTarget != contextTarget) return false

        return resolved.intent == AgentIntent.DETAIL_SUMMARY ||
            current.ambiguityReasons.any {
                it in setOf("DETAIL_CONTEXT_FOLLOW_UP", "UNRESOLVED_CURRENT_REFERENCE")
            } ||
            (!current.explicitQueryTarget && current.decision.intent != AgentIntent.GENERAL_CHAT)
    }

    private fun shouldReleaseDetailContext(
        contextType: String,
        current: AgentRouteAssessment,
        resolved: AgentIntentDecision,
        useDetailContext: Boolean,
        alreadySuperseded: Boolean,
        preserveDetailScope: Boolean = false
    ): Boolean {
        val contextTarget = runCatching { AgentQueryTarget.valueOf(contextType.trim().uppercase()) }.getOrNull()
            ?: return false
        if (alreadySuperseded) return true
        if ("EXPLICIT_TOPIC_BOUNDARY" in current.ambiguityReasons) return true
        if (preserveDetailScope) return false
        val negatedCurrentReference = "NEGATED_CURRENT_REFERENCE" in current.ambiguityReasons
        val alternativeEntityRequest = "ALTERNATIVE_ENTITY_REQUEST" in current.ambiguityReasons
        if (resolved.intent == AgentIntent.HUMAN_CONSULTATION) {
            return negatedCurrentReference || alternativeEntityRequest
        }
        if (resolved.intent == AgentIntent.SAFETY_SCREENING) {
            return negatedCurrentReference ||
                (current.decision.queryTarget != null && current.decision.queryTarget != contextTarget)
        }
        if (resolved.intent == AgentIntent.GENERAL_CHAT && "SOCIAL_INTERJECTION" in current.ambiguityReasons) {
            return false
        }
        return !useDetailContext
    }

    private fun AgentPromptEvidence.withoutCatalogItems(
        unresolvedIdentity: DetailContextIdentity?,
        target: AgentQueryTarget?
    ): CatalogExclusionResult {
        val report = report ?: return CatalogExclusionResult(this, verified = true)
        val identity = unresolvedIdentity?.enrichFrom(report.items)
            ?: return CatalogExclusionResult(this, verified = true)
        if (!identity.hasRequiredRelation(target)) {
            return CatalogExclusionResult(AgentPromptEvidence(), verified = false)
        }
        val items = report.items.filterNot { item ->
            val exactEntry = item.type.equals(identity.entryType, ignoreCase = true) &&
                item.id == identity.entryId
            exactEntry || when (target) {
                AgentQueryTarget.INSTITUTION -> identity.institutionId?.let { institutionId ->
                    item.type.uppercase() in setOf("INSTITUTION", "INSTITUTION_PROJECT") &&
                        (item.id == institutionId || item.institutionId == institutionId)
                } == true
                AgentQueryTarget.PROJECT -> identity.projectId?.let { projectId ->
                    item.type.uppercase() in setOf("PROJECT", "INSTITUTION_PROJECT") &&
                        (item.id == projectId || item.projectId == projectId)
                } == true
                AgentQueryTarget.DOCTOR, AgentQueryTarget.INSTITUTION_PROJECT, null -> false
            }
        }
        if (items.size == report.items.size) return CatalogExclusionResult(this, verified = true)
        val context = if (items.isEmpty()) {
            "Platform database search result: no direct match was found after excluding the current detail item."
        } else {
            val records = items.take(5).joinToString("\n") { item ->
                "[${item.type}] ${item.name}; ${item.subtitle}; " +
                    "${item.attributes.entries.take(5).joinToString { "${it.key}=${it.value}" }}; " +
                    item.summary.take(120)
            }
            """
                Platform database search results:
                $records
                Instruction: Summarize only the 1-2 most useful findings. Do not reintroduce the excluded current detail item.
            """.trimIndent()
        }
        return CatalogExclusionResult(
            evidence = copy(
                context = context,
                matchedEntityIds = items.groupBy { it.type }.mapValues { (_, grouped) -> grouped.map { it.id } },
                noMatch = noMatch || items.isEmpty(),
                report = report.copy(items = items).takeIf { items.isNotEmpty() }
            ),
            verified = true
        )
    }

    private fun DetailContextIdentity.enrichFrom(
        items: List<AgentCatalogItemResponse>
    ): DetailContextIdentity {
        if (entryType != "INSTITUTION_PROJECT" || (!institutionId.isNullOrBlank() && !projectId.isNullOrBlank())) {
            return this
        }
        val exactOffering = items.firstOrNull {
            it.type.equals("INSTITUTION_PROJECT", ignoreCase = true) && it.id == entryId
        } ?: return this
        return copy(
            institutionId = institutionId ?: exactOffering.institutionId?.takeIf(String::isNotBlank),
            projectId = projectId ?: exactOffering.projectId?.takeIf(String::isNotBlank)
        )
    }

    private fun cardDetailSummary(content: String?): String = plainTextContent(content).take(220)

    private fun plainTextContent(content: String?): String =
        content.orEmpty()
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&#160;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun isDetailContext(contextType: String): Boolean =
        contextType.trim().uppercase() in setOf("PROJECT", "INSTITUTION", "INSTITUTION_PROJECT", "DOCTOR")
}
