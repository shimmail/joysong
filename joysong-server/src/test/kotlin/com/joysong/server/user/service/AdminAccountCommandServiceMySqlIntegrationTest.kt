package com.joysong.server.user.service

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.support.WorktreeTestDatabase
import com.joysong.server.user.repository.AdminAccountGuardRepository
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
        "admin.bootstrap.phone=13800000000",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    AdminAccountCommandService::class,
    AdminAccountGuardRepository::class,
    RefreshTokenService::class,
    AdminAccountCommandServiceMySqlIntegrationTest.TestConfig::class,
)
class AdminAccountCommandServiceMySqlIntegrationTest {

    @Autowired
    private lateinit var service: AdminAccountCommandService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun cleanUp() {
        jdbcTemplate.update("DELETE FROM refresh_tokens")
        jdbcTemplate.update("DELETE FROM users")
    }

    @Test
    fun `existing configured administrator starts without ADMIN_PASSWORD and remains unchanged`() {
        insertUser("fixed-admin", FIXED_PHONE, "existing-hash", "Existing profile", "ADMIN")

        val verified = service.initializeBootstrapAdministrator(FIXED_PHONE, "")

        assertEquals("fixed-admin", verified.id)
        assertEquals(
            "fixed-admin|13800000000|existing-hash|Existing profile|ADMIN|ACTIVE",
            jdbcTemplate.queryForObject(
                "SELECT CONCAT_WS('|', id, phone, password_hash, nickname, role, account_state) FROM users WHERE id = 'fixed-admin'",
                String::class.java,
            ),
        )
    }

    @Test
    fun `second non-erased administrator prevents startup without writes`() {
        insertUser("fixed-admin", FIXED_PHONE, "fixed-hash", "Fixed profile", "ADMIN")
        insertUser("second-admin", "13900000000", "second-hash", "Second profile", "ADMIN")

        assertThrows(IllegalStateException::class.java) {
            service.initializeBootstrapAdministrator(FIXED_PHONE, "")
        }

        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users", Long::class.java))
        assertEquals("fixed-hash", jdbcTemplate.queryForObject(
            "SELECT password_hash FROM users WHERE id = 'fixed-admin'",
            String::class.java,
        ))
    }

    private fun insertUser(id: String, phone: String, passwordHash: String, nickname: String, role: String) {
        jdbcTemplate.update(
            "INSERT INTO users (id, phone, password_hash, nickname, role) VALUES (?, ?, ?, ?, ?)",
            id,
            phone,
            passwordHash,
            nickname,
            role,
        )
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun jwtTokenProvider(): JwtTokenProvider = io.mockk.mockk(relaxed = true)

        @Bean
        fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

        @Bean
        fun accountLifecycleGuard(userRepository: UserRepository): AccountLifecycleGuard =
            AccountLifecycleGuard(userRepository)
    }

    companion object {
        private const val FIXED_PHONE = "13800000000"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = FixedAdminMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class FixedAdminMySqlContainer(imageName: String) : MySQLContainer<FixedAdminMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
