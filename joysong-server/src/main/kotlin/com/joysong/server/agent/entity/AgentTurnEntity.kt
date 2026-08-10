package com.joysong.server.agent.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.LocalDateTime
import java.util.UUID

enum class AgentTurnStatus { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED }

@Entity
@Table(name = "agent_turns")
class AgentTurnEntity(
    @Id var id: String = UUID.randomUUID().toString(),
    @Column(name = "session_id", nullable = false) var sessionId: String = "",
    @Column(name = "sequence_no", nullable = false) var sequenceNo: Long = 0,
    @Column(name = "idempotency_key", nullable = false) var idempotencyKey: String = "",
    @Column(name = "request_hash", columnDefinition = "char(64)", length = 64, nullable = false) var requestHash: String = "",
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false) var status: AgentTurnStatus = AgentTurnStatus.PENDING,
    @Column(name = "trace_id", nullable = false) var traceId: String = "",
    @Column(name = "error_code") var errorCode: String? = null,
    @Column(name = "fallback_used", nullable = false) var fallbackUsed: Boolean = false,
    @Column(name = "model_name", nullable = false) var modelName: String = "",
    @Column(name = "prompt_version", nullable = false) var promptVersion: String = "",
    @Column(name = "started_at", nullable = false) var startedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "completed_at") var completedAt: LocalDateTime? = null,
    @Column(name = "total_duration_ms", nullable = false) var totalDurationMs: Long = 0,
    @Column(name = "created_at", nullable = false) var createdAt: LocalDateTime = LocalDateTime.now()
)
