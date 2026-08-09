package com.joysong.server.agent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
        "spring.sql.init.mode=never"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AgentV2MySqlIntegrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `empty database migrates to isolated agent v2 schema`() {
        val history = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
            String::class.java
        )
        assertEquals((1..10).map(Int::toString), history)

        val tables = jdbcTemplate.queryForList(
            """
            SELECT table_name FROM information_schema.tables
            WHERE table_schema = DATABASE() AND table_name LIKE 'agent_%'
            """.trimIndent(),
            String::class.java
        ).toSet()
        assertEquals(
            setOf(
                "agent_sessions", "agent_turns", "agent_messages",
                "agent_user_profiles", "agent_assessments", "agent_safety_events",
                "agent_plans", "agent_plan_items"
            ),
            tables
        )
        assertFalse(tables.any { it in setOf("agent_tool_audits", "agent_runs", "agent_run_steps") })
    }

    companion object {
        private const val DATABASE_NAME = "myapp_worktree_ai_agent_architecture_refactor"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = ReportingMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE_NAME)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @JvmStatic
        @DynamicPropertySource
        fun reportDatabaseBeforeFlyway(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { mysql.jdbcUrl }
            registry.add("spring.datasource.username") { mysql.username }
            registry.add("spring.datasource.password") { mysql.password }
        }
    }
}

class ReportingMySqlContainer(imageName: String) :
    MySQLContainer<ReportingMySqlContainer>(imageName) {

    override fun start() {
        super.start()
        println("AGENT_TEST_DB_HOST=$host")
        println("AGENT_TEST_DB_NAME=$databaseName")
    }
}
