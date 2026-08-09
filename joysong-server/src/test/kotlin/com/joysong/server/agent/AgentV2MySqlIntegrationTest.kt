package com.joysong.server.agent

import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatSessionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Paths
import java.util.UUID

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

    @Autowired
    private lateinit var sessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var turnRepository: AgentTurnRepository

    @Test
    fun `empty database migrates to isolated agent v2 schema`() {
        assertEquals("myapp_worktree_ai_agent_architecture_refactor", mysql.databaseName)
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

    @Test
    fun `turn uniqueness and running guard enforce the agent v2 turn contract`() {
        val sessionId = createSession()
        insertTurn(sessionId, 1, "first", "SUCCEEDED")

        assertConstraintViolation { insertTurn(sessionId, 1, "second", "FAILED") }
        assertConstraintViolation { insertTurn(sessionId, 2, "first", "FAILED") }

        insertTurn(sessionId, 2, "running-one", "RUNNING")
        assertConstraintViolation { insertTurn(sessionId, 3, "running-two", "RUNNING") }
        insertTurn(sessionId, 3, "completed-after-running", "FAILED")
    }

    @Test
    fun `message constraints and json values are persisted through agent v2 mappings`() {
        val sessionId = createSession("{\"goal\":\"glow\"}")
        val turnId = UUID.randomUUID().toString()
        turnRepository.saveAndFlush(
            AgentTurnEntity(
                id = turnId,
                sessionId = sessionId,
                sequenceNo = 1,
                idempotencyKey = "json-turn",
                requestHash = "a".repeat(64),
                status = AgentTurnStatus.SUCCEEDED,
                traceId = UUID.randomUUID().toString()
            )
        )
        val persistedSummary = sessionRepository.findById(sessionId).orElseThrow().summaryJson
        assertTrue(persistedSummary.contains("\"goal\"") && persistedSummary.contains("\"glow\""))
        assertEquals("\"glow\"", jdbcTemplate.queryForObject(
            "SELECT JSON_EXTRACT(summary_json, '$.goal') FROM agent_sessions WHERE id = ?",
            String::class.java,
            sessionId
        ))

        insertMessage(sessionId, turnId, 1, "TEXT")
        assertConstraintViolation { insertMessage(sessionId, turnId, 1, "TEXT") }
        assertConstraintViolation { insertMessage(sessionId, turnId, 2, "VIDEO") }
    }

    @Test
    fun `agent foreign keys stay inside the agent schema and message sequence needs no duplicate index`() {
        val foreignKeys = jdbcTemplate.queryForList(
            """
            SELECT referenced_table_name FROM information_schema.key_column_usage
            WHERE table_schema = DATABASE()
              AND table_name LIKE 'agent_%'
              AND referenced_table_name IS NOT NULL
            """.trimIndent(),
            String::class.java
        )
        assertTrue(foreignKeys.all { it.startsWith("agent_") })
        assertTrue(
            jdbcTemplate.queryForList(
                "SHOW INDEX FROM agent_messages WHERE Key_name = 'idx_agent_message_session_sequence'"
            ).isEmpty(),
            "the session/sequence unique key already supplies this index prefix"
        )
    }

    private fun createSession(summaryJson: String = "{}") = UUID.randomUUID().toString().also { sessionId ->
        sessionRepository.saveAndFlush(
            ChatSessionEntity(
                id = sessionId,
                userId = "test-user",
                persona = "CONSULTANT",
                contextType = "GENERAL",
                summaryJson = summaryJson
            )
        )
    }

    private fun insertTurn(sessionId: String, sequenceNo: Long, idempotencyKey: String, status: String) {
        jdbcTemplate.update(
            """
            INSERT INTO agent_turns (
                id, session_id, sequence_no, idempotency_key, request_hash, status, trace_id,
                started_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """.trimIndent(),
            UUID.randomUUID().toString(), sessionId, sequenceNo, idempotencyKey, "b".repeat(64), status,
            UUID.randomUUID().toString()
        )
    }

    private fun insertMessage(sessionId: String, turnId: String, sequenceNo: Long, contentType: String) {
        jdbcTemplate.update(
            """
            INSERT INTO agent_messages (
                id, session_id, turn_id, sequence_no, role, content_type, content, metadata_json, created_at
            ) VALUES (?, ?, ?, ?, 'ASSISTANT', ?, 'message', JSON_OBJECT(), CURRENT_TIMESTAMP(6))
            """.trimIndent(),
            UUID.randomUUID().toString(), sessionId, turnId, sequenceNo, contentType
        )
    }

    private fun assertConstraintViolation(block: () -> Unit) {
        assertThrows(DataAccessException::class.java, block)
    }

    companion object {
        private val DATABASE_NAME = worktreeDatabaseName()

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

        private fun worktreeDatabaseName(): String {
            val worktreeName = Paths.get(System.getProperty("user.dir")).parent.fileName.toString()
            return "myapp_worktree_${worktreeName.replace(Regex("[^A-Za-z0-9]+"), "_")}".lowercase()
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
