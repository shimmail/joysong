package com.joysong.server.order

import com.joysong.server.order.dto.OrderStatusEnum
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class OrderStatusEnumTest {

    @Test
    fun `fromValue 返回正确枚举实例`() {
        assertEquals(OrderStatusEnum.PENDING_PAYMENT, OrderStatusEnum.fromValue("PENDING_PAYMENT"))
        assertEquals(OrderStatusEnum.COMPLETED, OrderStatusEnum.fromValue("COMPLETED"))
        assertEquals(OrderStatusEnum.SETTLED, OrderStatusEnum.fromValue("SETTLED"))
    }

    @Test
    fun `fromValue 无效值返回 null`() {
        assertNull(OrderStatusEnum.fromValue("INVALID_STATUS"))
        assertNull(OrderStatusEnum.fromValue(""))
    }

    @Test
    fun `PENDING_PAYMENT 可以转换到 CONSULTATION_PAID 和 CANCELLED`() {
        assertTrue(OrderStatusEnum.PENDING_PAYMENT.canTransitionTo(OrderStatusEnum.CONSULTATION_PAID))
        assertTrue(OrderStatusEnum.PENDING_PAYMENT.canTransitionTo(OrderStatusEnum.CANCELLED))
        assertFalse(OrderStatusEnum.PENDING_PAYMENT.canTransitionTo(OrderStatusEnum.VERIFIED))
        assertFalse(OrderStatusEnum.PENDING_PAYMENT.canTransitionTo(OrderStatusEnum.COMPLETED))
    }

    @Test
    fun `CONSULTATION_PAID 可以转换到 VERIFIED CANCELLED REFUNDED`() {
        assertTrue(OrderStatusEnum.CONSULTATION_PAID.canTransitionTo(OrderStatusEnum.VERIFIED))
        assertTrue(OrderStatusEnum.CONSULTATION_PAID.canTransitionTo(OrderStatusEnum.CANCELLED))
        assertTrue(OrderStatusEnum.CONSULTATION_PAID.canTransitionTo(OrderStatusEnum.REFUNDED))
        assertFalse(OrderStatusEnum.CONSULTATION_PAID.canTransitionTo(OrderStatusEnum.COMPLETED))
    }

    @Test
    fun `VERIFIED 只能支付尾款 取消或进入纠纷`() {
        assertTrue(OrderStatusEnum.VERIFIED.canTransitionTo(OrderStatusEnum.BALANCE_PAID))
        assertFalse(OrderStatusEnum.VERIFIED.canTransitionTo(OrderStatusEnum.PENDING_COMPLETION))
        assertTrue(OrderStatusEnum.VERIFIED.canTransitionTo(OrderStatusEnum.CANCELLED))
        assertTrue(OrderStatusEnum.VERIFIED.canTransitionTo(OrderStatusEnum.DISPUTE_MEDIATION))
        assertFalse(OrderStatusEnum.VERIFIED.canTransitionTo(OrderStatusEnum.COMPLETED))
    }

    @Test
    fun `BALANCE_PAID 只能转换到 PENDING_COMPLETION`() {
        assertTrue(OrderStatusEnum.BALANCE_PAID.canTransitionTo(OrderStatusEnum.PENDING_COMPLETION))
        assertFalse(OrderStatusEnum.BALANCE_PAID.canTransitionTo(OrderStatusEnum.COMPLETED))
        assertFalse(OrderStatusEnum.BALANCE_PAID.canTransitionTo(OrderStatusEnum.CANCELLED))
    }

    @Test
    fun `PENDING_COMPLETION 可以转换到 COMPLETED 和 DISPUTE_MEDIATION`() {
        assertTrue(OrderStatusEnum.PENDING_COMPLETION.canTransitionTo(OrderStatusEnum.COMPLETED))
        assertTrue(OrderStatusEnum.PENDING_COMPLETION.canTransitionTo(OrderStatusEnum.DISPUTE_MEDIATION))
        assertFalse(OrderStatusEnum.PENDING_COMPLETION.canTransitionTo(OrderStatusEnum.SETTLED))
    }

    @Test
    fun `COMPLETED 只能转换到 PENDING_SETTLEMENT`() {
        assertTrue(OrderStatusEnum.COMPLETED.canTransitionTo(OrderStatusEnum.PENDING_SETTLEMENT))
        assertTrue(OrderStatusEnum.COMPLETED.canTransitionTo(OrderStatusEnum.DISPUTE_MEDIATION))
        assertFalse(OrderStatusEnum.COMPLETED.canTransitionTo(OrderStatusEnum.SETTLED))
    }

    @Test
    fun `PENDING_SETTLEMENT 可以转换到 SETTLED 和 DISPUTE_MEDIATION`() {
        assertTrue(OrderStatusEnum.PENDING_SETTLEMENT.canTransitionTo(OrderStatusEnum.SETTLED))
        assertTrue(OrderStatusEnum.PENDING_SETTLEMENT.canTransitionTo(OrderStatusEnum.DISPUTE_MEDIATION))
    }

    @Test
    fun `DISPUTE_MEDIATION 可以转换到多个状态`() {
        assertTrue(OrderStatusEnum.DISPUTE_MEDIATION.canTransitionTo(OrderStatusEnum.PENDING_COMPLETION))
        assertTrue(OrderStatusEnum.DISPUTE_MEDIATION.canTransitionTo(OrderStatusEnum.CANCELLED))
        assertTrue(OrderStatusEnum.DISPUTE_MEDIATION.canTransitionTo(OrderStatusEnum.REFUNDED))
        assertTrue(OrderStatusEnum.DISPUTE_MEDIATION.canTransitionTo(OrderStatusEnum.SETTLED))
    }

    @Test
    fun `终态 SETTLED CANCELLED REFUNDED 不允许任何转换`() {
        OrderStatusEnum.entries.forEach { target ->
            assertFalse(OrderStatusEnum.SETTLED.canTransitionTo(target), "SETTLED should not transition to $target")
            assertFalse(OrderStatusEnum.CANCELLED.canTransitionTo(target), "CANCELLED should not transition to $target")
            assertFalse(OrderStatusEnum.REFUNDED.canTransitionTo(target), "REFUNDED should not transition to $target")
        }
    }

    @Test
    fun `TRANSITION_MAP 包含所有枚举值`() {
        OrderStatusEnum.entries.forEach { status ->
            assertTrue(
                OrderStatusEnum.TRANSITION_MAP.containsKey(status),
                "TRANSITION_MAP should contain $status"
            )
        }
    }

    @Test
    fun `travel ground service flow has only its approved transitions`() {
        assertEquals(
            setOf(OrderStatusEnum.SERVICE_ACTIVE, OrderStatusEnum.CANCELLED),
            OrderStatusEnum.TRANSITION_MAP.getValue(OrderStatusEnum.PENDING_SERVICE_FEE)
        )
        assertEquals(
            setOf(OrderStatusEnum.REFUND_REVIEW),
            OrderStatusEnum.TRANSITION_MAP.getValue(OrderStatusEnum.SERVICE_ACTIVE)
        )
        assertEquals(
            setOf(OrderStatusEnum.SERVICE_ACTIVE, OrderStatusEnum.REFUND_PROCESSING),
            OrderStatusEnum.TRANSITION_MAP.getValue(OrderStatusEnum.REFUND_REVIEW)
        )
        assertEquals(
            setOf(OrderStatusEnum.REFUNDED),
            OrderStatusEnum.TRANSITION_MAP.getValue(OrderStatusEnum.REFUND_PROCESSING)
        )
    }
}
