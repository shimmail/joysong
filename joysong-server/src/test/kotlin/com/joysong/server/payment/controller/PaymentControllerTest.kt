package com.joysong.server.payment.controller

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
}
