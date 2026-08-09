package com.joysong.server.agent.context

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

data class AgentSessionSummary(
    val schemaVersion: Int = 1,
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
    @Value("\${agent.recent-message-limit:20}") private val recentMessageLimit: Int = 20,
    @Value("\${agent.message-retention-days:7}") private val messageRetentionDays: Long = 7
) : AgentChatHistoryPort {

    override fun load(userId: String, sessionId: String, maxMessages: Int, maxTokens: Int): AgentContext {
        val session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        val summary = parseSummary(session.summaryJson)
        val recent = messageRepository.findSucceededTurnMessagesBySessionId(sessionId)
            .filter { !it.createdAt.isBefore(LocalDateTime.now().minusDays(messageRetentionDays)) }
            .sortedBy { it.sequenceNo }
            .takeLast(maxMessages.coerceAtLeast(0).coerceAtMost(recentMessageLimit))
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
        catalogItems: List<AgentCatalogItemResponse>
    ) {
        val current = parseSummary(session.summaryJson)
        val entityRefs = current.entityRefs.toMutableMap()
        catalogItems.filter { it.type.trim().uppercase() in platformEntityTypes && isPlatformEntityId(it.id) }
            .groupBy { it.type.trim().uppercase() }
            .forEach { (type, items) -> entityRefs[type] = (entityRefs[type].orEmpty() + items.map { it.id }).distinct().take(20) }
        val topic = listOfNotNull(
            intent.trim().uppercase().takeIf(validIntents::contains),
            queryTarget?.trim()?.uppercase()?.takeIf(validQueryTargets::contains),
            nextAction.trim().uppercase().takeIf(validNextActions::contains)
        ).filter { it != "NONE" }
            .joinToString(":")
        val updated = current.copy(
            entityRefs = entityRefs.toSortedMap(),
            unresolvedTopics = (current.unresolvedTopics + topic).filter { it.isNotBlank() }.distinct().takeLast(20),
            lastSummarizedSequence = maxOf(current.lastSummarizedSequence, sequenceNo)
        )
        session.summaryJson = serializeSummary(updated)
        session.summaryUpdatedAt = LocalDateTime.now()
        session.updatedAt = LocalDateTime.now()
        sessionRepository.save(session)

        val succeeded = messageRepository.findSucceededTurnMessagesBySessionId(session.id).sortedBy { it.sequenceNo }
        val cutoff = LocalDateTime.now().minusDays(messageRetentionDays)
        val overLimit = succeeded.dropLast(recentMessageLimit.coerceAtLeast(0)).toSet()
        val expired = succeeded.filter { it.createdAt.isBefore(cutoff) }.toSet()
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
        return AgentSessionSummary(
            schemaVersion = root.intValue("schemaVersion", 1),
            goals = root.stringList("goals"),
            preferences = root.stringList("preferences"),
            constraints = root.stringList("constraints"),
            entityRefs = root.entityRefs(),
            unresolvedTopics = root.stringList("unresolvedTopics"),
            lastSummarizedSequence = root.longValue("lastSummarizedSequence", 0)
        )
    }

    private fun serializeSummary(summary: AgentSessionSummary): String = objectMapper.writeValueAsString(
        linkedMapOf(
            "schemaVersion" to summary.schemaVersion,
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
        ?.distinct()?.take(20).orEmpty()

    private fun JsonNode.entityRefs(): Map<String, List<String>> = get("entityRefs")?.takeIf { it.isObject }
        ?.fields()?.asSequence()?.mapNotNull { (key, value) ->
            key.trim().uppercase().takeIf(String::isNotBlank)?.let { normalized -> normalized to value.stringListFromNode() }
        }?.toMap()?.toSortedMap().orEmpty()

    private fun JsonNode.stringListFromNode(): List<String> = takeIf { isArray }
        ?.mapNotNull { it.asText(null)?.trim()?.takeIf(String::isNotBlank) }?.distinct()?.take(20).orEmpty()

    private fun JsonNode.intValue(name: String, default: Int): Int = get(name)?.takeIf { it.isInt || it.isLong }?.asInt() ?: default
    private fun JsonNode.longValue(name: String, default: Long): Long = get(name)?.takeIf { it.isIntegralNumber }?.asLong() ?: default
    private fun simpleTokenCount(content: String): Int = content.trim().split(Regex("\\s+")).filter(String::isNotBlank).size
    private fun isPlatformEntityId(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess

    private companion object {
        val validIntents = setOf("GENERAL_CHAT", "CATALOG_QA", "COMPARISON", "PLANNING", "DETAIL_SUMMARY", "SAFETY_SCREENING")
        val validQueryTargets = setOf("INSTITUTION", "DOCTOR", "PROJECT", "INSTITUTION_PROJECT")
        val validNextActions = setOf("NONE", "SHOW_CATALOG", "START_PLANNING", "COMPLETE_SAFETY_SCREENING")
        val platformEntityTypes = validQueryTargets
    }
}
