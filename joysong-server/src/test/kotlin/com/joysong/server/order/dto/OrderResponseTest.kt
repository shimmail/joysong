package com.joysong.server.order.dto

import com.joysong.server.order.entity.OrderEntity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class OrderResponseTest {

    @Test
    fun `completed travel service response exposes consultant and readable history but disables messaging`() {
        val order = OrderEntity(
            id = "order-1",
            userId = "user-1",
            projectName = "地接服务",
            price = BigDecimal("400.00"),
            status = OrderStatusEnum.COMPLETED.value,
            paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
            consultantId = "consultant-1",
            consultantName = "顾问",
            serviceActivatedAt = LocalDateTime.of(2026, 8, 22, 9, 0)
        )

        val response = OrderResponse.from(order)

        assertTrue(response.consultantDetailsVisible)
        assertTrue(response.serviceConversationReadable)
        assertFalse(response.serviceMessagingEnabled)
    }
}
