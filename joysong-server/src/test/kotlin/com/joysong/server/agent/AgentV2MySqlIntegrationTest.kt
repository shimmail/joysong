package com.joysong.server.agent

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.joysong.server.agent.context.AgentContextBuilder
import com.joysong.server.agent.dto.AgentCatalogItemResponse
import com.joysong.server.agent.dto.AgentCatalogReportResponse
import com.joysong.server.agent.entity.AgentTurnEntity
import com.joysong.server.agent.entity.AgentTurnStatus
import com.joysong.server.agent.orchestration.BeginTurnResult
import com.joysong.server.agent.orchestration.CompleteTurnCommand
import com.joysong.server.agent.orchestration.TurnLifecycleService
import com.joysong.server.agent.repository.AgentTurnRepository
import com.joysong.server.chat.entity.ChatSessionEntity
import com.joysong.server.chat.repository.ChatSessionRepository
import org.flywaydb.core.Flyway
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
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Paths
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
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

    @Autowired
    private lateinit var context: AgentContextBuilder

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `empty database migrates to isolated agent v2 schema`() {
        assertEquals(DATABASE_NAME, mysql.databaseName)
        val history = jdbcTemplate.queryForList(
            "SELECT version FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
            String::class.java
        )
        assertEquals((1..15).map(Int::toString), history)

        assertEquals(1, leaseColumnCount(jdbcTemplate))

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
    fun `already V10 agent turns schema upgrades with the turn lease column`() {
        val upgradeFlyway = Flyway.configure()
            .dataSource(upgradeMysql.jdbcUrl, upgradeMysql.username, upgradeMysql.password)
            .locations("classpath:db/migration")
            .target("10")
            .load()
        upgradeFlyway.migrate()

        val upgradeJdbcTemplate = JdbcTemplate(
            DriverManagerDataSource(upgradeMysql.jdbcUrl, upgradeMysql.username, upgradeMysql.password)
        )
        assertEquals(0, leaseColumnCount(upgradeJdbcTemplate))
        val legacySessionId = UUID.randomUUID().toString()
        val legacyTurnId = UUID.randomUUID().toString()
        upgradeJdbcTemplate.update(
            """
            INSERT INTO agent_sessions (
                id, user_id, persona, context_type, next_sequence_no, summary_json, created_at, updated_at
            ) VALUES (?, 'legacy-user', 'CONSULTANT', 'GENERAL', 2, JSON_OBJECT(), CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """.trimIndent(),
            legacySessionId
        )
        upgradeJdbcTemplate.update(
            """
            INSERT INTO agent_turns (
                id, session_id, sequence_no, idempotency_key, request_hash, status, trace_id,
                started_at, created_at
            ) VALUES (?, ?, 1, 'legacy-running', ?, 'RUNNING', ?,
                DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR), DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 1 HOUR))
            """.trimIndent(),
            legacyTurnId,
            legacySessionId,
            "a".repeat(64),
            UUID.randomUUID().toString()
        )

        Flyway.configure()
            .dataSource(upgradeMysql.jdbcUrl, upgradeMysql.username, upgradeMysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()

        assertEquals(1, leaseColumnCount(upgradeJdbcTemplate))
        assertEquals(
            1,
            upgradeJdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM agent_turns
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND lease_expires_at IS NOT NULL
                  AND lease_expires_at <= CURRENT_TIMESTAMP(6)
                """.trimIndent(),
                Int::class.java,
                legacyTurnId
            )
        )
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
    fun `workers wait on an explicit session lock before serialized starts create one turn`() {
        val sessionId = createSession()
        val executor = Executors.newFixedThreadPool(2)
        val lockExecutor = Executors.newSingleThreadExecutor()
        val sessionLocked = CountDownLatch(1)
        val releaseSession = CountDownLatch(1)
        try {
            val holder = lockExecutor.submit<Unit> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.queryForObject("SELECT id FROM agent_sessions WHERE id = ? FOR UPDATE", String::class.java, sessionId)
                    sessionLocked.countDown()
                    check(releaseSession.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(sessionLocked.await(10, TimeUnit.SECONDS))
            val results = listOf("concurrent-1", "concurrent-2").map { key ->
                executor.submit<BeginTurnResult> {
                    lifecycle.beginTurn(sessionId, "test-user", "hello", key)
                }
            }
            assertTrue(awaitSessionLockWaiters(2), "both workers must be blocked in MySQL on the held session row")
            releaseSession.countDown()
            holder.get(10, TimeUnit.SECONDS)
            val completed = results.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it is BeginTurnResult.Started })
            assertEquals(1, completed.count { it is BeginTurnResult.InProgress })
            val started = completed.filterIsInstance<BeginTurnResult.Started>().single()
            lifecycle.completeTurn(CompleteTurnCommand(started.turnId, "concurrent answer", "GENERAL_CHAT", null, "NONE"))
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_turns WHERE session_id = ?", Int::class.java, sessionId))
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ? AND role = 'USER'", Int::class.java, sessionId))
            assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ? AND role = 'ASSISTANT'", Int::class.java, sessionId))
            assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(DISTINCT sequence_no) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId))
            assertEquals(2L, jdbcTemplate.queryForObject("SELECT next_sequence_no FROM agent_sessions WHERE id = ?", Long::class.java, sessionId))
            assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turns WHERE session_id = ? AND status = 'RUNNING'", Int::class.java, sessionId
            ))
        } finally {
            releaseSession.countDown()
            executor.shutdownNow()
            lockExecutor.shutdownNow()
        }
    }

    @Test
    fun `completed turns allocate monotonic sequences and replay without duplicate messages`() {
        val sessionId = createSession()
        val item = AgentCatalogItemResponse(
            type = "PROJECT", id = UUID.randomUUID().toString(), name = "光子嫩肤", subtitle = "皮肤", summary = "改善暗沉",
            attributes = mapOf("价格" to "1000"), projectId = UUID.randomUUID().toString()
        )
        val report = AgentCatalogReportResponse(
            mode = "PROJECT", title = "项目结果", summary = "对比摘要", items = listOf(item),
            comparisonDimensions = listOf("价格"), warnings = listOf("需面诊")
        )
        val first = lifecycle.beginTurn(sessionId, "test-user", "first", "stable-key") as BeginTurnResult.Started
        lifecycle.completeTurn(CompleteTurnCommand(first.turnId, "first answer", "CATALOG_QA", "PROJECT", "SHOW_CATALOG", listOf(item), report))
        val replay = lifecycle.beginTurn(sessionId, "test-user", "first", "stable-key")
        val second = lifecycle.beginTurn(sessionId, "test-user", "second", "second-key") as BeginTurnResult.Started
        lifecycle.completeTurn(CompleteTurnCommand(second.turnId, "second answer", "GENERAL_CHAT", null, "NONE"))

        assertTrue(replay is BeginTurnResult.Replayed)
        val replayed = (replay as BeginTurnResult.Replayed).turn
        assertEquals(item, replayed.catalogItems.single())
        assertEquals(report, replayed.catalogReport)
        assertEquals("CATALOG_QA", replayed.intent)
        assertEquals("PROJECT", replayed.queryTarget)
        assertEquals("SHOW_CATALOG", replayed.nextAction)
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
    fun `planning completion projects catalog response metadata summary and replay to safe fields`() {
        val sessionId = createSession()
        val institutionId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val item = AgentCatalogItemResponse(
            type = "INSTITUTION_PROJECT",
            id = UUID.randomUUID().toString(),
            name = "光子嫩肤",
            subtitle = "恢复期1天 subtitle-marker",
            summary = "无痛零风险 description-marker",
            attributes = linkedMapOf(
                "机构价格" to "\$888",
                "评分" to "4.8",
                "评分说明" to "零风险 rating-marker",
                "宣传语" to "无痛 slogan-marker",
                "详情摘要" to "恢复期1天 detail-marker"
            ),
            institutionId = institutionId,
            projectId = projectId,
            canChatWithHuman = true
        )
        val report = AgentCatalogReportResponse(
            mode = "SUMMARY",
            title = "零风险 title-marker",
            summary = "无痛 report-marker",
            items = listOf(item),
            comparisonDimensions = listOf("机构价格", "恢复期1天 dimension-marker"),
            warnings = listOf("零风险 warning-marker")
        )
        val started = lifecycle.beginTurn(sessionId, "test-user", "planning", "planning-safe-key") as BeginTurnResult.Started

        val completed = lifecycle.completeTurn(
            CompleteTurnCommand(
                started.turnId,
                "safe planning answer",
                "PLANNING",
                "PROJECT",
                "SHOW_CATALOG",
                listOf(item),
                report
            )
        )
        val replay = lifecycle.beginTurn(sessionId, "test-user", "planning", "planning-safe-key") as BeginTurnResult.Replayed

        val projectedItem = completed.catalogItems.single()
        assertEquals(item.id, projectedItem.id)
        assertEquals(item.name, projectedItem.name)
        assertEquals(institutionId, projectedItem.institutionId)
        assertEquals(projectId, projectedItem.projectId)
        assertTrue(projectedItem.canChatWithHuman)
        assertEquals("", projectedItem.subtitle)
        assertEquals("", projectedItem.summary)
        assertEquals(mapOf("机构价格" to "\$888", "评分" to "4.8"), projectedItem.attributes)
        assertEquals(projectedItem, completed.catalogReport?.items?.single())
        assertTrue(completed.catalogReport?.comparisonDimensions.orEmpty().isEmpty())

        val persistedMetadata = jdbcTemplate.queryForObject(
            "SELECT metadata_json FROM agent_messages WHERE turn_id = ? AND role = 'ASSISTANT'",
            String::class.java,
            started.turnId
        ).orEmpty()
        val persistedSummary = jdbcTemplate.queryForObject(
            "SELECT summary_json FROM agent_sessions WHERE id = ?",
            String::class.java,
            sessionId
        ).orEmpty()
        val visiblePayloads = listOf(
            objectMapper.writeValueAsString(
                mapOf("catalogItems" to completed.catalogItems, "catalogReport" to completed.catalogReport)
            ),
            persistedMetadata,
            persistedSummary,
            objectMapper.writeValueAsString(
                mapOf("catalogItems" to replay.turn.catalogItems, "catalogReport" to replay.turn.catalogReport)
            )
        )
        listOf(
            "恢复期1天", "无痛", "零风险", "description-marker", "rating-marker",
            "slogan-marker", "detail-marker", "title-marker", "report-marker", "dimension-marker", "warning-marker"
        ).forEach { forbidden ->
            assertTrue(visiblePayloads.none { it.contains(forbidden) }, "must not expose $forbidden")
        }
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

    @Test
    fun `loading inactive session physically removes expired succeeded messages`() {
        val sessionId = createSession()
        val oldTurn = insertTurn(sessionId, 1, "old-success", "SUCCEEDED")
        insertMessage(sessionId, oldTurn, 1, "TEXT")
        jdbcTemplate.update("UPDATE agent_messages SET created_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 8 DAY) WHERE session_id = ?", sessionId)

        assertTrue(context.load("test-user", sessionId, 20, 1_000).messages.isEmpty())
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId))
    }

    @Test
    fun `retention physically removes expired failed and cancelled messages and caps all session rows`() {
        val sessionId = createSession()
        val failed = insertTurn(sessionId, 1, "expired-failed", "FAILED")
        val cancelled = insertTurn(sessionId, 2, "expired-cancelled", "CANCELLED")
        insertMessage(sessionId, failed, 1, "TEXT", "failed-old", "USER")
        insertMessage(sessionId, cancelled, 3, "TEXT", "cancelled-old", "USER")
        jdbcTemplate.update("UPDATE agent_messages SET created_at = DATE_SUB(CURRENT_TIMESTAMP(6), INTERVAL 8 DAY) WHERE session_id = ?", sessionId)

        assertTrue(context.load("test-user", sessionId, 20, 1_000).messages.isEmpty())
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId))

        repeat(21) { index ->
            val failedTurn = insertTurn(sessionId, (index + 3).toLong(), "failed-$index", "FAILED")
            insertMessage(sessionId, failedTurn, (index + 5).toLong(), "TEXT", "failed-$index", "USER")
        }
        context.load("test-user", sessionId, 20, 1_000)
        assertEquals(20, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId))
    }

    @Test
    fun `session lock makes completion observe a committed cancellation instead of overwriting it`() {
        val sessionId = createSession()
        val started = lifecycle.beginTurn(sessionId, "test-user", "race", "race-key") as BeginTurnResult.Started
        val sessionLocked = CountDownLatch(1)
        val releaseSession = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val completionExecutor = Executors.newSingleThreadExecutor()
        try {
            val holder = executor.submit<Unit> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.queryForObject("SELECT id FROM agent_sessions WHERE id = ? FOR UPDATE", String::class.java, sessionId)
                    sessionLocked.countDown()
                    check(releaseSession.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(sessionLocked.await(10, TimeUnit.SECONDS))
            val completion = completionExecutor.submit<Throwable?> {
                runCatching { lifecycle.completeTurn(CompleteTurnCommand(started.turnId, "must not win", "GENERAL_CHAT", null, "NONE")) }.exceptionOrNull()
            }
            assertTrue(awaitSessionLockWaiters(1), "completion must be blocked in MySQL on the held session row")
            lifecycle.cancelTurn(started.turnId, "TEST_CANCELLED", 1)
            releaseSession.countDown()
            holder.get(10, TimeUnit.SECONDS)
            assertTrue(completion.get(10, TimeUnit.SECONDS) is IllegalStateException)
            assertEquals("CANCELLED", jdbcTemplate.queryForObject("SELECT status FROM agent_turns WHERE id = ?", String::class.java, started.turnId))
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE turn_id = ? AND role = 'ASSISTANT'", Int::class.java, started.turnId))
        } finally {
            releaseSession.countDown()
            executor.shutdownNow()
            completionExecutor.shutdownNow()
        }
    }

    @Test
    fun `context returns messages only from succeeded turns`() {
        val sessionId = createSession()
        val succeeded = insertTurn(sessionId, 1, "succeeded", "SUCCEEDED")
        val running = insertTurn(sessionId, 2, "running", "RUNNING")
        val failed = insertTurn(sessionId, 3, "failed", "FAILED")
        insertMessage(sessionId, succeeded, 1, "TEXT", "succeeded-message")
        insertMessage(sessionId, running, 3, "TEXT", "running-message")
        insertMessage(sessionId, failed, 5, "TEXT", "failed-message")

        assertEquals(listOf("succeeded-message"), context.load("test-user", sessionId, 20, 1_000).messages.map { it.content })
    }

    @Test
    fun `controlled completion and clear serialize through the session lock without deadlock`() {
        val sessionId = createSession()
        val started = lifecycle.beginTurn(sessionId, "test-user", "complete", "complete-key") as BeginTurnResult.Started
        val sessionLocked = CountDownLatch(1)
        val releaseSession = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val lockExecutor = Executors.newSingleThreadExecutor()
        try {
            val holder = lockExecutor.submit<Unit> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.queryForObject("SELECT id FROM agent_sessions WHERE id = ? FOR UPDATE", String::class.java, sessionId)
                    sessionLocked.countDown()
                    check(releaseSession.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(sessionLocked.await(10, TimeUnit.SECONDS))
            val completion = executor.submit<Throwable?> {
                runCatching {
                    lifecycle.completeTurn(CompleteTurnCommand(started.turnId, "done", "GENERAL_CHAT", null, "NONE"))
                }.exceptionOrNull()
            }
            assertTrue(awaitSessionLockWaiters(1), "completion must be blocked in MySQL on the held session row")
            val clearing = executor.submit<Throwable?> {
                runCatching { context.clear("test-user", sessionId) }.exceptionOrNull()
            }
            assertTrue(awaitSessionLockWaiters(2), "completion and clear must both be blocked in MySQL on the held session row")
            releaseSession.countDown()
            holder.get(10, TimeUnit.SECONDS)

            assertEquals(null, clearing.get(15, TimeUnit.SECONDS))
            assertTrue(completion.get(15, TimeUnit.SECONDS)?.let { it is IllegalArgumentException } != false)
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_sessions WHERE id = ? AND deleted_at IS NOT NULL", Int::class.java, sessionId
            ))
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_turns WHERE session_id = ?", Int::class.java, sessionId))
            assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM agent_messages WHERE session_id = ?", Int::class.java, sessionId))
        } finally {
            releaseSession.countDown()
            executor.shutdownNow()
            lockExecutor.shutdownNow()
        }
    }

    private fun awaitSessionLockWaiters(expected: Int): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline) {
            val waiters = DriverManager.getConnection(mysql.jdbcUrl, "root", mysql.password).use { connection ->
                connection.prepareStatement(
                    """
                    SELECT COUNT(DISTINCT waits.REQUESTING_ENGINE_TRANSACTION_ID)
                    FROM performance_schema.data_lock_waits waits
                    JOIN performance_schema.data_locks requested
                      ON requested.ENGINE_LOCK_ID = waits.REQUESTING_ENGINE_LOCK_ID
                    JOIN performance_schema.data_locks blocking
                      ON blocking.ENGINE_LOCK_ID = waits.BLOCKING_ENGINE_LOCK_ID
                    WHERE requested.OBJECT_SCHEMA = DATABASE()
                      AND requested.OBJECT_NAME = 'agent_sessions'
                      AND blocking.OBJECT_SCHEMA = DATABASE()
                      AND blocking.OBJECT_NAME = 'agent_sessions'
                    """.trimIndent()
                ).use { statement ->
                    statement.executeQuery().use { result ->
                        check(result.next())
                        result.getInt(1)
                    }
                }
            }
            if (waiters >= expected) return true
            Thread.sleep(25)
        }
        return false
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

    private fun insertTurn(sessionId: String, sequenceNo: Long, idempotencyKey: String, status: String): String {
        val turnId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO agent_turns (
                id, session_id, sequence_no, idempotency_key, request_hash, status, trace_id,
                started_at, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
            """.trimIndent(),
            turnId, sessionId, sequenceNo, idempotencyKey, "b".repeat(64), status,
            UUID.randomUUID().toString()
        )
        return turnId
    }

    private fun insertMessage(
        sessionId: String,
        turnId: String,
        sequenceNo: Long,
        contentType: String,
        content: String = "message",
        role: String = "ASSISTANT"
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO agent_messages (
                id, session_id, turn_id, sequence_no, role, content_type, content, metadata_json, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, JSON_OBJECT(), CURRENT_TIMESTAMP(6))
            """.trimIndent(),
            UUID.randomUUID().toString(), sessionId, turnId, sequenceNo, role, contentType, content
        )
    }

    private fun assertConstraintViolation(block: () -> Unit) {
        assertThrows(DataAccessException::class.java, block)
    }

    @Test
    fun `concurrent requests recover one expired turn and create one successor`() {
        val sessionId = createSession()
        val expiredTurnId = insertTurn(sessionId, 1, "expired", "RUNNING")
        jdbcTemplate.update(
            "UPDATE agent_turns SET started_at = ?, lease_expires_at = ? WHERE id = ?",
            LocalDateTime.of(2000, 1, 1, 0, 0),
            LocalDateTime.of(2000, 1, 1, 0, 1),
            expiredTurnId
        )
        jdbcTemplate.update("UPDATE agent_sessions SET next_sequence_no = 2 WHERE id = ?", sessionId)
        val executor = Executors.newFixedThreadPool(2)
        val lockExecutor = Executors.newSingleThreadExecutor()
        val sessionLocked = CountDownLatch(1)
        val releaseSession = CountDownLatch(1)
        try {
            val holder = lockExecutor.submit<Unit> {
                TransactionTemplate(transactionManager).executeWithoutResult {
                    jdbcTemplate.queryForObject("SELECT id FROM agent_sessions WHERE id = ? FOR UPDATE", String::class.java, sessionId)
                    sessionLocked.countDown()
                    check(releaseSession.await(10, TimeUnit.SECONDS))
                }
            }
            assertTrue(sessionLocked.await(10, TimeUnit.SECONDS))
            val results = listOf("successor-1", "successor-2").map { key ->
                executor.submit<BeginTurnResult> {
                    lifecycle.beginTurn(sessionId, "test-user", "hello", key)
                }
            }
            assertTrue(awaitSessionLockWaiters(2), "both recovery workers must wait on the session row")
            releaseSession.countDown()
            holder.get(10, TimeUnit.SECONDS)
            val completed = results.map { it.get(15, TimeUnit.SECONDS) }

            assertEquals(1, completed.count { it is BeginTurnResult.Started })
            assertEquals(1, completed.count { it is BeginTurnResult.InProgress })
            assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_turns WHERE session_id = ? AND status = 'RUNNING'",
                Int::class.java,
                sessionId
            ))
            assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM agent_turns WHERE id = ?",
                String::class.java,
                expiredTurnId
            ))
            assertEquals("STALE_RECOVERED", jdbcTemplate.queryForObject(
                "SELECT error_code FROM agent_turns WHERE id = ?",
                String::class.java,
                expiredTurnId
            ))
        } finally {
            releaseSession.countDown()
            executor.shutdownNow()
            lockExecutor.shutdownNow()
        }
    }

    private fun leaseColumnCount(jdbcTemplate: JdbcTemplate): Long = jdbcTemplate.queryForObject(
        """select count(*) from information_schema.columns
           where table_schema = database()
             and table_name = 'agent_turns'
             and column_name = 'lease_expires_at'""",
        Long::class.java
    )!!

    companion object {
        private val DATABASE_NAME = worktreeDatabaseName()

        @Container
        @ServiceConnection
        @JvmField
        val mysql = ReportingMySqlContainer("mysql:8.0.39")
            .withDatabaseName(DATABASE_NAME)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        @Container
        @JvmField
        val upgradeMysql = ReportingMySqlContainer("mysql:8.0.39")
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

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean("turnLease")
    fun turnLease(): Duration = Duration.ofMinutes(2)
}

class ReportingMySqlContainer(imageName: String) :
    MySQLContainer<ReportingMySqlContainer>(imageName) {

    override fun start() {
        super.start()
        println("AGENT_TEST_DB_HOST=$host")
        println("AGENT_TEST_DB_NAME=$databaseName")
    }
}
