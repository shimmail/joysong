package com.joysong.server.identity.service

import com.joysong.server.common.privatefile.PrivateFileStorageService
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

class PrivateIdentityFileServiceTransactionTest {
    @TempDir
    lateinit var privateDirectory: Path

    private lateinit var jdbc: JdbcTemplate
    private lateinit var transactionTemplate: TransactionTemplate
    private lateinit var service: PrivateIdentityFileService

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:identity_files_${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
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
        jdbc.execute("CREATE TABLE identity_application_documents (file_id VARCHAR(36) PRIMARY KEY)")
        jdbc.execute(
            "CREATE TABLE refund_evidence_files (file_id VARCHAR(36) PRIMARY KEY, refund_id VARCHAR(36) NOT NULL, position INT NOT NULL)"
        )
        val lifecycleGuard = mockk<AccountLifecycleGuard> {
            every { requireActiveForWrite(any()) } returns mockk(relaxed = true)
        }
        val storage = PrivateFileStorageService(
            jdbcTemplate = jdbc,
            userMediaAssetService = UserMediaAssetService(jdbc, lifecycleGuard),
            privateUploadDirectory = privateDirectory.toString(),
        )
        service = PrivateIdentityFileService(
            jdbcTemplate = jdbc,
            lifecycleGuard = lifecycleGuard,
            privateFileStorageService = storage,
        )
        transactionTemplate = TransactionTemplate(DataSourceTransactionManager(dataSource))
    }

    @Test
    fun `identity upload keeps its existing response while delegating storage`() {
        val response = service.upload("user-1", "id_card_front", validPng())

        assertEquals("ID_CARD_FRONT", response.purpose)
        assertEquals("identity.png", response.originalName)
        assertEquals("image/png", response.contentType)
        assertEquals(12L, response.sizeBytes)
        assertTrue(response.fileId.isNotBlank())
        assertEquals(1, count("private_files"))
        assertEquals(1, count("user_media_assets"))
        assertTrue(singleStoredFile().fileName.toString().endsWith(".png"))
    }

    @Test
    fun `identity upload accepts a valid signed file name without an extension`() {
        val response = service.upload("user-1", "ID_CARD_FRONT", validPng("identity-document"))

        assertEquals("identity-document", response.originalName)
        assertEquals("image/png", response.contentType)
        assertEquals(1, count("private_files"))
        assertTrue(singleStoredFile().fileName.toString().endsWith(".png"))
    }

    @Test
    fun `transaction rollback removes delegated identity file and metadata`() {
        lateinit var storedPath: Path

        transactionTemplate.executeWithoutResult { status ->
            service.upload("user-1", "ID_CARD_FRONT", validPng())
            storedPath = singleStoredFile()
            status.setRollbackOnly()
        }

        assertFalse(Files.exists(storedPath))
        assertEquals(0, count("private_files"))
        assertEquals(0, count("user_media_assets"))
    }

    @Test
    fun `submitted identity file remains available to administrator through delegated read`() {
        val uploaded = service.upload("user-1", "ID_CARD_FRONT", validPng())
        jdbc.update("INSERT INTO identity_application_documents (file_id) VALUES (?)", uploaded.fileId)

        val download = service.loadSubmittedFileForAdmin(uploaded.fileId)

        assertTrue(Files.isRegularFile(download.path))
        assertEquals("identity.png", download.originalName)
        assertEquals("image/png", download.contentType)
    }

    @Test
    fun `deleteDraft marks metadata deleted when the physical file is already missing`() {
        val uploaded = service.upload("user-1", "ID_CARD_FRONT", validPng())
        Files.delete(singleStoredFile())

        service.deleteDraft("user-1", uploaded.fileId)

        assertEquals(
            "DELETED",
            jdbc.queryForObject(
                "SELECT status FROM private_files WHERE id = ?",
                String::class.java,
                uploaded.fileId,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM private_files WHERE id = ? AND deleted_at IS NOT NULL",
                Int::class.java,
                uploaded.fileId,
            ),
        )
    }

    @Test
    fun `identity draft deletion cannot remove bound refund evidence`() {
        val uploaded = service.upload("user-1", "ID_CARD_FRONT", validPng("refund.png"))
        jdbc.update("UPDATE private_files SET purpose = 'REFUND_EVIDENCE' WHERE id = ?", uploaded.fileId)
        jdbc.update(
            "INSERT INTO refund_evidence_files (file_id, refund_id, position) VALUES (?, 'refund-1', 0)",
            uploaded.fileId,
        )
        val storedPath = singleStoredFile()

        assertThrows(IllegalArgumentException::class.java) {
            service.deleteDraft("user-1", uploaded.fileId)
        }

        assertTrue(Files.isRegularFile(storedPath))
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject(
                "SELECT status FROM private_files WHERE id = ?",
                String::class.java,
                uploaded.fileId,
            ),
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM refund_evidence_files WHERE file_id = ?",
                Int::class.java,
                uploaded.fileId,
            ),
        )
    }

    private fun count(table: String): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table", Int::class.java)!!

    private fun singleStoredFile(): Path = Files.walk(privateDirectory).use { paths ->
        paths.filter { Files.isRegularFile(it) }.toList().single()
    }

    private fun validPng(originalName: String = "identity.png") = MockMultipartFile(
        "file",
        originalName,
        "image/png",
        byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x00,
        ),
    )
}
