package com.joysong.server.notification

import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Tag("mysql-integration")
@Testcontainers
class NotificationSchemaMigrationTest {

    @Test
    fun `V32_1 stores every professional application notification target`() {
        migrateTo("32")
        val jdbc = jdbc()

        assertEquals(30, columnLength(jdbc, "type"))
        assertEquals(20, columnLength(jdbc, "target_type"))
        seedLegacyNotifications(jdbc)

        migrateTo("32.1")

        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '32.1' AND success = 1",
                Int::class.java
            )
        )
        assertEquals(64, columnLength(jdbc, "type"))
        assertEquals(64, columnLength(jdbc, "target_type"))
        assertEquals("NO", columnAttribute(jdbc, "type", "is_nullable"))
        assertNull(columnAttribute(jdbc, "type", "column_default"))
        assertEquals("YES", columnAttribute(jdbc, "target_type", "is_nullable"))
        assertEquals("", columnAttribute(jdbc, "target_type", "column_default"))
        assertEquals(
            mapOf("legacy-default" to "", "legacy-null" to null),
            jdbc.query(
                "SELECT id, target_type FROM notifications WHERE id LIKE 'legacy-%' ORDER BY id"
            ) { rs, _ -> rs.getString("id") to rs.getString("target_type") }.toMap()
        )

        listOf(
            "PROFESSIONAL_APPLICATION_SUBMITTED" to "professional_doctor_review",
            "PROFESSIONAL_APPLICATION_WITHDRAWN" to "professional_consultant_review",
            "PROFESSIONAL_APPLICATION_APPROVED" to "professional_doctor_application",
            "PROFESSIONAL_APPLICATION_REJECTED" to "professional_consultant_application"
        ).forEachIndexed { index, (type, targetType) ->
            jdbc.update(
                """
                INSERT INTO notifications
                    (id, user_id, type, title, content, target_type, target_id, is_read)
                VALUES (?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent(),
                "notification-$index",
                "user-$index",
                type,
                "Professional application update",
                "Content",
                targetType,
                "request-$index"
            )
        }

        assertEquals(6, jdbc.queryForObject("SELECT COUNT(*) FROM notifications", Int::class.java))
    }

    private fun migrateTo(version: String? = null) {
        WorktreeTestDatabase.validateAndPrint(mysql)
        val configuration = Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
        if (version != null) configuration.target(version)
        configuration
            .load()
            .migrate()
    }

    private fun columnLength(jdbc: JdbcTemplate, columnName: String): Int =
        jdbc.queryForObject(
            """
            SELECT character_maximum_length
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'notifications'
              AND column_name = ?
            """.trimIndent(),
            Int::class.java,
            columnName
        )

    private fun columnAttribute(jdbc: JdbcTemplate, columnName: String, attribute: String): String? {
        require(attribute in setOf("is_nullable", "column_default"))
        return jdbc.queryForObject(
            """
            SELECT $attribute
            FROM information_schema.columns
            WHERE table_schema = DATABASE()
              AND table_name = 'notifications'
              AND column_name = ?
            """.trimIndent(),
            String::class.java,
            columnName
        )
    }

    private fun seedLegacyNotifications(jdbc: JdbcTemplate) {
        jdbc.update(
            """
            INSERT INTO notifications (id, user_id, type, title, target_id, is_read)
            VALUES ('legacy-default', 'legacy-user', 'LEGACY', 'Legacy', 'legacy-target', 0)
            """.trimIndent()
        )
        jdbc.update(
            """
            INSERT INTO notifications (id, user_id, type, title, target_type, target_id, is_read)
            VALUES ('legacy-null', 'legacy-user', 'LEGACY', 'Legacy', NULL, 'legacy-target', 0)
            """.trimIndent()
        )
    }

    private fun jdbc(): JdbcTemplate =
        JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))

    companion object {
        @Container
        @JvmField
        val mysql = NotificationSchemaMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class NotificationSchemaMySqlContainer(imageName: String) :
    MySQLContainer<NotificationSchemaMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
