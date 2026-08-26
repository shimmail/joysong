package com.joysong.server.identity.service

import com.joysong.server.support.LegacyMigrationTestResources
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.time.LocalDateTime
import java.nio.file.Path

@Tag("mysql-integration")
@Testcontainers
class ConsultantInstitutionChangeMigrationTest {

    @TempDir
    lateinit var legacyMigrationDirectory: Path

    @Test
    fun `fresh database creates the consultant institution request ledger`() {
        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)
        migrate(mysql.jdbcUrl, mysql.username, mysql.password, DATABASE_NAME, legacyMigrationLocation, "27")
        val jdbc = jdbc(mysql.jdbcUrl, mysql.username, mysql.password)

        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name = 'consultant_institution_change_requests'
                """.trimIndent(),
                Int::class.java
            )
        )

        val columns = jdbc.query(
            """
            SELECT column_name, data_type
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'consultant_institution_change_requests'
            ORDER BY ordinal_position
            """.trimIndent()
        ) { rs, _ -> rs.getString("column_name") to rs.getString("data_type") }
        assertEquals(
            listOf(
                "id", "consultant_id", "institution_id", "action", "status",
                "request_note", "review_note", "submitted_by", "reviewed_by",
                "submitted_at", "reviewed_at", "created_at", "updated_at", "pending_key"
            ),
            columns.map { it.first }
        )
        assertEquals(
            setOf("submitted_at", "reviewed_at", "created_at", "updated_at"),
            columns.filter { it.second == "timestamp" }.map { it.first }.toSet()
        )

        val foreignKeys = jdbc.query(
            """
            SELECT column_name, referenced_table_name
            FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE()
              AND table_name = 'consultant_institution_change_requests'
              AND referenced_table_name IS NOT NULL
            """.trimIndent()
        ) { rs, _ -> rs.getString("column_name") to rs.getString("referenced_table_name") }.toMap()
        assertEquals(
            mapOf(
                "consultant_id" to "users",
                "institution_id" to "institutions",
                "submitted_by" to "users",
                "reviewed_by" to "users"
            ),
            foreignKeys
        )

        val checks = jdbc.query(
            """
            SELECT tc.constraint_name, cc.check_clause
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.table_schema = DATABASE()
              AND tc.table_name = 'consultant_institution_change_requests'
              AND tc.constraint_type = 'CHECK'
            """.trimIndent()
        ) { rs, _ -> rs.getString("constraint_name") to rs.getString("check_clause").uppercase() }.toMap()
        assertTrue(checks.getValue("chk_consultant_institution_change_requests_action").contains("JOIN"))
        assertTrue(checks.getValue("chk_consultant_institution_change_requests_action").contains("LEAVE"))
        listOf("PENDING", "APPROVED", "REJECTED", "WITHDRAWN").forEach {
            assertTrue(checks.getValue("chk_consultant_institution_change_requests_status").contains(it))
        }
        assertTrue(checks.getValue("chk_consultant_institution_change_requests_review_note").contains("REJECTED"))

        val generatedColumn = jdbc.queryForMap(
            """
            SELECT extra, generation_expression
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'consultant_institution_change_requests'
              AND column_name = 'pending_key'
            """.trimIndent()
        )
        assertTrue(generatedColumn.getValue("extra").toString().contains("STORED GENERATED"))
        assertTrue(generatedColumn.getValue("generation_expression").toString().contains("PENDING"))
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'consultant_institution_change_requests'
                  AND column_name = 'pending_key'
                  AND non_unique = 0
                """.trimIndent(),
                Int::class.java
            )
        )
    }

    @Test
    fun `V27 backfills consultant join history without rewriting memberships`() {
        createHistoryDatabase()
        val historyUrl = mysql.jdbcUrl.replace("/$DATABASE_NAME", "/$HISTORY_DATABASE")
        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)
        migrate(historyUrl, "root", mysql.password, HISTORY_DATABASE, legacyMigrationLocation, "26")
        val jdbc = jdbc(historyUrl, "root", mysql.password)
        seedHistory(jdbc)
        val membershipsBefore = membershipSnapshots(jdbc)

        migrate(historyUrl, "root", mysql.password, HISTORY_DATABASE, legacyMigrationLocation)

        assertEquals(membershipsBefore, membershipSnapshots(jdbc))
        assertEquals(
            setOf(PENDING_ID, REJECTED_ID, REJECTED_BLANK_ID, APPROVED_ID),
            jdbc.queryForList(
                "SELECT id FROM consultant_institution_change_requests",
                String::class.java
            ).toSet()
        )
        assertHistory(
            jdbc,
            PENDING_ID,
            status = "PENDING",
            reviewNote = "",
            reviewedBy = null,
            reviewedAt = null,
            submittedAt = "2025-01-01T01:02:03",
            updatedAt = "2025-01-03T03:04:05"
        )
        assertHistory(
            jdbc,
            REJECTED_ID,
            status = "REJECTED",
            reviewNote = "not eligible",
            reviewedBy = REVIEWER_ID,
            reviewedAt = "2025-02-02T02:03:04",
            submittedAt = "2025-02-01T01:02:03",
            updatedAt = "2025-02-03T03:04:05"
        )
        assertHistory(
            jdbc,
            REJECTED_BLANK_ID,
            status = "REJECTED",
            reviewNote = "历史审核未填写原因",
            reviewedBy = REVIEWER_ID,
            reviewedAt = "2025-03-03T03:04:05",
            submittedAt = "2025-03-01T01:02:03",
            updatedAt = "2025-03-03T03:04:05"
        )
        assertHistory(
            jdbc,
            APPROVED_ID,
            status = "APPROVED",
            reviewNote = "confirmed",
            reviewedBy = REVIEWER_ID,
            reviewedAt = "2025-04-02T02:03:04",
            submittedAt = "2025-04-01T01:02:03",
            updatedAt = "2025-04-03T03:04:05"
        )

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM institution_memberships WHERE id = ? AND status = 'APPROVED' AND revoked_at IS NULL",
                Int::class.java,
                APPROVED_ID
            )
        )
        assertEquals(
            3,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM institution_memberships WHERE status IN ('PENDING', 'REJECTED') AND member_role = 'CONSULTANT'",
                Int::class.java
            )
        )
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM consultant_institution_change_requests WHERE id = ?",
                Int::class.java,
                REVOKED_ID
            )
        )
        assertEquals(
            "REVOKED",
            jdbc.queryForObject(
                "SELECT status FROM institution_memberships WHERE id = ?",
                String::class.java,
                REVOKED_ID
            )
        )
        assertEquals(
            "$CONSULTANT_ID:$PENDING_INSTITUTION_ID",
            jdbc.queryForObject(
                "SELECT pending_key FROM consultant_institution_change_requests WHERE id = ?",
                String::class.java,
                PENDING_ID
            )
        )
    }

    private fun migrate(
        jdbcUrl: String,
        username: String,
        password: String,
        databaseName: String,
        migrationLocation: String,
        target: String? = null
    ) {
        println("Migration database host=${mysql.host}:${mysql.getMappedPort(3306)}, database=$databaseName")
        require(databaseName.startsWith("myapp_worktree_"))
        val configuration = Flyway.configure()
            .dataSource(jdbcUrl, username, password)
            .locations(migrationLocation)
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    private fun createHistoryDatabase() {
        require(HISTORY_DATABASE.startsWith("myapp_worktree_"))
        val rootUrl = mysql.jdbcUrl.replace("/$DATABASE_NAME", "/mysql")
        DriverManager.getConnection(rootUrl, "root", mysql.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE DATABASE `$HISTORY_DATABASE` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
                )
            }
        }
    }

    private fun jdbc(jdbcUrl: String, username: String, password: String) =
        JdbcTemplate(DriverManagerDataSource(jdbcUrl, username, password))

    private fun seedHistory(jdbc: JdbcTemplate) {
        jdbc.update(
            """
            INSERT INTO users (id, password_hash, nickname, role) VALUES
                (?, 'hash', 'Migration Consultant', 'CONSULTANT'),
                (?, 'hash', 'Migration Reviewer', 'ADMIN')
            """.trimIndent(),
            CONSULTANT_ID,
            REVIEWER_ID
        )
        jdbc.update(
            """
            INSERT INTO institutions (id, name) VALUES
                (?, 'Pending Institution'),
                (?, 'Rejected Institution'),
                (?, 'Rejected Blank Institution'),
                (?, 'Approved Institution'),
                (?, 'Revoked Institution')
            """.trimIndent(),
            PENDING_INSTITUTION_ID,
            REJECTED_INSTITUTION_ID,
            REJECTED_BLANK_INSTITUTION_ID,
            APPROVED_INSTITUTION_ID,
            REVOKED_INSTITUTION_ID
        )
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, request_note, review_note,
                 confirmed_by, confirmed_at, revoked_at, created_at, updated_at)
            VALUES
                (?, ?, ?, 'CONSULTANT', 'PENDING', 'please join', '', NULL, NULL, NULL,
                 '2025-01-01 01:02:03', '2025-01-03 03:04:05'),
                (?, ?, ?, 'CONSULTANT', 'REJECTED', 'join rejected', 'not eligible', ?,
                 '2025-02-02 02:03:04', NULL, '2025-02-01 01:02:03', '2025-02-03 03:04:05'),
                (?, ?, ?, 'CONSULTANT', 'REJECTED', 'join rejected blank', '', ?,
                 NULL, NULL, '2025-03-01 01:02:03', '2025-03-03 03:04:05'),
                (?, ?, ?, 'CONSULTANT', 'APPROVED', 'please approve', 'confirmed', ?,
                 '2025-04-02 02:03:04', NULL, '2025-04-01 01:02:03', '2025-04-03 03:04:05'),
                (?, ?, ?, 'CONSULTANT', 'REVOKED', 'old relationship', 'later revoked', ?,
                 '2025-05-02 02:03:04', '2025-05-04 04:05:06',
                 '2025-05-01 01:02:03', '2025-05-04 04:05:06')
            """.trimIndent(),
            PENDING_ID, CONSULTANT_ID, PENDING_INSTITUTION_ID,
            REJECTED_ID, CONSULTANT_ID, REJECTED_INSTITUTION_ID, REVIEWER_ID,
            REJECTED_BLANK_ID, CONSULTANT_ID, REJECTED_BLANK_INSTITUTION_ID, REVIEWER_ID,
            APPROVED_ID, CONSULTANT_ID, APPROVED_INSTITUTION_ID, REVIEWER_ID,
            REVOKED_ID, CONSULTANT_ID, REVOKED_INSTITUTION_ID, REVIEWER_ID
        )
    }

    private fun membershipSnapshots(jdbc: JdbcTemplate): List<MembershipSnapshot> = jdbc.query(
        """
        SELECT id, status, request_note, review_note, confirmed_by, confirmed_at,
               revoked_at, created_at, updated_at
        FROM institution_memberships
        WHERE member_role = 'CONSULTANT'
        ORDER BY id
        """.trimIndent()
    ) { rs, _ ->
        MembershipSnapshot(
            id = rs.getString("id"),
            status = rs.getString("status"),
            requestNote = rs.getString("request_note"),
            reviewNote = rs.getString("review_note"),
            confirmedBy = rs.getString("confirmed_by"),
            confirmedAt = rs.getTimestamp("confirmed_at")?.toLocalDateTime(),
            revokedAt = rs.getTimestamp("revoked_at")?.toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    private fun assertHistory(
        jdbc: JdbcTemplate,
        id: String,
        status: String,
        reviewNote: String,
        reviewedBy: String?,
        reviewedAt: String?,
        submittedAt: String,
        updatedAt: String
    ) {
        val row = jdbc.queryForMap(
            """
            SELECT action, status, request_note, review_note, submitted_by, reviewed_by,
                   submitted_at, reviewed_at, created_at, updated_at
            FROM consultant_institution_change_requests
            WHERE id = ?
            """.trimIndent(),
            id
        )
        assertEquals("JOIN", row["action"])
        assertEquals(status, row["status"])
        assertFalse(row["request_note"].toString().isBlank())
        assertEquals(reviewNote, row["review_note"])
        assertEquals(CONSULTANT_ID, row["submitted_by"])
        assertEquals(reviewedBy, row["reviewed_by"])
        assertEquals(LocalDateTime.parse(submittedAt), (row["submitted_at"] as java.sql.Timestamp).toLocalDateTime())
        assertEquals(reviewedAt?.let(LocalDateTime::parse), (row["reviewed_at"] as? java.sql.Timestamp)?.toLocalDateTime())
        assertEquals(LocalDateTime.parse(submittedAt), (row["created_at"] as java.sql.Timestamp).toLocalDateTime())
        assertEquals(LocalDateTime.parse(updatedAt), (row["updated_at"] as java.sql.Timestamp).toLocalDateTime())
    }

    private data class MembershipSnapshot(
        val id: String,
        val status: String,
        val requestNote: String,
        val reviewNote: String,
        val confirmedBy: String?,
        val confirmedAt: LocalDateTime?,
        val revokedAt: LocalDateTime?,
        val createdAt: LocalDateTime,
        val updatedAt: LocalDateTime
    )

    companion object {
        private const val DATABASE_NAME = "myapp_worktree_institution_membership_application_review"
        private const val HISTORY_DATABASE = "myapp_worktree_institution_membership_application_review_history"
        private const val CONSULTANT_ID = "migration-consultant"
        private const val REVIEWER_ID = "migration-reviewer"
        private const val PENDING_ID = "consultant-membership-pending"
        private const val REJECTED_ID = "consultant-membership-rejected"
        private const val REJECTED_BLANK_ID = "consultant-membership-rejected-blank"
        private const val APPROVED_ID = "consultant-membership-approved"
        private const val REVOKED_ID = "consultant-membership-revoked"
        private const val PENDING_INSTITUTION_ID = "institution-pending"
        private const val REJECTED_INSTITUTION_ID = "institution-rejected"
        private const val REJECTED_BLANK_INSTITUTION_ID = "institution-rejected-blank"
        private const val APPROVED_INSTITUTION_ID = "institution-approved"
        private const val REVOKED_INSTITUTION_ID = "institution-revoked"

        @Container
        @JvmField
        val mysql = ConsultantMigrationMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE_NAME)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class ConsultantMigrationMySqlContainer(imageName: String) :
    MySQLContainer<ConsultantMigrationMySqlContainer>(imageName)
