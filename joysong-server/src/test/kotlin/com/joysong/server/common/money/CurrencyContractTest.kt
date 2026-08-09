package com.joysong.server.common.money

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.admin.entity.dto.ProjectRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.entity.OrderEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CurrencyContractTest {
    @Test
    fun `omitted currency defaults to USD on price-bearing models`() {
        assertEquals(CurrencyCode.USD, CurrencyCode.DEFAULT)
        assertEquals(CurrencyCode.USD, ProjectRequest().currency)
        assertEquals("USD", OrderEntity(id = "o-1", userId = "u-1", projectName = "p", price = BigDecimal.TEN, status = "PENDING").currency)
    }

    @Test
    fun `explicit USD is preserved`() {
        assertEquals(CurrencyCode.USD, ProjectRequest(currency = CurrencyCode.USD).currency)
    }

    @Test
    fun `order response serializes currency`() {
        val response = OrderResponse.from(
            OrderEntity(id = "o-1", userId = "u-1", projectName = "p", price = BigDecimal.TEN, status = "PENDING")
        )

        val json = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(response)

        assertTrue(json.contains("\"currency\":\"USD\""))
    }
}
