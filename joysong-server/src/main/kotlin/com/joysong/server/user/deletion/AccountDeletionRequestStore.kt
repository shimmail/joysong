package com.joysong.server.user.deletion

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.LocalDateTime

interface AccountDeletionRequestStore {
    fun insert(record: AccountDeletionRequestRecord)
    fun findForUpdate(requestId: String): AccountDeletionRequestRecord?
    fun findReusableForUserForUpdate(
        userId: String,
        stepUpMethod: AccountDeletionStepUpMethod,
        now: LocalDateTime,
    ): AccountDeletionRequestRecord?
    fun storeSmsChallenge(
        requestId: String,
        codeHash: String,
        expiresAt: LocalDateTime,
        resendAvailableAt: LocalDateTime,
    )
    fun storeVerificationAttempt(requestId: String, attemptCount: Int)
    fun storeAuthorization(requestId: String, authorizationHash: String, expiresAt: LocalDateTime)
    fun consumeAuthorization(requestId: String, idempotencyKeyHash: String, consumedAt: LocalDateTime)
    fun complete(
        requestId: String,
        status: AccountDeletionRequestStatus,
        outcome: AccountDeletionTerminalOutcome,
        terminalResultJson: String,
        completedAt: LocalDateTime,
    )
}

@Repository
class JdbcAccountDeletionRequestStore(
    private val jdbcTemplate: JdbcTemplate,
) : AccountDeletionRequestStore {
    override fun insert(record: AccountDeletionRequestRecord) {
        jdbcTemplate.update(
            """
            INSERT INTO account_deletion_requests
                (id, user_id, policy_version, status, step_up_method,
                 verification_code_hash, verification_attempt_count, verification_max_attempts,
                 verification_expires_at, resend_available_at, authorization_hash,
                 authorization_expires_at, authorization_consumed_at, idempotency_key_hash,
                 request_expires_at, terminal_outcome, terminal_result_json, completed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            record.id,
            record.userId,
            record.policyVersion,
            record.status.name,
            record.stepUpMethod.name,
            record.verificationCodeHash,
            record.verificationAttemptCount,
            record.verificationMaxAttempts,
            record.verificationExpiresAt,
            record.resendAvailableAt,
            record.authorizationHash,
            record.authorizationExpiresAt,
            record.authorizationConsumedAt,
            record.idempotencyKeyHash,
            record.requestExpiresAt,
            record.terminalOutcome?.name,
            record.terminalResultJson,
            record.completedAt,
        )
    }

    override fun findForUpdate(requestId: String): AccountDeletionRequestRecord? = queryForUpdate(requestId)

    override fun findReusableForUserForUpdate(
        userId: String,
        stepUpMethod: AccountDeletionStepUpMethod,
        now: LocalDateTime,
    ): AccountDeletionRequestRecord? = jdbcTemplate.query(
        """
        SELECT id, user_id, policy_version, status, step_up_method,
               verification_code_hash, verification_attempt_count, verification_max_attempts,
               verification_expires_at, resend_available_at, authorization_hash,
               authorization_expires_at, authorization_consumed_at, idempotency_key_hash,
               request_expires_at, terminal_outcome, terminal_result_json, completed_at
        FROM account_deletion_requests
        WHERE user_id = ?
          AND policy_version = ?
          AND status IN ('PREFLIGHTED', 'CODE_SENT')
          AND step_up_method = ?
          AND request_expires_at > ?
        ORDER BY CASE status WHEN 'CODE_SENT' THEN 0 ELSE 1 END, created_at DESC
        LIMIT 1
        FOR UPDATE
        """.trimIndent(),
        { rs, _ -> rs.toRecord() },
        userId,
        ACCOUNT_DELETION_POLICY_VERSION,
        stepUpMethod.name,
        now,
    ).firstOrNull()

    override fun storeSmsChallenge(
        requestId: String,
        codeHash: String,
        expiresAt: LocalDateTime,
        resendAvailableAt: LocalDateTime,
    ) {
        require(
            jdbcTemplate.update(
                """
                UPDATE account_deletion_requests
                SET verification_code_hash = ?, verification_expires_at = ?, resend_available_at = ?,
                    status = 'CODE_SENT'
                WHERE id = ?
                """.trimIndent(),
                codeHash,
                expiresAt,
                resendAvailableAt,
                requestId,
            ) == 1
        ) { "account deletion request disappeared" }
    }

    override fun storeVerificationAttempt(requestId: String, attemptCount: Int) {
        require(
            jdbcTemplate.update(
                "UPDATE account_deletion_requests SET verification_attempt_count = ? WHERE id = ?",
                attemptCount,
                requestId,
            ) == 1
        ) { "account deletion request disappeared" }
    }

    override fun storeAuthorization(requestId: String, authorizationHash: String, expiresAt: LocalDateTime) {
        require(
            jdbcTemplate.update(
                """
                UPDATE account_deletion_requests
                SET authorization_hash = ?, authorization_expires_at = ?, status = 'AUTHORIZED',
                    verification_code_hash = NULL
                WHERE id = ?
                """.trimIndent(),
                authorizationHash,
                expiresAt,
                requestId,
            ) == 1
        ) { "account deletion request disappeared" }
    }

    override fun consumeAuthorization(
        requestId: String,
        idempotencyKeyHash: String,
        consumedAt: LocalDateTime,
    ) {
        require(
            jdbcTemplate.update(
                """
                UPDATE account_deletion_requests
                SET idempotency_key_hash = ?, authorization_consumed_at = ?
                WHERE id = ? AND status = 'AUTHORIZED' AND authorization_consumed_at IS NULL
                """.trimIndent(),
                idempotencyKeyHash,
                consumedAt,
                requestId,
            ) == 1
        ) { "account deletion authorization was already consumed" }
    }

    override fun complete(
        requestId: String,
        status: AccountDeletionRequestStatus,
        outcome: AccountDeletionTerminalOutcome,
        terminalResultJson: String,
        completedAt: LocalDateTime,
    ) {
        require(
            jdbcTemplate.update(
                """
                UPDATE account_deletion_requests
                SET status = ?, terminal_outcome = ?, terminal_result_json = CAST(? AS JSON), completed_at = ?
                WHERE id = ?
                """.trimIndent(),
                status.name,
                outcome.name,
                terminalResultJson,
                completedAt,
                requestId,
            ) == 1
        ) { "account deletion request disappeared" }
    }

    private fun queryForUpdate(requestId: String): AccountDeletionRequestRecord? {
        return jdbcTemplate.query(
            """
            SELECT id, user_id, policy_version, status, step_up_method,
                   verification_code_hash, verification_attempt_count, verification_max_attempts,
                   verification_expires_at, resend_available_at, authorization_hash,
                   authorization_expires_at, authorization_consumed_at, idempotency_key_hash,
                   request_expires_at, terminal_outcome, terminal_result_json, completed_at
            FROM account_deletion_requests
            WHERE id = ? FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.toRecord() },
            requestId,
        ).firstOrNull()
    }

    private fun ResultSet.toRecord() = AccountDeletionRequestRecord(
        id = getString("id"),
        userId = getString("user_id"),
        policyVersion = getString("policy_version"),
        status = AccountDeletionRequestStatus.valueOf(getString("status")),
        stepUpMethod = AccountDeletionStepUpMethod.valueOf(getString("step_up_method")),
        verificationCodeHash = getString("verification_code_hash"),
        verificationAttemptCount = getInt("verification_attempt_count"),
        verificationMaxAttempts = getInt("verification_max_attempts"),
        verificationExpiresAt = getTimestamp("verification_expires_at")?.toLocalDateTime(),
        resendAvailableAt = getTimestamp("resend_available_at")?.toLocalDateTime(),
        authorizationHash = getString("authorization_hash"),
        authorizationExpiresAt = getTimestamp("authorization_expires_at")?.toLocalDateTime(),
        authorizationConsumedAt = getTimestamp("authorization_consumed_at")?.toLocalDateTime(),
        idempotencyKeyHash = getString("idempotency_key_hash"),
        requestExpiresAt = getTimestamp("request_expires_at").toLocalDateTime(),
        terminalOutcome = getString("terminal_outcome")?.let(AccountDeletionTerminalOutcome::valueOf),
        terminalResultJson = getString("terminal_result_json"),
        completedAt = getTimestamp("completed_at")?.toLocalDateTime(),
    )
}
