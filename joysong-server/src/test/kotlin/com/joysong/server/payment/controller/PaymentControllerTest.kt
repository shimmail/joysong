package com.joysong.server.payment.controller

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.payment.dto.PaymentNextActionResponse
import com.joysong.server.payment.provider.PaymentNextAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.web.bind.annotation.PostMapping

class PaymentControllerTest {
    @Test
    fun `public controller does not expose client payment confirmation`() {
        val postMappings = PaymentController::class.java.declaredMethods
            .mapNotNull { it.getAnnotation(PostMapping::class.java) }
            .flatMap { it.value.toList() }

        assertFalse(postMappings.any { it.contains("confirm") })
    }

    @Test
    fun `redirect next action exposes only provider-neutral response fields`() {
        val mapper = jacksonObjectMapper()
        val response = PaymentNextActionResponse.from(
            PaymentNextAction.Redirect("https://cashier.example/pay/attempt-1")
        )
        val json = mapper.readTree(mapper.writeValueAsString(response))

        assertEquals(setOf("type", "url"), json.fieldNames().asSequence().toSet())
        assertEquals("REDIRECT", json["type"].asText())
        assertEquals("https://cashier.example/pay/attempt-1", json["url"].asText())
    }
}
