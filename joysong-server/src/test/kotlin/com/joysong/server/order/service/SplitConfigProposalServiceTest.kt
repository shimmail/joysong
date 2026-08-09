package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal

class SplitConfigProposalServiceTest {
    @Test
    fun `proposal submit rejects rates exceeding the shared split policy before database access`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val accessService = mockk<ManagementAccessService>()
        val policy = OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal("40.00")
        })
        val service = SplitConfigProposalService(jdbcTemplate, accessService, policy)

        val error = assertThrows<IllegalArgumentException> {
            service.submit(
                adminActor(),
                SplitConfigProposalRequest(
                    doctorId = "doctor-1",
                    institutionProjectId = "project-1",
                    commissionRate = BigDecimal("20.01"),
                    institutionRate = BigDecimal("40.00")
                )
            )
        }

        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
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
