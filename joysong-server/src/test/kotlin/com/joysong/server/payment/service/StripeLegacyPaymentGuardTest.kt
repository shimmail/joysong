package com.joysong.server.payment.service

import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.repository.RefundItemRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier

class StripeLegacyPaymentGuardTest {
    private val payments = mockk<PaymentRepository>()
    private val refundItems = mockk<RefundItemRepository>()

    @Test
    fun `disabled legacy adapter blocks actionable Stripe payments with conservative status sets`() {
        val inFlight = slot<Collection<String>>()
        val refundable = slot<Collection<String>>()
        val known = slot<Collection<String>>()
        every {
            payments.countActionableLiabilitiesByProvider(
                "STRIPE",
                capture(inFlight),
                capture(refundable),
                capture(known)
            )
        } returns 1

        val error = assertThrows(IllegalStateException::class.java) {
            guard().afterSingletonsInstantiated()
        }

        assertEquals("STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER", error.message)
        assertEquals(
            setOf("CREATED", "REQUIRES_ACTION", "PROCESSING"),
            inFlight.captured.toSet()
        )
        assertEquals(
            setOf("SUCCEEDED", "SUCCESS", "PARTIALLY_REFUNDED", "REFUNDED"),
            refundable.captured.toSet()
        )
        assertEquals(PaymentStatus.entries.map { it.name }.toSet() + "SUCCESS", known.captured.toSet())
        verify(exactly = 0) { refundItems.countUnresolvedLiabilitiesByProvider(any(), any()) }
    }

    @Test
    fun `disabled legacy adapter blocks every non-success Stripe refund item`() {
        val successful = slot<Collection<String>>()
        every { payments.countActionableLiabilitiesByProvider(any(), any(), any(), any()) } returns 0
        every {
            refundItems.countUnresolvedLiabilitiesByProvider("STRIPE", capture(successful))
        } returns 1

        val error = assertThrows(IllegalStateException::class.java) {
            guard().afterSingletonsInstantiated()
        }

        assertEquals("STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER", error.message)
        assertEquals(setOf("SUCCEEDED", "SUCCESS"), successful.captured.toSet())
    }

    @Test
    fun `disabled legacy adapter allows startup only when both liability queries are empty`() {
        every { payments.countActionableLiabilitiesByProvider(any(), any(), any(), any()) } returns 0
        every { refundItems.countUnresolvedLiabilitiesByProvider(any(), any()) } returns 0

        assertDoesNotThrow { guard().afterSingletonsInstantiated() }

        verifyAll {
            payments.countActionableLiabilitiesByProvider(any(), any(), any(), any())
            refundItems.countUnresolvedLiabilitiesByProvider(any(), any())
        }
    }

    @Test
    fun `literal true ignoring case enables legacy adapter without querying`() {
        assertDoesNotThrow { guard(legacyEnabled = "TRUE").afterSingletonsInstantiated() }

        verify(exactly = 0) { payments.countActionableLiabilitiesByProvider(any(), any(), any(), any()) }
        verify(exactly = 0) { refundItems.countUnresolvedLiabilitiesByProvider(any(), any()) }
    }

    @Test
    fun `boolean aliases whitespace and other values fail closed before querying`() {
        listOf("yes", "on", "1", " true ", "false ", "invalid").forEach { value ->
            val error = assertThrows(IllegalStateException::class.java) {
                guard(legacyEnabled = value).afterSingletonsInstantiated()
            }

            assertEquals("STRIPE_LEGACY_ENABLED_INVALID", error.message)
        }

        verify(exactly = 0) { payments.countActionableLiabilitiesByProvider(any(), any(), any(), any()) }
        verify(exactly = 0) { refundItems.countUnresolvedLiabilitiesByProvider(any(), any()) }
    }

    @Test
    fun `payment query error fails closed with the operator error code`() {
        val databaseError = IllegalStateException("database unavailable")
        every {
            payments.countActionableLiabilitiesByProvider(any(), any(), any(), any())
        } throws databaseError

        val error = assertThrows(IllegalStateException::class.java) {
            guard().afterSingletonsInstantiated()
        }

        assertEquals("STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER", error.message)
        assertEquals(databaseError, error.cause)
    }

    @Test
    fun `refund query error fails closed with the operator error code`() {
        val databaseError = IllegalStateException("refund query unavailable")
        every { payments.countActionableLiabilitiesByProvider(any(), any(), any(), any()) } returns 0
        every { refundItems.countUnresolvedLiabilitiesByProvider(any(), any()) } throws databaseError

        val error = assertThrows(IllegalStateException::class.java) {
            guard().afterSingletonsInstantiated()
        }

        assertEquals("STRIPE_LEGACY_PAYMENTS_REQUIRE_ADAPTER", error.message)
        assertEquals(databaseError, error.cause)
    }

    @Test
    fun `invalid legacy flag fails context creation instead of disabling both controls`() {
        ApplicationContextRunner()
            .withBean(PaymentRepository::class.java, Supplier { mockk() })
            .withBean(RefundItemRepository::class.java, Supplier { mockk() })
            .withUserConfiguration(StripeLegacyPaymentGuard::class.java)
            .withPropertyValues("payment.stripe.legacy-enabled=not-a-boolean")
            .run { context -> assertNotNull(context.startupFailure) }
    }

    private fun guard(legacyEnabled: String = "false") = StripeLegacyPaymentGuard(
        paymentRepository = payments,
        refundItemRepository = refundItems,
        legacyEnabled = legacyEnabled
    )
}
