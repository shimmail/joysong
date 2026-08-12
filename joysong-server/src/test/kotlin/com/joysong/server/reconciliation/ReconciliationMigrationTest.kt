package com.joysong.server.reconciliation

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer

@Tag("mysql-integration")
class ReconciliationMigrationTest {
    @Test
    fun `latest migration aligns reconciliation currency with JPA mapping`() {
        ReconciliationMigrationMySqlContainer("mysql:8.0.39")
            .withDatabaseName("myapp_worktree_reconciliation_migration")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
            .use { mysql ->
                mysql.start()
                Flyway.configure()
                    .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate()

                val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
                assertEquals("varchar", jdbc.queryForObject("""
                    SELECT data_type
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'reconciliation_issues'
                      AND column_name = 'currency'
                """.trimIndent(), String::class.java))
                assertEquals(3, jdbc.queryForObject("""
                    SELECT character_maximum_length
                    FROM information_schema.columns
                    WHERE table_schema = DATABASE()
                      AND table_name = 'reconciliation_issues'
                      AND column_name = 'currency'
                """.trimIndent(), Long::class.java))
            }
    }
}

private class ReconciliationMigrationMySqlContainer(imageName: String) :
    MySQLContainer<ReconciliationMigrationMySqlContainer>(imageName) {
    override fun start() {
        require(databaseName.startsWith("myapp_worktree_"))
        super.start()
        println("RECONCILIATION_MIGRATION_DB_HOST=$host:${getMappedPort(3306)}")
        println("RECONCILIATION_MIGRATION_DB_NAME=$databaseName")
    }
}
