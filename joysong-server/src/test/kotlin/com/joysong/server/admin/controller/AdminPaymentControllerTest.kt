package com.joysong.server.admin.controller

import com.joysong.server.payment.domain.PaymentCompensationStatus
import com.joysong.server.payment.entity.PaymentCompensationCaseEntity
import com.joysong.server.payment.service.PaymentCompensationService
import com.joysong.server.payment.service.PaymentService
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.core.Authentication

class AdminPaymentControllerTest {
    @Test
    fun `compensation retry is visible and runs as the authenticated admin`() {
        val paymentService = mockk<PaymentService>()
        val compensationService = mockk<PaymentCompensationService>()
        val authentication = mockk<Authentication>()
        val compensation = PaymentCompensationCaseEntity(
            id = "compensation-1",
            paymentId = "payment-1",
            orderId = "order-1",
            userId = "user-1",
            provider = "ALIPAY_PLUS",
            providerPaymentId = "provider-payment-1",
            amountMinor = 39_999,
            currency = "USD",
            reasonCode = "PAYMENT_SUCCEEDED_AMOUNT_MISMATCH",
            status = PaymentCompensationStatus.PROCESSING.name,
            idempotencyKey = "payment-compensation-payment-1"
        )
        every { authentication.principal } returns "admin-1"
        every { compensationService.adminListAll() } returns listOf(compensation)
        every { compensationService.retryCompensationRefund("compensation-1", "admin-1") } returns compensation
        val controller = AdminPaymentController(paymentService, compensationService)

        val listed = controller.listPaymentCompensations()
        val retried = controller.retryPaymentCompensation("compensation-1", authentication)

        assertEquals(listOf(compensation), listed.data)
        assertEquals(compensation, retried.data)
        verify(exactly = 1) { compensationService.retryCompensationRefund("compensation-1", "admin-1") }
    }
}
