package com.joysong.server.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.context.AgentSessionSummary
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
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.AgentPromptEvidence
import com.joysong.server.agent.service.AgentRouteAssessment
import com.joysong.server.agent.service.ParsedAgentRoute
import com.joysong.server.config.AiAgentProperties
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime
import java.util.UUID

private data class PromptBuildResult(
    val prompt: String,
    val groundingPrompt: String,
    val evidence: AgentPromptEvidence
)

private data class LlmCallResult(
    val content: String,
    val fallbackUsed: Boolean
)

private data class GeneratedTurn(
    val content: String,
    val intentDecision: AgentIntentDecision,
    val llmResult: LlmCallResult,
    val catalogReport: AgentCatalogReportResponse?,
    val catalogItems: List<AgentCatalogItemResponse>
)

private data class BoundedRouteContext(
    val decisions: List<AgentIntentDecision>,
    val ambiguityReasons: List<String>
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
    val traceId: String? = null
)

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
    private val turnLifecycleService: TurnLifecycleService,
    private val agentOperationLogger: AgentOperationLogger,
    private val agentContextBuilder: AgentContextBuilder,
    private val aiAgentAvailabilityGuard: AiAgentAvailabilityGuard,
    private val objectMapper: ObjectMapper,
    @Qualifier("agentLlmRestTemplate") private val restTemplate: RestTemplate,
    @Qualifier("agentIntentParserRestTemplate") private val intentParserRestTemplate: RestTemplate,
    private val aiAgentProperties: AiAgentProperties,
    @Value("\${openai.fast-reasoning-effort:none}") private val fastReasoningEffort: String,
    @Value("\${openai.complex-reasoning-effort:low}") private val complexReasoningEffort: String
) {
    private val logger = LoggerFactory.getLogger(ChatService::class.java)

    /**
     * 创建新的聊天会话
     */
    @Transactional
    fun createSession(userId: String, request: CreateSessionRequest): ChatSessionEntity {
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

    private fun sendMessageInternal(
        sessionId: String,
        userId: String,
        request: SendMessageRequest,
        llmCaller: (List<Map<String, String>>, GenerationProfile) -> LlmCallResult
    ): ChatTurnResult {
        val content = request.content.trim()
        require(content.isNotEmpty()) { "消息内容不能为空" }
        require(content.length <= 5000) { "消息内容不能超过 5000 字" }
        val idempotencyKey = request.idempotencyKey?.trim()?.also {
            require(it.isNotEmpty() && it.length <= 100) { "INVALID_IDEMPOTENCY_KEY" }
        } ?: UUID.randomUUID().toString()
        val begin = try {
            turnLifecycleService.beginTurn(sessionId, userId, content, idempotencyKey)
        } catch (_: IdempotencyKeyConflictException) {
            throw AgentChatException.idempotencyConflict()
        } catch (_: IllegalArgumentException) {
            throw AgentChatException.sessionNotFound()
        }
        return when (begin) {
            is BeginTurnResult.Replayed -> begin.turn
            BeginTurnResult.InProgress -> throw AgentChatException.turnInProgress()
            BeginTurnResult.IdempotencyExpired -> throw AgentChatException.idempotencyExpired()
            is BeginTurnResult.Started -> executeStartedTurn(begin, sessionId, userId, content, llmCaller)
        }
    }

    private fun executeStartedTurn(
        begin: BeginTurnResult.Started,
        sessionId: String,
        userId: String,
        content: String,
        llmCaller: (List<Map<String, String>>, GenerationProfile) -> LlmCallResult
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
        llmCaller: (List<Map<String, String>>, GenerationProfile) -> LlmCallResult
    ): ChatTurnResult {
        val context = agentContextBuilder.load(userId, sessionId, 20, 4_000)
        val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw AgentChatException("SESSION_NOT_FOUND", begin.traceId)
        val generated = generateTurn(
            session = session,
            content = content,
            historyMessages = context.messages,
            summary = context.summary,
            appendCurrentUser = true,
            llmCaller = llmCaller
        )
        val durationMs = elapsedMs(totalStartedAt)
        val completed = turnLifecycleService.completeTurn(
            CompleteTurnCommand(
                turnId = begin.turnId,
                content = generated.content,
                intent = generated.intentDecision.intent.name,
                queryTarget = generated.intentDecision.queryTarget?.name,
                nextAction = generated.intentDecision.nextAction.name,
                catalogReport = generated.catalogReport,
                catalogItems = generated.catalogItems,
                durationMs = durationMs,
                fallbackUsed = generated.llmResult.fallbackUsed,
                modelName = aiAgentProperties.model
            )
        )
        agentOperationLogger.completed(begin.traceId, begin.turnId, sessionId, durationMs, aiAgentProperties.model)
        return completed
    }

    private fun generateTurn(
        session: ChatSessionEntity,
        content: String,
        historyMessages: List<ChatMessageEntity>,
        summary: AgentSessionSummary?,
        appendCurrentUser: Boolean,
        llmCaller: (List<Map<String, String>>, GenerationProfile) -> LlmCallResult
    ): GeneratedTurn {
        val llmMessages = mutableListOf<Map<String, String>>()
        val currentRouteAssessment = agentIntentRouter.assessCurrent(content, session.contextType)
        val boundedContext by lazy {
            boundedRouteContext(historyMessages, summary, session.contextType)
        }
        val localRouteAssessment = if (currentRouteAssessment.requiresContextCompletion) {
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
            parseAmbiguousRoute(content, routeAssessment, boundedContext.decisions)
        } else null
        val intentDecision = agentIntentRouter.mergeParsedRoute(routeAssessment, parsedRoute)
        val labelSummary = generationLabelSummary(routeAssessment, parsedRoute, intentDecision)
        val previousUserQueries = historyMessages.filter { it.role.equals("USER", true) }
            .map { it.content }
            .takeLast(4)
        val contextualQuery = agentCatalogService.contextualSearchQuery(content, previousUserQueries)
        val generationProfile = generationProfile(intentDecision.intent)
        val catalogSearchQuery = listOf(contextualQuery, parsedRoute?.keywords.orEmpty().joinToString(" "))
            .filter { it.isNotBlank() }
            .joinToString(" ")
        val promptBuild = getSystemPrompt(
            session.persona,
            session.contextType,
            session.contextId,
            content,
            catalogSearchQuery,
            intentDecision,
            labelSummary
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

        val llmResult = llmCaller(llmMessages, generationProfile)
        val aiContent = enforcePlanningBoundary(
            intent = intentDecision.intent,
            content = naturalizeUserFacingLanguage(llmResult.content)
        )
        val currentContextItems = currentContextCatalogItems(session.contextType, session.contextId)
        val visibleReport = promptBuild.evidence.report?.takeIf {
            intentDecision.intent == AgentIntent.COMPARISON && it.items.isNotEmpty()
        }
        val visibleItems = if (visibleReport == null) {
            when {
                isDetailContext(session.contextType) && currentContextItems.isNotEmpty() ->
                    currentContextItems
                else -> promptBuild.evidence.report?.items.orEmpty().take(4)
            }
        } else emptyList()
        return GeneratedTurn(
            content = aiContent,
            intentDecision = intentDecision,
            llmResult = llmResult,
            catalogReport = visibleReport,
            catalogItems = visibleItems
        )
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
        profile: GenerationProfile = generationProfile(AgentIntent.GENERAL_CHAT)
    ): LlmCallResult {
        // Demo replies are opt-in for local development. Production must not
        // persist a fabricated assistant answer as if it came from a model.
        if (aiAgentProperties.apiKey.isBlank()) {
            if (!aiAgentProperties.demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            return LlmCallResult(
                "你好！我是娇颜颂的AI助手，目前处于演示模式。配置 OPENAI_API_KEY 环境变量后即可使用完整的AI对话功能。",
                true
            )
        }

        return try {
            val url = providerChatCompletionsUrl()
            val headers = HttpHeaders().apply {
                setBearerAuth(aiAgentProperties.apiKey)
                contentType = MediaType.APPLICATION_JSON
            }
            val body = mutableMapOf<String, Any>(
                "model" to aiAgentProperties.model,
                "messages" to messages,
                "temperature" to 0.25,
                "max_tokens" to profile.maxOutputTokens,
                // FastAIToken 当前可能不支持稳定 SSE；确认兼容后再改为 true。
                "stream" to false
            )
            reasoningEffort(profile)?.let { body["reasoning_effort"] = it }
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
        val summaryDecisions = summary?.unresolvedTopics.orEmpty().mapNotNull {
            summaryContextDecision(it, agentIntentRouter)
        }
        val historyAssessments = if (summaryDecisions.isEmpty()) {
            historyMessages.filter { it.role.equals("USER", true) }
                .takeLast(4)
                .map { agentIntentRouter.assessCurrent(it.content, "GENERAL") }
        } else {
            emptyList()
        }
        val sessionDecision = runCatching { AgentQueryTarget.valueOf(contextType.trim().uppercase()) }
            .getOrNull()
            ?.let { agentIntentRouter.validatedDecision(AgentIntent.CATALOG_QA, it) }
        return BoundedRouteContext(
            decisions = (summaryDecisions.ifEmpty { historyAssessments.map { it.decision } }) + listOfNotNull(sessionDecision),
            ambiguityReasons = historyAssessments.flatMap { it.ambiguityReasons }.distinct()
        )
    }

    private fun parseAmbiguousRoute(
        rawQuery: String,
        local: AgentRouteAssessment,
        boundedContext: List<AgentIntentDecision>
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
            val instruction = """
                Classify one medical-aesthetic chat request. Return JSON only:
                {"intent":"GENERAL_CHAT|CATALOG_QA|COMPARISON|PLANNING|DETAIL_SUMMARY|SAFETY_SCREENING","intents":["optional additional intent labels"],"queryTarget":"INSTITUTION|DOCTOR|PROJECT|INSTITUTION_PROJECT|null","keywords":["..."]}
                Use SAFETY_SCREENING for possible contraindications or health risks. Use PLANNING for goals with budget, downtime or personal constraints.
                Keywords may contain only useful cities, treatments, categories, tags, clinic names or doctor names from the text. Maximum 8 items. Do not invent IDs or facts.
                Local decision: ${local.decision.intent}/${local.decision.queryTarget ?: "NONE"}. Locked fields: intent=${local.explicitIntent}, queryTarget=${local.explicitQueryTarget}.
                You may fill only unlocked fields. Do not change locked fields.
            """.trimIndent()
            val body = mapOf(
                "model" to aiAgentProperties.resolvedIntentModel(),
                "messages" to listOf(
                    mapOf("role" to "system", "content" to instruction),
                    mapOf("role" to "user", "content" to "Current: $rawQuery\nBounded route context: $context")
                ),
                "temperature" to 0,
                "max_tokens" to 180,
                "stream" to false
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
            if (!isCompatibleParsedRoute(local, intent, intents, target)) {
                throw IntentParserRouteException("INCOMPATIBLE_ROUTE")
            }
            ParsedAgentRoute(intent, target, keywords, intents)
        } catch (error: Exception) {
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

    private fun isCompatibleParsedRoute(
        local: AgentRouteAssessment,
        intent: AgentIntent,
        intents: Set<AgentIntent>,
        target: AgentQueryTarget?
    ): Boolean {
        val parsedIntents = intents + intent
        if (local.unresolvedSafetyNegation && AgentIntent.SAFETY_SCREENING in parsedIntents) return true
        if (intent in setOf(AgentIntent.GENERAL_CHAT, AgentIntent.SAFETY_SCREENING) && target != null) return false
        if (local.explicitIntent && local.decision.intent !in parsedIntents) return false
        if (local.explicitQueryTarget && target != local.decision.queryTarget) return false
        if (local.unresolvedSafetyNegation && parsedIntents.none {
                it in setOf(local.decision.intent, AgentIntent.SAFETY_SCREENING)
            }
        ) return false
        return true
    }

    private class IntentParserRouteException(val category: String) : IllegalArgumentException(category)

    /** Reserved SSE path. Disabled by default through OPENAI_STREAM_ENABLED=false. */
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
        userQuery: String,
        catalogSearchQuery: String,
        intentDecision: AgentIntentDecision,
        labelSummary: GenerationLabelSummary
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
        val contextInfo = buildContextInfo(contextType, contextId)
        val detailSessionPolicy = if (isDetailContext(contextType)) """
            当前处于详情会话。回答时优先围绕当前页面实体，不要跳出到泛泛科普；如果用户追问价格、恢复期、风险、医生或机构，直接基于当前页面给出简短回答。
        """.trimIndent() else ""
        val summaryMode = intentDecision.intent == AgentIntent.DETAIL_SUMMARY
        val shouldSearch = intentDecision.searchCatalog && !summaryMode
        val evidence = if (shouldSearch) {
            agentCatalogService.promptEvidence(
                query = userQuery,
                searchQuery = catalogSearchQuery,
                targetQuery = catalogSearchQuery,
                queryTarget = intentDecision.queryTarget
            )
        } else AgentPromptEvidence()
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
                    doctorRepository.findById(contextId).orElse(null)?.let { doctor ->
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

    private data class GenerationProfile(
        val historyMessageLimit: Int,
        val maxOutputTokens: Int,
        val complexReasoning: Boolean
    )

    private fun generationProfile(intent: AgentIntent): GenerationProfile = when (intent) {
        AgentIntent.GENERAL_CHAT -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 280,
            complexReasoning = false
        )
        AgentIntent.CATALOG_QA -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 420,
            complexReasoning = false
        )
        AgentIntent.DETAIL_SUMMARY -> GenerationProfile(
            historyMessageLimit = 4,
            maxOutputTokens = 260,
            complexReasoning = false
        )
        AgentIntent.COMPARISON, AgentIntent.PLANNING, AgentIntent.SAFETY_SCREENING -> GenerationProfile(
            historyMessageLimit = 6,
            maxOutputTokens = 600,
            complexReasoning = true
        )
    }

    private fun reasoningEffort(profile: GenerationProfile): String? {
        if (!aiAgentProperties.model.trim().lowercase().startsWith("gpt-5")) return null
        val configured = if (profile.complexReasoning) complexReasoningEffort else fastReasoningEffort
        return configured.trim().lowercase().takeIf { it in setOf("none", "minimal", "low", "medium", "high") }
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

                "DOCTOR" -> doctorRepository.findById(normalizedId).orElse(null)?.let { doctor ->
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
