package com.joysong.server.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.TurnLifecycleService
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
@Import(TurnLifecycleService::class, AgentContextBuilder::class, AgentLifecycleTestConfig::class)
class AgentV2MySqlIntegrationTest {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var sessionRepository: ChatSessionRepository

    @Autowired
    private lateinit var turnRepository: AgentTurnRepository

    @Autowired
    private lateinit var lifecycle: TurnLifecycleService

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

    @Test
    fun `concurrent workers create at most one running turn for a session`() {
        val sessionId = createSession()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf("concurrent-1", "concurrent-2").map { key ->
                executor.submit<BeginTurnResult> { lifecycle.beginTurn(sessionId, "test-user", "hello", key) }
            }.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, results.count { it is BeginTurnResult.Started })
            assertEquals(1, results.count { it is BeginTurnResult.InProgress })
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turns WHERE session_id = ? AND status = 'RUNNING'", Int::class.java, sessionId
            ))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `completed turns allocate monotonic sequences and replay without duplicate messages`() {
        val sessionId = createSession()
        val first = lifecycle.beginTurn(sessionId, "test-user", "first", "stable-key") as BeginTurnResult.Started
        lifecycle.completeTurn(CompleteTurnCommand(first.turnId, "first answer", "GENERAL_CHAT", null, "NONE"))
        val replay = lifecycle.beginTurn(sessionId, "test-user", "first", "stable-key")
        val second = lifecycle.beginTurn(sessionId, "test-user", "second", "second-key") as BeginTurnResult.Started
        lifecycle.completeTurn(CompleteTurnCommand(second.turnId, "second answer", "GENERAL_CHAT", null, "NONE"))

        assertTrue(replay is BeginTurnResult.Replayed)
        assertEquals(listOf(1L, 2L), jdbcTemplate.queryForList(
            "SELECT sequence_no FROM agent_turns WHERE session_id = ? ORDER BY sequence_no", Long::class.java, sessionId
        ))
        assertEquals(4, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId
        ))
        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agent_turns WHERE session_id = ? AND status = 'RUNNING'", Int::class.java, sessionId
        ))
    }

    @Test
    fun `terminal lifecycle operations leave no running turn`() {
        val sessionId = createSession()
        val failed = lifecycle.beginTurn(sessionId, "test-user", "will fail", "fail-key") as BeginTurnResult.Started
        lifecycle.failTurn(failed.turnId, "MODEL_TIMEOUT", 10)
        val cancelled = lifecycle.beginTurn(sessionId, "test-user", "will cancel", "cancel-key") as BeginTurnResult.Started
        lifecycle.cancelTurn(cancelled.turnId, "CLIENT_CANCELLED", 10)

        assertEquals(0, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agent_turns WHERE session_id = ? AND status = 'RUNNING'", Int::class.java, sessionId
        ))
    }

    @Test
    fun `completed turn pruning persists no more than twenty messages including the current turn`() {
        val sessionId = createSession()
        repeat(11) { index ->
            val started = lifecycle.beginTurn(sessionId, "test-user", "message-$index", "retention-$index") as BeginTurnResult.Started
            lifecycle.completeTurn(CompleteTurnCommand(started.turnId, "answer-$index", "GENERAL_CHAT", null, "NONE"))
        }

        assertEquals(20, jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId
        ))
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

@TestConfiguration(proxyBeanMethods = false)
class AgentLifecycleTestConfig {
    @Bean
    fun objectMapper(): ObjectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
}

class ReportingMySqlContainer(imageName: String) :
    MySQLContainer<ReportingMySqlContainer>(imageName) {

    override fun start() {
        super.start()
        println("AGENT_TEST_DB_HOST=$host")
        println("AGENT_TEST_DB_NAME=$databaseName")
    }
}
