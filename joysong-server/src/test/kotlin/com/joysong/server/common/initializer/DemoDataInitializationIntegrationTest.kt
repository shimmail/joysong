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
    "spring.datasource.username=test",
    "spring.datasource.password=test",
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
    "ai-agent.base-url=https://dashscope.aliyuncs.com/compatible-mode/v1",
    "ai-agent.api-key=demo-data-test-key",
    "ai-agent.model=demo-data-test-model",
    "ai-agent.intent-model=demo-data-test-intent-model",
    "order.split.platform-rate=40.00"
])
class DemoDataInitializationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `fresh database keeps catalog demos without generating order data`() {
        assertEquals(
            listOf(BigDecimal("3999.00"), BigDecimal("4299.00")),
            jdbcTemplate.queryForList(
                "SELECT price FROM doctor_projects WHERE institution_project_id = ? ORDER BY price",
                BigDecimal::class.java,
                SeedIds.IP_ID_1
            )
        )

        assertEquals(
            listOf(
                ConfigSnapshot(SeedIds.SPLIT_CONFIG_ID, SeedIds.DOC_ID_1, SeedIds.IP_ID_1, BigDecimal("3999.00")),
                ConfigSnapshot(SeedIds.SPLIT_CONFIG_ID_2, SeedIds.DOC_ID_2, SeedIds.IP_ID_1, BigDecimal("4299.00"))
            ),
            jdbcTemplate.query(
                """
                SELECT id, doctor_id, institution_project_id, medical_list_price
                FROM doctor_institution_project_configs
                WHERE institution_project_id = ?
                ORDER BY id
                """.trimIndent(),
                { rs, _ ->
                    ConfigSnapshot(
                        rs.getString("id"),
                        rs.getString("doctor_id"),
                        rs.getString("institution_project_id"),
                        rs.getBigDecimal("medical_list_price").setScale(2)
                    )
                },
                SeedIds.IP_ID_1
            )
        )

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM users u
                JOIN institution_memberships m ON m.user_id = u.id
                WHERE u.id = ? AND u.nickname = ?
                  AND m.institution_id = ? AND m.member_role = 'CONSULTANT'
                  AND m.status = 'APPROVED' AND m.revoked_at IS NULL
                """.trimIndent(),
                Long::class.java,
                SeedIds.CONSULTANT_ID,
                "安娜咨询师",
                SeedIds.INST_ID_1
            )
        )

        assertEquals(0L, count("orders"))
        assertEquals(0L, count("payments"))
        assertEquals(0L, count("reviews"))
        assertEquals(0L, count("favorites"))
        assertEquals(5L, count("diaries"))
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM diaries WHERE order_id <> ''",
                Long::class.java
            )
        )
    }

    private fun count(table: String): Long =
        jdbcTemplate.queryForObject("SELECT COUNT(*) FROM $table", Long::class.java) ?: 0L

    private data class ConfigSnapshot(
        val id: String,
        val doctorId: String,
        val institutionProjectId: String,
        val medicalListPrice: BigDecimal
    )

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
