package com.joysong.server.common.initializer

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import jakarta.persistence.EntityManager
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(6)
class OrderDataInitializer(
    private val orderRepository: OrderRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val entityManager: EntityManager
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        // The repository soft-delete filter must not make stable IDs look absent.
        val persistedOrderCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders",
            Long::class.java
        ) ?: 0L
        if (persistedOrderCount > 0L) return

        val pendingCreatedAt = LocalDateTime.of(2026, 7, 9, 10, 30)
        val activeCreatedAt = LocalDateTime.of(2026, 7, 10, 11, 0)
        val activePaidAt = LocalDateTime.of(2026, 7, 10, 11, 1)
        val completedCreatedAt = LocalDateTime.of(2026, 7, 6, 16, 0)
        val completedPaidAt = LocalDateTime.of(2026, 7, 6, 16, 1)
        val completedAt = LocalDateTime.of(2026, 7, 8, 12, 0)

        orderRepository.saveAll(listOf(
            // 399900 * 4000 / 10000 = 159960
            OrderEntity(
                id = SeedIds.ORDER_ID_1,
                userId = SeedIds.USER_ID_1,
                projectName = "玻尿酸填充",
                institutionName = "上海娇颜颂医美中心",
                currency = "USD",
                price = BigDecimal("1599.60"),
                totalAmountMinor = 159_960,
                paidAmount = BigDecimal.ZERO,
                paidAmountMinor = 0,
                status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
                paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
                medicalListPriceMinor = 399_900,
                platformServiceRateBps = 4_000,
                travelGroundServiceFeeMinor = 159_960,
                createdAt = pendingCreatedAt,
                appointmentTime = LocalDateTime.of(2026, 7, 12, 14, 0),
                projectId = SeedIds.PROJ_ID_1,
                institutionId = SeedIds.INST_ID_1,
                doctorId = SeedIds.DOC_ID_1,
                doctorName = "王医生",
                institutionProjectId = SeedIds.IP_ID_1,
                orderNo = "JOY20260709103000001"
            ),
            // 429900 * 4000 / 10000 = 171960
            OrderEntity(
                id = SeedIds.ORDER_ID_2,
                userId = SeedIds.USER_ID_2,
                projectName = "玻尿酸填充",
                institutionName = "上海娇颜颂医美中心",
                currency = "USD",
                price = BigDecimal("1719.60"),
                totalAmountMinor = 171_960,
                paidAmount = BigDecimal("1719.60"),
                paidAmountMinor = 171_960,
                status = OrderStatusEnum.SERVICE_ACTIVE.value,
                paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
                medicalListPriceMinor = 429_900,
                platformServiceRateBps = 4_000,
                travelGroundServiceFeeMinor = 171_960,
                createdAt = activeCreatedAt,
                appointmentTime = LocalDateTime.of(2026, 7, 15, 10, 0),
                projectId = SeedIds.PROJ_ID_1,
                institutionId = SeedIds.INST_ID_1,
                doctorId = SeedIds.DOC_ID_2,
                doctorName = "李医生",
                institutionProjectId = SeedIds.IP_ID_1,
                orderNo = "JOY20260710110000002",
                paymentTime = activePaidAt,
                serviceActivatedAt = activePaidAt
            ),
            // 399900 * 4000 / 10000 = 159960
            OrderEntity(
                id = SeedIds.ORDER_ID_3,
                userId = SeedIds.USER_ID_1,
                projectName = "玻尿酸填充",
                institutionName = "上海娇颜颂医美中心",
                currency = "USD",
                price = BigDecimal("1599.60"),
                totalAmountMinor = 159_960,
                paidAmount = BigDecimal("1599.60"),
                paidAmountMinor = 159_960,
                status = OrderStatusEnum.COMPLETED.value,
                paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
                medicalListPriceMinor = 399_900,
                platformServiceRateBps = 4_000,
                travelGroundServiceFeeMinor = 159_960,
                createdAt = completedCreatedAt,
                appointmentTime = LocalDateTime.of(2026, 7, 7, 10, 0),
                hasReview = true,
                projectId = SeedIds.PROJ_ID_1,
                institutionId = SeedIds.INST_ID_1,
                doctorId = SeedIds.DOC_ID_1,
                doctorName = "王医生",
                institutionProjectId = SeedIds.IP_ID_1,
                orderNo = "JOY20260706160000003",
                paymentTime = completedPaidAt,
                serviceActivatedAt = completedPaidAt,
                completedAt = completedAt
            )
        ))

        entityManager.flush()

        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO favorites (id, user_id, target_type, target_id, target_name, target_image, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_1, "PROJECT", SeedIds.PROJ_ID_1, "玻尿酸填充", ""),
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_1, "DOCTOR", SeedIds.DOC_ID_1, "王医生", ""),
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_2, "INSTITUTION", SeedIds.INST_ID_1, "上海娇颜颂医美中心", "")
            )
        )

        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO reviews (id, order_id, user_id, doctor_id, rating, content, tags, images, target_type, target_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                arrayOf(
                    UUID.randomUUID().toString(), SeedIds.ORDER_ID_3, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, 5,
                    "旅行地接服务安排顺畅，王医生讲解专业，机构回访及时。",
                    "专业,服务贴心,推荐", "", "INSTITUTION", SeedIds.INST_ID_1
                )
            )
        )

        refreshReviewAggregates()

        jdbcTemplate.batchUpdate(
            """
            INSERT IGNORE INTO payments
                (id, order_id, user_id, amount, method, status, paid_at, transaction_id,
                 payment_type, provider, payment_method, currency, amount_minor,
                 provider_payment_id, provider_transaction_id, idempotency_key, authorized_at, created_at)
            VALUES (?, ?, ?, ?, ?, 'SUCCEEDED', ?, ?, 'TRAVEL_GROUND_SERVICE_FEE',
                    'ALIPAY_PLUS', 'ALIPAY_PLUS', 'USD', ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            listOf(
                arrayOf(
                    "96000001-0000-4000-8000-000000000002", SeedIds.ORDER_ID_2, SeedIds.USER_ID_2,
                    BigDecimal("1719.60"), "ALIPAY_PLUS", activePaidAt, "TX20260710110100002", 171_960L,
                    "DEMO-PAYMENT-2", "DEMO-TRANSACTION-2", "demo-travel-order-2", activePaidAt, activePaidAt
                ),
                arrayOf(
                    "96000001-0000-4000-8000-000000000003", SeedIds.ORDER_ID_3, SeedIds.USER_ID_1,
                    BigDecimal("1599.60"), "ALIPAY_PLUS", completedPaidAt, "TX20260706160100003", 159_960L,
                    "DEMO-PAYMENT-3", "DEMO-TRANSACTION-3", "demo-travel-order-3", completedPaidAt, completedPaidAt
                )
            )
        )
    }

    private fun refreshReviewAggregates() {
        jdbcTemplate.update(
            """
            UPDATE institutions i
            LEFT JOIN (
                SELECT target_id AS institution_id, COUNT(*) AS review_count, ROUND(AVG(rating), 1) AS rating
                FROM reviews
                WHERE deleted_at IS NULL AND target_type = 'INSTITUTION'
                GROUP BY target_id
            ) stats ON stats.institution_id = i.id
            SET i.review_count = COALESCE(stats.review_count, 0), i.rating = COALESCE(stats.rating, 0.0)
            WHERE i.deleted_at IS NULL
            """.trimIndent()
        )
        jdbcTemplate.update(
            """
            UPDATE doctors d
            LEFT JOIN (
                SELECT doctor_id, COUNT(*) AS review_count, ROUND(AVG(rating), 1) AS rating
                FROM reviews
                WHERE deleted_at IS NULL AND target_type = 'INSTITUTION' AND doctor_id <> ''
                GROUP BY doctor_id
            ) stats ON stats.doctor_id = d.id
            SET d.review_count = COALESCE(stats.review_count, 0), d.rating = COALESCE(stats.rating, 0.0)
            WHERE d.deleted_at IS NULL
            """.trimIndent()
        )
        jdbcTemplate.update(
            """
            UPDATE institution_projects ip
            LEFT JOIN (
                SELECT o.institution_project_id, COUNT(*) AS review_count, ROUND(AVG(r.rating), 1) AS rating
                FROM reviews r
                JOIN orders o ON o.id = r.order_id
                WHERE r.deleted_at IS NULL AND r.target_type = 'INSTITUTION'
                  AND o.deleted_at IS NULL AND o.institution_project_id <> ''
                GROUP BY o.institution_project_id
            ) stats ON stats.institution_project_id = ip.id
            SET ip.review_count = COALESCE(stats.review_count, 0), ip.rating = COALESCE(stats.rating, 0.0)
            WHERE ip.deleted_at IS NULL
            """.trimIndent()
        )
    }
}
