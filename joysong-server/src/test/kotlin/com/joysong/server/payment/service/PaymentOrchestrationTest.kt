package com.joysong.server.payment.service

import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.repository.PaymentRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDateTime

class PaymentOrchestrationTest {
    private val paymentRepository = mockk<PaymentRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val orderStatusLogService = mockk<OrderStatusLogService>()
    private val persistence = mockk<PaymentPersistenceService>()
    private val gateway = mockk<PaymentGateway>()

    @Test
    fun `provider create uses payment scoped stable idempotency key`() {
        val prepared = payment(status = PaymentStatus.CREATED.name)
        val succeeded = prepared.copy(status = PaymentStatus.SUCCEEDED.name, providerPaymentId = "pi_1")
        val request = slot<ProviderCreatePaymentRequest>()
        every { gateway.provider } returns PaymentProvider.STRIPE
        every {
            persistence.prepareAttempt(any(), any(), any(), any(), any(), any())
        } returns prepared
        every { gateway.createPayment(capture(request)) } returns ProviderPaymentResult(
            PaymentStatus.SUCCEEDED,
            "pi_1",
            amountMinor = 1000,
            currency = "USD"
        )
        every { persistence.applyProviderResult(prepared.id, any()) } returns succeeded

        val result = service().createPaymentSession(
            prepared.orderId,
            prepared.userId,
            PaymentType.CONSULTATION_FEE,
            PaymentProvider.STRIPE,
            "card",
            "client-key-123"
        )

        assertEquals("payment-create-${prepared.id}", request.captured.idempotencyKey)
        assertEquals(PaymentStatus.SUCCEEDED.name, result.payment.status)
    }

    @Test
    fun `unknown provider outcome remains processing for reconciliation`() {
        val prepared = payment(status = PaymentStatus.CREATED.name)
        every { gateway.provider } returns PaymentProvider.STRIPE
        every {
            persistence.prepareAttempt(any(), any(), any(), any(), any(), any())
        } returns prepared
        every { gateway.createPayment(any()) } throws PaymentProviderException(
            "PROVIDER_TIMEOUT",
            retryable = true,
            outcomeUnknown = true
        )
        every {
            persistence.markProviderError(
                prepared.id,
                PaymentStatus.PROCESSING,
                "PROVIDER_TIMEOUT",
                any()
            )
        } returns prepared.copy(status = PaymentStatus.PROCESSING.name)

        assertThrows(PaymentProviderException::class.java) {
            service().createPaymentSession(
                prepared.orderId,
                prepared.userId,
                PaymentType.CONSULTATION_FEE,
                PaymentProvider.STRIPE,
                "CARD",
                "client-key-123"
            )
        }
        verify(exactly = 1) {
            persistence.markProviderError(
                prepared.id,
                PaymentStatus.PROCESSING,
                "PROVIDER_TIMEOUT",
                any()
            )
        }
    }

    @Test
    fun `unavailable Alipay Plus is rejected before local attempt persistence`() {
        val service = PaymentService(
            paymentRepository,
            orderRepository,
            orderStatusLogService,
            PaymentGatewayRegistry(emptyList()),
            persistence
        )

        val error = assertThrows(PaymentProviderException::class.java) {
            service.createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "client-key-123"
            )
        }

        assertEquals("PAYMENT_PROVIDER_UNAVAILABLE", error.errorCode)
        verify(exactly = 0) { persistence.prepareAttempt(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `service fee attempt snapshots USD amount and one local thirty minute deadline`() {
        val repositories = persistenceRepositories()
        val saved = slot<PaymentEntity>()
        every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123") } returns null
        every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder()
        every {
            repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                "order-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                PaymentStatus.successfulDatabaseValues
            )
        } returns null
        every {
            repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
                "order-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                any()
            )
        } returns null
        every { repositories.payment.saveAndFlush(capture(saved)) } answers { saved.captured }
        val persistence = PaymentPersistenceService(repositories.payment, repositories.order, repositories.log)

        val before = LocalDateTime.now()
        val attempt = persistence.prepareAttempt(
            "order-1",
            "user-1",
            PaymentType.TRAVEL_GROUND_SERVICE_FEE,
            PaymentProvider.ALIPAY_PLUS,
            "ALIPAY_PLUS_CASHIER",
            "client-key-123"
        )
        val after = LocalDateTime.now()

        assertEquals(40_000L, attempt.amountMinor)
        assertEquals(BigDecimal("400.00"), attempt.amount)
        assertEquals("USD", attempt.currency)
        assertEquals(attempt.createdAt.plusMinutes(30), attempt.expiresAt)
        check(!attempt.createdAt.isBefore(before) && !attempt.createdAt.isAfter(after))
    }

    @Test
    fun `active or successful service fee payment prevents another local attempt`() {
        val statuses = listOf(
            PaymentStatus.CREATED.name,
            PaymentStatus.REQUIRES_ACTION.name,
            PaymentStatus.PROCESSING.name,
            PaymentStatus.SUCCEEDED.name
        )

        statuses.forEach { status ->
            val repositories = persistenceRepositories()
            val existing = travelPayment(status = status, idempotencyKey = "first-key-123")
            every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "retry-key-123") } returns null
            every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder()
            every {
                repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                    "order-1",
                    PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                    PaymentStatus.successfulDatabaseValues
                )
            } returns existing.takeIf { status == PaymentStatus.SUCCEEDED.name }
            if (status != PaymentStatus.SUCCEEDED.name) {
                every {
                    repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
                        "order-1",
                        PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                        any()
                    )
                } returns existing
            }

            val result = PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).prepareAttempt(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "retry-key-123"
            )

            assertSame(existing, result, status)
            verify(exactly = 0) { repositories.payment.saveAndFlush(any()) }
        }
    }

    @Test
    fun `failed or expired service fee payment allows retry with a new idempotency key`() {
        listOf(PaymentStatus.FAILED, PaymentStatus.EXPIRED).forEach { previousStatus ->
            val repositories = persistenceRepositories()
            every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "retry-key-123") } returns null
            every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder()
            every {
                repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                    "order-1",
                    PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                    PaymentStatus.successfulDatabaseValues
                )
            } returns null
            every {
                repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
                    "order-1",
                    PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                    any()
                )
            } returns null
            every { repositories.payment.saveAndFlush(any()) } answers { firstArg() }

            val result = PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).prepareAttempt(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "retry-key-123"
            )

            assertEquals(PaymentStatus.CREATED.name, result.status, previousStatus.name)
            verify(exactly = 1) { repositories.payment.saveAndFlush(any()) }
        }
    }

    @Test
    fun `provider expiry is clamped to the local deadline`() {
        val localDeadline = LocalDateTime.of(2026, 8, 22, 12, 30)
        listOf(
            localDeadline.minusMinutes(5) to localDeadline.minusMinutes(5),
            localDeadline.plusMinutes(5) to localDeadline,
            null to localDeadline
        ).forEach { (providerDeadline, expected) ->
            val repositories = persistenceRepositories()
            val prepared = travelPayment(
                status = PaymentStatus.CREATED.name,
                expiresAt = localDeadline
            )
            every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
            every { repositories.payment.save(any()) } answers { firstArg() }

            val updated = PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).applyProviderResult(
                prepared.id,
                ProviderPaymentResult(
                    status = PaymentStatus.REQUIRES_ACTION,
                    providerPaymentId = "alipay-1",
                    amountMinor = 40_000,
                    currency = "USD",
                    expiresAt = providerDeadline
                )
            )

            assertEquals(expected, updated.expiresAt)
        }
    }

    @Test
    fun `task three records verified service fee success without activating the order`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdForUpdate(prepared.orderId) } returns travelOrder()

        val updated = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-1",
                providerTransactionId = "txn-1",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, updated.status)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    private fun service() = PaymentService(
        paymentRepository,
        orderRepository,
        orderStatusLogService,
        PaymentGatewayRegistry(listOf(gateway)),
        persistence
    )

    private fun payment(status: String) = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("10.00"),
        method = "ONLINE",
        status = status,
        paymentType = PaymentType.CONSULTATION_FEE.name,
        provider = PaymentProvider.STRIPE.name,
        paymentMethod = "CARD",
        currency = "USD",
        amountMinor = 1000
    )

    private data class PersistenceRepositories(
        val payment: PaymentRepository,
        val order: OrderRepository,
        val log: OrderStatusLogService
    )

    private fun persistenceRepositories() = PersistenceRepositories(
        payment = mockk(),
        order = mockk(),
        log = mockk()
    )

    private fun travelOrder() = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        totalAmountMinor = 40_000,
        currency = "USD",
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        travelGroundServiceFeeMinor = 40_000,
        status = "PENDING_SERVICE_FEE"
    )

    private fun travelPayment(
        status: String,
        idempotencyKey: String = "client-key-123",
        expiresAt: LocalDateTime = LocalDateTime.of(2026, 8, 22, 12, 30)
    ) = PaymentEntity(
        id = "payment-1",
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal("400.00"),
        method = "ONLINE",
        status = status,
        paymentType = PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
        provider = PaymentProvider.ALIPAY_PLUS.name,
        paymentMethod = "ALIPAY_PLUS_CASHIER",
        currency = "USD",
        amountMinor = 40_000,
        idempotencyKey = idempotencyKey,
        expiresAt = expiresAt
    )
}
