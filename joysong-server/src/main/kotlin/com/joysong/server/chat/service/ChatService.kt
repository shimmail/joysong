package com.joysong.server.chat.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.chat.dto.CreateSessionRequest
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.agent.service.AgentText
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
import com.joysong.server.agent.service.AgentQueryTarget
import com.joysong.server.agent.service.AgentPromptEvidence
import com.joysong.server.agent.service.AgentTraceRecord
import com.joysong.server.agent.service.AgentTraceService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestTemplate
import java.time.LocalDateTime
import java.util.UUID

private data class PromptBuildResult(
    val prompt: String,
    val groundingPrompt: String,
    val databaseSearched: Boolean,
    val evidence: AgentPromptEvidence
)

private data class LlmCallResult(
    val content: String,
    val called: Boolean,
    val httpStatus: Int?,
    val inputTokens: Int?,
    val outputTokens: Int?,
    val durationMs: Long,
    val fallbackUsed: Boolean,
    val error: String?
)

data class ChatTurnResult(
    val message: ChatMessageEntity,
    val catalogReport: AgentCatalogReportResponse? = null,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val intent: String = "GENERAL_CHAT",
    val queryTarget: String? = null,
    val nextAction: String = "NONE"
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
    private val agentTraceService: AgentTraceService,
    private val objectMapper: ObjectMapper,
    @Qualifier("llmRestTemplate") private val restTemplate: RestTemplate,
    @Qualifier("intentParserRestTemplate") private val intentParserRestTemplate: RestTemplate,
    @Value("\${openai.api-key:}") private val openaiApiKey: String,
    @Value("\${openai.base-url:}") private val openaiBaseUrl: String,
    @Value("\${openai.model:gpt-4.1-mini}") private val openaiModel: String,
    @Value("\${openai.stream-enabled:false}") private val streamEnabled: Boolean,
    @Value("\${openai.intent-parser-enabled:true}") private val intentParserEnabled: Boolean,
    @Value("\${openai.fast-reasoning-effort:none}") private val fastReasoningEffort: String,
    @Value("\${openai.complex-reasoning-effort:low}") private val complexReasoningEffort: String,
    @Value("\${openai.demo-fallback-enabled:false}") private val demoFallbackEnabled: Boolean
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
        messageRepository.findFirstBySessionIdOrderByCreatedAtDesc(sessionId)?.content.orEmpty()

    /**
     * 获取单个会话
     */
    fun getSession(sessionId: String, userId: String): ChatSessionEntity? {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
    }

    /**
     * 发送消息并获取 AI 响应
     */
    @Transactional
    fun sendMessage(
        sessionId: String,
        userId: String,
        request: SendMessageRequest
    ): ChatTurnResult = sendMessageInternal(sessionId, userId, request, ::callLLM)

    @Transactional
    fun sendMessageStreaming(
        sessionId: String,
        userId: String,
        request: SendMessageRequest,
        onDelta: (String) -> Unit
    ): ChatTurnResult {
        check(streamEnabled) { "AI streaming is disabled" }
        return sendMessageInternal(sessionId, userId, request) { messages, profile ->
            callLLMStreaming(messages, profile, onDelta)
        }
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
        val traceId = UUID.randomUUID().toString()
        val totalStartedAt = System.nanoTime()
        // 1. 验证 session 归属
        val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")

        // 2. 保存用户消息
        val userMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "USER",
            content = content,
            createdAt = LocalDateTime.now()
        )
        messageRepository.save(userMessage)

        // 3. 保留短上下文，减少网关首字延迟与无关历史干扰
        val historyMessages = messageRepository.findTop10BySessionIdOrderByCreatedAtDesc(sessionId)
            .reversed()
            .takeLast(6)

        // 4. 构建 LLM 消息列表
        val llmMessages = mutableListOf<Map<String, String>>()
        val databaseStartedAt = System.nanoTime()
        val userHistory = historyMessages.filter { it.role.equals("USER", true) }
        // The current message was saved before history was loaded and is therefore
        // the last USER entry. Remove it by position as well as identity; relying on
        // JPA entity identity alone can accidentally feed the current follow-up back
        // into contextual keyword resolution.
        val previousUserQueries = userHistory
            .let { messages ->
                if (messages.lastOrNull()?.id == userMessage.id || messages.lastOrNull()?.content == content) {
                    messages.dropLast(1)
                } else messages
            }
            .map { it.content }
            .takeLast(4)
        val contextualQuery = agentCatalogService.contextualSearchQuery(content, previousUserQueries)
        val localRouteAssessment = agentIntentRouter.assess(contextualQuery, session.contextType)
        // A custom institution-project name may not contain generic words such as
        // “项目” or “套餐”. Resolve effective inherited details before deciding that
        // the message is general chat, otherwise the database search is skipped.
        val routeAssessment = if (
            localRouteAssessment.decision.intent == AgentIntent.GENERAL_CHAT &&
            agentCatalogService.hasInstitutionProjectMatch(contextualQuery)
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
        val parsedRoute = if (intentParserEnabled && routeAssessment.requiresLlmParsing) {
            parseAmbiguousRoute(content, contextualQuery, routeAssessment.decision)
        } else null
        // Deterministic safety detection can only be preserved or upgraded, never downgraded by a model.
        val intentDecision = when {
            routeAssessment.decision.intent == AgentIntent.SAFETY_SCREENING -> routeAssessment.decision
            parsedRoute?.intent == AgentIntent.SAFETY_SCREENING -> agentIntentRouter.validatedDecision(AgentIntent.SAFETY_SCREENING, null)
            parsedRoute != null -> agentIntentRouter.validatedDecision(parsedRoute.intent, parsedRoute.queryTarget)
            else -> routeAssessment.decision
        }
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
            intentDecision
        )
        val databaseDurationMs = elapsedMs(databaseStartedAt)
        llmMessages.add(mapOf("role" to "system", "content" to promptBuild.prompt))
        historyMessages.takeLast(generationProfile.historyMessageLimit).forEach { msg ->
            llmMessages.add(mapOf(
                "role" to msg.role.lowercase(),
                "content" to msg.content
            ))
        }
        // Keep the completed catalog search closest to generation. This prevents older
        // conversation content from overriding the same result set used by UI cards.
        if (promptBuild.groundingPrompt.isNotBlank()) {
            llmMessages.add(mapOf("role" to "system", "content" to promptBuild.groundingPrompt))
        }

        // 5. 调用 LLM API
        val llmResult = llmCaller(llmMessages, generationProfile)
        val aiContent = naturalizeUserFacingLanguage(llmResult.content)

        // 6. 保存 AI 响应消息
        val aiMessage = ChatMessageEntity(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            role = "ASSISTANT",
            content = aiContent,
            createdAt = LocalDateTime.now()
        )
        messageRepository.save(aiMessage)

        // 7. 更新 session 的 updatedAt
        session.updatedAt = LocalDateTime.now()
        sessionRepository.save(session)

        runCatching {
            agentTraceService.save(
                AgentTraceRecord(
                    traceId = traceId,
                    userId = userId,
                    sessionId = sessionId,
                    query = content,
                    intent = intentDecision.intent.name,
                    databaseSearch = promptBuild.databaseSearched,
                    detectedKeywords = promptBuild.evidence.detectedKeywords,
                    detectedConcerns = agentTraceService.detectConcerns(content),
                    matchedEntityIds = promptBuild.evidence.matchedEntityIds,
                    llmCalled = llmResult.called,
                    modelName = openaiModel,
                    gatewayUrl = openaiBaseUrl,
                    totalDurationMs = elapsedMs(totalStartedAt),
                    databaseDurationMs = databaseDurationMs,
                    llmDurationMs = llmResult.durationMs,
                    httpStatus = llmResult.httpStatus,
                    inputTokens = llmResult.inputTokens,
                    outputTokens = llmResult.outputTokens,
                    fallbackUsed = llmResult.fallbackUsed,
                    answerLength = aiContent.length,
                    errorSummary = llmResult.error
                )
            )
            logger.info("AI_TRACE traceId={} sessionId={} intent={} db={} llmStatus={} totalMs={} answerLength={}", traceId, sessionId, intentDecision.intent.name, promptBuild.databaseSearched, llmResult.httpStatus, elapsedMs(totalStartedAt), aiContent.length)
        }.onFailure { logger.warn("AI trace save failed traceId={}: {}", traceId, it.message) }

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
        return ChatTurnResult(
            message = aiMessage,
            catalogReport = visibleReport,
            catalogItems = visibleItems,
            intent = intentDecision.intent.name,
            queryTarget = intentDecision.queryTarget?.name,
            nextAction = intentDecision.nextAction.name
        )
    }

    /**
     * 删除消息（验证会话归属后删除）
     */
    @Transactional
    fun deleteMessage(messageId: String, userId: String) {
        val message = messageRepository.findById(messageId).orElse(null)
            ?: throw IllegalArgumentException("消息不存在")

        // 验证消息所属会话归当前用户所有
        sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(message.sessionId, userId)
            ?: throw IllegalArgumentException("无权删除该消息")

        messageRepository.delete(message)
    }

    /**
     * 获取会话的所有消息
     */
    fun getMessages(
        sessionId: String,
        userId: String,
        limit: Int = 100,
        before: LocalDateTime? = null
    ): List<ChatMessageEntity> {
        // 验证 session 归属
        sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        val pageable = PageRequest.of(0, limit.coerceIn(1, 100))
        val messages = if (before == null) {
            messageRepository.findBySessionIdOrderByCreatedAtDesc(sessionId, pageable)
        } else {
            messageRepository.findBySessionIdAndCreatedAtBeforeOrderByCreatedAtDesc(sessionId, before, pageable)
        }
        return messages.reversed()
    }

    @Transactional
    fun clearMessages(sessionId: String, userId: String) {
        sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        messageRepository.deleteBySessionId(sessionId)
    }

    @Transactional
    fun deleteSession(sessionId: String, userId: String) {
        val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        messageRepository.deleteBySessionId(sessionId)
        sessionRepository.delete(session)
    }

    @Transactional
    fun clearSessions(userId: String, persona: String) {
        sessionRepository.findByUserIdAndPersonaAndDeletedAtIsNull(userId, normalizePersona(persona)).forEach { session ->
            messageRepository.deleteBySessionId(session.id)
            sessionRepository.delete(session)
        }
    }

    /**
     * 调用 OpenAI Chat Completions API
     */
    private fun callLLM(
        messages: List<Map<String, String>>,
        profile: GenerationProfile = generationProfile(AgentIntent.GENERAL_CHAT)
    ): LlmCallResult {
        val startedAt = System.nanoTime()
        // Demo replies are opt-in for local development. Production must not
        // persist a fabricated assistant answer as if it came from a model.
        if (openaiApiKey.isBlank()) {
            if (!demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            return LlmCallResult("你好！我是娇颜颂的AI助手，目前处于演示模式。配置 OPENAI_API_KEY 环境变量后即可使用完整的AI对话功能。", false, null, null, null, elapsedMs(startedAt), true, "OPENAI_API_KEY is blank")
        }

        return try {
            val url = "${openaiBaseUrl.trimEnd('/')}/chat/completions"
            val headers = HttpHeaders().apply {
                setBearerAuth(openaiApiKey)
                contentType = MediaType.APPLICATION_JSON
            }
            val body = mutableMapOf<String, Any>(
                "model" to openaiModel,
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
            val usage = responseBody?.get("usage") as? Map<*, *>
            val content = message?.get("content") as? String
            if (content.isNullOrBlank() && !demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            LlmCallResult(
                content = content ?: "抱歉，我暂时无法回答这个问题。",
                called = true,
                httpStatus = response.statusCode.value(),
                inputTokens = (usage?.get("prompt_tokens") ?: usage?.get("input_tokens"))?.toString()?.toIntOrNull(),
                outputTokens = (usage?.get("completion_tokens") ?: usage?.get("output_tokens"))?.toString()?.toIntOrNull(),
                durationMs = elapsedMs(startedAt),
                fallbackUsed = content == null,
                error = null
            )
        } catch (e: Exception) {
            if (!demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE", e)
            }
            // 判断是否为代理/连接层面的错误（代理未配置、代理不可达、无法连接到目标服务器）
            val cause = e.cause
            val isProxyOrConnectionIssue = e is org.springframework.web.client.ResourceAccessException &&
                (cause is java.net.ConnectException ||
                 cause is java.net.UnknownHostException ||
                 (cause is java.io.IOException && cause !is java.net.SocketTimeoutException))
            if (isProxyOrConnectionIssue) {
                logger.warn("LLM API 连接失败（可能是代理未配置或不可达），降级返回模拟响应: ${e.message}")
                // 代理/网络问题：返回友好提示
                LlmCallResult("你好！我是娇颜颂的AI助手，目前网络暂时无法连接AI服务。请检查代理配置（google.proxy-url）或稍后再试。", true, httpStatusOf(e), null, null, elapsedMs(startedAt), true, e.message)
            } else {
                logger.error("调用 LLM API 失败: ${e.message}", e)
                // 真实API错误（如认证失败、限流等）：返回带错误信息的提示
                LlmCallResult("抱歉，AI服务暂时不可用（${e.message?.take(100) ?: "未知错误"}），请稍后再试。", true, httpStatusOf(e), null, null, elapsedMs(startedAt), true, e.message)
            }
        }
    }

    private fun normalizePersona(persona: String): String {
        val normalized = persona.trim().uppercase()
        require(normalized in setOf("BESTIE", "CONSULTANT")) { "不支持的 AI 角色" }
        return normalized
    }

    private data class ParsedRoute(
        val intent: AgentIntent,
        val queryTarget: AgentQueryTarget?,
        val keywords: List<String>
    )

    private fun parseAmbiguousRoute(rawQuery: String, contextualQuery: String, fallback: AgentIntentDecision): ParsedRoute? {
        if (openaiApiKey.isBlank()) return null
        val startedAt = System.nanoTime()
        return runCatching {
            val url = "${openaiBaseUrl.trimEnd('/')}/chat/completions"
            val headers = HttpHeaders().apply {
                setBearerAuth(openaiApiKey)
                contentType = MediaType.APPLICATION_JSON
            }
            val instruction = """
                Classify one medical-aesthetic chat request. Return JSON only:
                {"intent":"GENERAL_CHAT|CATALOG_QA|COMPARISON|PLANNING|DETAIL_SUMMARY|SAFETY_SCREENING","queryTarget":"INSTITUTION|DOCTOR|PROJECT|INSTITUTION_PROJECT|null","keywords":["..."]}
                Use SAFETY_SCREENING for possible contraindications or health risks. Use PLANNING for goals with budget, downtime or personal constraints.
                Keywords may contain only useful cities, treatments, categories, tags, clinic names or doctor names from the text. Maximum 8 items. Do not invent IDs or facts.
            """.trimIndent()
            val body = mapOf(
                "model" to openaiModel,
                "messages" to listOf(
                    mapOf("role" to "system", "content" to instruction),
                    mapOf("role" to "user", "content" to "Current: $rawQuery\nContextual: $contextualQuery\nLocal fallback: ${fallback.intent}/${fallback.queryTarget}")
                ),
                "temperature" to 0,
                "max_tokens" to 180,
                "stream" to false
            )
            val response = intentParserRestTemplate.exchange(url, HttpMethod.POST, HttpEntity(body, headers), Map::class.java)
            val choice = (response.body?.get("choices") as? List<*>)?.firstOrNull() as? Map<*, *>
            val message = choice?.get("message") as? Map<*, *>
            val content = message?.get("content")?.toString().orEmpty()
            val json = content.substringAfter('{', "").substringBeforeLast('}', "").takeIf(String::isNotBlank)?.let { "{$it}" }
                ?: return@runCatching null
            val node = objectMapper.readTree(json)
            val intent = runCatching { AgentIntent.valueOf(node.path("intent").asText()) }.getOrNull()
                ?: return@runCatching null
            val targetText = node.path("queryTarget").asText().takeUnless { it.isBlank() || it.equals("null", true) }
            val target = targetText?.let { runCatching { AgentQueryTarget.valueOf(it) }.getOrNull() }
            val keywords = node.path("keywords").takeIf { it.isArray }?.mapNotNull { item ->
                item.asText().trim().takeIf { it.length in 2..40 }
            }.orEmpty().distinct().take(8)
            ParsedRoute(intent, target, keywords)
        }.onFailure {
            logger.info("Ambiguous intent parsing fell back to local route after {}ms: {}", elapsedMs(startedAt), it.message)
        }.getOrNull()
    }

    /** Reserved SSE path. Disabled by default through OPENAI_STREAM_ENABLED=false. */
    private fun callLLMStreaming(
        messages: List<Map<String, String>>,
        profile: GenerationProfile,
        onDelta: (String) -> Unit
    ): LlmCallResult {
        val startedAt = System.nanoTime()
        if (openaiApiKey.isBlank()) return callLLM(messages, profile)
        return try {
            val url = "${openaiBaseUrl.trimEnd('/')}/chat/completions"
            val body = mutableMapOf<String, Any>(
                "model" to openaiModel,
                "messages" to messages,
                "temperature" to 0.25,
                "max_tokens" to profile.maxOutputTokens,
                "stream" to true
            )
            reasoningEffort(profile)?.let { body["reasoning_effort"] = it }
            var status: Int? = null
            val content = restTemplate.execute(
                url, HttpMethod.POST,
                { request ->
                    request.headers.setBearerAuth(openaiApiKey)
                    request.headers.contentType = MediaType.APPLICATION_JSON
                    request.headers.accept = listOf(MediaType.TEXT_EVENT_STREAM)
                    objectMapper.writeValue(request.body, body)
                },
                { response ->
                    status = response.statusCode.value()
                    val answer = StringBuilder()
                    response.body.bufferedReader(Charsets.UTF_8).useLines { lines ->
                        lines.forEach { line ->
                            val data = line.trim().removePrefix("data:").trim()
                            if (!line.trim().startsWith("data:") || data.isBlank() || data == "[DONE]") return@forEach
                            val chunk = objectMapper.readValue(data, Map::class.java)
                            val choice = (chunk["choices"] as? List<*>)?.firstOrNull() as? Map<*, *>
                            val delta = choice?.get("delta") as? Map<*, *>
                            (delta?.get("content") as? String)?.takeIf { it.isNotEmpty() }?.let {
                                answer.append(it)
                                onDelta(it)
                            }
                        }
                    }
                    answer.toString()
                }
            ).orEmpty()
            if (content.isBlank() && !demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE")
            }
            LlmCallResult(
                content.ifBlank { "抱歉，我暂时无法回答这个问题。" }, true, status,
                null, null, elapsedMs(startedAt), content.isBlank(), null
            )
        } catch (error: Exception) {
            if (!demoFallbackEnabled) {
                throw IllegalStateException("AI_PROVIDER_UNAVAILABLE", error)
            }
            logger.error("调用流式 LLM API 失败: ${error.message}", error)
            LlmCallResult(
                "抱歉，AI服务暂时不可用（${error.message?.take(100) ?: "未知错误"}），请稍后再试。",
                true, httpStatusOf(error), null, null, elapsedMs(startedAt), true, error.message
            )
        }
    }

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
        contextType: String = "GENERAL",
        contextId: String = "",
        userQuery: String = "",
        catalogSearchQuery: String = userQuery,
        intentDecision: AgentIntentDecision = agentIntentRouter.decide(catalogSearchQuery, contextType)
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
                本轮是规划诉求。先给出简短的方向性建议；缺少健康筛查、预算或恢复期信息时，
                引导用户进入需求档案和安全筛查，不在普通聊天中伪造完整治疗计划。
            """.trimIndent()
            else -> ""
        }
        return PromptBuildResult(
            prompt = listOf(basePrompt, responsePolicy, contextInfo, detailSessionPolicy, intentPolicy).filter(String::isNotBlank).joinToString("\n\n"),
            groundingPrompt = evidenceInstruction,
            databaseSearched = shouldSearch,
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

    private fun httpStatusOf(error: Exception): Int? =
        (error as? org.springframework.web.client.HttpStatusCodeException)?.statusCode?.value()

    /**
     * 根据上下文类型和ID查询实体信息，构建上下文提示段落
     */
    private fun buildContextInfo(contextType: String, contextId: String): String {
        if (contextId.isBlank() || contextType == "GENERAL") return ""
        return try {
            when (contextType.uppercase()) {
                "PROJECT" -> {
                    projectRepository.findById(contextId).orElse(null)?.let { project ->
                        val priceText = if (project.referencePrice > java.math.BigDecimal.ZERO) "￥${project.referencePrice}" else "未设定"
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
                            "机构价格：￥${offering.price}\n" +
                            "原价：${offering.originalPrice?.let { "￥$it" } ?: "未提供"}\n" +
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
        } catch (e: Exception) {
            logger.warn("查询上下文信息失败: contextType=$contextType, contextId=$contextId, error=${e.message}")
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
        if (!openaiModel.trim().lowercase().startsWith("gpt-5")) return null
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
                                AgentText.value("参考价", "Reference price") to "¥${project.referencePrice.toPlainString()}",
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
                                        AgentText.value("机构价格", "Clinic price") to "¥${offering.price.toPlainString()}",
                                        AgentText.value("项目参考价", "Reference price") to "¥${project.referencePrice.toPlainString()}",
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
        } catch (e: Exception) {
            logger.warn("构建当前上下文卡片失败: contextType=$normalizedType, contextId=$normalizedId, error=${e.message}")
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
