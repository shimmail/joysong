package com.joysong.server.payment.provider

import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.function.Supplier

class SimulatedAlipayPlusPaymentGatewayTest {
    private val now = Instant.parse("2026-08-22T08:00:00Z")

    @Test
    fun `development payment succeeds immediately without a cashier redirect`() {
        val result = gateway().createPayment(
            ProviderCreatePaymentRequest(
                paymentId = "payment-1",
                orderId = "order-1",
                amountMinor = 12_345,
                currency = "USD",
                paymentMethod = "ALIPAY_PLUS_CASHIER",
                idempotencyKey = "payment-create-payment-1"
            )
        )

        assertEquals(PaymentProvider.ALIPAY_PLUS, gateway().provider)
        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals("simulated-alipay-plus-payment-payment-1", result.providerPaymentId)
        assertEquals("simulated-alipay-plus-transaction-payment-1", result.providerTransactionId)
        assertEquals(12_345, result.amountMinor)
        assertEquals("USD", result.currency)
        assertEquals(LocalDateTime.ofInstant(now, ZoneOffset.UTC), result.paidAt)
        assertNull(result.nextAction)
    }

    @Test
    fun `development refund succeeds immediately with a stable simulated id`() {
        val request = ProviderRefundRequest(
            refundItemId = "refund-item-1",
            providerPaymentId = "simulated-alipay-plus-payment-payment-1",
            amountMinor = 12_345,
            currency = "USD",
            idempotencyKey = "refund-refund-1-payment-1"
        )

        val result = gateway().refund(request)

        assertEquals(PaymentStatus.SUCCEEDED, result.status)
        assertEquals("simulated-alipay-plus-refund-refund-item-1", result.providerRefundId)
        assertNull(result.failureCode)
        assertNull(result.failureMessage)
    }

    @Test
    fun `development refund refuses a real provider payment id`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            gateway().refund(
                ProviderRefundRequest(
                    refundItemId = "refund-item-1",
                    providerPaymentId = "202608221234567890",
                    amountMinor = 12_345,
                    currency = "USD",
                    idempotencyKey = "refund-refund-1-payment-1"
                )
            )
        }

        assertEquals("SIMULATED_PAYMENT_ID_REQUIRED", error.message)
    }

    @Test
    fun `simulator bean loads only when enabled in development`() {
        context(profile = "dev", enabled = true).run { context ->
            assertTrue(context.containsBean("simulatedAlipayPlusPaymentGateway"))
        }
        context(profile = "dev", enabled = false).run { context ->
            assertFalse(context.containsBean("simulatedAlipayPlusPaymentGateway"))
        }
        context(profile = "prod", enabled = true).run { context ->
            assertFalse(context.containsBean("simulatedAlipayPlusPaymentGateway"))
        }
    }

    private fun gateway() = SimulatedAlipayPlusPaymentGateway(
        Clock.fixed(now, ZoneOffset.UTC)
    )

    private fun context(profile: String, enabled: Boolean) = ApplicationContextRunner()
        .withBean(Clock::class.java, Supplier { Clock.fixed(now, ZoneOffset.UTC) })
        .withUserConfiguration(SimulatedAlipayPlusPaymentGateway::class.java)
        .withInitializer { applicationContext ->
            applicationContext.environment.setActiveProfiles(profile)
        }
        .withPropertyValues("payment.alipay-plus.simulated-enabled=$enabled")
}
