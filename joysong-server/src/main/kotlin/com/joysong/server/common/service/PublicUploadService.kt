package com.joysong.server.common.service

import com.fasterxml.jackson.annotation.JsonInclude
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.multipart.MultipartFile
import java.sql.ResultSet
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.nio.charset.StandardCharsets
import java.util.UUID

enum class PublicUploadStatus { PENDING, COMPLETE, FAILED }

@JsonInclude(JsonInclude.Include.NON_NULL)
data class PublicUploadData(
    val uploadId: String,
    val status: PublicUploadStatus,
    val url: String? = null,
    val replayed: Boolean? = null,
    val retryAfterMs: Long? = null,
    val errorCode: String? = null,
    val retryable: Boolean? = null,
)

internal enum class PublicUploadResultType { COMPLETE, PENDING, CONFLICT, FAILED }

internal data class PublicUploadTimings(
    val stageDurationNanos: Long = 0,
    val storageDurationNanos: Long = 0,
    val registryDurationNanos: Long = 0,
)

internal data class PublicUploadResult(
    val type: PublicUploadResultType,
    val data: PublicUploadData,
    val timings: PublicUploadTimings = PublicUploadTimings(),
)

internal data class PublicUploadFingerprint(
    val folder: String,
    val contentSha256: String,
    val contentLength: Long,
    val contentType: String,
    val storageKey: String,
)

internal data class PublicUploadAttempt(
    val ownerUserId: String,
    val clientUploadId: String,
    val fingerprint: PublicUploadFingerprint,
    val status: PublicUploadStatus,
    val leaseToken: String?,
    val leaseExpiresAt: LocalDateTime?,
    val publicUrl: String?,
    val failureCode: String?,
    val failureRetryable: Boolean?,
)

internal sealed interface PublicUploadClaim {
    data class Acquired(val leaseToken: String) : PublicUploadClaim
    data class Complete(val url: String) : PublicUploadClaim
    data class Pending(val retryAfterMs: Long) : PublicUploadClaim
    data object Conflict : PublicUploadClaim
}

internal interface PublicUploadAttemptStore {
    fun claim(
        ownerUserId: String,
        clientUploadId: String,
        fingerprint: PublicUploadFingerprint,
        now: LocalDateTime,
    ): PublicUploadClaim

    fun complete(
        ownerUserId: String,
        clientUploadId: String,
        leaseToken: String,
        publicUrl: String,
        now: LocalDateTime,
    )

    fun fail(
        ownerUserId: String,
        clientUploadId: String,
        leaseToken: String,
        errorCode: String,
        retryable: Boolean,
        now: LocalDateTime,
    )

    fun findOwned(ownerUserId: String, clientUploadId: String): PublicUploadAttempt?
    fun deleteExpired(now: LocalDateTime): Int
}

@Repository
internal class JdbcPublicUploadAttemptStore(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : PublicUploadAttemptStore {
    override fun claim(
        ownerUserId: String,
        clientUploadId: String,
        fingerprint: PublicUploadFingerprint,
        now: LocalDateTime,
    ): PublicUploadClaim = requireNotNull(transactionTemplate.execute {
        // Demo deployments intentionally disable the application's general
        // scheduler, so uploads also provide an index-backed cleanup
        // opportunity for seven-day-old idempotency records.
        deleteExpired(now)
        val leaseToken = UUID.randomUUID().toString()
        val leaseExpiresAt = now.plus(LEASE_DURATION)
        val recordExpiresAt = now.plus(RECORD_RETENTION)
        jdbcTemplate.update(
            """
            INSERT INTO public_upload_attempts
                (id, owner_user_id, client_upload_id, folder, content_sha256, content_length,
                 content_type, storage_key, status, lease_token, lease_expires_at, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
            ON DUPLICATE KEY UPDATE client_upload_id = VALUES(client_upload_id)
            """.trimIndent(),
            UUID.randomUUID().toString(),
            ownerUserId,
            clientUploadId,
            fingerprint.folder,
            fingerprint.contentSha256,
            fingerprint.contentLength,
            fingerprint.contentType,
            fingerprint.storageKey,
            leaseToken,
            leaseExpiresAt,
            recordExpiresAt,
        )
        val current = requireNotNull(findOwnedForUpdate(ownerUserId, clientUploadId)) {
            "public upload attempt disappeared"
        }
        if (current.fingerprint != fingerprint) {
            return@execute PublicUploadClaim.Conflict
        }
        if (current.status == PublicUploadStatus.COMPLETE && current.publicUrl != null) {
            return@execute PublicUploadClaim.Complete(current.publicUrl)
        }
        if (current.leaseToken == leaseToken) {
            return@execute PublicUploadClaim.Acquired(leaseToken)
        }
        val currentLeaseExpiry = current.leaseExpiresAt
        if (current.status == PublicUploadStatus.PENDING &&
            currentLeaseExpiry != null && currentLeaseExpiry.isAfter(now)
        ) {
            return@execute PublicUploadClaim.Pending(retryAfter(currentLeaseExpiry, now))
        }

        val updated = jdbcTemplate.update(
            """
            UPDATE public_upload_attempts
            SET status = 'PENDING', lease_token = ?, lease_expires_at = ?,
                public_url = NULL, failure_code = NULL, failure_retryable = NULL,
                completed_at = NULL, expires_at = ?
            WHERE owner_user_id = ? AND client_upload_id = ?
            """.trimIndent(),
            leaseToken,
            leaseExpiresAt,
            recordExpiresAt,
            ownerUserId,
            clientUploadId,
        )
        check(updated == 1) { "public upload attempt disappeared" }
        PublicUploadClaim.Acquired(leaseToken)
    })

    override fun complete(
        ownerUserId: String,
        clientUploadId: String,
        leaseToken: String,
        publicUrl: String,
        now: LocalDateTime,
    ) {
        check(
            jdbcTemplate.update(
                """
                UPDATE public_upload_attempts
                SET status = 'COMPLETE', public_url = ?, completed_at = ?,
                    lease_token = NULL, lease_expires_at = NULL,
                    failure_code = NULL, failure_retryable = NULL, expires_at = ?
                WHERE owner_user_id = ? AND client_upload_id = ?
                  AND status = 'PENDING' AND lease_token = ?
                """.trimIndent(),
                publicUrl,
                now,
                now.plus(RECORD_RETENTION),
                ownerUserId,
                clientUploadId,
                leaseToken,
            ) == 1
        ) { "public upload lease was lost" }
    }

    override fun fail(
        ownerUserId: String,
        clientUploadId: String,
        leaseToken: String,
        errorCode: String,
        retryable: Boolean,
        now: LocalDateTime,
    ) {
        jdbcTemplate.update(
            """
            UPDATE public_upload_attempts
            SET status = 'FAILED', failure_code = ?, failure_retryable = ?,
                lease_token = NULL, lease_expires_at = NULL, expires_at = ?
            WHERE owner_user_id = ? AND client_upload_id = ?
              AND status = 'PENDING' AND lease_token = ?
            """.trimIndent(),
            errorCode,
            retryable,
            now.plus(RECORD_RETENTION),
            ownerUserId,
            clientUploadId,
            leaseToken,
        )
    }

    override fun findOwned(ownerUserId: String, clientUploadId: String): PublicUploadAttempt? =
        queryOwned(ownerUserId, clientUploadId, forUpdate = false)

    override fun deleteExpired(now: LocalDateTime): Int = jdbcTemplate.update(
        "DELETE FROM public_upload_attempts WHERE expires_at < ?",
        now,
    )

    private fun findOwnedForUpdate(ownerUserId: String, clientUploadId: String): PublicUploadAttempt? =
        queryOwned(ownerUserId, clientUploadId, forUpdate = true)

    private fun queryOwned(
        ownerUserId: String,
        clientUploadId: String,
        forUpdate: Boolean,
    ): PublicUploadAttempt? = jdbcTemplate.query(
        """
        SELECT owner_user_id, client_upload_id, folder, content_sha256, content_length,
               content_type, storage_key, status, lease_token, lease_expires_at,
               public_url, failure_code, failure_retryable
        FROM public_upload_attempts
        WHERE owner_user_id = ? AND client_upload_id = ?${if (forUpdate) " FOR UPDATE" else ""}
        """.trimIndent(),
        { rs, _ -> rs.toPublicUploadAttempt() },
        ownerUserId,
        clientUploadId,
    ).firstOrNull()

    private fun ResultSet.toPublicUploadAttempt() = PublicUploadAttempt(
        ownerUserId = getString("owner_user_id"),
        clientUploadId = getString("client_upload_id"),
        fingerprint = PublicUploadFingerprint(
            folder = getString("folder"),
            contentSha256 = getString("content_sha256"),
            contentLength = getLong("content_length"),
            contentType = getString("content_type"),
            storageKey = getString("storage_key"),
        ),
        status = PublicUploadStatus.valueOf(getString("status")),
        leaseToken = getString("lease_token"),
        leaseExpiresAt = getTimestamp("lease_expires_at")?.toLocalDateTime(),
        publicUrl = getString("public_url"),
        failureCode = getString("failure_code"),
        failureRetryable = getObject("failure_retryable")?.let { getBoolean("failure_retryable") },
    )

    private fun retryAfter(leaseExpiresAt: LocalDateTime, now: LocalDateTime): Long =
        Duration.between(now, leaseExpiresAt).toMillis().coerceIn(MIN_RETRY_AFTER_MS, MAX_RETRY_AFTER_MS)

    private companion object {
        val LEASE_DURATION: Duration = Duration.ofSeconds(180)
        val RECORD_RETENTION: Duration = Duration.ofDays(7)
        const val MIN_RETRY_AFTER_MS = 250L
        const val MAX_RETRY_AFTER_MS = 2_000L
    }
}

@Service
class PublicUploadService internal constructor(
    private val fileUploadService: FileUploadService,
    private val attemptStore: PublicUploadAttemptStore,
    private val metrics: PublicUploadMetrics,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    internal fun upload(
        ownerUserId: String,
        uploadId: String,
        file: MultipartFile,
        folder: String,
        customFileName: String?,
        requestId: String,
        requestStartedAtNanos: Long = System.nanoTime(),
    ): PublicUploadResult {
        validateUploadId(uploadId)
        require(customFileName == null) { "IDEMPOTENT_CUSTOM_FILE_NAME_UNSUPPORTED" }
        val requestStartedAt = requestStartedAtNanos
        var contentLength: Long? = null
        var metricOutcome = UploadMetricOutcome.FAILED
        try {
            fileUploadService.prepare(file).use { prepared ->
                contentLength = prepared.contentLength
                val storageKey = fileUploadService.storageKey(
                    prepared,
                    folder,
                    ownerScopedObjectName(ownerUserId, uploadId),
                )
                val fingerprint = PublicUploadFingerprint(
                    folder = folder,
                    contentSha256 = prepared.contentSha256,
                    contentLength = prepared.contentLength,
                    contentType = prepared.contentType,
                    storageKey = storageKey,
                )
                return when (val claim = attemptStore.claim(
                    ownerUserId,
                    uploadId,
                    fingerprint,
                    LocalDateTime.now(clock),
                )) {
                    is PublicUploadClaim.Complete -> {
                        metricOutcome = UploadMetricOutcome.REPLAYED
                        result(
                            type = PublicUploadResultType.COMPLETE,
                            data = PublicUploadData(
                                uploadId = uploadId,
                                status = PublicUploadStatus.COMPLETE,
                                url = claim.url,
                                replayed = true,
                            ),
                            prepared = prepared,
                        )
                    }
                    is PublicUploadClaim.Pending -> {
                        metricOutcome = UploadMetricOutcome.PENDING
                        result(
                            type = PublicUploadResultType.PENDING,
                            data = PublicUploadData(
                                uploadId = uploadId,
                                status = PublicUploadStatus.PENDING,
                                replayed = false,
                                retryAfterMs = claim.retryAfterMs,
                            ),
                            prepared = prepared,
                        )
                    }
                    PublicUploadClaim.Conflict -> {
                        metricOutcome = UploadMetricOutcome.CONFLICT
                        result(
                            type = PublicUploadResultType.CONFLICT,
                            data = PublicUploadData(
                                uploadId = uploadId,
                                status = PublicUploadStatus.FAILED,
                                replayed = false,
                                errorCode = "UPLOAD_ID_REUSED",
                                retryable = false,
                            ),
                            prepared = prepared,
                        )
                    }
                    is PublicUploadClaim.Acquired -> uploadClaimed(
                        ownerUserId = ownerUserId,
                        uploadId = uploadId,
                        folder = folder,
                        storageKey = storageKey,
                        leaseToken = claim.leaseToken,
                        prepared = prepared,
                        requestId = requestId,
                        requestStartedAt = requestStartedAt,
                    ).also {
                        metricOutcome = when (it.type) {
                            PublicUploadResultType.COMPLETE -> UploadMetricOutcome.COMPLETE
                            else -> UploadMetricOutcome.FAILED
                        }
                    }
                }
            }
        } finally {
            metrics.recordRequest(metricOutcome, System.nanoTime() - requestStartedAt, contentLength)
        }
    }

    internal fun status(ownerUserId: String, uploadId: String): PublicUploadData? {
        validateUploadId(uploadId)
        val attempt = attemptStore.findOwned(ownerUserId, uploadId) ?: return null
        return when (attempt.status) {
            PublicUploadStatus.COMPLETE -> PublicUploadData(
                uploadId = uploadId,
                status = PublicUploadStatus.COMPLETE,
                url = attempt.publicUrl,
                replayed = true,
            )
            PublicUploadStatus.PENDING -> PublicUploadData(
                uploadId = uploadId,
                status = PublicUploadStatus.PENDING,
                replayed = false,
                retryAfterMs = attempt.leaseExpiresAt?.let {
                    Duration.between(LocalDateTime.now(clock), it).toMillis().coerceIn(250, 2_000)
                } ?: 250,
            )
            PublicUploadStatus.FAILED -> PublicUploadData(
                uploadId = uploadId,
                status = PublicUploadStatus.FAILED,
                replayed = false,
                errorCode = attempt.failureCode ?: "UPLOAD_FAILED",
                retryable = attempt.failureRetryable ?: true,
            )
        }
    }

    @Scheduled(fixedDelayString = "\${upload.idempotency-cleanup-delay-ms:3600000}")
    internal fun cleanExpiredAttempts() {
        val deleted = attemptStore.deleteExpired(LocalDateTime.now(clock))
        if (deleted > 0) logger.info("Expired public upload attempt records removed: {}", deleted)
    }

    private fun uploadClaimed(
        ownerUserId: String,
        uploadId: String,
        folder: String,
        storageKey: String,
        leaseToken: String,
        prepared: PreparedPublicUpload,
        requestId: String,
        requestStartedAt: Long,
    ): PublicUploadResult = try {
        val receipt = fileUploadService.uploadPreparedAtKey(ownerUserId, prepared, folder, storageKey)
        attemptStore.complete(
            ownerUserId,
            uploadId,
            leaseToken,
            receipt.url,
            LocalDateTime.now(clock),
        )
        logger.info(
            "Public upload completed requestId={} uploadId={} bytes={} stageMs={} storageMs={} " +
                "registryMs={} totalMs={} result=complete",
            requestId,
            uploadId,
            receipt.contentLength,
            receipt.stageDurationNanos.toMillis(),
            receipt.storageDurationNanos.toMillis(),
            receipt.registryDurationNanos.toMillis(),
            (System.nanoTime() - requestStartedAt).toMillis(),
        )
        PublicUploadResult(
            type = PublicUploadResultType.COMPLETE,
            data = PublicUploadData(
                uploadId = uploadId,
                status = PublicUploadStatus.COMPLETE,
                url = receipt.url,
                replayed = false,
            ),
            timings = PublicUploadTimings(
                stageDurationNanos = receipt.stageDurationNanos,
                storageDurationNanos = receipt.storageDurationNanos,
                registryDurationNanos = receipt.registryDurationNanos,
            ),
        )
    } catch (error: Exception) {
        val errorCode = "UPLOAD_FAILED"
        runCatching {
            attemptStore.fail(
                ownerUserId,
                uploadId,
                leaseToken,
                errorCode,
                retryable = true,
                now = LocalDateTime.now(clock),
            )
        }.onFailure {
            logger.error("Public upload failure state could not be stored requestId={} uploadId={}", requestId, uploadId)
        }
        logger.warn(
            "Public upload failed requestId={} uploadId={} bytes={} stageMs={} totalMs={} " +
                "result=failed errorType={}",
            requestId,
            uploadId,
            prepared.contentLength,
            prepared.stageDurationNanos.toMillis(),
            (System.nanoTime() - requestStartedAt).toMillis(),
            error.javaClass.simpleName,
        )
        result(
            type = PublicUploadResultType.FAILED,
            data = PublicUploadData(
                uploadId = uploadId,
                status = PublicUploadStatus.FAILED,
                replayed = false,
                errorCode = errorCode,
                retryable = true,
            ),
            prepared = prepared,
        )
    }

    private fun result(
        type: PublicUploadResultType,
        data: PublicUploadData,
        prepared: PreparedPublicUpload,
    ) = PublicUploadResult(
        type = type,
        data = data,
        timings = PublicUploadTimings(stageDurationNanos = prepared.stageDurationNanos),
    )

    private fun validateUploadId(uploadId: String) {
        require(runCatching { UUID.fromString(uploadId) }.getOrNull()?.toString() == uploadId) {
            "INVALID_UPLOAD_ID"
        }
    }

    private fun ownerScopedObjectName(ownerUserId: String, uploadId: String): String =
        UUID.nameUUIDFromBytes("$ownerUserId:$uploadId".toByteArray(StandardCharsets.UTF_8)).toString()

    private fun Long.toMillis(): Long = Duration.ofNanos(this).toMillis()
}
