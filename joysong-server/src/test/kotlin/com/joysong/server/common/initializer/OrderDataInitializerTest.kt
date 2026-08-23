package com.joysong.server.common.initializer

import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.LocalDateTime

class OrderDataInitializerTest {
    @Test
    fun `seeds exact travel ground service order and payment snapshots`() {
        val orderRepository = mockk<OrderRepository>()
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val entityManager = mockk<EntityManager>(relaxed = true)
        val savedOrders = slot<List<OrderEntity>>()
        val batchWrites = mutableListOf<JdbcBatchWrite>()
        val updateSql = mutableListOf<String>()
        every { jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long::class.java) } returns 0L
        every { orderRepository.saveAll(capture(savedOrders)) } answers { savedOrders.captured }
        every { jdbcTemplate.batchUpdate(any<String>(), any<List<Array<out Any>>>()) } answers {
            batchWrites += JdbcBatchWrite(
                sql = firstArg(),
                rows = secondArg<List<Array<out Any>>>().map { it.toList() }
            )
            intArrayOf()
        }
        every { jdbcTemplate.update(any<String>()) } answers {
            updateSql += firstArg<String>()
            1
        }

        OrderDataInitializer(orderRepository, jdbcTemplate, entityManager).run(emptyArray())

        assertEquals(
            listOf(
                OrderSeedSnapshot(
                    SeedIds.ORDER_ID_1, SeedIds.USER_ID_1, SeedIds.DOC_ID_1,
                    BigDecimal("1599.60"), 159_960, BigDecimal.ZERO, 0,
                    "PENDING_SERVICE_FEE", 399_900, 159_960,
                    LocalDateTime.of(2026, 7, 9, 10, 30),
                    null, null, null, false
                ),
                OrderSeedSnapshot(
                    SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_2,
                    BigDecimal("1719.60"), 171_960, BigDecimal("1719.60"), 171_960,
                    "SERVICE_ACTIVE", 429_900, 171_960,
                    LocalDateTime.of(2026, 7, 10, 11, 0),
                    LocalDateTime.of(2026, 7, 10, 11, 1),
                    LocalDateTime.of(2026, 7, 10, 11, 1),
                    null, false
                ),
                OrderSeedSnapshot(
                    SeedIds.ORDER_ID_3, SeedIds.USER_ID_1, SeedIds.DOC_ID_1,
                    BigDecimal("1599.60"), 159_960, BigDecimal("1599.60"), 159_960,
                    "COMPLETED", 399_900, 159_960,
                    LocalDateTime.of(2026, 7, 6, 16, 0),
                    LocalDateTime.of(2026, 7, 6, 16, 1),
                    LocalDateTime.of(2026, 7, 6, 16, 1),
                    LocalDateTime.of(2026, 7, 8, 12, 0), true
                )
            ),
            savedOrders.captured.map { order ->
                assertEquals("玻尿酸填充", order.projectName)
                assertEquals("上海娇颜颂医美中心", order.institutionName)
                assertEquals("USD", order.currency)
                assertEquals("TRAVEL_GROUND_SERVICE_ONLY", order.paymentFlow)
                assertEquals(4_000, order.platformServiceRateBps)
                assertEquals(SeedIds.PROJ_ID_1, order.projectId)
                assertEquals(SeedIds.INST_ID_1, order.institutionId)
                assertEquals(SeedIds.IP_ID_1, order.institutionProjectId)
                OrderSeedSnapshot(
                    order.id, order.userId, order.doctorId,
                    order.price, order.totalAmountMinor, order.paidAmount, order.paidAmountMinor,
                    order.status, order.medicalListPriceMinor, order.travelGroundServiceFeeMinor,
                    order.createdAt, order.paymentTime, order.serviceActivatedAt, order.completedAt, order.hasReview
                )
            }
        )

        val paymentWrite = batchWrites.single { it.sql.contains("INSERT IGNORE INTO payments") }
        assertEquals(2, paymentWrite.rows.size)
        assertTrue(paymentWrite.sql.contains("'SUCCEEDED'"))
        assertTrue(paymentWrite.sql.contains("'TRAVEL_GROUND_SERVICE_FEE'"))
        assertTrue(paymentWrite.sql.contains("'USD'"))
        assertEquals(
            listOf(
                listOf(
                    "96000001-0000-4000-8000-000000000002", SeedIds.ORDER_ID_2, SeedIds.USER_ID_2,
                    BigDecimal("1719.60"), "ALIPAY_PLUS", LocalDateTime.of(2026, 7, 10, 11, 1),
                    "TX20260710110100002", 171_960L, "DEMO-PAYMENT-2", "DEMO-TRANSACTION-2",
                    "demo-travel-order-2", LocalDateTime.of(2026, 7, 10, 11, 1),
                    LocalDateTime.of(2026, 7, 10, 11, 1)
                ),
                listOf(
                    "96000001-0000-4000-8000-000000000003", SeedIds.ORDER_ID_3, SeedIds.USER_ID_1,
                    BigDecimal("1599.60"), "ALIPAY_PLUS", LocalDateTime.of(2026, 7, 6, 16, 1),
                    "TX20260706160100003", 159_960L, "DEMO-PAYMENT-3", "DEMO-TRANSACTION-3",
                    "demo-travel-order-3", LocalDateTime.of(2026, 7, 6, 16, 1),
                    LocalDateTime.of(2026, 7, 6, 16, 1)
                )
            ),
            paymentWrite.rows
        )

        val allSql = (batchWrites.map { it.sql } + updateSql).joinToString("\n")
        assertFalse(allSql.contains("CONSULTATION_FEE"))
        assertFalse(allSql.contains("BALANCE"))
    }

    private data class JdbcBatchWrite(val sql: String, val rows: List<List<Any>>)

    private data class OrderSeedSnapshot(
        val id: String,
        val userId: String,
        val doctorId: String,
        val price: BigDecimal,
        val totalAmountMinor: Long?,
        val paidAmount: BigDecimal,
        val paidAmountMinor: Long?,
        val status: String,
        val medicalListPriceMinor: Long?,
        val travelGroundServiceFeeMinor: Long?,
        val createdAt: LocalDateTime,
        val paymentTime: LocalDateTime?,
        val serviceActivatedAt: LocalDateTime?,
        val completedAt: LocalDateTime?,
        val hasReview: Boolean
    )
}
