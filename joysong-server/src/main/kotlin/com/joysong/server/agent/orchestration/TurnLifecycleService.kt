package com.joysong.server.agent.orchestration

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.repository.ChatMessageRepository
import com.joysong.server.chat.repository.ChatSessionRepository
import com.joysong.server.chat.service.ChatTurnResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.UUID

sealed interface BeginTurnResult {
    data class Started(val turnId: String, val traceId: String, val sequenceNo: Long) : BeginTurnResult
    data class Replayed(val turn: ChatTurnResult) : BeginTurnResult
    data object InProgress : BeginTurnResult
    data object IdempotencyExpired : BeginTurnResult
}

data class CompleteTurnCommand(
    val turnId: String,
    val content: String,
    val intent: String,
    val queryTarget: String?,
    val nextAction: String,
    val catalogItems: List<AgentCatalogItemResponse> = emptyList(),
    val catalogReport: AgentCatalogReportResponse? = null,
    val durationMs: Long = 0,
    val fallbackUsed: Boolean = false,
    val modelName: String = "",
    val promptVersion: String = ""
)

class IdempotencyKeyConflictException : IllegalStateException("IDEMPOTENCY_KEY_CONFLICT")

@Service
class TurnLifecycleService(
    private val sessionRepository: ChatSessionRepository,
    private val messageRepository: ChatMessageRepository,
    private val turnRepository: AgentTurnRepository,
    private val contextBuilder: AgentContextBuilder,
    private val objectMapper: ObjectMapper
) {
    @Transactional
    fun beginTurn(sessionId: String, userId: String, content: String, idempotencyKey: String): BeginTurnResult {
        val canonicalContent = content.trim().also { require(it.isNotEmpty()) { "消息内容不能为空" } }
        val session = sessionRepository.findByIdAndUserIdForUpdate(sessionId, userId)
            ?: throw IllegalArgumentException("会话不存在或无权访问")
        val requestHash = sha256(canonicalContent)
        turnRepository.findBySessionIdAndIdempotencyKey(sessionId, idempotencyKey)?.let { existing ->
            if (existing.requestHash != requestHash) throw IdempotencyKeyConflictException()
            return when (existing.status) {
                AgentTurnStatus.SUCCEEDED -> messageRepository.findByTurnIdAndRole(existing.id, "ASSISTANT")
                    ?.let { BeginTurnResult.Replayed(reconstruct(it)) } ?: BeginTurnResult.IdempotencyExpired
                AgentTurnStatus.RUNNING -> BeginTurnResult.InProgress
                else -> throw IdempotencyKeyConflictException()
            }
        }
        if (turnRepository.findBySessionIdAndStatus(sessionId, AgentTurnStatus.RUNNING) != null) return BeginTurnResult.InProgress

        val sequenceNo = session.nextSequenceNo
        val traceId = UUID.randomUUID().toString()
        val turn = AgentTurnEntity(
            sessionId = session.id,
            sequenceNo = sequenceNo,
            idempotencyKey = idempotencyKey,
            requestHash = requestHash,
            status = AgentTurnStatus.RUNNING,
            traceId = traceId
        )
        turnRepository.save(turn)
        messageRepository.save(
            ChatMessageEntity(
                sessionId = session.id,
                turnId = turn.id,
                sequenceNo = sequenceNo * 2 - 1,
                role = "USER",
                content = canonicalContent,
                metadataJson = "{}"
            )
        )
        session.nextSequenceNo = sequenceNo + 1
        session.updatedAt = LocalDateTime.now()
        sessionRepository.save(session)
        return BeginTurnResult.Started(turn.id, traceId, sequenceNo)
    }

    @Transactional
    fun completeTurn(command: CompleteTurnCommand): ChatTurnResult {
        val sessionId = turnRepository.findSessionIdById(command.turnId) ?: throw IllegalArgumentException("回合不存在")
        val session = sessionRepository.findByIdForUpdate(sessionId) ?: throw IllegalArgumentException("会话不存在")
        val turn = turnRepository.findByIdForUpdate(command.turnId) ?: throw IllegalArgumentException("回合不存在")
        check(turn.sessionId == session.id) { "回合与会话不匹配" }
        val existingAssistant = messageRepository.findByTurnIdAndRole(turn.id, "ASSISTANT")
        if (turn.status == AgentTurnStatus.SUCCEEDED && existingAssistant != null) return reconstruct(existingAssistant)
        check(turn.status == AgentTurnStatus.RUNNING) { "回合不是运行状态" }
        val metadata = metadata(command)
        val assistant = existingAssistant ?: messageRepository.save(
            ChatMessageEntity(
                sessionId = session.id,
                turnId = turn.id,
                sequenceNo = turn.sequenceNo * 2,
                role = "ASSISTANT",
                content = command.content,
                metadataJson = metadata
            )
        )
        turn.status = AgentTurnStatus.SUCCEEDED
        turn.completedAt = LocalDateTime.now()
        turn.totalDurationMs = command.durationMs
        turn.fallbackUsed = command.fallbackUsed
        turn.modelName = command.modelName
        turn.promptVersion = command.promptVersion
        contextBuilder.updateSummaryAndPrune(session, turn.sequenceNo, command.intent, command.queryTarget, command.nextAction, command.catalogItems)
        turnRepository.save(turn)
        return reconstruct(assistant)
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun failTurn(turnId: String, errorCode: String, durationMs: Long) = finishTurn(turnId, AgentTurnStatus.FAILED, errorCode, durationMs)

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun cancelTurn(turnId: String, errorCode: String, durationMs: Long) = finishTurn(turnId, AgentTurnStatus.CANCELLED, errorCode, durationMs)

    private fun finishTurn(turnId: String, status: AgentTurnStatus, errorCode: String, durationMs: Long) {
        val turn = turnRepository.findByIdForUpdate(turnId) ?: return
        if (turn.status != AgentTurnStatus.RUNNING) return
        turn.status = status
        turn.errorCode = errorCode
        turn.totalDurationMs = durationMs
        turn.completedAt = LocalDateTime.now()
        turnRepository.save(turn)
    }

    private fun metadata(command: CompleteTurnCommand): String = objectMapper.writeValueAsString(
        linkedMapOf(
            "intent" to command.intent,
            "queryTarget" to command.queryTarget,
            "nextAction" to command.nextAction,
            "catalogItems" to command.catalogItems,
            "catalogReport" to command.catalogReport
        )
    )

    private fun reconstruct(message: ChatMessageEntity): ChatTurnResult {
        val metadata = objectMapper.readTree(message.metadataJson.ifBlank { "{}" })
        return ChatTurnResult(
            message = message,
            catalogItems = metadata.get("catalogItems")?.takeIf { it.isArray }
                ?.map { objectMapper.treeToValue(it, AgentCatalogItemResponse::class.java) }.orEmpty(),
            catalogReport = metadata.get("catalogReport")?.takeUnless { it.isNull }
                ?.let { objectMapper.treeToValue(it, AgentCatalogReportResponse::class.java) },
            intent = metadata.path("intent").asText("GENERAL_CHAT"),
            queryTarget = metadata.get("queryTarget")?.takeUnless { it.isNull }?.asText(),
            nextAction = metadata.path("nextAction").asText("NONE")
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
