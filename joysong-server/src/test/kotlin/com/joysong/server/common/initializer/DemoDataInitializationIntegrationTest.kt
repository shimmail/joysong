package com.joysong.server.common.initializer

import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@Tag("mysql-integration")
@Testcontainers
@SpringBootTest(properties = [
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    "spring.task.scheduling.enabled=false",
    "payment.reconciliation.enabled=false",
    "payment.stripe.legacy-enabled=false",
    "seed.demo.enabled=true",
    "seed.demo.password=demo-test-password",
    "admin.bootstrap.phone=13800138000",
    "admin.bootstrap.password=demo-admin-password",
    "jwt.secret=0123456789abcdef0123456789abcdef",
    "google.client-id=demo-data-test-google-client",
    "ai-agent.provider=QWEN",
    "ai-agent.base-url=https://invalid.example/v1",
    "ai-agent.api-key=demo-data-test-key",
    "ai-agent.model=demo-data-test-model",
    "ai-agent.intent-model=demo-data-test-intent-model",
    "order.split.platform-rate=40.00"
])
class DemoDataInitializationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `fresh database contains only coherent travel ground service demo data`() {
        val doctorPrices = jdbcTemplate.queryForList(
            """
            SELECT price FROM doctor_projects
            WHERE institution_project_id = ?
            ORDER BY price
            """.trimIndent(),
            BigDecimal::class.java,
            SeedIds.IP_ID_1
        )
        val legacyOrderCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE payment_flow <> 'TRAVEL_GROUND_SERVICE_ONLY'",
            Long::class.java
        )
        val legacyPaymentCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM payments WHERE payment_type IN ('CONSULTATION_FEE', 'BALANCE')",
            Long::class.java
        )
        val travelOrderCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders WHERE payment_flow = 'TRAVEL_GROUND_SERVICE_ONLY'",
            Long::class.java
        )
        val successfulTravelPaymentCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM payments
            WHERE payment_type = 'TRAVEL_GROUND_SERVICE_FEE'
              AND status IN ('SUCCESS', 'SUCCEEDED')
            """.trimIndent(),
            Long::class.java
        )

        assertEquals(listOf(BigDecimal("3999.00"), BigDecimal("4299.00")), doctorPrices)
        assertEquals(0L, legacyOrderCount)
        assertEquals(0L, legacyPaymentCount)
        assertEquals(3L, travelOrderCount)
        assertEquals(2L, successfulTravelPaymentCount)
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmField
        val mysql = DemoDataInitializationMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class DemoDataInitializationMySqlContainer(imageName: String) :
    MySQLContainer<DemoDataInitializationMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
