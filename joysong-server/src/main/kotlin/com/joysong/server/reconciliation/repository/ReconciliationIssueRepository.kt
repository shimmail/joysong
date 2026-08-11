package com.joysong.server.reconciliation.repository

import com.joysong.server.reconciliation.entity.ReconciliationIssueEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface ReconciliationIssueRepository : JpaRepository<ReconciliationIssueEntity, Long> {
    @Modifying
    @Query(
        value = """
            INSERT INTO reconciliation_issues
                (issue_type, object_type, object_id, expected_minor, actual_minor, currency, severity,
                 status, occurrence_count, first_detected_at, last_detected_at, resolved_at, details)
            VALUES
                (:issueType, :objectType, :objectId, :expectedMinor, :actualMinor, :currency, :severity,
                 'OPEN', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, :details)
            ON DUPLICATE KEY UPDATE
                expected_minor = VALUES(expected_minor),
                actual_minor = VALUES(actual_minor),
                currency = VALUES(currency),
                severity = VALUES(severity),
                details = VALUES(details),
                last_detected_at = CURRENT_TIMESTAMP,
                occurrence_count = occurrence_count + 1
        """,
        nativeQuery = true
    )
    fun upsertActiveIssue(
        @Param("issueType") issueType: String,
        @Param("objectType") objectType: String,
        @Param("objectId") objectId: String,
        @Param("expectedMinor") expectedMinor: Long,
        @Param("actualMinor") actualMinor: Long,
        @Param("currency") currency: String?,
        @Param("severity") severity: String,
        @Param("details") details: String
    ): Int

    @Modifying
    @Query(
        """
            update ReconciliationIssueEntity issue
            set issue.status = 'RESOLVED', issue.resolvedAt = CURRENT_TIMESTAMP
            where issue.issueType = :issueType and issue.objectType = :objectType
              and issue.objectId = :objectId and issue.resolvedAt is null
        """
    )
    fun resolveActiveIssue(
        @Param("issueType") issueType: String,
        @Param("objectType") objectType: String,
        @Param("objectId") objectId: String
    ): Int
}
