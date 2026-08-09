package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.Timestamp
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
@Import(AdminIdentityService::class, AdminIdentityServiceMySqlIntegrationTest.TestConfig::class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminIdentityServiceMySqlIntegrationTest {

    @Autowired
    private lateinit var service: AdminIdentityService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var transactionManager: PlatformTransactionManager

    @BeforeEach
    fun seedData() {
        insertUser(USER_ID, "13800000101", "集成测试用户", "USER")
        insertUser(ADMIN_ID, "13800000102", "集成测试管理员", "ADMIN")
        insertUser(SECOND_ADMIN_ID, "13800000103", "第二管理员", "ADMIN")
        jdbcTemplate.update(
            "INSERT INTO institutions (id, name) VALUES (?, ?)",
            INSTITUTION_ID,
            "Testcontainers 机构"
        )
    }

    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM institution_memberships WHERE user_id = ?", USER_ID)
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ?", USER_ID)
        jdbcTemplate.update("DELETE FROM institutions WHERE id = ?", INSTITUTION_ID)
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?, ?)", USER_ID, ADMIN_ID, SECOND_ADMIN_ID)
    }

    @Test
    fun `concurrent first bindings both commit one relationship`() {
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val futures = List(2) {
                executor.submit<ConcurrentBindingResult> {
                    TransactionTemplate(transactionManager).apply {
                        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    }.execute {
                        check(TransactionSynchronizationManager.isActualTransactionActive()) {
                            "并发工作线程在开始屏障释放时必须已有活动事务"
                        }
                        val connectionId = jdbcTemplate.queryForObject(
                            "SELECT CONNECTION_ID()",
                            Long::class.java
                        ) ?: error("无法读取 MySQL 连接 ID")

                        ready.countDown()
                        check(start.await(10, TimeUnit.SECONDS)) { "并发开始信号超时" }
                        ConcurrentBindingResult(
                            service.bindConsultant(USER_ID, INSTITUTION_ID, ADMIN_ID),
                            connectionId
                        )
                    }
                        ?: error("并发绑定事务未返回结果")
                }
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "两个事务未能同时就绪")
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }

            assertEquals(2, results.map { it.connectionId }.distinct().size)
            assertEquals(1, results.map { it.binding.membershipId }.distinct().size)
            assertEquals(1L, countConsultantRoles())
            assertEquals(1L, countConsultantMemberships())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `Flyway applies B1 baseline followed by production migrations V2 through V10`() {
        val history = jdbcTemplate.query(
            "SELECT version, type FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL ORDER BY installed_rank",
            { rs, _ -> FlywayMigration(rs.getString("version"), rs.getString("type")) }
        )

        assertEquals((1..10).map(Int::toString), history.map(FlywayMigration::version))
        assertEquals("SQL_BASELINE", history.single { it.version == "1" }.type)
    }

    @Test
    fun `membership foreign key failure physically rolls back role insert`() {
        assertThrows(DataIntegrityViolationException::class.java) {
            service.bindConsultant(USER_ID, INSTITUTION_ID, "missing-admin")
        }

        assertEquals(0L, countConsultantRoles())
        assertEquals(0L, countConsultantMemberships())
    }

    @Test
    fun `repeated approved binding preserves original audit fields`() {
        service.bindConsultant(USER_ID, INSTITUTION_ID, ADMIN_ID)
        val originalRoleAudit = roleAudit()
        val originalMembershipAudit = membershipAudit()

        Thread.sleep(Duration.ofSeconds(1).toMillis())
        val repeated = service.bindConsultant(USER_ID, INSTITUTION_ID, SECOND_ADMIN_ID)

        assertEquals(originalRoleAudit, roleAudit())
        assertEquals(originalMembershipAudit, membershipAudit())
        assertEquals(ADMIN_ID, originalMembershipAudit.confirmedBy)
        assertEquals("APPROVED", repeated.status)
    }

    private fun insertUser(id: String, phone: String, nickname: String, role: String) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, phone, password_hash, nickname, role)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            phone,
            "integration-test-password-hash",
            nickname,
            role
        )
    }

    private fun countConsultantRoles(): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_code = 'CONSULTANT'",
        Long::class.java,
        USER_ID
    )

    private fun countConsultantMemberships(): Long = jdbcTemplate.queryForObject(
        """
        SELECT COUNT(*) FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
        """.trimIndent(),
        Long::class.java,
        USER_ID,
        INSTITUTION_ID
    )

    private fun roleAudit(): RoleAudit = jdbcTemplate.queryForObject(
        """
        SELECT activated_at, updated_at FROM user_roles
        WHERE user_id = ? AND role_code = 'CONSULTANT'
        """.trimIndent(),
        { rs, _ -> RoleAudit(rs.getTimestamp("activated_at"), rs.getTimestamp("updated_at")) },
        USER_ID
    ) ?: error("CONSULTANT role not found")

    private fun membershipAudit(): MembershipAudit = jdbcTemplate.queryForObject(
        """
        SELECT confirmed_by, confirmed_at, updated_at FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
        """.trimIndent(),
        { rs, _ ->
            MembershipAudit(
                confirmedBy = rs.getString("confirmed_by"),
                confirmedAt = rs.getTimestamp("confirmed_at"),
                updatedAt = rs.getTimestamp("updated_at")
            )
        },
        USER_ID,
        INSTITUTION_ID
    ) ?: error("CONSULTANT membership not found")

    private data class RoleAudit(val activatedAt: Timestamp, val updatedAt: Timestamp)
    private data class ConcurrentBindingResult(
        val binding: ConsultantBindingAdminView,
        val connectionId: Long
    )
    private data class FlywayMigration(val version: String, val type: String)
    private data class MembershipAudit(
        val confirmedBy: String,
        val confirmedAt: Timestamp,
        val updatedAt: Timestamp
    )

    @TestConfiguration
    class TestConfig {
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    companion object {
        private const val USER_ID = "tc-user-consultant"
        private const val ADMIN_ID = "tc-admin-confirmer"
        private const val SECOND_ADMIN_ID = "tc-admin-second"
        private const val INSTITUTION_ID = "tc-institution"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = JoysongMySqlContainer("mysql:8.0.39")
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class JoysongMySqlContainer(imageName: String) :
    MySQLContainer<JoysongMySqlContainer>(imageName)
