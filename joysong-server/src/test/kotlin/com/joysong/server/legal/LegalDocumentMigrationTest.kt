package com.joysong.server.legal

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

@Tag("mysql-integration")
@Testcontainers
class LegalDocumentMigrationTest {

    @Test
    fun `fresh database contains legal release constraints`() {
        printAndValidateDatabase()
        Flyway.configure()
            .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        val jdbc = JdbcTemplate(DriverManagerDataSource(mysql.jdbcUrl, mysql.username, mysql.password))
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_releases'",
                Int::class.java
            )
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'legal_document_contents'",
                Int::class.java
            )
        )
    }

    private fun printAndValidateDatabase() {
        require(mysql.databaseName == expectedDatabaseName())
        require(mysql.databaseName.startsWith("myapp_worktree_"))
        println("Migration database host=${mysql.host}:${mysql.getMappedPort(3306)}, database=${mysql.databaseName}")
    }

    private fun expectedDatabaseName(): String {
        val worktree = generateSequence(currentDirectory()) { it.parent }
            .firstOrNull { Files.exists(it.resolve(".git")) }
            ?: error("Unable to find the current Git worktree from ${currentDirectory()}")
        val worktreeId = worktree.fileName.toString()
            .removePrefix("worktree_")
            .replace(Regex("[^A-Za-z0-9]+"), "_")
            .trim('_')
            .lowercase()
        return "myapp_worktree_$worktreeId"
    }

    private fun currentDirectory(): Path =
        Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()

    companion object {
        @Container
        @JvmField
        val mysql = MySqlLegalContainer("mysql:8.0.39")
            .withDatabaseName("myapp_worktree_legal_documents")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class MySqlLegalContainer(image: String) : MySQLContainer<MySqlLegalContainer>(image)
