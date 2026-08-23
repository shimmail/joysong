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

class OrderDataInitializerTest {
    @Test
    fun `seeds only travel ground service orders and payments`() {
        val orderRepository = mockk<OrderRepository>()
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val entityManager = mockk<EntityManager>(relaxed = true)
        val savedOrders = slot<List<OrderEntity>>()
        val sqlWrites = mutableListOf<String>()
        every { jdbcTemplate.queryForObject("SELECT COUNT(*) FROM orders", Long::class.java) } returns 0L
        every { orderRepository.saveAll(capture(savedOrders)) } answers { savedOrders.captured }
        every { jdbcTemplate.batchUpdate(any<String>(), any<List<Array<out Any>>>()) } answers {
            sqlWrites += firstArg<String>()
            intArrayOf()
        }
        every { jdbcTemplate.update(any<String>()) } answers {
            sqlWrites += firstArg<String>()
            1
        }

        OrderDataInitializer(orderRepository, jdbcTemplate, entityManager).run(emptyArray())

        val allSql = sqlWrites.joinToString("\n")
        assertEquals(
            setOf("PENDING_SERVICE_FEE", "SERVICE_ACTIVE", "COMPLETED"),
            savedOrders.captured.map { it.status }.toSet()
        )
        assertTrue(savedOrders.captured.all { it.paymentFlow == "TRAVEL_GROUND_SERVICE_ONLY" })
        assertTrue(savedOrders.captured.all { it.platformServiceRateBps == 4000 })
        assertFalse(allSql.contains("CONSULTATION_FEE"))
        assertFalse(allSql.contains("BALANCE"))
        assertTrue(allSql.contains("TRAVEL_GROUND_SERVICE_FEE"))
    }
}
