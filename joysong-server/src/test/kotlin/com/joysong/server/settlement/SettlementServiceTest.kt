package com.joysong.server.settlement

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementService
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
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
            OrderSplitRatePolicy(OrderSplitProperties()),
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

        assertEquals("订单分账信息不完整，缺少机构、机构项目、医美顾问或医生快照", error.message)
        verify(exactly = 0) { configRepository.findByDoctorIdAndInstitutionProjectId(any(), any()) }
        verify(exactly = 0) { settlementRepository.save(any()) }
    }

    @Test
    fun `settlement stores medical beauty consultant share and derived doctor remainder`() {
        val order = completeOrder().copy(price = BigDecimal("100.01"))
        val config = DoctorInstitutionProjectConfigEntity(
            doctorId = order.doctorId,
            institutionProjectId = order.institutionProjectId,
            institutionRate = BigDecimal("35.00"),
            commissionRate = BigDecimal("10.00")
        )
        val savedSettlement = slot<SettlementEntity>()
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every { settlementRepository.findByOrderId(order.id) } returns null
        every {
            configRepository.findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
        } returns config
        every { settlementRepository.save(capture(savedSettlement)) } answers { savedSettlement.captured }

        val result = service.saveSettlement(order.id)

        assertEquals(BigDecimal("40.00"), result.platformRate)
        assertEquals(BigDecimal("35.00"), result.institutionRate)
        assertEquals(BigDecimal("10.00"), result.consultantRate)
        assertEquals(BigDecimal("15.00"), result.doctorRate)
        assertEquals(
            result.totalAmount,
            result.platformAmount + result.institutionAmount + result.consultantAmount + result.doctorAmount
        )
        assertEquals(BigDecimal("15.01"), savedSettlement.captured.doctorAmount)
    }

    @Test
    fun `settlement without config uses shared defaults and zero medical beauty consultant share`() {
        val order = completeOrder()
        every { orderRepository.findById(order.id) } returns Optional.of(order)
        every { settlementRepository.findByOrderId(order.id) } returns null
        every {
            configRepository.findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
        } returns null
        every { settlementRepository.save(any()) } answers { firstArg() }

        val result = service.saveSettlement(order.id)

        assertEquals(BigDecimal("40.00"), result.platformRate)
        assertEquals(BigDecimal("40.00"), result.institutionRate)
        assertEquals(BigDecimal.ZERO, result.consultantRate)
        assertEquals(BigDecimal("20.00"), result.doctorRate)
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
