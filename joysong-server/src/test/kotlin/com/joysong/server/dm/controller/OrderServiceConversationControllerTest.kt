package com.joysong.server.dm.controller

import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.service.OrderServiceConversationService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication

class OrderServiceConversationControllerTest {
    @Test
    fun `post derives actor from authentication and accepts only order id`() {
        val service = mockk<OrderServiceConversationService>()
        val authentication = mockk<Authentication>()
        val conversation = mockk<DmConversationResponse>()
        every { authentication.principal } returns "user-1"
        every { service.getOrCreate("order-1", "user-1") } returns conversation
        val controller = OrderServiceConversationController(service)

        val response = controller.getOrCreate("order-1", authentication)

        assertEquals(conversation, response.data)
        verify(exactly = 1) { service.getOrCreate("order-1", "user-1") }
    }
}
