package com.joysong.server.reconciliation.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/** Durable, non-mutating record of an internal revenue consistency failure. */
@Entity
@Table(name = "reconciliation_issues")
class ReconciliationIssueEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "issue_type", nullable = false, length = 50)
    val issueType: String = "",

    @Column(name = "object_type", nullable = false, length = 30)
    val objectType: String = "",

    @Column(name = "object_id", nullable = false, length = 100)
    val objectId: String = "",

    @Column(name = "expected_minor", nullable = false)
    var expectedMinor: Long = 0,

    @Column(name = "actual_minor", nullable = false)
    var actualMinor: Long = 0,

    @Column(name = "currency", length = 3)
    var currency: String? = null,

    @Column(name = "severity", nullable = false, length = 20)
    var severity: String = "ERROR",

    @Column(name = "status", nullable = false, length = 20)
    var status: String = "OPEN",

    @Column(name = "occurrence_count", nullable = false)
    var occurrenceCount: Long = 1,

    @Column(name = "first_detected_at", nullable = false)
    val firstDetectedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "last_detected_at", nullable = false)
    var lastDetectedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "resolved_at")
    var resolvedAt: LocalDateTime? = null,

    @Column(name = "details", columnDefinition = "TEXT")
    var details: String? = null,

    @Column(name = "active_key", insertable = false, updatable = false)
    val activeKey: String? = null
)
