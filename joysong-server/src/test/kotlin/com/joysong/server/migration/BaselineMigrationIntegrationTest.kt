package com.joysong.server.migration

import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BaselineMigrationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `fresh database applies B33 baseline through V37 refund evidence`() {
        val history = jdbcTemplate.query(
            """
            SELECT version, type, script
            FROM flyway_schema_history
            WHERE success = 1 AND version IS NOT NULL
            ORDER BY installed_rank
            """.trimIndent(),
        ) { rs, _ -> Triple(rs.getString("version"), rs.getString("type"), rs.getString("script")) }

        assertEquals(
            listOf(
                Triple("33", "SQL_BASELINE", "B33__current_schema.sql"),
                Triple("34", "SQL", "V34__harden_admin_account_lifecycle.sql"),
                Triple("35", "SQL", "V35__snapshot_order_pricing_policy_revision.sql"),
                Triple("36", "SQL", "V36__add_account_lifecycle_foundation.sql"),
                Triple("37", "SQL", "V37__add_refund_evidence_files.sql"),
            ),
            history,
        )
        assertEquals(
            listOf("account_deletion_requests", "user_media_assets"),
            jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('account_deletion_requests', 'user_media_assets') ORDER BY table_name",
                String::class.java,
            ),
        )
        assertEquals(
            listOf("account_state", "erased_at", "erased_email_digest", "erased_phone_digest"),
            jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name IN ('account_state', 'erased_at', 'erased_phone_digest', 'erased_email_digest') ORDER BY column_name",
                String::class.java,
            ),
        )
        assertEquals(
            listOf("file_id"),
            jdbcTemplate.queryForList(
                """
                SELECT column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND table_name = 'refund_evidence_files'
                  AND constraint_name = 'PRIMARY'
                ORDER BY ordinal_position
                """.trimIndent(),
                String::class.java,
            ),
        )
        assertEquals(
            listOf(
                Triple("idx_refund_evidence_refund", 1, "refund_id"),
                Triple("PRIMARY", 0, "file_id"),
                Triple("uk_refund_evidence_position", 0, "refund_id"),
                Triple("uk_refund_evidence_position", 0, "position"),
            ),
            jdbcTemplate.query(
                """
                SELECT index_name, non_unique, column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = 'refund_evidence_files'
                ORDER BY index_name, seq_in_index
                """.trimIndent(),
            ) { rs, _ -> Triple(rs.getString("index_name"), rs.getInt("non_unique"), rs.getString("column_name")) },
        )
        assertEquals(
            listOf(
                listOf("fk_refund_evidence_file", "file_id", "private_files", "id"),
                listOf("fk_refund_evidence_refund", "refund_id", "refunds", "id"),
            ),
            jdbcTemplate.query(
                """
                SELECT constraint_name, column_name, referenced_table_name, referenced_column_name
                FROM information_schema.key_column_usage
                WHERE table_schema = DATABASE() AND table_name = 'refund_evidence_files'
                  AND referenced_table_name IS NOT NULL
                ORDER BY constraint_name, ordinal_position
                """.trimIndent(),
            ) { rs, _ ->
                listOf(
                    rs.getString("constraint_name"),
                    rs.getString("column_name"),
                    rs.getString("referenced_table_name"),
                    rs.getString("referenced_column_name"),
                )
            },
        )
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class IsolatedBaselineMySqlContainer(imageName: String) :
    MySQLContainer<IsolatedBaselineMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
