package com.joysong.server.admin.controller

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.service.OrderSplitRatePolicy
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class OrderSplitPolicyControllerTest {
    @Test
    fun `policy endpoint returns configured platform rate in BaseResponse`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
        every { access.actor(authentication) } returns actor
        val properties = OrderSplitProperties().apply { platformRate = BigDecimal("37.50") }

        val response = OrderSplitPolicyController(OrderSplitRatePolicy(properties), access)
            .get(authentication)

        assertEquals(200, response.code)
        assertEquals(BigDecimal("37.50"), response.data?.platformRate)
        verify(exactly = 1) { access.actor(authentication) }
    }

    @Test
    fun `policy endpoint propagates denied management access`() {
        val authentication = mockk<Authentication>()
        val access = mockk<ManagementAccessService>()
        every { access.actor(authentication) } throws AccessDeniedException("无权访问")
        val controller = OrderSplitPolicyController(
            OrderSplitRatePolicy(OrderSplitProperties()),
            access
        )

        assertThrows<AccessDeniedException> { controller.get(authentication) }
        verify(exactly = 1) { access.actor(authentication) }
    }
}
