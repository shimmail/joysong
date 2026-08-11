package com.joysong.server.settlement

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.nio.file.Paths

class RevenueLedgerMigrationIntegrationTest {
    @Test
    fun `isolated database names derive from active worktree directory`() {
        val fresh = databaseName("fresh")
        val duplicates = databaseName("duplicates")

        assertTrue(fresh.startsWith("myapp_worktree_"))
        assertTrue(fresh.contains("revenue_sharing"))
        assertTrue(fresh != duplicates)
    }

    @Test
    fun `fresh isolated database migrates through revenue ledger`() {
        val rootUrl = rootUrl()
        val database = databaseName("fresh")
        recreate(rootUrl, database)
        val targetUrl = databaseUrl(rootUrl, database)
        println("MYSQL_HOST=${host(rootUrl)}")
        println("MYSQL_DATABASE=$database")

        Flyway.configure().dataSource(targetUrl, USER, PASSWORD).locations("classpath:db/migration").load().migrate()

        DriverManager.getConnection(targetUrl, USER, PASSWORD).use { connection ->
            val tables = connection.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() " +
                        "AND table_name IN ('settlement_allocations','wallets','wallet_ledger_entries','reconciliation_issues')"
                ).use { result -> result.next(); result.getInt(1) }
            }
            assertEquals(4, tables)
        }
    }

    @Test
    fun `upgrade blocks historical duplicate settlements before unique index`() {
        val rootUrl = rootUrl()
        val database = databaseName("duplicates")
        recreate(rootUrl, database)
        val targetUrl = databaseUrl(rootUrl, database)
        println("MYSQL_HOST=${host(rootUrl)}")
        println("MYSQL_DATABASE=$database")
        val flyway = Flyway.configure().dataSource(targetUrl, USER, PASSWORD).locations("classpath:db/migration").target("15").load()
        flyway.migrate()
        DriverManager.getConnection(targetUrl, USER, PASSWORD).use { connection ->
            connection.createStatement().executeUpdate(
                "INSERT INTO settlements (order_id,total_amount,platform_amount,institution_amount,doctor_amount,status) " +
                    "VALUES ('duplicate-order',1,0,0,1,'PENDING'),('duplicate-order',1,0,0,1,'PENDING')"
            )
        }

        val error = runCatching {
            Flyway.configure().dataSource(targetUrl, USER, PASSWORD).locations("classpath:db/migration").load().migrate()
        }.exceptionOrNull()

        assertTrue(error?.message.orEmpty().contains("duplicate settlements.order_id", ignoreCase = true))
    }

    private fun rootUrl(): String {
        val value = System.getenv("WORKTREE_MIGRATION_DB_URL")
        assumeTrue(!value.isNullOrBlank(), "WORKTREE_MIGRATION_DB_URL is not set")
        require(Regex("^jdbc:mysql://[^/]+/mysql(?:\\?.*)?$").matches(value!!))
        return value
    }

    private fun recreate(rootUrl: String, database: String) {
        require(database.startsWith("myapp_worktree_"))
        DriverManager.getConnection(rootUrl, USER, PASSWORD).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$database`")
                statement.execute("CREATE DATABASE `$database` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci")
            }
        }
    }

    private fun databaseName(scenario: String): String {
        val current = Paths.get("").toAbsolutePath().normalize()
        val worktreeDirectory = if (current.fileName.toString() == "joysong-server") current.parent else current
        val worktreeId = worktreeDirectory.fileName.toString().lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        require(worktreeId.isNotBlank())
        return "myapp_worktree_${worktreeId}_$scenario"
    }

    private fun databaseUrl(rootUrl: String, database: String) = rootUrl.replace(Regex("/mysql(?:\\?.*)?$"), "/$database")
    private fun host(rootUrl: String) = Regex("^jdbc:mysql://([^/]+)/").find(rootUrl)!!.groupValues[1]

    private companion object {
        const val USER = "root"
        const val PASSWORD = "codex-test"
    }
}
