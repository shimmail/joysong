package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.support.WorktreeTestDatabase
import com.joysong.server.user.repository.AdminAccountGuardRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
        "admin.bootstrap.phone=13800000000",
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    AdminAccountCommandService::class,
    AdminAccountGuardRepository::class,
    RefreshTokenService::class,
    AdminAccountCommandServiceMySqlIntegrationTest.TestConfig::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminAccountCommandServiceMySqlIntegrationTest {

    @Autowired
    private lateinit var service: AdminAccountCommandService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var guardRepository: AdminAccountGuardRepository

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun seedAdministrators() {
        dropFailureTriggers()
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id IN (?, ?)", ADMIN_A, ADMIN_B)
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", ADMIN_A, ADMIN_B)
        insertAdministrator(ADMIN_A, "13900000001")
        insertAdministrator(ADMIN_B, "13900000002")
        insertRefreshToken("token-a", ADMIN_A, "a".repeat(64))
        insertRefreshToken("token-b", ADMIN_B, "b".repeat(64))
    }

    @AfterEach
    fun cleanUp() {
        dropFailureTriggers()
        jdbcTemplate.update("DELETE FROM refresh_tokens WHERE user_id IN (?, ?)", ADMIN_A, ADMIN_B)
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", ADMIN_A, ADMIN_B)
    }

    @Test
    fun `two administrators concurrently demoting each other allow exactly one commit`() {
        val results = runWithObservedGuardSerialization(
            { service.updateRole(ADMIN_A, "USER") ?: error("admin A missing") },
            { service.updateRole(ADMIN_B, "USER") ?: error("admin B missing") },
        )

        assertOneSuccessfulMutationAndOneAvailableAdministrator(results)
    }

    @Test
    fun `two administrators concurrently deactivating each other allow exactly one commit`() {
        val results = runWithObservedGuardSerialization(
            { check(service.deactivate(ADMIN_A).first) },
            { check(service.deactivate(ADMIN_B).first) },
        )

        assertOneSuccessfulMutationAndOneAvailableAdministrator(results)
    }

    @Test
    fun `concurrent demotion and deactivation allow exactly one commit`() {
        val results = runWithObservedGuardSerialization(
            { service.updateRole(ADMIN_A, "USER") ?: error("admin A missing") },
            { check(service.deactivate(ADMIN_B).first) },
        )

        assertOneSuccessfulMutationAndOneAvailableAdministrator(results)
    }

    @Test
    fun `outer repeatable-read snapshot cannot bypass the current administrator count`() {
        val snapshotEstablished = CountDownLatch(1)
        val continueSecondTransaction = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val secondResult = executor.submit<Result<Unit>> {
            runCatching {
                newTransaction().executeWithoutResult {
                    assertEquals(
                        "REPEATABLE-READ",
                        jdbcTemplate.queryForObject("SELECT @@transaction_isolation", String::class.java),
                    )
                    jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long::class.java)
                    snapshotEstablished.countDown()
                    check(continueSecondTransaction.await(10, TimeUnit.SECONDS)) {
                        "旧快照事务未被放行"
                    }
                    service.updateRole(ADMIN_B, "USER") ?: error("admin B missing")
                }
            }
        }

        try {
            assertTrue(snapshotEstablished.await(10, TimeUnit.SECONDS), "旧快照事务未能建立读视图")
            service.updateRole(ADMIN_A, "USER") ?: error("admin A missing")
            continueSecondTransaction.countDown()

            val result = secondResult.get(30, TimeUnit.SECONDS)
            assertTrue(result.exceptionOrNull() is IllegalArgumentException)
            assertEquals(1L, availableAdministratorCount())
        } finally {
            continueSecondTransaction.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `refresh token revocation failure leaves administrator role unchanged`() {
        jdbcTemplate.execute(
            """
            CREATE TRIGGER test_fail_refresh_revoke
            BEFORE UPDATE ON refresh_tokens
            FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced refresh revoke failure'
            """.trimIndent()
        )

        assertThrows(DataAccessException::class.java) {
            service.updateRole(ADMIN_A, "USER")
        }

        assertEquals("ADMIN", roleOf(ADMIN_A))
        assertEquals(0L, revokedTokenCount())
    }

    @Test
    fun `administrator deactivation write failure rolls back refresh token revocation`() {
        jdbcTemplate.execute(
            """
            CREATE TRIGGER test_fail_admin_state_write
            BEFORE UPDATE ON users
            FOR EACH ROW
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced admin state write failure'
            """.trimIndent()
        )

        assertThrows(DataAccessException::class.java) {
            service.deactivate(ADMIN_A)
        }

        assertEquals("ADMIN", roleOf(ADMIN_A))
        assertEquals(1L, activeUserCount(ADMIN_A))
        assertEquals(0L, revokedTokenCount())
    }

    private fun runWithObservedGuardSerialization(
        first: () -> Any?,
        second: () -> Any?,
    ): List<Result<Unit>> {
        val firstHasGuard = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondTransactionStarted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val firstResult = executor.submit<Result<Unit>> {
                runCatching {
                    newTransaction().executeWithoutResult {
                        check(TransactionSynchronizationManager.isActualTransactionActive())
                        guardRepository.lock()
                        firstHasGuard.countDown()
                        check(releaseFirst.await(10, TimeUnit.SECONDS)) { "首个管理员事务未被放行" }
                        first()
                    }
                }
            }
            assertTrue(firstHasGuard.await(10, TimeUnit.SECONDS), "首个管理员事务未能持有生命周期锁")

            val secondResult = executor.submit<Result<Unit>> {
                runCatching {
                    newTransaction().executeWithoutResult {
                        check(TransactionSynchronizationManager.isActualTransactionActive())
                        secondTransactionStarted.countDown()
                        second()
                    }
                }
            }
            assertTrue(secondTransactionStarted.await(10, TimeUnit.SECONDS), "第二个管理员事务未能启动")
            try {
                assertThrows(TimeoutException::class.java) {
                    secondResult.get(2, TimeUnit.SECONDS)
                }
            } finally {
                releaseFirst.countDown()
            }

            listOf(
                firstResult.get(30, TimeUnit.SECONDS),
                secondResult.get(30, TimeUnit.SECONDS),
            )
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }

    private fun newTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private fun assertOneSuccessfulMutationAndOneAvailableAdministrator(results: List<Result<Unit>>) {
        assertEquals(1, results.count(Result<Unit>::isSuccess))
        assertEquals(1, results.count(Result<Unit>::isFailure))
        assertTrue(results.single(Result<Unit>::isFailure).exceptionOrNull() is IllegalArgumentException)
        assertEquals(1L, availableAdministratorCount())
        assertEquals(1L, revokedTokenCount())
    }

    private fun insertAdministrator(id: String, phone: String) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, phone, password_hash, nickname, role)
            VALUES (?, ?, 'integration-password-hash', 'Integration Admin', 'ADMIN')
            """.trimIndent(),
            id,
            phone,
        )
    }

    private fun insertRefreshToken(id: String, userId: String, tokenHash: String) {
        jdbcTemplate.update(
            """
            INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at)
            VALUES (?, ?, ?, DATE_ADD(NOW(), INTERVAL 1 DAY))
            """.trimIndent(),
            id,
            userId,
            tokenHash,
        )
    }

    private fun roleOf(id: String): String = jdbcTemplate.queryForObject(
        "SELECT role FROM users WHERE id = ?",
        String::class.java,
        id,
    )

    private fun activeUserCount(id: String): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NULL",
        Long::class.java,
        id,
    )

    private fun availableAdministratorCount(): Long = jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*) FROM users
        WHERE role = 'ADMIN'
          AND deleted_at IS NULL
          AND phone REGEXP '^1[0-9]{10}$'
          AND TRIM(password_hash) <> ''
        """.trimIndent(),
        Long::class.java,
    ) ?: 0L

    private fun revokedTokenCount(): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM refresh_tokens WHERE user_id IN (?, ?) AND revoked_at IS NOT NULL",
        Long::class.java,
        ADMIN_A,
        ADMIN_B,
    )

    private fun dropFailureTriggers() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_refresh_revoke")
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_fail_admin_state_write")
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = io.mockk.mockk(relaxed = true)

        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
    }

    companion object {
        private const val ADMIN_A = "lifecycle-admin-a"
        private const val ADMIN_B = "lifecycle-admin-b"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = AdminLifecycleMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withCommand("--log-bin-trust-function-creators=1")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class AdminLifecycleMySqlContainer(imageName: String) :
    MySQLContainer<AdminLifecycleMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
