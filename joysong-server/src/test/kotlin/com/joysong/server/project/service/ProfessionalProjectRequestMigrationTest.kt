package com.joysong.server.project.service

import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@Tag("mysql-integration")
@Testcontainers
class ProfessionalProjectRequestMigrationTest {
    @Test
    fun `V28 preserves legacy platform requests and enforces the request ledger contract`() {
        assertEquals("myapp_worktree_doctor_project_application_full_vo", WorktreeTestDatabase.databaseName())
        migrateTo("27")
        val jdbc = jdbc()
        seedLegacyRequests(jdbc)

        migrateTo("28")

        val legacy = jdbc.queryForObject(
            """
            SELECT id, tags, images, category_tags, currency, reference_price, slogan, cover_image, sales_count
            FROM professional_project_requests
            WHERE id = 'legacy-platform-request'
            """.trimIndent()
        ) { rs, _ ->
            LegacyPlatformSnapshot(
                id = rs.getString("id"),
                tags = rs.getString("tags"),
                images = rs.getString("images"),
                categoryTags = rs.getString("category_tags"),
                currency = rs.getString("currency"),
                referencePrice = rs.getBigDecimal("reference_price"),
                slogan = rs.getString("slogan"),
                coverImage = rs.getString("cover_image"),
                salesCount = rs.getInt("sales_count")
            )
        }!!
        assertEquals(
            LegacyPlatformSnapshot(
                id = "legacy-platform-request",
                tags = "[]",
                images = "[]",
                categoryTags = "[]",
                currency = "USD",
                referencePrice = BigDecimal("0.00"),
                slogan = "",
                coverImage = "",
                salesCount = 0
            ),
            legacy
        )
        assertEquals(
            LegacyChangesRequestedSnapshot(
                status = "CHANGES_REQUESTED",
                reviewNote = "Legacy changes requested note",
                reviewedBy = "migration-reviewer"
            ),
            jdbc.queryForObject(
                """
                SELECT status, review_note, reviewed_by
                FROM professional_project_requests
                WHERE id = 'legacy-changes-requested'
                """.trimIndent()
            ) { rs, _ ->
                LegacyChangesRequestedSnapshot(
                    status = rs.getString("status"),
                    reviewNote = rs.getString("review_note"),
                    reviewedBy = rs.getString("reviewed_by")
                )
            }
        )

        val columns = jdbc.query(
            """
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'professional_project_requests'
            """.trimIndent()
        ) { rs, _ -> rs.getString("column_name") to rs.getString("data_type") }.toMap()
        assertFalse(columns.containsKey("service_content"))
        assertEquals("decimal", columns["price"])
        setOf(
            "tags", "slogan", "detail_content", "currency", "cover_image", "images", "sales_count",
            "reference_price", "category_tags", "original_price", "is_active", "consultation_fee",
            "commission_rate", "institution_rate"
        ).forEach { assertTrue(columns.containsKey(it), "Missing V28 column $it") }
        setOf("tags", "images", "category_tags").forEach { assertEquals("json", columns[it]) }

        assertDoesNotThrow {
            insertValidInstitutionRequest(jdbc)
        }
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM professional_project_requests WHERE id = 'valid-institution-request'",
                Int::class.java
            )
        )

        assertRejected("negative reference price", "chk_project_requests_amounts") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, name, category, description, reference_price)
                VALUES ('negative-reference-price', 'PLATFORM', 'migration-doctor', 'Name', 'Category', 'Description', -0.01)
                """.trimIndent()
            )
        }
        assertRejected("negative sales count", "chk_project_requests_sales_count") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, name, category, description, sales_count)
                VALUES ('negative-sales-count', 'PLATFORM', 'migration-doctor', 'Name', 'Category', 'Description', -1)
                """.trimIndent()
            )
        }
        assertRejected("negative institution price", "chk_project_requests_amounts") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                     consultation_fee, commission_rate, institution_rate, reference_price, category_tags, status, review_note)
                VALUES ('negative-institution-price', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', -0.01, 1, 0, 0, 0, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
        assertRejected("negative original price", "chk_project_requests_amounts") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, original_price, is_active,
                     consultation_fee, commission_rate, institution_rate, reference_price, category_tags, status, review_note)
                VALUES ('negative-original-price', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', 1, -0.01, 1, 0, 0, 0, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
        assertRejected("negative consultation fee", "chk_project_requests_amounts") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                     consultation_fee, commission_rate, institution_rate, reference_price, category_tags, status, review_note)
                VALUES ('negative-consultation-fee', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', 1, 1, -0.01, 0, 0, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
        assertRejected("out of range commission rate", "chk_project_requests_rates") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                     consultation_fee, commission_rate, institution_rate, reference_price, category_tags, status, review_note)
                VALUES ('out-of-range-rate', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', 1, 1, 0, 100.01, 0, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
        assertRejected("institution and consultant rates exceed 100", "chk_project_requests_rates") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                     consultation_fee, commission_rate, institution_rate, reference_price, category_tags, status, review_note)
                VALUES ('invalid-rate-sum', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', 1, 1, 0, 40, 70, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
        assertRejected("platform rows cannot contain institution pricing", "chk_project_requests_shape") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, name, category, description, price)
                VALUES ('invalid-platform-shape', 'PLATFORM', 'migration-doctor', 'Name', 'Category', 'Description', 1)
                """.trimIndent()
            )
        }
        assertRejected("institution rows require submitted split values", "chk_project_requests_shape") {
            jdbc.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                     consultation_fee, commission_rate, reference_price, category_tags, status, review_note)
                VALUES ('invalid-institution-shape', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                        'migration-project', 1, 1, 0, 0, NULL, NULL, 'REJECTED', 'constraint fixture')
                """.trimIndent()
            )
        }
    }

    private fun migrateTo(version: String) {
        WorktreeTestDatabase.validateAndPrint(mysql)
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .target(version)
            .load()
            .migrate()
    }

    private fun jdbc(): JdbcTemplate =
        JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))

    private fun seedLegacyRequests(jdbc: JdbcTemplate) {
        jdbc.update(
            """
            INSERT INTO users (id, password_hash, nickname) VALUES
                ('migration-doctor', 'hash', 'Doctor'),
                ('migration-reviewer', 'hash', 'Reviewer')
            """.trimIndent()
        )
        jdbc.update("INSERT INTO doctors (id, name) VALUES ('migration-doctor', 'Doctor')")
        jdbc.update("INSERT INTO institutions (id, name) VALUES ('migration-institution', 'Institution')")
        jdbc.update("INSERT INTO projects (id, name) VALUES ('migration-project', 'Project')")
        jdbc.update(
            """
            INSERT INTO professional_project_requests
                (id, request_type, doctor_id, name, category, description, service_content, price_suggestion, notes)
            VALUES ('legacy-platform-request', 'PLATFORM', 'migration-doctor', 'Legacy name', 'Legacy category',
                    'Legacy description', NULL, NULL, 'legacy note')
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO professional_project_requests
                (id, request_type, doctor_id, name, category, description, service_content, price_suggestion,
                 status, review_note, reviewed_by, reviewed_at, notes)
            VALUES ('legacy-changes-requested', 'PLATFORM', 'migration-doctor', 'Legacy changes', 'Legacy category',
                    'Legacy description', NULL, NULL, 'CHANGES_REQUESTED', 'Legacy changes requested note',
                    'migration-reviewer', '2026-08-15 01:02:03', 'legacy note')
            """.trimIndent()
        )
    }

    private fun insertValidInstitutionRequest(jdbc: JdbcTemplate): Int =
        jdbc.update(
            """
            INSERT INTO professional_project_requests
                (id, request_type, doctor_id, institution_id, project_id, price, is_active,
                 consultation_fee, commission_rate, institution_rate, reference_price, category_tags)
            VALUES ('valid-institution-request', 'INSTITUTION', 'migration-doctor', 'migration-institution',
                    'migration-project', 100, 1, 10, 20, 30, NULL, NULL)
            """.trimIndent()
        )

    private fun assertRejected(label: String, expectedConstraint: String, action: () -> Any?) {
        val failure = assertThrows(Exception::class.java) { action() }
        val exceptionMessages = generateSequence(failure as Throwable?) { it.cause }
            .mapNotNull(Throwable::message)
            .toList()
        assertTrue(
            exceptionMessages.any { it.contains(expectedConstraint, ignoreCase = true) },
            "$label must fail $expectedConstraint but messages were $exceptionMessages"
        )
    }

    private data class LegacyPlatformSnapshot(
        val id: String,
        val tags: String,
        val images: String,
        val categoryTags: String,
        val currency: String,
        val referencePrice: BigDecimal,
        val slogan: String,
        val coverImage: String,
        val salesCount: Int
    )

    private data class LegacyChangesRequestedSnapshot(
        val status: String,
        val reviewNote: String,
        val reviewedBy: String
    )

    companion object {
        @Container
        @JvmField
        val mysql = ProfessionalProjectRequestMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class ProfessionalProjectRequestMySqlContainer(imageName: String) :
    MySQLContainer<ProfessionalProjectRequestMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
