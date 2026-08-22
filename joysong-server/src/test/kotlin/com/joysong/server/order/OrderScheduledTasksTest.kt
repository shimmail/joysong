package com.joysong.server.order

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderScheduledTasks
import com.joysong.server.order.service.OrderService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementReleaseService
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class OrderScheduledTasksTest {
    @Test
    fun `thirty minute timeout closes both legacy and travel service pending orders`() {
        val orderRepository = mockk<OrderRepository>()
        val orderService = mockk<OrderService>()
        val settlementRepository = mockk<SettlementRepository>()
        val settlementReleaseService = mockk<SettlementReleaseService>()
        val legacyOrder = mockk<OrderEntity> { every { id } returns "legacy-order" }
        val travelOrder = mockk<OrderEntity> { every { id } returns "travel-order" }
        every {
            orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatusEnum.PENDING_PAYMENT.value,
                any<LocalDateTime>()
            )
        } returns listOf(legacyOrder)
        every {
            orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatusEnum.PENDING_SERVICE_FEE.value,
                any<LocalDateTime>()
            )
        } returns listOf(travelOrder)
        justRun { orderService.cancelExpiredPendingOrder(any()) }

        OrderScheduledTasks(
            orderRepository,
            orderService,
            settlementRepository,
            settlementReleaseService
        ).cancelExpiredPendingOrders()

        verify(exactly = 1) {
            orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatusEnum.PENDING_PAYMENT.value,
                any<LocalDateTime>()
            )
        }
        verify(exactly = 1) {
            orderRepository.findByStatusAndCreatedAtBefore(
                OrderStatusEnum.PENDING_SERVICE_FEE.value,
                any<LocalDateTime>()
            )
        }
        verify(exactly = 1) { orderService.cancelExpiredPendingOrder("legacy-order") }
        verify(exactly = 1) { orderService.cancelExpiredPendingOrder("travel-order") }
    }
}
