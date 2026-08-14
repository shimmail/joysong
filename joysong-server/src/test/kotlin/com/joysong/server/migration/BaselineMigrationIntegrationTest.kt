package com.joysong.server.migration

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

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

    @Test
    fun `fresh database applies only B26 as SQL baseline`() {
        val history = jdbcTemplate.query(
            """
            SELECT version, type, script
            FROM flyway_schema_history
            WHERE success = 1 AND version IS NOT NULL
            ORDER BY installed_rank
            """.trimIndent()
        ) { rs, _ -> Triple(rs.getString("version"), rs.getString("type"), rs.getString("script")) }

        assertEquals(listOf(Triple("26", "SQL_BASELINE", "B26__current_schema.sql")), history)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
            .withDatabaseName("myapp_worktree_joysong_b26_test")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class IsolatedBaselineMySqlContainer(imageName: String) :
    MySQLContainer<IsolatedBaselineMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        println("Resolved database host: $host:$firstMappedPort")
        println("Resolved database name: $databaseName")
    }
}
