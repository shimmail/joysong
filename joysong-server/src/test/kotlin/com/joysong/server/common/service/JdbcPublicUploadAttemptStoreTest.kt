package com.joysong.server.common.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDateTime
import java.util.UUID

class JdbcPublicUploadAttemptStoreTest {
    @Test
    fun `claim lease completion conflict ownership and expiry are atomic`() {
        val jdbc = JdbcTemplate(
            DriverManagerDataSource(
                "jdbc:h2:mem:public_upload_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa",
                "",
            ),
        )
        createTable(jdbc)
        val store = JdbcPublicUploadAttemptStore(
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(requireNotNull(jdbc.dataSource))),
        )
        val now = LocalDateTime.parse("2026-09-03T03:00:00")
        val uploadId = UUID.randomUUID().toString()
        val fingerprint = fingerprint("a".repeat(64))

        val acquired = assertInstanceOf(
            PublicUploadClaim.Acquired::class.java,
            store.claim("user-1", uploadId, fingerprint, now),
        )
        assertInstanceOf(
            PublicUploadClaim.Pending::class.java,
            store.claim("user-1", uploadId, fingerprint, now.plusSeconds(1)),
        )
        assertEquals(
            PublicUploadClaim.Conflict,
            store.claim("user-1", uploadId, fingerprint("b".repeat(64)), now.plusSeconds(2)),
        )

        store.complete("user-1", uploadId, acquired.leaseToken, "https://cdn/image.png", now.plusSeconds(3))
        assertEquals(
            PublicUploadClaim.Complete("https://cdn/image.png"),
            store.claim("user-1", uploadId, fingerprint, now.plusSeconds(4)),
        )
        assertNull(store.findOwned("user-2", uploadId))

        val expiredLeaseId = UUID.randomUUID().toString()
        store.claim("user-1", expiredLeaseId, fingerprint, now)
        assertInstanceOf(
            PublicUploadClaim.Acquired::class.java,
            store.claim("user-1", expiredLeaseId, fingerprint, now.plusSeconds(181)),
        )

        val cleanupTriggerId = UUID.randomUUID().toString()
        store.claim("user-1", cleanupTriggerId, fingerprint, now.plusDays(8))
        assertNull(store.findOwned("user-1", uploadId))
        assertNull(store.findOwned("user-1", expiredLeaseId))
        assertEquals(0, store.deleteExpired(now.plusDays(8)))
    }

    private fun fingerprint(hash: String) = PublicUploadFingerprint(
        folder = "diary",
        contentSha256 = hash,
        contentLength = 12,
        contentType = "image/png",
        storageKey = "diary/object.png",
    )

    private fun createTable(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            CREATE TABLE public_upload_attempts (
                id VARCHAR(36) NOT NULL PRIMARY KEY,
                owner_user_id VARCHAR(36) NOT NULL,
                client_upload_id VARCHAR(36) NOT NULL,
                folder VARCHAR(64) NOT NULL,
                content_sha256 CHAR(64) NOT NULL,
                content_length BIGINT NOT NULL,
                content_type VARCHAR(64) NOT NULL,
                storage_key VARCHAR(512) NOT NULL,
                status VARCHAR(16) NOT NULL,
                lease_token VARCHAR(36),
                lease_expires_at TIMESTAMP(6),
                public_url VARCHAR(2048),
                failure_code VARCHAR(64),
                failure_retryable BOOLEAN,
                completed_at TIMESTAMP(6),
                expires_at TIMESTAMP(6) NOT NULL,
                created_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                updated_at TIMESTAMP(6) DEFAULT CURRENT_TIMESTAMP(6),
                UNIQUE (owner_user_id, client_upload_id)
            )
            """.trimIndent(),
        )
    }
}
