package com.joysong.server.migration

import com.joysong.server.support.LegacyMigrationTestResources
import com.joysong.server.support.WorktreeTestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Path

@Tag("mysql-integration")
@Testcontainers
@JdbcTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.sql.init.mode=never"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BaselineMigrationIntegrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @TempDir
    lateinit var legacyMigrationDirectory: Path

    @Test
    fun `fresh database applies B33 baseline and V34 admin guard`() {
        val history = jdbcTemplate.query(
            """
            SELECT version, type, script
            FROM flyway_schema_history
            WHERE success = 1 AND version IS NOT NULL
            ORDER BY installed_rank
            """.trimIndent()
        ) { rs, _ -> Triple(rs.getString("version"), rs.getString("type"), rs.getString("script")) }

        assertEquals(
            listOf(
                Triple("33", "SQL_BASELINE", "B33__current_schema.sql"),
                Triple("34", "SQL", "V34__harden_admin_account_lifecycle.sql"),
            ),
            history
        )
        assertEquals(
            listOf("ACTIVE_ADMIN"),
            jdbcTemplate.queryForList(
                "SELECT guard_key FROM admin_account_guard ORDER BY guard_key",
                String::class.java,
            )
        )
    }

    @Test
    fun `B33 plus V34 schema matches legacy migrations through V34`() {
        WorktreeTestDatabase.validateAndPrint(legacyMysql)
        val legacyJdbc = JdbcTemplate(
            DriverManagerDataSource(legacyMysql.jdbcUrl, legacyMysql.username, legacyMysql.password)
        )

        val legacyMigrationLocation = LegacyMigrationTestResources.prepare(legacyMigrationDirectory)
        migrateLegacy(legacyMigrationLocation, "32")
        migrateLegacy("classpath:db/migration")

        assertEquals(
            listOf("26", "27", "28", "29", "30", "31", "32", "32.1", "32.2", "33", "34"),
            legacyJdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
                String::class.java
            )
        )
        assertEquals(schemaSnapshot(legacyJdbc), schemaSnapshot(jdbcTemplate))
    }

    private fun migrateLegacy(location: String, target: String? = null) {
        val configuration = Flyway.configure()
            .dataSource(legacyMysql.jdbcUrl, legacyMysql.username, legacyMysql.password)
            .locations(location)
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
        if (target != null) configuration.target(target)
        configuration.load().migrate()
    }

    private data class SchemaSnapshot(
        val tables: List<List<String?>>,
        val columns: List<List<String?>>,
        val indexes: List<List<String?>>,
        val constraints: List<List<String?>>,
        val foreignKeys: List<List<String?>>,
        val checks: List<List<String?>>,
    )

    private fun schemaSnapshot(jdbc: JdbcTemplate): SchemaSnapshot = SchemaSnapshot(
        tables = canonicalRows(
            jdbc,
            """
            SELECT table_name, table_type, engine, row_format, table_collation, create_options, table_comment
            FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY BINARY table_name
            """.trimIndent()
        ),
        columns = canonicalRows(
            jdbc,
            """
            SELECT table_name, ordinal_position, column_name, column_type, is_nullable,
                   column_default, extra, character_set_name, collation_name,
                   numeric_precision, numeric_scale, datetime_precision, generation_expression, column_comment
            FROM information_schema.columns
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY BINARY table_name, ordinal_position
            """.trimIndent()
        ),
        indexes = canonicalRows(
            jdbc,
            """
            SELECT table_name, index_name, non_unique, seq_in_index, column_name, collation,
                   sub_part, nullable, index_type, expression, is_visible
            FROM information_schema.statistics
            WHERE table_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY BINARY table_name, BINARY index_name, seq_in_index
            """.trimIndent()
        ),
        constraints = canonicalRows(
            jdbc,
            """
            SELECT table_name, constraint_name, constraint_type, enforced
            FROM information_schema.table_constraints
            WHERE constraint_schema = DATABASE() AND table_name <> 'flyway_schema_history'
            ORDER BY BINARY table_name, BINARY constraint_name
            """.trimIndent()
        ),
        foreignKeys = canonicalRows(
            jdbc,
            """
            SELECT kcu.table_name, kcu.constraint_name, kcu.ordinal_position, kcu.column_name,
                   kcu.referenced_table_name, kcu.referenced_column_name,
                   rc.update_rule, rc.delete_rule
            FROM information_schema.key_column_usage kcu
            JOIN information_schema.referential_constraints rc
              ON rc.constraint_schema = kcu.constraint_schema
             AND rc.constraint_name = kcu.constraint_name
             AND rc.table_name = kcu.table_name
            WHERE kcu.constraint_schema = DATABASE()
              AND kcu.table_name <> 'flyway_schema_history'
              AND kcu.referenced_table_name IS NOT NULL
            ORDER BY BINARY kcu.table_name, BINARY kcu.constraint_name, kcu.ordinal_position
            """.trimIndent()
        ),
        checks = canonicalRows(
            jdbc,
            """
            SELECT tc.table_name, tc.constraint_name, cc.check_clause, tc.enforced
            FROM information_schema.table_constraints tc
            JOIN information_schema.check_constraints cc
              ON cc.constraint_schema = tc.constraint_schema
             AND cc.constraint_name = tc.constraint_name
            WHERE tc.constraint_schema = DATABASE()
              AND tc.table_name <> 'flyway_schema_history'
              AND tc.constraint_type = 'CHECK'
            ORDER BY BINARY tc.table_name, BINARY tc.constraint_name
            """.trimIndent()
        ),
    )

    private fun canonicalRows(jdbc: JdbcTemplate, sql: String): List<List<String?>> =
        jdbc.query(sql) { rs, _ ->
            (1..rs.metaData.columnCount).map { column -> rs.getString(column) }
        }

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @Container
        @JvmField
        val legacyMysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
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
