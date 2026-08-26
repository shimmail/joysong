package com.joysong.server.institution.service

import com.joysong.server.support.LegacyMigrationTestResources
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.LocalDateTime
import java.nio.file.Path

@Tag("mysql-integration")
@Testcontainers
class DoctorInstitutionProjectFullEditMigrationTest {
    @TempDir
    lateinit var legacyMigrationDirectory: Path

    @Test
    fun `V33 preserves v1 rows while adding versioned project storage`() {
        assertEquals(WorktreeTestDatabase.databaseName(), mysql.databaseName)
        val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)

        migrate(legacyMigrationLocation, "32")
        seedV32History(jdbc)
        migrate("classpath:db/migration")

        assertEquals(
            listOf("26", "27", "28", "29", "30", "31", "32", "32.1", "32.2", "33"),
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
        assertTimestamp6(jdbc, "doctor_projects", "updated_at", "current_timestamp(6)", "on update current_timestamp(6)")
        assertTimestamp6(
            jdbc,
            "doctor_institution_project_configs",
            "updated_at",
            "current_timestamp(6)",
            "on update current_timestamp(6)"
        )
        assertTimestamp6(jdbc, "doctor_project_change_requests", "base_doctor_project_updated_at", null, "")
        assertTimestamp6(jdbc, "doctor_project_change_requests", "base_config_updated_at", null, "")
        assertSixDigitTimestampRoundTrip(jdbc)

        jdbc.update("UPDATE doctor_project_change_requests SET status = 'APPROVED' WHERE id = 'legacy-request'")
        insertPendingV2(jdbc, "pending-v2", status = "PENDING")
        assertEquals(2, jdbc.queryForObject("SELECT payload_version FROM doctor_project_change_requests WHERE id = 'pending-v2'", Int::class.java))
        assertNull(jdbc.queryForObject("SELECT approval_audit_snapshot FROM doctor_project_change_requests WHERE id = 'pending-v2'", String::class.java))

        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-current-snapshot", currentProjectSnapshot = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-proposed-snapshot", proposedProjectSnapshot = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-policy-revision", pricingPolicyRevision = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-proposed-price", medicalListPrice = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-current-price", currentPrice = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "obsolete-price-suggestion", priceSuggestion = BigDecimal("102.00")) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-base-doctor-time", baseDoctorProjectUpdatedAt = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-platform-rate", currentPlatformRate = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "missing-service-fee", proposedTravelGroundServiceFee = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "negative-current-price", currentPrice = BigDecimal("-0.01")) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "negative-service-fee", proposedTravelGroundServiceFee = BigDecimal("-0.01")) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "platform-rate-over-100", currentPlatformRate = BigDecimal("100.01")) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "config-id-without-time", baseConfigUpdatedAt = null) }
        assertV2ConstraintViolation { insertPendingV2(jdbc, "config-time-without-id", baseConfigId = null) }
    }

    private fun migrate(location: String, target: String? = null) {
        WorktreeTestDatabase.validateAndPrint(mysql)
        val config = Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations(location)
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
        proposedProjectSnapshot: String? = "{\"name\":\"after\"}",
        status: String = "CHANGES_REQUESTED",
        baseDoctorProjectUpdatedAt: Timestamp? = Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 10, 0)),
        baseConfigId: String? = "config-1",
        baseConfigUpdatedAt: Timestamp? = Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 10, 0)),
        currentPlatformRate: BigDecimal? = BigDecimal("40.00"),
        proposedTravelGroundServiceFee: BigDecimal? = BigDecimal("40.80"),
    ) {
        jdbc.update(
            """
            INSERT INTO doctor_project_change_requests (
                id, doctor_id, institution_id, institution_project_id, request_type,
                service_description, status, submitted_by, payload_version, price_suggestion,
                medical_list_price, current_price, base_institution_project_version,
                base_platform_inheritance_hash, pricing_policy_revision, shared_changed,
                current_project_snapshot, proposed_project_snapshot, current_doctor_is_active,
                proposed_doctor_is_active, base_doctor_project_updated_at,
                base_config_id, base_config_updated_at, current_platform_rate,
                proposed_travel_ground_service_fee
            ) VALUES (?, 'legacy-doctor', 'legacy-institution', 'legacy-ip', 'PROFILE_UPDATE',
                'v2 profile', ?, 'legacy-doctor', 2, ?, ?, ?, 0, ?, ?, TRUE, ?,
                ?, TRUE, FALSE, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            status,
            priceSuggestion,
            medicalListPrice,
            currentPrice,
            "a".repeat(64),
            pricingPolicyRevision,
            currentProjectSnapshot,
            proposedProjectSnapshot,
            baseDoctorProjectUpdatedAt,
            baseConfigId,
            baseConfigUpdatedAt,
            currentPlatformRate,
            proposedTravelGroundServiceFee,
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

    private fun assertTimestamp6(
        jdbc: JdbcTemplate,
        table: String,
        column: String,
        expectedDefault: String?,
        expectedExtra: String
    ) {
        val metadata = jdbc.queryForMap(
            """
            SELECT data_type, datetime_precision, is_nullable, column_default, extra
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ?
            """.trimIndent(),
            table,
            column
        )
        assertEquals("timestamp", metadata["data_type"], "$table.$column data type")
        assertEquals(6, (metadata["datetime_precision"] as Number).toInt(), "$table.$column precision")
        assertEquals("YES", metadata["is_nullable"], "$table.$column nullability")
        assertEquals(expectedDefault, (metadata["column_default"] as String?)?.lowercase(), "$table.$column default")
        val extra = (metadata["extra"] as String).lowercase()
        if (expectedExtra.isEmpty()) {
            assertEquals(false, extra.contains("on update"), "$table.$column extra")
        } else {
            assertTrue(extra.contains(expectedExtra), "$table.$column extra was $extra")
        }
    }

    private fun assertSixDigitTimestampRoundTrip(jdbc: JdbcTemplate) {
        val precise = Timestamp.valueOf(LocalDateTime.of(2026, 8, 24, 10, 0, 0, 123456000))
        jdbc.update(
            """
            INSERT INTO doctor_institution_project_configs
                (id, doctor_id, institution_project_id, consultation_fee, commission_rate,
                 institution_rate, medical_list_price)
            VALUES ('legacy-config', 'legacy-doctor', 'legacy-ip', 1.00, 2.00, 3.00, 99.00)
            """.trimIndent()
        )
        jdbc.update(
            "UPDATE doctor_projects SET updated_at = ? WHERE doctor_id = 'legacy-doctor' AND institution_project_id = 'legacy-ip'",
            precise
        )
        jdbc.update("UPDATE doctor_institution_project_configs SET updated_at = ? WHERE id = 'legacy-config'", precise)
        jdbc.update(
            "UPDATE doctor_project_change_requests SET base_doctor_project_updated_at = ?, base_config_id = 'legacy-config', base_config_updated_at = ? WHERE id = 'legacy-request'",
            precise,
            precise
        )
        assertEquals(
            precise,
            jdbc.queryForObject(
                "SELECT updated_at FROM doctor_projects WHERE doctor_id = 'legacy-doctor' AND institution_project_id = 'legacy-ip'",
                Timestamp::class.java
            )
        )
        assertEquals(
            precise,
            jdbc.queryForObject("SELECT updated_at FROM doctor_institution_project_configs WHERE id = 'legacy-config'", Timestamp::class.java)
        )
        assertEquals(
            precise,
            jdbc.queryForObject(
                "SELECT base_doctor_project_updated_at FROM doctor_project_change_requests WHERE id = 'legacy-request'",
                Timestamp::class.java
            )
        )
        assertEquals(
            precise,
            jdbc.queryForObject(
                "SELECT base_config_updated_at FROM doctor_project_change_requests WHERE id = 'legacy-request'",
                Timestamp::class.java
            )
        )
    }

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
