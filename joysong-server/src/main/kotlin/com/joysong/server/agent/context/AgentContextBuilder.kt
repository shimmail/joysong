package com.joysong.server.agent.context

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.agent.service.PlanningCatalogProjection
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID

data class AgentSessionSummary(
    val schemaVersion: Int = 1,
    val focusSemanticsVersion: Int = 0,
    val goals: List<String> = emptyList(),
    val preferences: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val entityRefs: Map<String, List<String>> = emptyMap(),
    val unresolvedTopics: List<String> = emptyList(),
    val lastSummarizedSequence: Long = 0
)

data class AgentContext(
    val summary: AgentSessionSummary,
    val messages: List<ChatMessageEntity>
)

enum class ConversationFocusUpdate { PRESERVE, CLEAR, SET }

interface AgentChatHistoryPort {
    fun load(userId: String, sessionId: String, maxMessages: Int, maxTokens: Int): AgentContext
    fun clear(userId: String, sessionId: String)
}

@Service
class AgentContextBuilder(
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: ChatMessageRepository,
    private val turnRepository: AgentTurnRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    @Value("\${agent.recent-message-limit:20}") private val recentMessageLimit: Int = 20,
    @Value("\${agent.message-retention-days:7}") private val messageRetentionDays: Long = 7
) : AgentChatHistoryPort {

    @Transactional
    override fun load(userId: String, sessionId: String, maxMessages: Int, maxTokens: Int): AgentContext {
        val session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        val summary = parseSummary(session.summaryJson)
        val cutoff = LocalDateTime.now(clock).minusDays(messageRetentionDays)
        val allMessages = messageRepository.findBySessionIdOrderBySequenceNoAsc(sessionId)
        val expired = allMessages.filter { it.createdAt.isBefore(cutoff) }
        val overLimit = allMessages.dropLast(recentMessageLimit.coerceAtLeast(0)).toSet()
        val prune = (expired + overLimit).toSet()
        if (session.summaryJson != serializeSummary(summary) || prune.isNotEmpty()) {
            persistSummary(session, summary)
        }
        if (prune.isNotEmpty()) {
            messageRepository.deleteAll(prune)
        }
        val succeeded = messageRepository.findSucceededTurnMessagesBySessionId(sessionId)
        val recent = succeeded
            .filterNot { it in prune }
            .sortedBy { it.sequenceNo }
            .takeLast(maxMessages.coerceAtLeast(0).coerceAtMost(recentMessageLimit))
            .map { PlanningCatalogProjection.projectStoredMessage(it, objectMapper) }
        val bounded = recent.asReversed()
            .fold(mutableListOf<ChatMessageEntity>() to 0) { (selected, tokenCount), message ->
                val next = tokenCount + simpleTokenCount(message.content)
                if (next <= maxTokens.coerceAtLeast(0)) {
                    selected += message
                    selected to next
                } else selected to tokenCount
            }.first
            .asReversed()
        return AgentContext(summary, bounded)
    }

    /** Called by turn completion within its existing short transaction. */
    fun updateSummaryAndPrune(
        session: ChatSessionEntity,
        sequenceNo: Long,
        intent: String,
        queryTarget: String?,
        nextAction: String,
        catalogItems: List<AgentCatalogItemResponse>,
        conversationFocusUpdate: ConversationFocusUpdate = ConversationFocusUpdate.SET
    ) {
        val current = parseSummary(session.summaryJson)
        val entityRefs = current.entityRefs.toMutableMap()
        catalogItems.filter { it.type.trim().uppercase() in platformEntityTypes && isPlatformEntityId(it.id) }
            .groupBy { it.type.trim().uppercase() }
            .forEach { (type, items) -> entityRefs[type] = (entityRefs[type].orEmpty() + items.map { it.id }).distinct().take(20) }
        val resolvedTopic = listOfNotNull(
            intent.trim().uppercase().takeIf(validIntents::contains),
            queryTarget?.trim()?.uppercase()?.takeIf(validQueryTargets::contains),
            nextAction.trim().uppercase().takeIf(validNextActions::contains)
        ).filter { it != "NONE" }
            .joinToString(":")
            .takeIf(::isValidTopic)
        val resolvedFocusTopic = resolvedTopic?.takeIf { it.substringBefore(":") in focusIntents }
        val sessionDetailFocus = session.contextType.trim().uppercase()
            .takeIf { session.contextId.isNotBlank() && it in platformEntityTypes }
            ?.let { "CATALOG_QA:$it:SHOW_CATALOG" }
        val legacyCanonicalFocus = if (
            current.focusSemanticsVersion == 0 && conversationFocusUpdate == ConversationFocusUpdate.PRESERVE
        ) {
            sessionDetailFocus ?: resolvedFocusTopic
        } else {
            null
        }
        val adoptsCurrentFocusSemantics = current.focusSemanticsVersion == currentFocusSemanticsVersion ||
            conversationFocusUpdate != ConversationFocusUpdate.PRESERVE ||
            legacyCanonicalFocus != null
        val baseTopics = if (current.focusSemanticsVersion == 0 && adoptsCurrentFocusSemantics) {
            listOfNotNull(legacyCanonicalFocus)
        } else {
            current.unresolvedTopics
        }
        val topic = when (conversationFocusUpdate) {
            ConversationFocusUpdate.PRESERVE -> resolvedTopic?.takeIf {
                it.substringBefore(":") in setOf("SAFETY_SCREENING", "HUMAN_CONSULTATION")
            }
            ConversationFocusUpdate.CLEAR -> "GENERAL_CHAT"
            ConversationFocusUpdate.SET -> resolvedTopic
        }
        val updated = current.copy(
            focusSemanticsVersion = if (adoptsCurrentFocusSemantics) currentFocusSemanticsVersion else 0,
            entityRefs = entityRefs.toSortedMap(),
            unresolvedTopics = topic?.let { latest ->
                (baseTopics.filterNot { it == latest } + latest).takeLast(20)
            } ?: baseTopics,
            lastSummarizedSequence = maxOf(current.lastSummarizedSequence, sequenceNo)
        )
        persistSummary(session, updated)

        val allMessages = messageRepository.findBySessionIdOrderBySequenceNoAsc(session.id)
        val cutoff = LocalDateTime.now(clock).minusDays(messageRetentionDays)
        val overLimit = allMessages.dropLast(recentMessageLimit.coerceAtLeast(0)).toSet()
        val expired = allMessages.filter { it.createdAt.isBefore(cutoff) }.toSet()
        val prune = (overLimit + expired).toList()
        if (prune.isNotEmpty()) messageRepository.deleteAll(prune)
    }

    @Transactional
    override fun clear(userId: String, sessionId: String) {
        val session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        messageRepository.deleteBySessionId(session.id)
        turnRepository.deleteBySessionId(session.id)
        sessionRepository.delete(session)
    }

    private fun parseSummary(raw: String): AgentSessionSummary {
        val root = try {
            objectMapper.readTree(raw.ifBlank { "{}" })
        } catch (error: Exception) {
            throw IllegalArgumentException("会话摘要格式无效", error)
        }
        if (root.intValue("schemaVersion", summarySchemaVersion) != summarySchemaVersion) return AgentSessionSummary()
        return AgentSessionSummary(
            schemaVersion = summarySchemaVersion,
            focusSemanticsVersion = root.intValue("focusSemanticsVersion", 0)
                .takeIf { it == currentFocusSemanticsVersion } ?: 0,
            entityRefs = root.entityRefs(),
            unresolvedTopics = root.stringList("unresolvedTopics").filter(::isValidTopic),
            lastSummarizedSequence = root.longValue("lastSummarizedSequence", 0).coerceAtLeast(0)
        )
    }

    private fun persistSummary(session: ChatSessionEntity, summary: AgentSessionSummary) {
        session.summaryJson = serializeSummary(summary)
        val now = LocalDateTime.now(clock)
        session.summaryUpdatedAt = now
        session.updatedAt = now
        sessionRepository.save(session)
    }

    fun serializeSummary(summary: AgentSessionSummary): String = objectMapper.writeValueAsString(
        linkedMapOf(
            "schemaVersion" to summary.schemaVersion,
            "focusSemanticsVersion" to summary.focusSemanticsVersion,
            "goals" to summary.goals,
            "preferences" to summary.preferences,
            "constraints" to summary.constraints,
            "entityRefs" to summary.entityRefs.toSortedMap(),
            "unresolvedTopics" to summary.unresolvedTopics,
            "lastSummarizedSequence" to summary.lastSummarizedSequence
        )
    )

    private fun JsonNode.stringList(name: String): List<String> = get(name)?.takeIf { it.isArray }
        ?.mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotBlank) }
        ?.takeLast(20).orEmpty()

    private fun JsonNode.entityRefs(): Map<String, List<String>> = get("entityRefs")?.takeIf { it.isObject }
        ?.fields()?.asSequence()?.mapNotNull { (key, value) ->
            key.trim().uppercase().takeIf(platformEntityTypes::contains)?.let { normalized ->
                normalized to value.stringListFromNode().filter(::isPlatformEntityId)
            }
        }?.toMap()?.toSortedMap().orEmpty()

    private fun JsonNode.stringListFromNode(): List<String> = takeIf { isArray }
        ?.mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotBlank) }?.distinct()?.take(20).orEmpty()

    private fun JsonNode.intValue(name: String, default: Int): Int = get(name)?.takeIf { it.isInt || it.isLong }?.asInt() ?: default
    private fun JsonNode.longValue(name: String, default: Long): Long = get(name)?.takeIf { it.isIntegralNumber }?.asLong() ?: default
    private fun simpleTokenCount(content: String): Int = content.codePointCount(0, content.length)
    private fun isPlatformEntityId(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess
    private fun isValidTopic(value: String): Boolean {
        val parts = value.split(":")
        val intent = parts.firstOrNull() ?: return false
        if (intent !in validIntents || parts.size !in 1..3) return false
        val target = parts.getOrNull(1)?.takeIf(validQueryTargets::contains)
        val action = when {
            parts.size == 1 -> null
            target != null -> parts.getOrNull(2)
            parts.size == 2 -> parts[1]
            else -> return false
        }
        if (action != null && action !in validNextActions - "NONE") return false
        if (parts.size == 3 && target == null) return false
        return when (intent) {
            "GENERAL_CHAT" -> target == null && action == null
            "SAFETY_SCREENING" -> target == null && action in setOf(null, "COMPLETE_SAFETY_SCREENING")
            "PLANNING" -> action in setOf(null, "START_PLANNING")
            "CATALOG_QA", "COMPARISON", "DETAIL_SUMMARY" -> action in setOf(null, "SHOW_CATALOG")
            "HUMAN_CONSULTATION" -> target == "INSTITUTION" && action in setOf(null, "SELECT_INSTITUTION")
            else -> false
        }
    }

    private companion object {
        const val summarySchemaVersion = 1
        const val currentFocusSemanticsVersion = 1
        val validIntents = setOf("GENERAL_CHAT", "CATALOG_QA", "COMPARISON", "PLANNING", "DETAIL_SUMMARY", "HUMAN_CONSULTATION", "SAFETY_SCREENING")
        val focusIntents = setOf("CATALOG_QA", "COMPARISON", "PLANNING", "DETAIL_SUMMARY")
        val validQueryTargets = setOf("INSTITUTION", "DOCTOR", "PROJECT", "INSTITUTION_PROJECT")
        val validNextActions = setOf("NONE", "SHOW_CATALOG", "START_PLANNING", "SELECT_INSTITUTION", "COMPLETE_SAFETY_SCREENING")
        val platformEntityTypes = validQueryTargets
    }
}
