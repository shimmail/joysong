package com.joysong.server.user.deletion

import com.aliyun.oss.OSS
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.util.UUID

class UserMediaDeletionWorkerTest {
    @TempDir lateinit var root: Path

    private lateinit var worker: UserMediaDeletionWorker

    @BeforeEach
    fun setUp() {
        worker = UserMediaDeletionWorker(
            jdbcTemplate = mockk<JdbcTemplate>(relaxed = true),
            publicUploadDirectory = root.toString(),
            privateUploadDirectory = root.toString(),
            ossBucketName = "",
            privateOssBucketName = "",
            ossClientProvider = mockk<ObjectProvider<OSS>>(relaxed = true),
            metrics = mockk<AccountDeletionMetrics>(relaxed = true),
            schedulingEnabled = true,
        )
    }

    @Test
    fun `missing registered local object is already a successful deletion`() {
        assertDoesNotThrow { worker.deleteLocal(root.toString(), "avatars/missing.png") }
    }

    @Test
    fun `registered local object is deleted inside the configured root`() {
        val file = root.resolve("avatars/user.png")
        Files.createDirectories(file.parent)
        Files.write(file, byteArrayOf(1, 2, 3))

        worker.deleteLocal(root.toString(), "avatars/user.png")

        assertFalse(Files.exists(file))
    }

    @Test
    fun `storage traversal never reaches an object outside the configured root`() {
        assertThrows(IllegalArgumentException::class.java) {
            UserMediaAssetService.validateStorageKey("../outside.png")
        }
        assertThrows(IllegalArgumentException::class.java) {
            worker.deleteLocal(root.toString(), "../outside.png")
        }
    }

    @Test
    fun `private OSS asset is deleted from the private bucket and marked deleted`() {
        val oss = mockk<OSS>(relaxed = true)
        val fixture = pendingPrivateOssAsset(oss)

        fixture.worker.processUser("user-1")

        verify(exactly = 1) {
            oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY)
        }
        assertEquals("DELETED", fixture.deleteStatus())
    }

    @Test
    fun `failed private OSS deletion remains retryable and a later success marks it deleted`() {
        val oss = mockk<OSS>(relaxed = true)
        every { oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY) } throws
            IllegalStateException("temporary OSS failure")
        val fixture = pendingPrivateOssAsset(oss)

        fixture.worker.processUser("user-1")

        assertEquals("FAILED", fixture.deleteStatus())
        assertEquals(1, fixture.deleteAttempts())
        fixture.jdbc.update("UPDATE user_media_assets SET retry_after = NULL WHERE id = 'asset-1'")
        every { oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY) } returns mockk(relaxed = true)

        fixture.worker.processUser("user-1")

        verify(exactly = 2) { oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY) }
        assertEquals("DELETED", fixture.deleteStatus())
        assertEquals(2, fixture.deleteAttempts())
    }

    @Test
    fun `invalid provider marks only that row failed and does not block the rest of the batch`() {
        val oss = mockk<OSS>(relaxed = true)
        val fixture = pendingPrivateOssAsset(oss)
        fixture.jdbc.update(
            """
            INSERT INTO user_media_assets
                (id, owner_user_id, storage_key, storage_provider, delete_status)
            VALUES ('asset-bad', 'user-1', 'private/v1/ff/bad.png', 'UNRECOGNIZED', 'PENDING')
            """.trimIndent(),
        )

        fixture.worker.processUser("user-1")

        verify(exactly = 1) { oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY) }
        assertEquals("DELETED", fixture.deleteStatus())
        assertEquals("FAILED", fixture.deleteStatus("asset-bad"))
    }

    @Test
    fun `ordinary processing respects upload grace while application startup recovers it immediately`() {
        val oss = mockk<OSS>(relaxed = true)
        val fixture = pendingPrivateOssAsset(oss)
        fixture.jdbc.update(
            "UPDATE user_media_assets SET delete_status = 'UPLOAD_PENDING', retry_after = ? WHERE id = 'asset-1'",
            LocalDateTime.now().plusMinutes(10),
        )

        fixture.worker.processUser("user-1")

        verify(exactly = 0) { oss.deleteObject(any<String>(), any<String>()) }
        assertEquals("UPLOAD_PENDING", fixture.deleteStatus())

        fixture.worker.recoverInterruptedUploads()

        verify(exactly = 1) { oss.deleteObject(PRIVATE_BUCKET, PRIVATE_STORAGE_KEY) }
        assertEquals("DELETED", fixture.deleteStatus())
    }

    @Test
    fun `application startup leaves interrupted uploads untouched when scheduling is disabled`() {
        val oss = mockk<OSS>(relaxed = true)
        val fixture = pendingPrivateOssAsset(oss, schedulingEnabled = false)
        fixture.jdbc.update(
            "UPDATE user_media_assets SET delete_status = 'UPLOAD_PENDING' WHERE id = 'asset-1'",
        )

        fixture.worker.recoverInterruptedUploads()

        verify(exactly = 0) { oss.deleteObject(any<String>(), any<String>()) }
        assertEquals("UPLOAD_PENDING", fixture.deleteStatus())
        assertEquals(0, fixture.deleteAttempts())
    }

    private fun pendingPrivateOssAsset(
        oss: OSS,
        schedulingEnabled: Boolean = true,
    ): PrivateOssDeletionFixture {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:private_media_delete_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE user_media_assets (
                id VARCHAR(36) PRIMARY KEY,
                owner_user_id VARCHAR(36) NOT NULL,
                storage_key VARCHAR(512) NOT NULL UNIQUE,
                storage_provider VARCHAR(32) NOT NULL,
                delete_status VARCHAR(32) NOT NULL,
                delete_attempt_count INT NOT NULL DEFAULT 0,
                retry_after TIMESTAMP NULL,
                last_delete_attempt_at TIMESTAMP NULL,
                last_delete_error VARCHAR(512),
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO user_media_assets
                (id, owner_user_id, storage_key, storage_provider, delete_status)
            VALUES ('asset-1', 'user-1', '$PRIVATE_STORAGE_KEY', 'OSS_PRIVATE', 'PENDING')
            """.trimIndent(),
        )
        val ossProvider = mockk<ObjectProvider<OSS>> {
            every { ifAvailable } returns oss
        }
        val privateWorker = UserMediaDeletionWorker(
            jdbcTemplate = jdbc,
            publicUploadDirectory = root.toString(),
            privateUploadDirectory = root.toString(),
            ossBucketName = "joysong-public-test",
            privateOssBucketName = " $PRIVATE_BUCKET ",
            ossClientProvider = ossProvider,
            metrics = mockk(relaxed = true),
            schedulingEnabled = schedulingEnabled,
        )
        return PrivateOssDeletionFixture(jdbc, privateWorker)
    }

    private data class PrivateOssDeletionFixture(
        val jdbc: JdbcTemplate,
        val worker: UserMediaDeletionWorker,
    ) {
        fun deleteStatus(id: String = "asset-1"): String = requireNotNull(jdbc.queryForObject(
            "SELECT delete_status FROM user_media_assets WHERE id = ?",
            String::class.java,
            id,
        ))

        fun deleteAttempts(): Int = requireNotNull(jdbc.queryForObject(
            "SELECT delete_attempt_count FROM user_media_assets WHERE id = 'asset-1'",
            Int::class.java,
        ))
    }

    companion object {
        private const val PRIVATE_BUCKET = "joysong-private-test"
        private const val PRIVATE_STORAGE_KEY = "private/v1/ab/object.png"
    }
}
