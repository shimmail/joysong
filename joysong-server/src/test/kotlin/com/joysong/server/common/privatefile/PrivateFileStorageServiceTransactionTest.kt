package com.joysong.server.common.privatefile

import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class PrivateFileStorageServiceTransactionTest {
    @TempDir
    lateinit var privateDirectory: Path

    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var service: PrivateFileStorageService

    private val policy = PrivateFilePolicy(
        allowedContentTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "application/pdf" to "pdf",
        ),
    )

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:private_files_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        jdbc = JdbcTemplate(dataSource)
        jdbc.execute(
            """
            CREATE TABLE private_files (
                id VARCHAR(36) PRIMARY KEY,
                owner_user_id VARCHAR(36) NOT NULL,
                purpose VARCHAR(40) NOT NULL,
                storage_key VARCHAR(500) NOT NULL UNIQUE,
                original_name VARCHAR(255),
                content_type VARCHAR(100) NOT NULL,
                size_bytes BIGINT NOT NULL,
                sha256 VARCHAR(64) NOT NULL,
                status VARCHAR(20) NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                deleted_at TIMESTAMP NULL
            )
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE user_media_assets (
                id VARCHAR(36) PRIMARY KEY,
                owner_user_id VARCHAR(36) NOT NULL,
                storage_key VARCHAR(512) NOT NULL UNIQUE,
                asset_type VARCHAR(32) NOT NULL,
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
        val lifecycleGuard = mockk<AccountLifecycleGuard> {
            every { requireActiveForWrite(any()) } returns mockk(relaxed = true)
        }
        service = PrivateFileStorageService(
            jdbcTemplate = jdbc,
            userMediaAssetService = UserMediaAssetService(jdbc, lifecycleGuard),
            privateUploadDirectory = privateDirectory.toString(),
        )
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `store accepts valid JPEG PNG WebP and PDF signatures at exactly 10 MiB`() {
        val formats = listOf(
            Triple("photo.jpeg", "image/jpeg", "jpg"),
            Triple("scan.png", "image/png", "png"),
            Triple("clip.webp", "image/webp", "webp"),
            Triple("receipt.pdf", "application/pdf", "pdf"),
        )

        val stored = formats.map { (name, contentType, canonicalExtension) ->
            service.store(
                ownerUserId = "user-1",
                purpose = "REFUND_EVIDENCE",
                file = multipart(name, contentType, validBytes(contentType, TEN_MIB.toInt())),
                policy = policy,
                assetType = "REFUND_EVIDENCE",
            ).also {
                assertEquals(TEN_MIB, it.sizeBytes)
                assertEquals(contentType, it.contentType)
                assertTrue(it.storageKey.endsWith(".$canonicalExtension"))
                assertEquals(TEN_MIB, Files.size(privateDirectory.resolve(it.storageKey)))
            }
        }

        assertEquals(4, stored.size)
        assertEquals(4, count("private_files"))
        assertEquals(4, count("user_media_assets"))
    }

    @Test
    fun `store rejects invalid size MIME extension and signature before persistence`() {
        val invalidFiles = listOf(
            multipart("empty.jpg", "image/jpeg", byteArrayOf()),
            multipart("large.png", "image/png", validBytes("image/png", TEN_MIB.toInt() + 1)),
            multipart("note.txt", "text/plain", "hello".toByteArray()),
            multipart("wrong.png", "image/jpeg", validBytes("image/jpeg", 12)),
            multipart("forged.jpg", "image/jpeg", validBytes("image/png", 12)),
        )

        invalidFiles.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                service.store("user-1", "REFUND_EVIDENCE", invalid, policy, "REFUND_EVIDENCE")
            }
        }

        assertEquals(0, count("private_files"))
        assertEquals(0, count("user_media_assets"))
        assertTrue(storedFiles().isEmpty())
    }

    @Test
    fun `transaction rollback removes every physical file and metadata row written in the transaction`() {
        lateinit var first: StoredPrivateFile
        lateinit var second: StoredPrivateFile

        transactionTemplate.executeWithoutResult { status ->
            first = service.store(
                "user-1",
                "REFUND_EVIDENCE",
                multipart("first.jpg", "image/jpeg", validBytes("image/jpeg", 12)),
                policy,
                "REFUND_EVIDENCE",
            )
            second = service.store(
                "user-1",
                "REFUND_EVIDENCE",
                multipart("second.pdf", "application/pdf", validBytes("application/pdf", 12)),
                policy,
                "REFUND_EVIDENCE",
            )
            assertTrue(Files.isRegularFile(privateDirectory.resolve(first.storageKey)))
            assertTrue(Files.isRegularFile(privateDirectory.resolve(second.storageKey)))
            status.setRollbackOnly()
        }

        assertFalse(Files.exists(privateDirectory.resolve(first.storageKey)))
        assertFalse(Files.exists(privateDirectory.resolve(second.storageKey)))
        assertEquals(0, count("private_files"))
        assertEquals(0, count("user_media_assets"))
    }

    @Test
    fun `stored original name removes header path quote and control characters and is at most 255 characters`() {
        val unsafeName = "bad\r\n/path\\\"${1.toChar()}${"x".repeat(300)}.png"

        val stored = service.store(
            "user-1",
            "REFUND_EVIDENCE",
            multipart(unsafeName, "image/png", validBytes("image/png", 12)),
            policy,
            "REFUND_EVIDENCE",
        )
        val persistedName = jdbc.queryForObject(
            "SELECT original_name FROM private_files WHERE id = ?",
            String::class.java,
            stored.fileId,
        )!!

        assertEquals(persistedName, stored.originalName)
        assertTrue(persistedName.length <= 255)
        assertFalse(persistedName.any { it == '\r' || it == '\n' || it == '/' || it == '\\' || it == '"' || it.isISOControl() })
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    private fun storedFiles(): List<Path> = Files.walk(privateDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList()
    }

    private fun multipart(name: String, contentType: String, bytes: ByteArray) =
        MockMultipartFile("file", name, contentType, bytes)

    private fun validBytes(contentType: String, size: Int): ByteArray = ByteArray(size).also { bytes ->
        when (contentType) {
            "image/jpeg" -> byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()).copyInto(bytes)
            "image/png" -> byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            ).copyInto(bytes)
            "image/webp" -> {
                "RIFF".toByteArray(Charsets.US_ASCII).copyInto(bytes, 0)
                "WEBP".toByteArray(Charsets.US_ASCII).copyInto(bytes, 8)
            }
            "application/pdf" -> "%PDF-".toByteArray(Charsets.US_ASCII).copyInto(bytes)
        }
    }

    companion object {
        private const val TEN_MIB = 10L * 1024 * 1024
    }
}
