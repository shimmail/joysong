package com.joysong.server.report.entity

import jakarta.persistence.*
import java.time.LocalDateTime
import java.util.UUID

@Entity
@Table(name = "reports")
data class ReportEntity(
    @Id
    @Column(name = "id", length = 36)
    var id: String = UUID.randomUUID().toString(),

    @Column(name = "user_id", nullable = false, length = 36)
    var userId: String = "",

    @Column(name = "target_type", nullable = false, length = 20)
    var targetType: String = "",

    @Column(name = "target_id", nullable = false, length = 36)
    var targetId: String = "",

    @Column(name = "reason", nullable = false, length = 50)
    var reason: String = "",

    @Column(name = "description", columnDefinition = "TEXT")
    var description: String? = null,

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "pending",

    @Column(name = "created_at")
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "target_summary", columnDefinition = "TEXT")
    var targetSummary: String? = null,

    @Column(name = "deleted", nullable = false)
    var deleted: Boolean = false,

    @Column(name = "updated_at")
    var updatedAt: LocalDateTime? = null
)
