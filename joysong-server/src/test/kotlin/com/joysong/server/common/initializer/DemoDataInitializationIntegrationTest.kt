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
import java.time.LocalDateTime

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
    fun `fresh database contains exact coherent travel ground service demo graph`() {
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

        val orders = jdbcTemplate.query(
            """
            SELECT id, user_id, doctor_id, project_id, institution_id, institution_project_id,
                   currency, price, total_amount_minor, paid_amount, paid_amount_minor, status,
                   payment_flow, medical_list_price_minor, platform_service_rate_bps,
                   travel_ground_service_fee_minor, created_at, payment_time,
                   service_activated_at, completed_at, has_review
            FROM orders
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                OrderSnapshot(
                    rs.getString("id"), rs.getString("user_id"), rs.getString("doctor_id"),
                    rs.getString("project_id"), rs.getString("institution_id"),
                    rs.getString("institution_project_id"), rs.getString("currency"),
                    rs.getBigDecimal("price").setScale(2), rs.getLong("total_amount_minor"),
                    rs.getBigDecimal("paid_amount").setScale(2), rs.getLong("paid_amount_minor"),
                    rs.getString("status"), rs.getString("payment_flow"),
                    rs.getLong("medical_list_price_minor"), rs.getInt("platform_service_rate_bps"),
                    rs.getLong("travel_ground_service_fee_minor"), rs.getTimestamp("created_at").toLocalDateTime(),
                    rs.getTimestamp("payment_time")?.toLocalDateTime(),
                    rs.getTimestamp("service_activated_at")?.toLocalDateTime(),
                    rs.getTimestamp("completed_at")?.toLocalDateTime(), rs.getBoolean("has_review")
                )
            }
        )
        assertEquals(
            listOf(
                OrderSnapshot(
                    SeedIds.ORDER_ID_1, SeedIds.USER_ID_1, SeedIds.DOC_ID_1,
                    SeedIds.PROJ_ID_1, SeedIds.INST_ID_1, SeedIds.IP_ID_1, "USD",
                    BigDecimal("1599.60"), 159_960, BigDecimal.ZERO.setScale(2), 0,
                    "PENDING_SERVICE_FEE", "TRAVEL_GROUND_SERVICE_ONLY", 399_900, 4_000, 159_960,
                    LocalDateTime.of(2026, 7, 9, 10, 30), null, null, null, false
                ),
                OrderSnapshot(
                    SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_2,
                    SeedIds.PROJ_ID_1, SeedIds.INST_ID_1, SeedIds.IP_ID_1, "USD",
                    BigDecimal("1719.60"), 171_960, BigDecimal("1719.60"), 171_960,
                    "SERVICE_ACTIVE", "TRAVEL_GROUND_SERVICE_ONLY", 429_900, 4_000, 171_960,
                    LocalDateTime.of(2026, 7, 10, 11, 0), LocalDateTime.of(2026, 7, 10, 11, 1),
                    LocalDateTime.of(2026, 7, 10, 11, 1), null, false
                ),
                OrderSnapshot(
                    SeedIds.ORDER_ID_3, SeedIds.USER_ID_1, SeedIds.DOC_ID_1,
                    SeedIds.PROJ_ID_1, SeedIds.INST_ID_1, SeedIds.IP_ID_1, "USD",
                    BigDecimal("1599.60"), 159_960, BigDecimal("1599.60"), 159_960,
                    "COMPLETED", "TRAVEL_GROUND_SERVICE_ONLY", 399_900, 4_000, 159_960,
                    LocalDateTime.of(2026, 7, 6, 16, 0), LocalDateTime.of(2026, 7, 6, 16, 1),
                    LocalDateTime.of(2026, 7, 6, 16, 1), LocalDateTime.of(2026, 7, 8, 12, 0), true
                )
            ),
            orders
        )

        val payments = jdbcTemplate.query(
            """
            SELECT id, order_id, user_id, amount_minor, status, payment_type, currency
            FROM payments
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                PaymentSnapshot(
                    rs.getString("id"), rs.getString("order_id"), rs.getString("user_id"),
                    rs.getLong("amount_minor"), rs.getString("status"),
                    rs.getString("payment_type"), rs.getString("currency")
                )
            }
        )
        assertEquals(2L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payments", Long::class.java))
        assertEquals(
            listOf(
                PaymentSnapshot(
                    "96000001-0000-4000-8000-000000000002", SeedIds.ORDER_ID_2, SeedIds.USER_ID_2,
                    171_960, "SUCCEEDED", "TRAVEL_GROUND_SERVICE_FEE", "USD"
                ),
                PaymentSnapshot(
                    "96000001-0000-4000-8000-000000000003", SeedIds.ORDER_ID_3, SeedIds.USER_ID_1,
                    159_960, "SUCCEEDED", "TRAVEL_GROUND_SERVICE_FEE", "USD"
                )
            ),
            payments
        )

        assertEquals(
            listOf(ReviewSnapshot(SeedIds.ORDER_ID_3, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, "INSTITUTION", SeedIds.INST_ID_1)),
            jdbcTemplate.query(
                "SELECT order_id, user_id, doctor_id, target_type, target_id FROM reviews ORDER BY order_id",
                { rs, _ ->
                    ReviewSnapshot(
                        rs.getString("order_id"), rs.getString("user_id"), rs.getString("doctor_id"),
                        rs.getString("target_type"), rs.getString("target_id")
                    )
                }
            )
        )
        assertEquals(
            listOf(DiaryOrderSnapshot(SeedIds.ORDER_ID_3, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, SeedIds.PROJ_ID_1, SeedIds.INST_ID_1, SeedIds.IP_ID_1)),
            jdbcTemplate.query(
                """
                SELECT order_id, user_id, doctor_id, project_id, institution_id, institution_project_id
                FROM diaries
                WHERE order_id <> ''
                ORDER BY order_id
                """.trimIndent(),
                { rs, _ ->
                    DiaryOrderSnapshot(
                        rs.getString("order_id"), rs.getString("user_id"), rs.getString("doctor_id"),
                        rs.getString("project_id"), rs.getString("institution_id"),
                        rs.getString("institution_project_id")
                    )
                }
            )
        )
        assertEquals(5L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM diaries", Long::class.java))
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM reviews r
                LEFT JOIN orders o ON o.id = r.order_id
                WHERE o.id IS NULL OR o.status <> 'COMPLETED'
                   OR r.user_id <> o.user_id OR r.doctor_id <> o.doctor_id
                """.trimIndent(),
                Long::class.java
            )
        )
        assertEquals(
            0L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM diaries d
                LEFT JOIN orders o ON o.id = d.order_id
                WHERE d.order_id <> '' AND (
                    o.id IS NULL OR o.status <> 'COMPLETED'
                    OR d.user_id <> o.user_id OR d.doctor_id <> o.doctor_id
                    OR d.project_id <> o.project_id OR d.institution_id <> o.institution_id
                    OR d.institution_project_id <> o.institution_project_id
                )
                """.trimIndent(),
                Long::class.java
            )
        )
    }

    private data class ConfigSnapshot(
        val id: String,
        val doctorId: String,
        val institutionProjectId: String,
        val medicalListPrice: BigDecimal
    )

    private data class OrderSnapshot(
        val id: String,
        val userId: String,
        val doctorId: String,
        val projectId: String,
        val institutionId: String,
        val institutionProjectId: String,
        val currency: String,
        val price: BigDecimal,
        val totalAmountMinor: Long,
        val paidAmount: BigDecimal,
        val paidAmountMinor: Long,
        val status: String,
        val paymentFlow: String,
        val medicalListPriceMinor: Long,
        val platformServiceRateBps: Int,
        val travelGroundServiceFeeMinor: Long,
        val createdAt: LocalDateTime,
        val paymentTime: LocalDateTime?,
        val serviceActivatedAt: LocalDateTime?,
        val completedAt: LocalDateTime?,
        val hasReview: Boolean
    )

    private data class PaymentSnapshot(
        val id: String,
        val orderId: String,
        val userId: String,
        val amountMinor: Long,
        val status: String,
        val paymentType: String,
        val currency: String
    )

    private data class ReviewSnapshot(
        val orderId: String,
        val userId: String,
        val doctorId: String,
        val targetType: String,
        val targetId: String
    )

    private data class DiaryOrderSnapshot(
        val orderId: String,
        val userId: String,
        val doctorId: String,
        val projectId: String,
        val institutionId: String,
        val institutionProjectId: String
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
