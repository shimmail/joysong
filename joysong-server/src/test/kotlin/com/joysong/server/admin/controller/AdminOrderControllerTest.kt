package com.joysong.server.admin.controller

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.service.OrderService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.repository.SettlementRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class AdminOrderControllerTest {
    @Test
    fun `admin config save rejects rates exceeding the shared split policy before persistence`() {
        val authentication = mockk<Authentication>()
        val orderService = mockk<OrderService>()
        val orderStatusLogService = mockk<OrderStatusLogService>()
        val settlementRepository = mockk<SettlementRepository>()
        val configRepository = mockk<DoctorInstitutionProjectConfigRepository>()
        val accessService = mockk<ManagementAccessService>()
        every { accessService.actor(authentication) } returns adminActor()
        val policy = OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal("40.00")
        })
        val controller = AdminOrderController(
            orderService,
            orderStatusLogService,
            settlementRepository,
            configRepository,
            accessService,
            policy
        )

        val request = UpsertConfigRequest(
            doctorId = "doctor-1",
            institutionProjectId = "project-1",
            consultationFee = BigDecimal.ZERO,
            commissionRate = BigDecimal("20.01"),
            institutionRate = BigDecimal("40.00")
        )

        val error = assertThrows<IllegalArgumentException> {
            controller.upsertConfig(authentication, request)
        }

        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
        verify(exactly = 0) { configRepository.save(any()) }
    }

    private fun adminActor() = ManagementActor(
        userId = "admin-1",
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )
}
