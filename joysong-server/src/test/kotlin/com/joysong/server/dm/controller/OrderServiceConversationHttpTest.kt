package com.joysong.server.dm.controller

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.dm.service.DmService
import com.joysong.server.dm.service.OrderServiceConversationService
import com.joysong.server.order.service.OrderContractException
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [OrderServiceConversationController::class, DmController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [JwtAuthenticationFilter::class]
        )
    ]
)
@Import(GlobalExceptionHandler::class)
class OrderServiceConversationHttpTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @MockBean lateinit var orderConversation: OrderServiceConversationService
    @MockBean lateinit var dm: DmService

    @Test
    @WithMockUser(username = "consultant-1")
    fun `service conversation and dm endpoints expose typed status`() {
        given(orderConversation.getOrCreate("order-1", "consultant-1"))
            .willThrow(OrderContractException.serviceReadOnly())
        mvc.perform(post("/api/orders/order-1/service-conversation").with(actor()).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ORDER_SERVICE_READ_ONLY"))

        given(dm.getMessages("conversation-1", "consultant-1", 30, null))
            .willThrow(OrderContractException.roleRequired())
        mvc.perform(get("/api/dm/conversations/conversation-1/messages").with(actor()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.errorCode").value("CONSULTANT_ROLE_REQUIRED"))

        given(dm.sendMessage("conversation-1", "consultant-1", "hello", "TEXT"))
            .willThrow(OrderContractException.serviceReadOnly())
        mvc.perform(
            post("/api/dm/conversations/conversation-1/messages")
                .with(actor())
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"content":"hello"}""")
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ORDER_SERVICE_READ_ONLY"))

        given(dm.markAsRead("conversation-1", "consultant-1"))
            .willThrow(OrderContractException.serviceNotActive())
        mvc.perform(put("/api/dm/conversations/conversation-1/read").with(actor()).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ORDER_SERVICE_NOT_ACTIVE"))

        given(dm.deleteMessage("message-1", "consultant-1"))
            .willThrow(OrderContractException.serviceAccessDenied())
        mvc.perform(delete("/api/dm/messages/message-1").with(actor()).with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.errorCode").value("ORDER_SERVICE_ACCESS_DENIED"))
    }

    private fun actor() = authentication(
        UsernamePasswordAuthenticationToken(
            "consultant-1",
            "n/a",
            listOf(SimpleGrantedAuthority("ROLE_USER"))
        )
    )
}
