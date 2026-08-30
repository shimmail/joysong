package com.joysong.server.refund.service

import com.joysong.server.common.privatefile.PrivateFileStorageService
import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

class RefundEvidenceFileServiceTest {
    @TempDir
    lateinit var privateDirectory: Path

    private lateinit var jdbc: JdbcTemplate
    private lateinit var storage: PrivateFileStorageService
    private lateinit var service: RefundEvidenceFileService

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:refund_evidence_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        jdbc = JdbcTemplate(dataSource)
        createSchema()
        val lifecycleGuard = mockk<AccountLifecycleGuard> {
            every { requireActiveForWrite(any()) } returns mockk(relaxed = true)
        }
        storage = PrivateFileStorageService(
            jdbcTemplate = jdbc,
            userMediaAssetService = UserMediaAssetService(jdbc, lifecycleGuard),
            privateUploadDirectory = privateDirectory.toString(),
        )
        service = RefundEvidenceFileService(jdbc, storage)
    }

    @Test
    fun `storeForRefund with no files returns empty and writes no rows`() {
        assertEquals(emptyList<Any>(), service.storeForRefund("refund-1", "user-1", emptyList()))
        assertEquals(0, count("private_files"))
        assertEquals(0, count("refund_evidence_files"))
    }

    @Test
    fun `storeForRefund stores five files in submission order with positions zero through four`() {
        val files = (0..4).map { index -> validPng("evidence-$index.png") }

        val responses = service.storeForRefund("refund-1", "user-1", files)

        assertEquals(listOf(0, 1, 2, 3, 4), responses.map { it.position })
        assertEquals(files.map { it.originalFilename }, responses.map { it.originalName })
        assertEquals(listOf(0, 1, 2, 3, 4), jdbc.queryForList(
            "SELECT position FROM refund_evidence_files WHERE refund_id = ? ORDER BY position",
            Int::class.java,
            "refund-1",
        ))
        assertEquals(5, count("private_files"))
        assertEquals(5, count("refund_evidence_files"))
    }

    @Test
    fun `storeForRefund rejects six files before private storage is called`() {
        val privateStorage = mockk<PrivateFileStorageService>()
        val limitedService = RefundEvidenceFileService(jdbc, privateStorage)
        val files = (0..5).map { validPng("evidence-$it.png") }

        assertThrows(IllegalArgumentException::class.java) {
            limitedService.storeForRefund("refund-1", "user-1", files)
        }

        verify(exactly = 0) {
            privateStorage.store(any(), any(), any(), any(), any())
        }
        assertEquals(0, count("refund_evidence_files"))
    }

    @Test
    fun `listForRefund returns only active nondeleted refund evidence ordered by position`() {
        seedPrivateFile("active-2", "REFUND_EVIDENCE", "ACTIVE", null, "two.png")
        seedPrivateFile("active-0", "REFUND_EVIDENCE", "ACTIVE", null, "zero.png")
        seedPrivateFile("deleted-at", "REFUND_EVIDENCE", "ACTIVE", "CURRENT_TIMESTAMP", "deleted-at.png")
        seedPrivateFile("deleted-status", "REFUND_EVIDENCE", "DELETED", null, "deleted-status.png")
        seedPrivateFile("wrong-purpose", "ID_CARD_FRONT", "ACTIVE", null, "identity.png")
        seedPrivateFile("other-refund", "REFUND_EVIDENCE", "ACTIVE", null, "other.png")
        associate("refund-1", "active-2", 2)
        associate("refund-1", "active-0", 0)
        associate("refund-1", "deleted-at", 1)
        associate("refund-1", "deleted-status", 3)
        associate("refund-1", "wrong-purpose", 4)
        associate("refund-2", "other-refund", 0)

        val responses = service.listForRefund("refund-1")

        assertEquals(listOf("active-0", "active-2"), responses.map { it.fileId })
        assertEquals(listOf(0, 2), responses.map { it.position })
    }

    @Test
    fun `loadContentForAdmin requires refund and file to match the same association`() {
        seedPrivateFile("file-a", "REFUND_EVIDENCE", "ACTIVE", null, "a.png", "refund/file-a.png")
        seedPrivateFile("file-b", "REFUND_EVIDENCE", "ACTIVE", null, "b.png", "refund/file-b.png")
        associate("refund-a", "file-a", 0)
        associate("refund-b", "file-b", 0)
        Files.createDirectories(privateDirectory.resolve("refund"))
        Files.write(privateDirectory.resolve("refund/file-a.png"), PNG_BYTES)
        Files.write(privateDirectory.resolve("refund/file-b.png"), PNG_BYTES)

        val content = service.loadContentForAdmin("refund-a", "file-a")
        assertEquals(privateDirectory.resolve("refund/file-a.png").toAbsolutePath().normalize(), content.path)

        val mismatched = assertThrows(IllegalArgumentException::class.java) {
            service.loadContentForAdmin("refund-a", "file-b")
        }
        Files.delete(privateDirectory.resolve("refund/file-a.png"))
        val missingPhysical = assertThrows(IllegalArgumentException::class.java) {
            service.loadContentForAdmin("refund-a", "file-a")
        }
        assertEquals("REFUND_EVIDENCE_NOT_FOUND", mismatched.message)
        assertEquals(mismatched.message, missingPhysical.message)
    }

    private fun createSchema() {
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
        jdbc.execute(
            """
            CREATE TABLE refund_evidence_files (
                file_id VARCHAR(36) PRIMARY KEY,
                refund_id VARCHAR(36) NOT NULL,
                position SMALLINT NOT NULL,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                UNIQUE (refund_id, position)
            )
            """.trimIndent(),
        )
    }

    private fun seedPrivateFile(
        id: String,
        purpose: String,
        status: String,
        deletedAtExpression: String?,
        originalName: String,
        storageKey: String = "seed/$id.png",
    ) {
        val deletedAt = if (deletedAtExpression == null) "NULL" else deletedAtExpression
        jdbc.update(
            """
            INSERT INTO private_files
                (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256, status, deleted_at)
            VALUES (?, 'user-1', ?, ?, ?, 'image/png', 12, ?, ?, $deletedAt)
            """.trimIndent(),
            id,
            purpose,
            storageKey,
            originalName,
            "0".repeat(64),
            status,
        )
    }

    private fun associate(refundId: String, fileId: String, position: Int) {
        jdbc.update(
            "INSERT INTO refund_evidence_files (file_id, refund_id, position) VALUES (?, ?, ?)",
            fileId,
            refundId,
            position,
        )
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    private fun validPng(name: String): MultipartFile = MockMultipartFile("files", name, "image/png", PNG_BYTES)

    companion object {
        private val PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        )
    }
}
