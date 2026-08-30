package com.joysong.server.order.consultant

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.order.service.OrderContractException
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.context.annotation.Import
import org.springframework.security.test.context.support.WithMockUser
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [ConsultantOrderController::class],
    excludeFilters = [
        ComponentScan.Filter(
            type = FilterType.ASSIGNABLE_TYPE,
            classes = [JwtAuthenticationFilter::class],
        ),
    ],
)
@Import(GlobalExceptionHandler::class)
class ConsultantOrderControllerHttpTest @Autowired constructor(
    private val mvc: MockMvc,
) {
    @MockBean lateinit var service: ConsultantOrderQueryService

    @Test
    @WithMockUser(username = "consultant-1")
    fun `list derives consultant from authentication and parses offset`() {
        given(
            service.list(
                "consultant-1",
                ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 7, 20),
            ),
        ).willReturn(emptyPage(offset = 7, limit = 20))

        mvc.perform(get("/api/consultant/orders").param("offset", "7"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.offset").value(7))

        verify(service).list("consultant-1", ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 7, 20))
    }

    @Test
    @WithMockUser(username = "consultant-1")
    fun `invalid stage and pagination return stable HTTP 400`() {
        listOf(
            "/api/consultant/orders?stage=active",
            "/api/consultant/orders?offset=-1",
            "/api/consultant/orders?limit=101",
            "/api/consultant/orders?limit=nope",
        ).forEach { path ->
            mvc.perform(get(path))
                .andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.errorCode").value("INVALID_CONSULTANT_ORDER_STAGE"))
        }

        verifyNoInteractions(service)
    }

    @Test
    @WithMockUser(username = "user-1")
    fun `revoked consultant uses real HTTP 403`() {
        given(service.detail("user-1", "order-1"))
            .willThrow(OrderContractException.roleRequired())

        mvc.perform(get("/api/consultant/orders/order-1"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.code").value(403))
            .andExpect(jsonPath("$.errorCode").value("CONSULTANT_ROLE_REQUIRED"))
    }

    private fun emptyPage(offset: Int, limit: Int) = ConsultantOrderPageResponse(
        items = emptyList(),
        offset = offset,
        limit = limit,
        hasMore = false,
    )
}
