package com.joysong.server.user.deletion

import com.joysong.server.support.WorktreeTestDatabase
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDateTime

@Tag("mysql-integration")
@Testcontainers
@JdbcTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.sql.init.mode=never",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class AccountDeletionDataEraserMySqlIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `account erasure retains refund evidence file and active media asset`() {
        jdbcTemplate.update(
            "INSERT INTO users (id, phone, password_hash, nickname, role, account_state) VALUES (?, ?, 'hash', 'Refund user', 'USER', 'ACTIVE')",
            REFUND_USER_ID,
            "+8613800138111",
        )
        jdbcTemplate.update(
            "INSERT INTO orders (id, user_id, project_name, price, status) VALUES (?, ?, 'Travel service', 400.00, 'REFUND_REVIEW')",
            REFUND_ORDER_ID,
            REFUND_USER_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO refunds (id, order_id, user_id, amount, requested_amount_minor, reason)
            VALUES (?, ?, ?, 400.00, 40000, 'Travel cancelled')
            """.trimIndent(),
            REFUND_ID,
            REFUND_ORDER_ID,
            REFUND_USER_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO private_files
                (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256)
            VALUES (?, ?, 'REFUND_EVIDENCE', ?, 'receipt.pdf', 'application/pdf', 128, ?)
            """.trimIndent(),
            REFUND_FILE_ID,
            REFUND_USER_ID,
            REFUND_STORAGE_KEY,
            "b".repeat(64),
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_media_assets
                (id, owner_user_id, storage_key, asset_type, storage_provider, delete_status)
            VALUES (?, ?, ?, 'REFUND_EVIDENCE', 'LOCAL_PRIVATE', 'ACTIVE')
            """.trimIndent(),
            REFUND_ASSET_ID,
            REFUND_USER_ID,
            REFUND_STORAGE_KEY,
        )
        jdbcTemplate.update(
            "INSERT INTO refund_evidence_files (file_id, refund_id, position) VALUES (?, ?, 0)",
            REFUND_FILE_ID,
            REFUND_ID,
        )
        val properties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        val eraser = JdbcAccountDeletionDataEraser(
            jdbcTemplate,
            AccountDeletionCrypto(properties),
            UserMediaAssetService(jdbcTemplate, mockk<AccountLifecycleGuard>(relaxed = true)),
        )

        eraser.erase(
            UserEntity(id = REFUND_USER_ID, phone = "+8613800138111", passwordHash = "hash"),
            LocalDateTime.parse("2026-08-30T10:00:00"),
        )

        assertEquals(1L, countForRefund("SELECT COUNT(*) FROM private_files WHERE id = ?", REFUND_FILE_ID))
        assertEquals(
            1L,
            countForRefund(
                "SELECT COUNT(*) FROM refund_evidence_files WHERE refund_id = ? AND file_id = ?",
                REFUND_ID,
                REFUND_FILE_ID,
            ),
        )
        assertEquals(
            1L,
            countForRefund(
                "SELECT COUNT(*) FROM user_media_assets WHERE id = ? AND delete_status = 'ACTIVE'",
                REFUND_ASSET_ID,
            ),
        )
    }

    @Test
    fun `erasure clears revoked professional identity without private file foreign key failures`() {
        seedRevokedProfessionalIdentity()
        val properties = AccountDeletionProperties().apply {
            hmacSecret = "0123456789abcdef-test"
        }
        val mediaAssets = UserMediaAssetService(
            jdbcTemplate,
            mockk<AccountLifecycleGuard>(relaxed = true),
        )
        val eraser = JdbcAccountDeletionDataEraser(
            jdbcTemplate,
            AccountDeletionCrypto(properties),
            mediaAssets,
        )

        eraser.erase(
            UserEntity(
                id = USER_ID,
                phone = "+8613800138000",
                passwordHash = "hash",
                nickname = "Sensitive user",
            ),
            LocalDateTime.parse("2026-08-29T10:00:00"),
        )

        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM users
                WHERE id = ? AND account_state = 'ERASED' AND phone IS NULL AND nickname = ''
                """.trimIndent(),
            ),
        )
        assertEquals(1L, count("SELECT COUNT(*) FROM private_files WHERE owner_user_id = ?"))
        assertEquals(0L, count("SELECT COUNT(*) FROM identity_application_documents iad JOIN identity_applications ia ON ia.id = iad.application_id WHERE ia.user_id = ?"))
        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM identity_applications
                WHERE user_id = ? AND JSON_LENGTH(application_data) = 0 AND review_note = ''
                """.trimIndent(),
            ),
        )
        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM doctors
                WHERE id = ? AND name = '' AND credentials = '' AND contact_phone = ''
                  AND is_verified = 0 AND deleted_at IS NOT NULL
                """.trimIndent(),
            ),
        )
        assertEquals(0L, count("SELECT COUNT(*) FROM doctor_institutions WHERE doctor_id = ?"))
        assertEquals(0L, count("SELECT COUNT(*) FROM platform_cooperation_agreements WHERE user_id = ?"))
        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM platform_cooperation_agreements pca
                JOIN private_files pf ON pf.id = pca.agreement_file_id
                WHERE pf.owner_user_id = ? AND pca.institution_id IS NOT NULL
                """.trimIndent(),
            ),
        )
        assertEquals(
            1L,
            count(
                """
                SELECT COUNT(*) FROM user_media_assets
                WHERE owner_user_id = ? AND storage_key = 'private/deletion-institution-agreement-file.pdf'
                  AND delete_status = 'ACTIVE'
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `pending direct cooperation agreement blocks account deletion and remains stored`() {
        seedRevokedProfessionalIdentity()
        insertPrivateFile(PENDING_AGREEMENT_FILE_ID)
        jdbcTemplate.update(
            """
            INSERT INTO platform_cooperation_agreements
                (id, user_id, cooperation_role, status, agreement_file_id, business_entity_name, business_license_no)
            VALUES (?, ?, 'DOCTOR', 'PENDING', ?, 'Pending entity', 'Pending license')
            """.trimIndent(),
            PENDING_AGREEMENT_ID,
            USER_ID,
            PENDING_AGREEMENT_FILE_ID,
        )

        val blockers = LocalAccountDeletionBlockerService(jdbcTemplate).evaluate(
            UserEntity(id = USER_ID, passwordHash = "hash"),
        )

        assertEquals(listOf("PLATFORM_COOPERATION_AGREEMENT"), blockers.map { it.type })
        assertEquals(
            1L,
            count(
                "SELECT COUNT(*) FROM platform_cooperation_agreements WHERE user_id = ? AND status = 'PENDING'",
            ),
        )
    }

    private fun seedRevokedProfessionalIdentity() {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, phone, password_hash, nickname, role, account_state)
            VALUES (?, ?, 'hash', 'Sensitive user', 'USER', 'ACTIVE')
            """.trimIndent(),
            USER_ID,
            "+8613800138000",
        )
        listOf(IDENTITY_FILE_ID, PRACTICE_FILE_ID, AGREEMENT_FILE_ID, INSTITUTION_AGREEMENT_FILE_ID)
            .forEach(::insertPrivateFile)
        jdbcTemplate.update(
            """
            INSERT INTO user_media_assets
                (id, owner_user_id, storage_key, asset_type, storage_provider, delete_status)
            VALUES (?, ?, ?, 'IDENTITY', 'LOCAL_PRIVATE', 'ACTIVE')
            """.trimIndent(),
            INSTITUTION_AGREEMENT_ASSET_ID,
            USER_ID,
            "private/$INSTITUTION_AGREEMENT_FILE_ID.pdf",
        )
        jdbcTemplate.update(
            """
            INSERT INTO identity_applications
                (id, user_id, role_code, status, application_data, review_note)
            VALUES (?, ?, 'DOCTOR', 'APPROVED', JSON_OBJECT('realName', 'Sensitive name'), 'Sensitive note')
            """.trimIndent(),
            APPLICATION_ID,
            USER_ID,
        )
        jdbcTemplate.update(
            "INSERT INTO identity_application_documents (application_id, file_id, document_type) VALUES (?, ?, 'LICENSE')",
            APPLICATION_ID,
            IDENTITY_FILE_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_roles (user_id, role_code, status, source_application_id)
            VALUES (?, 'DOCTOR', 'REVOKED', ?)
            """.trimIndent(),
            USER_ID,
            APPLICATION_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctors (id, name, title, bio, credentials, is_verified, contact_phone)
            VALUES (?, 'Sensitive doctor', 'Chief', 'Sensitive bio', 'Sensitive credential', 0, '13800138000')
            """.trimIndent(),
            USER_ID,
        )
        jdbcTemplate.update("INSERT INTO institutions (id, name) VALUES (?, 'Test institution')", INSTITUTION_ID)
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, status, registration_no, registration_file_id)
            VALUES (?, ?, ?, 'REVOKED', 'Sensitive registration', ?)
            """.trimIndent(),
            RELATION_ID,
            USER_ID,
            INSTITUTION_ID,
            PRACTICE_FILE_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_cooperation_agreements
                (id, user_id, cooperation_role, status, agreement_file_id, business_entity_name, business_license_no)
            VALUES (?, ?, 'DOCTOR', 'TERMINATED', ?, 'Sensitive entity', 'Sensitive license')
            """.trimIndent(),
            AGREEMENT_ID,
            USER_ID,
            AGREEMENT_FILE_ID,
        )
        jdbcTemplate.update(
            """
            INSERT INTO platform_cooperation_agreements
                (id, institution_id, cooperation_role, status, agreement_file_id, business_entity_name, business_license_no)
            VALUES (?, ?, 'INSTITUTION', 'ACTIVE', ?, 'Institution entity', 'Institution license')
            """.trimIndent(),
            INSTITUTION_AGREEMENT_ID,
            INSTITUTION_ID,
            INSTITUTION_AGREEMENT_FILE_ID,
        )
    }

    private fun insertPrivateFile(fileId: String) {
        jdbcTemplate.update(
            """
            INSERT INTO private_files
                (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256)
            VALUES (?, ?, 'IDENTITY', ?, 'sensitive.pdf', 'application/pdf', 128, ?)
            """.trimIndent(),
            fileId,
            USER_ID,
            "private/$fileId.pdf",
            "a".repeat(64),
        )
    }

    private fun count(sql: String): Long =
        requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, USER_ID))

    private fun countForRefund(sql: String, vararg args: Any): Long =
        requireNotNull(jdbcTemplate.queryForObject(sql, Long::class.java, *args))

    companion object {
        private const val USER_ID = "deletion-professional-user"
        private const val APPLICATION_ID = "deletion-identity-application"
        private const val IDENTITY_FILE_ID = "deletion-identity-file"
        private const val PRACTICE_FILE_ID = "deletion-practice-file"
        private const val AGREEMENT_FILE_ID = "deletion-agreement-file"
        private const val INSTITUTION_AGREEMENT_FILE_ID = "deletion-institution-agreement-file"
        private const val PENDING_AGREEMENT_FILE_ID = "deletion-pending-agreement-file"
        private const val INSTITUTION_ID = "deletion-institution"
        private const val RELATION_ID = "deletion-doctor-relation"
        private const val AGREEMENT_ID = "deletion-platform-agreement"
        private const val INSTITUTION_AGREEMENT_ID = "deletion-institution-agreement"
        private const val PENDING_AGREEMENT_ID = "deletion-pending-agreement"
        private const val INSTITUTION_AGREEMENT_ASSET_ID = "deletion-institution-agreement-asset"
        private const val REFUND_USER_ID = "deletion-refund-user"
        private const val REFUND_ORDER_ID = "deletion-refund-order"
        private const val REFUND_ID = "deletion-refund"
        private const val REFUND_FILE_ID = "deletion-refund-file"
        private const val REFUND_ASSET_ID = "deletion-refund-asset"
        private const val REFUND_STORAGE_KEY = "deletion-refund-user/REFUND_EVIDENCE/deletion-refund-file.pdf"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = AccountDeletionEraserMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class AccountDeletionEraserMySqlContainer(imageName: String) :
    MySQLContainer<AccountDeletionEraserMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
