package com.joysong.server.order.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.service.PaymentService
import com.joysong.server.payment.service.PaymentSessionResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.math.BigDecimal
import java.util.function.Supplier

class DevelopmentOrderAutoPaymentServiceTest {
    @Test
    fun `auto payment fixes the service fee contract and reuses the order derived key`() {
        val paymentService = mockk<PaymentService>()
        val payment = payment(PaymentStatus.SUCCEEDED)
        every {
            paymentService.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "dev-order-autopay-order-1"
            )
        } returns PaymentSessionResult(payment)
        val service = DevelopmentOrderAutoPaymentService(paymentService)

        val first = service.attempt("order-1", "user-1")
        val replay = service.attempt("order-1", "user-1")

        assertTrue(first.successful)
        assertSame(payment, first.payment)
        assertEquals(first, replay)
        verify(exactly = 2) {
            paymentService.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "dev-order-autopay-order-1"
            )
        }
    }

    @Test
    fun `provider failures become logged non-success results without leaking provider detail`() {
        val appender = logAppender()
        val cases = listOf(
            PaymentProviderException(
                errorCode = "PAYMENT_DECLINED",
                retryable = false,
                outcomeUnknown = false,
                message = "secret decline detail"
            ),
            PaymentProviderException(
                errorCode = "PROVIDER_TIMEOUT",
                retryable = true,
                outcomeUnknown = true,
                message = "secret timeout detail"
            )
        )

        cases.forEach { providerError ->
            val paymentService = mockk<PaymentService>()
            every {
                paymentService.createPaymentSession(any(), any(), any(), any(), any(), any())
            } throws providerError

            val result = DevelopmentOrderAutoPaymentService(paymentService)
                .attempt("order-1", "user-1")

            assertFalse(result.successful)
            assertNull(result.payment)
            assertEquals(providerError.errorCode, result.failureCode)
            assertEquals(providerError.outcomeUnknown, result.outcomeUnknown)
        }

        val logs = appender.list.joinToString("\n") { it.formattedMessage }
        assertTrue(logs.contains("orderId=order-1"))
        assertTrue(logs.contains("errorCode=PAYMENT_DECLINED"))
        assertTrue(logs.contains("errorCode=PROVIDER_TIMEOUT"))
        assertTrue(logs.contains("outcomeUnknown=true"))
        assertFalse(logs.contains("secret decline detail"))
        assertFalse(logs.contains("secret timeout detail"))
    }

    @Test
    fun `non-terminal provider result is reported as non-success`() {
        val paymentService = mockk<PaymentService>()
        val payment = payment(PaymentStatus.PROCESSING)
        every {
            paymentService.createPaymentSession(any(), any(), any(), any(), any(), any())
        } returns PaymentSessionResult(payment)

        val result = DevelopmentOrderAutoPaymentService(paymentService)
            .attempt("order-1", "user-1")

        assertFalse(result.successful)
        assertSame(payment, result.payment)
        assertEquals(PaymentStatus.PROCESSING.name, result.failureCode)
        assertTrue(result.outcomeUnknown)
    }

    @Test
    fun `auto payment bean requires development profile and both payment flags`() {
        context(listOf("dev"), simulatedEnabled = true, autoPayEnabled = true).run { context ->
            assertTrue(context.containsBean("developmentOrderAutoPaymentService"))
        }
        listOf(
            Triple(listOf("dev"), false, true),
            Triple(listOf("dev"), true, false),
            Triple(listOf("dev"), false, false),
            Triple(listOf("prod"), true, true),
            Triple(listOf("dev", "prod"), true, true)
        ).forEach { (profiles, simulatedEnabled, autoPayEnabled) ->
            context(profiles, simulatedEnabled, autoPayEnabled).run { context ->
                assertFalse(
                    context.containsBean("developmentOrderAutoPaymentService"),
                    "$profiles simulated=$simulatedEnabled autoPay=$autoPayEnabled"
                )
            }
        }
    }

    private fun context(
        profiles: List<String>,
        simulatedEnabled: Boolean,
        autoPayEnabled: Boolean
    ) = ApplicationContextRunner()
        .withBean(PaymentService::class.java, Supplier { mockk(relaxed = true) })
        .withUserConfiguration(DevelopmentOrderAutoPaymentService::class.java)
        .withInitializer { context -> context.environment.setActiveProfiles(*profiles.toTypedArray()) }
        .withPropertyValues(
            "payment.alipay-plus.simulated-enabled=$simulatedEnabled",
            "payment.development.order-auto-pay-enabled=$autoPayEnabled"
        )

    private fun payment(status: PaymentStatus) = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("400.00"),
        method = "ONLINE",
        status = status.name,
        paymentType = PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
        provider = PaymentProvider.ALIPAY_PLUS.name,
        paymentMethod = "ALIPAY_PLUS_CASHIER",
        currency = "USD",
        amountMinor = 40_000,
        idempotencyKey = "dev-order-autopay-order-1"
    )

    private fun logAppender(): ListAppender<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DevelopmentOrderAutoPaymentService::class.java) as Logger
        return ListAppender<ILoggingEvent>().also {
            it.start()
            logger.addAppender(it)
        }
    }
}
