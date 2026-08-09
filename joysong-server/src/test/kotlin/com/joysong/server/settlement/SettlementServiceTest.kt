package com.joysong.server.settlement

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementService
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.Optional

class SettlementServiceTest {
    @MockK private lateinit var settlementRepository: SettlementRepository
    @MockK private lateinit var orderRepository: OrderRepository
    @MockK private lateinit var configRepository: DoctorInstitutionProjectConfigRepository
    @MockK private lateinit var orderStatusLogService: OrderStatusLogService

    private lateinit var service: SettlementService

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
        service = SettlementService(
            settlementRepository,
            orderRepository,
            OrderSplitProperties(),
            configRepository,
            orderStatusLogService
        )
    }

    @Test
    fun `历史订单缺少咨询师快照时禁止分账`() {
        val order = completeOrder().copy(consultantId = "", consultantName = "")
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every { settlementRepository.findByOrderId(order.id) } returns null

        val error = assertThrows<IllegalStateException> {
            service.saveSettlement(order.id)
        }

        assertEquals("订单分账信息不完整，缺少机构、机构项目、咨询师或医生快照", error.message)
        verify(exactly = 0) { configRepository.findByDoctorIdAndInstitutionProjectId(any(), any()) }
        verify(exactly = 0) { settlementRepository.save(any()) }
    }

    private fun completeOrder() = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        institutionName = "机构",
        consultantId = "consultant-1",
        consultantName = "咨询师",
        doctorId = "doctor-1",
        doctorName = "医生",
        institutionId = "institution-1",
        institutionProjectId = "institution-project-1",
        price = BigDecimal("100.00"),
        status = OrderStatusEnum.PENDING_SETTLEMENT.value
    )
}
