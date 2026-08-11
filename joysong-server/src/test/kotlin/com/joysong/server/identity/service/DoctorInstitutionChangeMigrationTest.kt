package com.joysong.server.identity.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator
import java.sql.DriverManager
import java.time.LocalDateTime

class DoctorInstitutionChangeMigrationTest {

    @Test
    fun `V13 migration declares the doctor institution request ledger contract`() {
        val migration = requireNotNull(
            javaClass.getResource("/db/migration/V13__add_doctor_institution_change_requests.sql")
        ).readText()
        val normalized = migration.replace(Regex("\\s+"), " ").trim()

        assertContains(normalized, "CREATE TABLE doctor_institution_change_requests")
        assertContains(normalized, "action VARCHAR(20) NOT NULL")
        assertContains(normalized, "status VARCHAR(20) NOT NULL DEFAULT 'PENDING'")
        assertContains(normalized, "action IN ('JOIN', 'LEAVE')")
        assertContains(normalized, "status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')")
        assertContains(normalized, "CASE WHEN status = 'PENDING' THEN CONCAT(doctor_id, ':', institution_id) ELSE NULL END")
        assertContains(normalized, "UNIQUE KEY uk_doctor_institution_change_requests_pending (pending_key)")

        assertContains(normalized, "INSERT INTO doctor_institution_change_requests")
        assertContains(normalized, "FROM doctor_institutions")
        assertContains(normalized, "WHEN status = 'CHANGES_REQUESTED' THEN 'REJECTED'")
        assertContains(normalized, "WHEN status = 'REVOKED' THEN 'APPROVED'")
        assertContains(normalized, "NULLIF(TRIM(COALESCE(review_note, '')), '') IS NULL")
        assertContains(normalized, "THEN '历史审核未填写原因'")
        assertContains(normalized, "ELSE COALESCE(review_note, '')")
        assertContains(normalized, "DELETE FROM doctor_institutions WHERE status NOT IN ('APPROVED', 'REVOKED')")
        assertContains(normalized, "UPDATE institution_memberships SET status = 'REJECTED' WHERE status = 'CHANGES_REQUESTED'")
        assertFalse(normalized.contains("chk_doctor_institutions_status"))
        assertFalse(normalized.contains("chk_institution_memberships_status"))

        listOf(
            "FOREIGN KEY (doctor_id) REFERENCES doctors(id)",
            "FOREIGN KEY (institution_id) REFERENCES institutions(id)",
            "FOREIGN KEY (submitted_by) REFERENCES users(id)",
            "FOREIGN KEY (reviewed_by) REFERENCES users(id)"
        ).forEach { assertContains(normalized, it) }

        listOf(
            "CONSTRAINT chk_doctor_institution_change_requests_action CHECK (action IN ('JOIN', 'LEAVE'))",
            "CONSTRAINT chk_doctor_institution_change_requests_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN'))",
            "CONSTRAINT chk_doctor_institution_change_requests_review_note CHECK (status <> 'REJECTED' OR review_note <> '')"
        ).forEach { assertContains(normalized, it) }
    }

    @Test
    fun `V14 finalizes relationship and consultant membership statuses`() {
        val migration = requireNotNull(
            javaClass.getResource("/db/migration/V14__finalize_institution_membership_statuses.sql")
        ).readText().replace(Regex("\\s+"), " ").trim()

        assertContains(migration, "INSERT INTO doctor_institution_change_requests")
        assertContains(migration, "legacy.status IN ('PENDING', 'REJECTED', 'CHANGES_REQUESTED')")
        assertContains(migration, "WHEN legacy.status = 'CHANGES_REQUESTED' THEN 'REJECTED'")
        assertContains(migration, "WHEN legacy.status IN ('REJECTED', 'CHANGES_REQUESTED')")
        assertContains(migration, "THEN '历史审核未填写原因'")
        assertContains(migration, "legacy.confirmed_by")
        assertContains(migration, "legacy.confirmed_at")
        assertContains(migration, "UPDATE institution_memberships SET status = 'REJECTED' WHERE status = 'CHANGES_REQUESTED'")
        assertContains(migration, "CONSTRAINT chk_doctor_institutions_status CHECK (status IN ('APPROVED', 'REVOKED'))")
        assertContains(migration, "CONSTRAINT chk_institution_memberships_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED'))")
    }

    @Test
    fun `V13 migrates historical doctor and consultant fixtures in MySQL`() {
        val rootUrl = System.getenv("WORKTREE_MIGRATION_DB_URL")
        assumeTrue(!rootUrl.isNullOrBlank(), "WORKTREE_MIGRATION_DB_URL is not set")

        val databaseName = HISTORY_DATABASE
        assertTrue(databaseName.startsWith("myapp_worktree_"))
        val mysqlRootPattern = Regex("^jdbc:mysql://([^/]+)/mysql(?:\\?.*)?$")
        assertTrue(mysqlRootPattern.matches(rootUrl!!), "WORKTREE_MIGRATION_DB_URL must target the /mysql database")
        val rootMatch = mysqlRootPattern.matchEntire(rootUrl)
            ?: error("WORKTREE_MIGRATION_DB_URL did not match the MySQL root URL")
        val resolvedHost = rootMatch.groupValues[1]
        val targetUrl = rootUrl.replace(Regex("/mysql(?:\\?.*)?$"), "/$databaseName")
        assertFalse(targetUrl == rootUrl)
        println("MYSQL_HOST=$resolvedHost")
        println("MYSQL_DATABASE=$databaseName")

        DriverManager.getConnection(rootUrl, DB_USER, DB_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute(
                    "CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
                )
            }
        }

        val dataSource = DriverManagerDataSource(targetUrl, DB_USER, DB_PASSWORD)
        LEGACY_MIGRATIONS.forEach { applySqlScript(dataSource, it) }
        val jdbcTemplate = JdbcTemplate(dataSource)
        seedHistoricalFixtures(jdbcTemplate)
        applySqlScript(dataSource, V13_MIGRATION)

        val history = jdbcTemplate.query(
            """
            SELECT request_note, action, status, review_note,
                   reviewed_by, submitted_at, reviewed_at, created_at, updated_at
            FROM doctor_institution_change_requests
            """.trimIndent()
        ) { rs, _ ->
            rs.getString("request_note") to LedgerHistory(
                action = rs.getString("action"),
                status = rs.getString("status"),
                reviewNote = rs.getString("review_note"),
                reviewedBy = rs.getString("reviewed_by"),
                submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
            )
        }.toMap()
        assertEquals(
            setOf("pending", "approved", "rejected-empty", "changes-requested", "revoked", "soft-deleted"),
            history.keys
        )
        assertHistoryRow(
            history,
            "pending",
            "PENDING",
            "",
            "2025-01-01 01:02:03",
            null,
            "2025-01-01 01:02:03",
            "2025-01-01 01:02:03"
        )
        assertHistoryRow(
            history,
            "approved",
            "APPROVED",
            "approved reason",
            "2025-02-01 01:02:03",
            "2025-02-02 02:03:04",
            "2025-02-01 01:02:03",
            "2025-02-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            history,
            "rejected-empty",
            "REJECTED",
            "历史审核未填写原因",
            "2025-03-01 01:02:03",
            "2025-03-03 03:04:05",
            "2025-03-01 01:02:03",
            "2025-03-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            history,
            "changes-requested",
            "REJECTED",
            "changes reason",
            "2025-04-01 01:02:03",
            "2025-04-03 03:04:05",
            "2025-04-01 01:02:03",
            "2025-04-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            history,
            "revoked",
            "APPROVED",
            "revoked reason",
            "2025-05-01 01:02:03",
            "2025-05-02 02:03:04",
            "2025-05-01 01:02:03",
            "2025-05-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            history,
            "soft-deleted",
            "REJECTED",
            "soft deleted reason",
            "2025-06-01 01:02:03",
            "2025-06-03 03:04:05",
            "2025-06-01 01:02:03",
            "2025-06-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )

        assertEquals(
            setOf("doctor-approved", "doctor-revoked"),
            jdbcTemplate.queryForList(
                "SELECT id FROM doctor_institutions ORDER BY id",
                String::class.java
            ).toSet()
        )
        assertEquals(
            "REJECTED",
            jdbcTemplate.queryForObject(
                "SELECT status FROM institution_memberships WHERE id = ?",
                String::class.java,
                CONSULTANT_MEMBERSHIP_ID
            )
        )

        assertEquals(
            "$DOCTOR_ID:$PENDING_INSTITUTION_ID",
            jdbcTemplate.queryForObject(
                "SELECT pending_key FROM doctor_institution_change_requests WHERE request_note = 'pending'",
                String::class.java
            )
        )
        assertThrows(DataIntegrityViolationException::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO doctor_institution_change_requests
                    (id, doctor_id, institution_id, action, status, submitted_by)
                VALUES (?, ?, ?, 'LEAVE', 'PENDING', ?)
                """.trimIndent(),
                "duplicate-pending",
                DOCTOR_ID,
                PENDING_INSTITUTION_ID,
                DOCTOR_ID
            )
        }

        val foreignKeys = jdbcTemplate.query(
            """
            SELECT column_name, referenced_table_name
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE table_schema = DATABASE()
              AND table_name = 'doctor_institution_change_requests'
              AND referenced_table_name IS NOT NULL
            """.trimIndent()
        ) { rs, _ -> rs.getString("column_name") to rs.getString("referenced_table_name") }
            .toMap()
        assertEquals(
            mapOf(
                "doctor_id" to "doctors",
                "institution_id" to "institutions",
                "submitted_by" to "users",
                "reviewed_by" to "users"
            ),
            foreignKeys
        )

        val generatedColumn = jdbcTemplate.queryForMap(
            """
            SELECT extra, generation_expression
            FROM information_schema.COLUMNS
            WHERE table_schema = DATABASE()
              AND table_name = 'doctor_institution_change_requests'
              AND column_name = 'pending_key'
            """.trimIndent()
        )
        assertTrue(generatedColumn["extra"].toString().contains("STORED GENERATED"))
        assertTrue(generatedColumn["generation_expression"].toString().contains("PENDING"))
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT non_unique
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'doctor_institution_change_requests'
                  AND index_name = 'uk_doctor_institution_change_requests_pending'
                LIMIT 1
                """.trimIndent(),
                Int::class.java
            )
        )
    }

    @Test
    fun `V14 preserves rolling legacy doctor decisions in MySQL`() {
        val rootUrl = System.getenv("WORKTREE_MIGRATION_DB_URL")
        assumeTrue(!rootUrl.isNullOrBlank(), "WORKTREE_MIGRATION_DB_URL is not set")

        val databaseName = V14_HISTORY_DATABASE
        assertTrue(databaseName.startsWith("myapp_worktree_"))
        val mysqlRootPattern = Regex("^jdbc:mysql://([^/]+)/mysql(?:\\?.*)?$")
        assertTrue(mysqlRootPattern.matches(rootUrl!!), "WORKTREE_MIGRATION_DB_URL must target the /mysql database")
        val rootMatch = mysqlRootPattern.matchEntire(rootUrl)
            ?: error("WORKTREE_MIGRATION_DB_URL did not match the MySQL root URL")
        val resolvedHost = rootMatch.groupValues[1]
        val targetUrl = rootUrl.replace(Regex("/mysql(?:\\?.*)?$"), "/$databaseName")
        assertFalse(targetUrl == rootUrl)
        println("MYSQL_HOST=$resolvedHost")
        println("MYSQL_DATABASE=$databaseName")

        DriverManager.getConnection(rootUrl, DB_USER, DB_PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute(
                    "CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
                )
            }
        }

        val dataSource = DriverManagerDataSource(targetUrl, DB_USER, DB_PASSWORD)
        LEGACY_MIGRATIONS.forEach { applySqlScript(dataSource, it) }
        val jdbcTemplate = JdbcTemplate(dataSource)
        seedHistoricalFixtures(jdbcTemplate)
        applySqlScript(dataSource, V13_MIGRATION)
        seedRollingLegacyDoctorDecisions(jdbcTemplate)
        applySqlScript(dataSource, V14_MIGRATION)

        val rollingHistory = jdbcTemplate.query(
            """
            SELECT request_note, action, status, review_note,
                   reviewed_by, submitted_at, reviewed_at, created_at, updated_at
            FROM doctor_institution_change_requests
            WHERE request_note LIKE 'rolling-%'
            """.trimIndent()
        ) { rs, _ ->
            rs.getString("request_note") to LedgerHistory(
                action = rs.getString("action"),
                status = rs.getString("status"),
                reviewNote = rs.getString("review_note"),
                reviewedBy = rs.getString("reviewed_by"),
                submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
            )
        }.toMap()
        assertEquals(
            setOf(
                "rolling-pending",
                "rolling-duplicate-pending",
                "rolling-rejected-empty",
                "rolling-rejected-cleared",
                "rolling-changes"
            ),
            rollingHistory.keys
        )
        assertHistoryRow(
            rollingHistory,
            "rolling-pending",
            "PENDING",
            "",
            "2026-01-01 01:02:03",
            null,
            "2026-01-01 01:02:03",
            "2026-01-01 01:02:03"
        )
        assertHistoryRow(
            rollingHistory,
            "rolling-duplicate-pending",
            "REJECTED",
            "历史待审核申请已由新关系申请接管",
            "2026-01-02 01:02:03",
            "2026-01-03 03:04:05",
            "2026-01-02 01:02:03",
            "2026-01-03 03:04:05"
        )
        assertHistoryRow(
            rollingHistory,
            "rolling-rejected-empty",
            "REJECTED",
            "历史审核未填写原因",
            "2026-02-01 01:02:03",
            "2026-02-02 02:03:04",
            "2026-02-01 01:02:03",
            "2026-02-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            rollingHistory,
            "rolling-rejected-cleared",
            "REJECTED",
            "历史审核未填写原因",
            "2026-02-04 01:02:03",
            "2026-02-06 03:04:05",
            "2026-02-04 01:02:03",
            "2026-02-06 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertHistoryRow(
            rollingHistory,
            "rolling-changes",
            "REJECTED",
            "rolling changes reason",
            "2026-03-01 01:02:03",
            "2026-03-02 02:03:04",
            "2026-03-01 01:02:03",
            "2026-03-03 03:04:05",
            reviewedBy = REVIEWER_ID
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM doctor_institutions WHERE id LIKE 'rolling-%'",
                Long::class.java
            )
        )
        val constraintError = assertThrows(Exception::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO doctor_institutions
                    (id, doctor_id, institution_id, is_primary, status)
                VALUES ('rolling-invalid', ?, ?, 0, 'PENDING')
                """.trimIndent(),
                DOCTOR_ID,
                ROLLING_PENDING_INSTITUTION_ID
            )
        }
        assertTrue(constraintError.message.orEmpty().contains("chk_doctor_institutions_status"))
        val membershipConstraintError = assertThrows(Exception::class.java) {
            jdbcTemplate.update(
                """
                INSERT INTO institution_memberships
                    (id, user_id, institution_id, member_role, status)
                VALUES ('rolling-invalid-membership', ?, ?, 'CONSULTANT', 'CHANGES_REQUESTED')
                """.trimIndent(),
                CONSULTANT_ID,
                ROLLING_PENDING_INSTITUTION_ID
            )
        }
        assertTrue(membershipConstraintError.message.orEmpty().contains("chk_institution_memberships_status"))
    }

    private fun applySqlScript(dataSource: DriverManagerDataSource, path: String) {
        ResourceDatabasePopulator(ClassPathResource(path)).apply {
            setContinueOnError(false)
            setSqlScriptEncoding("UTF-8")
            execute(dataSource)
        }
    }

    private fun seedHistoricalFixtures(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, password_hash, nickname, role) VALUES
                (?, 'hash', 'Fixture Doctor', 'USER'),
                (?, 'hash', 'Fixture Reviewer', 'ADMIN'),
                (?, 'hash', 'Fixture Consultant', 'CONSULTANT')
            """.trimIndent(),
            DOCTOR_ID,
            REVIEWER_ID,
            CONSULTANT_ID
        )
        jdbcTemplate.update("INSERT INTO doctors (id, name) VALUES (?, 'Fixture Doctor')", DOCTOR_ID)
        jdbcTemplate.update(
            """
            INSERT INTO institutions (id, name) VALUES
                (?, 'Pending Institution'),
                (?, 'Approved Institution'),
                (?, 'Rejected Institution'),
                (?, 'Changes Institution'),
                (?, 'Revoked Institution'),
                (?, 'Soft Deleted Institution')
            """.trimIndent(),
            PENDING_INSTITUTION_ID,
            APPROVED_INSTITUTION_ID,
            REJECTED_INSTITUTION_ID,
            CHANGES_INSTITUTION_ID,
            REVOKED_INSTITUTION_ID,
            SOFT_DELETED_INSTITUTION_ID
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, is_primary, status, request_note, review_note,
                 confirmed_by, confirmed_at, created_at, updated_at, deleted_at)
            VALUES
                ('doctor-pending', ?, ?, 0, 'PENDING', 'pending', '', NULL, NULL,
                 '2025-01-01 01:02:03', '2025-01-01 01:02:03', NULL),
                ('doctor-approved', ?, ?, 1, 'APPROVED', 'approved', 'approved reason', ?,
                 '2025-02-02 02:03:04', '2025-02-01 01:02:03', '2025-02-03 03:04:05', NULL),
                ('doctor-rejected-empty', ?, ?, 0, 'REJECTED', 'rejected-empty', '', ?,
                 '2025-03-02 02:03:04', '2025-03-01 01:02:03', '2025-03-03 03:04:05', NULL),
                ('doctor-changes', ?, ?, 0, 'CHANGES_REQUESTED', 'changes-requested', 'changes reason', ?,
                 '2025-04-02 02:03:04', '2025-04-01 01:02:03', '2025-04-03 03:04:05', NULL),
                ('doctor-revoked', ?, ?, 0, 'REVOKED', 'revoked', 'revoked reason', ?,
                 '2025-05-02 02:03:04', '2025-05-01 01:02:03', '2025-05-03 03:04:05', NULL),
                ('doctor-soft-deleted', ?, ?, 0, 'REJECTED', 'soft-deleted', 'soft deleted reason', ?,
                 '2025-06-02 02:03:04', '2025-06-01 01:02:03', '2025-06-03 03:04:05',
                 '2025-06-07 07:08:09')
            """.trimIndent(),
            DOCTOR_ID,
            PENDING_INSTITUTION_ID,
            DOCTOR_ID,
            APPROVED_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            REJECTED_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            CHANGES_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            REVOKED_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            SOFT_DELETED_INSTITUTION_ID,
            REVIEWER_ID
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, request_note, review_note)
            VALUES (?, ?, ?, 'CONSULTANT', 'CHANGES_REQUESTED', 'consultant request', 'consultant reason')
            """.trimIndent(),
            CONSULTANT_MEMBERSHIP_ID,
            CONSULTANT_ID,
            PENDING_INSTITUTION_ID
        )
    }

    private fun seedRollingLegacyDoctorDecisions(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.update(
            """
            INSERT INTO institutions (id, name) VALUES
                (?, 'Rolling Pending Institution'),
                (?, 'Rolling Rejected Institution'),
                (?, 'Rolling Rejected Cleared Institution'),
                (?, 'Rolling Changes Institution')
            """.trimIndent(),
            ROLLING_PENDING_INSTITUTION_ID,
            ROLLING_REJECTED_INSTITUTION_ID,
            ROLLING_REJECTED_CLEARED_INSTITUTION_ID,
            ROLLING_CHANGES_INSTITUTION_ID
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, is_primary, status, request_note, review_note,
                 confirmed_by, confirmed_at, created_at, updated_at, deleted_at)
            VALUES
                ('rolling-pending', ?, ?, 0, 'PENDING', 'rolling-pending', '', NULL, NULL,
                 '2026-01-01 01:02:03', '2026-01-01 01:02:03', NULL),
                ('rolling-duplicate-pending', ?, ?, 0, 'PENDING', 'rolling-duplicate-pending', '', NULL, NULL,
                 '2026-01-02 01:02:03', '2026-01-03 03:04:05', NULL),
                ('rolling-rejected-empty', ?, ?, 0, 'REJECTED', 'rolling-rejected-empty', '', ?,
                 '2026-02-02 02:03:04', '2026-02-01 01:02:03', '2026-02-03 03:04:05', NULL),
                ('rolling-rejected-cleared', ?, ?, 0, 'REJECTED', 'rolling-rejected-cleared', '', ?,
                 NULL, '2026-02-04 01:02:03', '2026-02-06 03:04:05', NULL),
                ('rolling-changes', ?, ?, 0, 'CHANGES_REQUESTED', 'rolling-changes', 'rolling changes reason', ?,
                 '2026-03-02 02:03:04', '2026-03-01 01:02:03', '2026-03-03 03:04:05', NULL)
            """.trimIndent(),
            DOCTOR_ID,
            ROLLING_PENDING_INSTITUTION_ID,
            DOCTOR_ID,
            PENDING_INSTITUTION_ID,
            DOCTOR_ID,
            ROLLING_REJECTED_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            ROLLING_REJECTED_CLEARED_INSTITUTION_ID,
            REVIEWER_ID,
            DOCTOR_ID,
            ROLLING_CHANGES_INSTITUTION_ID,
            REVIEWER_ID
        )
    }

    private fun assertHistoryRow(
        history: Map<String, LedgerHistory>,
        requestNote: String,
        status: String,
        reviewNote: String,
        submittedAt: String,
        reviewedAt: String?,
        createdAt: String,
        updatedAt: String,
        reviewedBy: String? = null
    ) {
        val row = history.getValue(requestNote)
        assertEquals("JOIN", row.action)
        assertEquals(status, row.status)
        assertEquals(reviewNote, row.reviewNote)
        assertEquals(reviewedBy, row.reviewedBy)
        assertEquals(timestamp(submittedAt), row.submittedAt)
        assertEquals(reviewedAt?.let(::timestamp), row.reviewedAt)
        assertEquals(timestamp(createdAt), row.createdAt)
        assertEquals(timestamp(updatedAt), row.updatedAt)
    }

    private fun timestamp(value: String): LocalDateTime =
        LocalDateTime.parse(value.replace(' ', 'T'))

    private data class LedgerHistory(
        val action: String,
        val status: String,
        val reviewNote: String,
        val reviewedBy: String?,
        val submittedAt: LocalDateTime,
        val reviewedAt: LocalDateTime?,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    )

    companion object {
        private const val DB_USER = "root"
        private const val DB_PASSWORD = "codex-test"
        private const val HISTORY_DATABASE = "myapp_worktree_institution_membership_lifecycle_history"
        private const val V14_HISTORY_DATABASE = "myapp_worktree_institution_membership_lifecycle_v14_history"
        private const val V13_MIGRATION = "db/migration/V13__add_doctor_institution_change_requests.sql"
        private const val V14_MIGRATION = "db/migration/V14__finalize_institution_membership_statuses.sql"
        private const val DOCTOR_ID = "fixture-doctor"
        private const val REVIEWER_ID = "fixture-reviewer"
        private const val CONSULTANT_ID = "fixture-consultant"
        private const val PENDING_INSTITUTION_ID = "fixture-institution-pending"
        private const val APPROVED_INSTITUTION_ID = "fixture-institution-approved"
        private const val REJECTED_INSTITUTION_ID = "fixture-institution-rejected"
        private const val CHANGES_INSTITUTION_ID = "fixture-institution-changes"
        private const val REVOKED_INSTITUTION_ID = "fixture-institution-revoked"
        private const val SOFT_DELETED_INSTITUTION_ID = "fixture-institution-soft-deleted"
        private const val ROLLING_PENDING_INSTITUTION_ID = "fixture-institution-rolling-pending"
        private const val ROLLING_REJECTED_INSTITUTION_ID = "fixture-institution-rolling-rejected"
        private const val ROLLING_REJECTED_CLEARED_INSTITUTION_ID = "fixture-rolling-rejected-cleared"
        private const val ROLLING_CHANGES_INSTITUTION_ID = "fixture-institution-rolling-changes"
        private const val CONSULTANT_MEMBERSHIP_ID = "fixture-consultant-membership"
        private val LEGACY_MIGRATIONS = listOf(
            "db/migration/B1__init_schema.sql",
            "db/migration/V2__remove_diary_cover_image.sql",
            "db/migration/V3__add_refund_reason_code_constraint.sql",
            "db/migration/V4__add_doctor_contact_phone.sql",
            "db/migration/V5__add_recommended_institution_projects_index.sql",
            "db/migration/V6__enforce_canonical_order_reviews.sql",
            "db/migration/V7__create_diary_shares.sql",
            "db/migration/V8__default_price_currency_to_usd.sql",
            "db/migration/V9__add_order_consultant_snapshot.sql",
            "db/migration/V10__rebuild_agent_v2.sql",
            "db/migration/V11__add_institution_membership_request_notes.sql",
            "db/migration/V12__add_professional_project_requests.sql"
        )
    }

    private fun assertContains(migration: String, contract: String) {
        assertTrue(migration.contains(contract), "Migration must contain: $contract")
    }
}
