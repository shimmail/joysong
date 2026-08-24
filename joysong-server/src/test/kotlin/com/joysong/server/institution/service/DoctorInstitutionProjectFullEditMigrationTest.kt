package com.joysong.server.institution.service

import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
class DoctorInstitutionProjectFullEditMigrationTest {
    @Test
    fun `V33 preserves v1 rows while adding versioned project storage`() {
        assertEquals(WorktreeTestDatabase.databaseName(), mysql.databaseName)
        val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))

        migrate("32")
        seedV32History(jdbc)
        migrate()

        assertEquals(
            (26..33).map(Int::toString),
            jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String::class.java
            )
        )
        assertEquals(1, jdbc.queryForObject("SELECT payload_version FROM doctor_project_change_requests WHERE id = 'legacy-request'", Int::class.java))
        assertEquals("unchanged schedule", jdbc.queryForObject("SELECT schedule_note FROM doctor_project_change_requests WHERE id = 'legacy-request'", String::class.java))
        assertEquals("unchanged current schedule", jdbc.queryForObject("SELECT current_schedule_note FROM doctor_project_change_requests WHERE id = 'legacy-request'", String::class.java))
        assertEquals(true, jdbc.queryForObject("SELECT is_active FROM doctor_projects WHERE doctor_id = 'legacy-doctor' AND institution_project_id = 'legacy-ip'", Boolean::class.java))
        assertEquals(0L, jdbc.queryForObject("SELECT version FROM institution_projects WHERE id = 'legacy-ip'", Long::class.java))
        assertNull(jdbc.queryForObject("SELECT base_institution_project_version FROM doctor_project_change_requests WHERE id = 'legacy-request'", Long::class.java))
        assertNull(jdbc.queryForObject("SELECT proposed_project_snapshot FROM doctor_project_change_requests WHERE id = 'legacy-request'", String::class.java))
        assertTrue(isNullable(jdbc, "institution_projects", "cover_image"))
        assertTrue(isNullable(jdbc, "institution_projects", "images"))
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema = DATABASE() AND table_name = 'doctor_projects' AND index_name = 'idx_doctor_projects_public_lookup'", Int::class.java))

        jdbc.update("UPDATE doctor_project_change_requests SET status = 'APPROVED' WHERE id = 'legacy-request'")
        insertPendingV2(jdbc, "pending-v2")
        assertEquals(2, jdbc.queryForObject("SELECT payload_version FROM doctor_project_change_requests WHERE id = 'pending-v2'", Int::class.java))
        assertNull(jdbc.queryForObject("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id = 'pending-v2'", String::class.java))

        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-current-snapshot", currentProjectSnapshot = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-policy-revision", pricingPolicyRevision = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-proposed-price", medicalListPrice = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-current-price", currentPrice = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "obsolete-price-suggestion", priceSuggestion = BigDecimal("102.00")) }
    }

    private fun migrate(target: String? = null) {
        WorktreeTestDatabase.validateAndPrint(mysql)
        val config = Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
        if (target != null) config.target(target)
        config.load().migrate()
    }

    private fun seedV32History(jdbc: JdbcTemplate) {
        jdbc.update("INSERT INTO users (id, password_hash, nickname) VALUES ('legacy-doctor', 'x', 'D')")
        jdbc.update("INSERT INTO doctors (id, name, is_verified) VALUES ('legacy-doctor', 'D', 1)")
        jdbc.update("INSERT INTO institutions (id, name, is_verified) VALUES ('legacy-institution', 'I', 1)")
        jdbc.update("INSERT INTO projects (id, name) VALUES ('legacy-project', 'P')")
        jdbc.update("INSERT INTO institution_projects (id, institution_id, project_id, price, cover_image, images) VALUES ('legacy-ip', 'legacy-institution', 'legacy-project', 100.00, '', '')")
        jdbc.update("INSERT INTO doctor_projects (doctor_id, project_id, institution_project_id, price, schedule_note) VALUES ('legacy-doctor', 'legacy-project', 'legacy-ip', 99.00, 'doctor schedule')")
        jdbc.update(
            """
            INSERT INTO doctor_project_change_requests (
                id, doctor_id, institution_id, institution_project_id, request_type,
                service_description, price_suggestion, consultation_fee, commission_rate, institution_rate,
                current_price, current_consultation_fee, current_commission_rate, current_institution_rate,
                current_platform_rate, current_doctor_rate, service_tags, schedule_note, cover_image, images,
                current_schedule_note, status, submitted_by
            ) VALUES (
                'legacy-request', 'legacy-doctor', 'legacy-institution', 'legacy-ip', 'PROFILE_UPDATE',
                'legacy profile', 101.00, 1.00, 2.00, 3.00,
                99.00, 1.00, 2.00, 3.00, 10.00, 85.00, '[]', 'unchanged schedule', '', '[]',
                'unchanged current schedule', 'PENDING', 'legacy-doctor'
            )
            """.trimIndent()
        )
    }

    private fun insertPendingV2(
        jdbc: JdbcTemplate,
        id: String,
        priceSuggestion: BigDecimal? = null,
        medicalListPrice: BigDecimal? = BigDecimal("102.00"),
        currentPrice: BigDecimal? = BigDecimal("99.00"),
        pricingPolicyRevision: String? = "policy-v1",
        currentProjectSnapshot: String? = "{\"name\":\"before\"}",
    ) {
        jdbc.update(
            """
            INSERT INTO doctor_project_change_requests (
                id, doctor_id, institution_id, institution_project_id, request_type,
                service_description, status, submitted_by, payload_version, price_suggestion,
                medical_list_price, current_price, base_institution_project_version,
                base_platform_inheritance_hash, pricing_policy_revision, shared_changed,
                current_project_snapshot, proposed_project_snapshot, current_doctor_is_active,
                proposed_doctor_is_active
            ) VALUES (?, 'legacy-doctor', 'legacy-institution', 'legacy-ip', 'PROFILE_UPDATE',
                'v2 profile', 'PENDING', 'legacy-doctor', 2, ?, ?, ?, 0, ?, ?, TRUE, ?,
                '{\"name\":\"after\"}', TRUE, FALSE)
            """.trimIndent(),
            id,
            priceSuggestion,
            medicalListPrice,
            currentPrice,
            "a".repeat(64),
            pricingPolicyRevision,
            currentProjectSnapshot,
        )
    }

    private fun assertV2ConstraintViolation(operation: () -> Unit) {
        assertThrows(Exception::class.java, operation)
    }

    private fun isNullable(jdbc: JdbcTemplate, table: String, column: String): Boolean =
        jdbc.queryForObject(
            "SELECT is_nullable FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?",
            String::class.java,
            table,
            column
        ) == "YES"

    companion object {
        @Container
        @JvmField
        val mysql = DoctorInstitutionProjectFullEditMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class DoctorInstitutionProjectFullEditMySqlContainer(imageName: String) :
    MySQLContainer<DoctorInstitutionProjectFullEditMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
