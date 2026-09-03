package com.joysong.server.user.deletion

import com.aliyun.oss.OSS
import com.joysong.server.user.service.AccountLifecycleGuard
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

enum class UserMediaStorageProvider { LOCAL_PUBLIC, OSS_PUBLIC, LOCAL_PRIVATE, OSS_PRIVATE }

data class AccountErasedEvent(val userId: String, val requestId: String)

@Service
class UserMediaAssetService(
    private val jdbcTemplate: JdbcTemplate,
    private val lifecycleGuard: AccountLifecycleGuard,
) {
    @Transactional
    fun register(
        userId: String,
        storageKey: String,
        assetType: String,
        storageProvider: UserMediaStorageProvider,
    ) {
        lifecycleGuard.requireActiveForWrite(userId)
        validateStorageKey(storageKey)
        val existingOwner = jdbcTemplate.query(
            "SELECT owner_user_id FROM user_media_assets WHERE storage_key = ? FOR UPDATE",
            { rs, _ -> rs.getString("owner_user_id") },
            storageKey,
        ).firstOrNull()
        if (existingOwner == null) {
            jdbcTemplate.update(
                """
                INSERT INTO user_media_assets
                    (id, owner_user_id, storage_key, asset_type, storage_provider, delete_status)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                UUID.randomUUID().toString(),
                userId,
                storageKey,
                assetType.take(32),
                storageProvider.name,
            )
        } else {
            require(existingOwner == userId) { "storage key is already owned by another user" }
            jdbcTemplate.update(
                """
                UPDATE user_media_assets
                SET asset_type = ?, storage_provider = ?, delete_status = 'ACTIVE',
                    delete_attempt_count = 0, retry_after = NULL, last_delete_error = NULL
                WHERE storage_key = ? AND owner_user_id = ?
                """.trimIndent(),
                assetType.take(32),
                storageProvider.name,
                storageKey,
                userId,
            )
        }
    }

    fun markPending(userId: String): Int = jdbcTemplate.update(
        """
        UPDATE user_media_assets uma
        SET delete_status = 'PENDING', retry_after = NULL, last_delete_error = NULL
        WHERE uma.owner_user_id = ? AND uma.delete_status <> 'DELETED'
          AND NOT EXISTS (
              SELECT 1
              FROM private_files pf
              JOIN platform_cooperation_agreements pca ON pca.agreement_file_id = pf.id
              WHERE pf.storage_key = uma.storage_key
          )
          AND NOT EXISTS (
              SELECT 1
              FROM private_files pf
              JOIN refund_evidence_files ref ON ref.file_id = pf.id
              WHERE pf.storage_key = uma.storage_key
          )
        """.trimIndent(),
        userId,
    )

    companion object {
        fun validateStorageKey(storageKey: String) {
            require(storageKey.isNotBlank() && storageKey.length <= 512) { "invalid storage key" }
            require(!storageKey.startsWith('/') && !storageKey.startsWith('\\')) { "invalid storage key" }
            val normalized = storageKey.replace('\\', '/').split('/')
            require(normalized.none { it.isBlank() || it == "." || it == ".." }) { "invalid storage key" }
        }
    }
}

private data class PendingMediaAsset(
    val id: String,
    val ownerUserId: String,
    val storageKey: String,
    val storageProvider: String,
    val attemptCount: Int,
)

@Service
class UserMediaDeletionWorker(
    private val jdbcTemplate: JdbcTemplate,
    @Value("\${upload.local-dir:./data/uploads}") private val publicUploadDirectory: String,
    @Value("\${upload.private-dir:./data/private}") private val privateUploadDirectory: String,
    @Value("\${oss.bucket-name:}") private val ossBucketName: String,
    @Value("\${oss.private-bucket-name:}") private val privateOssBucketName: String,
    private val ossClientProvider: ObjectProvider<OSS>,
    private val metrics: AccountDeletionMetrics,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun afterAccountErased(event: AccountErasedEvent) {
        processUser(event.userId)
    }

    @Scheduled(fixedDelayString = "\${app.account-deletion.media-retry-delay-ms:60000}")
    fun retryPendingAssets() {
        findPending(limit = 50).forEach(::deleteOne)
    }

    @EventListener(ApplicationReadyEvent::class)
    fun recoverInterruptedUploads() {
        findInterruptedUploads().forEach(::deleteOne)
    }

    fun processUser(userId: String) {
        findPending(userId = userId, limit = 100).forEach(::deleteOne)
    }

    private fun findPending(userId: String? = null, limit: Int): List<PendingMediaAsset> {
        val userClause = if (userId == null) "" else " AND owner_user_id = ?"
        val sql =
            """
            SELECT id, owner_user_id, storage_key, storage_provider, delete_attempt_count
            FROM user_media_assets
            WHERE (
                    (delete_status IN ('PENDING', 'FAILED') AND (retry_after IS NULL OR retry_after <= NOW()))
                    OR (delete_status = 'UPLOAD_PENDING' AND retry_after IS NOT NULL AND retry_after <= NOW())
                  )$userClause
            ORDER BY created_at
            LIMIT ?
            """.trimIndent()
        val mapper = pendingMediaAssetMapper()
        return if (userId == null) {
            jdbcTemplate.query(sql, mapper, limit)
        } else {
            jdbcTemplate.query(sql, mapper, userId, limit)
        }
    }

    private fun findInterruptedUploads(): List<PendingMediaAsset> = jdbcTemplate.query(
        """
        SELECT id, owner_user_id, storage_key, storage_provider, delete_attempt_count
        FROM user_media_assets
        WHERE delete_status = 'UPLOAD_PENDING'
        ORDER BY created_at
        """.trimIndent(),
        pendingMediaAssetMapper(),
    )

    private fun pendingMediaAssetMapper(): RowMapper<PendingMediaAsset> = RowMapper { rs, _ ->
        PendingMediaAsset(
            id = rs.getString("id"),
            ownerUserId = rs.getString("owner_user_id"),
            storageKey = rs.getString("storage_key"),
            storageProvider = rs.getString("storage_provider"),
            attemptCount = rs.getInt("delete_attempt_count"),
        )
    }

    private fun deleteOne(asset: PendingMediaAsset) {
        try {
            UserMediaAssetService.validateStorageKey(asset.storageKey)
            when (UserMediaStorageProvider.valueOf(asset.storageProvider)) {
                UserMediaStorageProvider.LOCAL_PUBLIC -> deleteLocal(publicUploadDirectory, asset.storageKey)
                UserMediaStorageProvider.LOCAL_PRIVATE -> deleteLocal(privateUploadDirectory, asset.storageKey)
                UserMediaStorageProvider.OSS_PUBLIC -> {
                    val bucketName = ossBucketName.trim()
                    require(bucketName.isNotBlank()) { "OSS bucket is unavailable" }
                    val client = ossClientProvider.ifAvailable
                        ?: throw IllegalStateException("OSS client is unavailable")
                    client.deleteObject(bucketName, asset.storageKey)
                }
                UserMediaStorageProvider.OSS_PRIVATE -> {
                    val bucketName = privateOssBucketName.trim()
                    require(bucketName.isNotBlank()) { "Private OSS bucket is unavailable" }
                    val client = ossClientProvider.ifAvailable
                        ?: throw IllegalStateException("OSS client is unavailable")
                    client.deleteObject(bucketName, asset.storageKey)
                }
            }
            jdbcTemplate.update(
                """
                UPDATE user_media_assets
                SET delete_status = 'DELETED', delete_attempt_count = delete_attempt_count + 1,
                    last_delete_attempt_at = NOW(), retry_after = NULL, last_delete_error = NULL
                WHERE id = ? AND delete_status <> 'DELETED'
                """.trimIndent(),
                asset.id,
            )
        } catch (error: Exception) {
            val nextAttempt = asset.attemptCount + 1
            val retryAt = LocalDateTime.now().plusSeconds(backoffSeconds(nextAttempt))
            jdbcTemplate.update(
                """
                UPDATE user_media_assets
                SET delete_status = 'FAILED', delete_attempt_count = ?, last_delete_attempt_at = NOW(),
                    retry_after = ?, last_delete_error = ?
                WHERE id = ? AND delete_status <> 'DELETED'
                """.trimIndent(),
                nextAttempt,
                retryAt,
                error.javaClass.simpleName.take(512),
                asset.id,
            )
            metrics.count("media_retry", "MEDIA_DELETE_FAILED")
            logger.warn("Account deletion media cleanup will retry userId={}", asset.ownerUserId)
        }
    }

    internal fun deleteLocal(root: String, storageKey: String) {
        val base = Path.of(root).toAbsolutePath().normalize()
        val target = base.resolve(storageKey).normalize()
        require(target.startsWith(base)) { "invalid storage key" }
        Files.deleteIfExists(target)
    }

    private fun backoffSeconds(attempt: Int): Long =
        (30L * (1L shl attempt.coerceIn(0, 10))).coerceAtMost(86_400L)
}
