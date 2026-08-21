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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
    fun `new flow rejects medical payment types explicitly`() {
        val repositories = persistenceRepositories()
        every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123") } returns null
        every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder()

        val error = assertThrows(IllegalArgumentException::class.java) {
            PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).prepareAttempt(
                "order-1",
                "user-1",
                PaymentType.BALANCE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "client-key-123"
            )
        }

        assertEquals("MEDICAL_PAYMENT_NOT_SUPPORTED", error.message)
        verify(exactly = 0) { repositories.payment.saveAndFlush(any()) }
    }

    @Test
    fun `active or successful service fee payment prevents another local attempt`() {
        val statuses = listOf(
            PaymentStatus.CREATED.name,
            PaymentStatus.REQUIRES_ACTION.name,
            PaymentStatus.PROCESSING.name,
            PaymentStatus.SUCCEEDED.name,
            "SUCCESS"
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
            } returns existing.takeIf { status in PaymentStatus.successfulDatabaseValues }
            if (status !in PaymentStatus.successfulDatabaseValues) {
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
            val previous = travelPayment(previousStatus.name, idempotencyKey = "previous-key-123")
            val attempts = mutableListOf(previous)
            every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", any()) } answers {
                val key = secondArg<String>()
                attempts.firstOrNull { it.userId == "user-1" && it.idempotencyKey == key }
            }
            every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder()
            every {
                repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                    "order-1",
                    PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                    PaymentStatus.successfulDatabaseValues
                )
            } answers {
                val statuses = thirdArg<Collection<String>>()
                attempts.lastOrNull { it.status in statuses }
            }
            every {
                repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
                    "order-1",
                    PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                    any()
                )
            } answers {
                val statuses = thirdArg<Collection<String>>()
                attempts.firstOrNull { it.status in statuses }
            }
            every { repositories.payment.saveAndFlush(any()) } answers {
                firstArg<PaymentEntity>().also(attempts::add)
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

            assertEquals(PaymentStatus.CREATED.name, result.status, previousStatus.name)
            assertEquals(2, attempts.size, previousStatus.name)
            assertSame(previous, attempts.first(), previousStatus.name)
            verify(exactly = 1) { repositories.payment.saveAndFlush(any()) }
        }
    }

    @Test
    fun `cancelled order rejects same key nonterminal attempt before provider call`() {
        val repositories = persistenceRepositories()
        val gateway = mockk<PaymentGateway>()
        val existing = travelPayment(PaymentStatus.CREATED.name)
        every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
        every {
            repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123")
        } returns existing
        every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder("CANCELLED")

        val error = assertThrows(IllegalArgumentException::class.java) {
            travelService(repositories, gateway).createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "client-key-123"
            )
        }

        assertEquals("当前状态[CANCELLED]不允许支付旅游地接服务费", error.message)
        verify(exactly = 1) { repositories.order.findByIdForUpdate("order-1") }
        verify(exactly = 0) { gateway.createPayment(any()) }
        verify(exactly = 0) { gateway.queryPayment(any()) }
    }

    @Test
    fun `cancelled order rejects active overlap with new key before provider call`() {
        val repositories = persistenceRepositories()
        val gateway = mockk<PaymentGateway>()
        val existing = travelPayment(PaymentStatus.REQUIRES_ACTION.name, idempotencyKey = "previous-key-123")
        every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
        every {
            repositories.payment.findByUserIdAndIdempotencyKey("user-1", "retry-key-123")
        } returns null
        every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder("CANCELLED")
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
        } returns existing

        val error = assertThrows(IllegalArgumentException::class.java) {
            travelService(repositories, gateway).createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "retry-key-123"
            )
        }

        assertEquals("当前状态[CANCELLED]不允许支付旅游地接服务费", error.message)
        verify(exactly = 0) { gateway.createPayment(any()) }
        verify(exactly = 0) { gateway.queryPayment(any()) }
    }

    @Test
    fun `terminal same key returns old result without recontacting provider`() {
        listOf("SUCCESS", PaymentStatus.FAILED.name, PaymentStatus.EXPIRED.name).forEach { status ->
            val repositories = persistenceRepositories()
            val gateway = mockk<PaymentGateway>()
            val existing = travelPayment(status)
            every { gateway.provider } returns PaymentProvider.ALIPAY_PLUS
            every {
                repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123")
            } returns existing

            val result = travelService(repositories, gateway).createPaymentSession(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "client-key-123"
            )

            assertSame(existing, result.payment, status)
            verify(exactly = 0) { repositories.order.findByIdForUpdate(any()) }
            verify(exactly = 0) { gateway.createPayment(any()) }
            verify(exactly = 0) { gateway.queryPayment(any()) }
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
    fun `verified service fee success atomically activates bound service once`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        var orderState = travelOrder().copy(
            couponId = 7,
            userCouponId = 8,
            discountAmount = BigDecimal("9.99"),
            discountAmountMinor = 999
        )
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val providerResult = ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "alipay-1",
            providerTransactionId = "txn-1",
            amountMinor = 40_000,
            currency = "USD"
        )

        val persistence = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        )
        val updated = persistence.applyProviderResult(prepared.id, providerResult)
        val activatedAt = orderState.serviceActivatedAt

        assertEquals(PaymentStatus.SUCCEEDED.name, updated.status)
        assertNotNull(updated.paidAt)
        assertEquals("SERVICE_ACTIVE", orderState.status)
        assertNotNull(activatedAt)
        assertEquals(BigDecimal("400.00"), orderState.paidAmount)
        assertEquals(40_000L, orderState.paidAmountMinor)
        assertNull(orderState.paymentTime)
        assertNull(orderState.balancePaidAt)
        assertNull(orderState.verifiedAt)
        assertNull(orderState.verifyCode)
        assertEquals(7, orderState.couponId)
        assertEquals(8, orderState.userCouponId)
        assertEquals(BigDecimal("9.99"), orderState.discountAmount)
        assertEquals(999L, orderState.discountAmountMinor)
        verify(exactly = 1) { repositories.order.save(any()) }
        verify(exactly = 1) {
            repositories.log.logTransition(
                "order-1",
                "PENDING_SERVICE_FEE",
                "SERVICE_ACTIVE",
                "user-1",
                "USER",
                any()
            )
        }

        val replayed = persistence.applyProviderResult(prepared.id, providerResult)
        assertEquals(updated.paidAt, replayed.paidAt)
        assertEquals(activatedAt, orderState.serviceActivatedAt)
        verify(exactly = 1) { repositories.order.save(any()) }
        verify(exactly = 1) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `successful service fee result requires amount and currency`() {
        listOf(
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-missing-amount",
                amountMinor = null,
                currency = "USD"
            ) to "PAYMENT_AMOUNT_MISSING",
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-missing-currency",
                amountMinor = 40_000,
                currency = null
            ) to "PAYMENT_CURRENCY_MISSING",
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-wrong-amount",
                amountMinor = 39_999,
                currency = "USD"
            ) to "PAYMENT_AMOUNT_MISMATCH",
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-wrong-currency",
                amountMinor = 40_000,
                currency = "CNY"
            ) to "PAYMENT_CURRENCY_MISMATCH"
        ).forEach { (result, expectedMessage) ->
            val repositories = persistenceRepositories()
            val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
            every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
            every { repositories.payment.save(any()) } answers { firstArg() }
            every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns travelOrder()

            val error = assertThrows(IllegalArgumentException::class.java) {
                PaymentPersistenceService(
                    repositories.payment,
                    repositories.order,
                    repositories.log
                ).applyProviderResult(prepared.id, result)
            }

            assertEquals(expectedMessage, error.message)
        }
    }

    @Test
    fun `late second provider success keeps channel truth and flags duplicate without reactivation`() {
        val repositories = persistenceRepositories()
        val paymentStates = mutableMapOf(
            "payment-1" to travelPayment(PaymentStatus.PROCESSING.name, id = "payment-1"),
            "payment-2" to travelPayment(PaymentStatus.PROCESSING.name, id = "payment-2")
        )
        var orderState = travelOrder()
        every { repositories.payment.findByIdForUpdate(any()) } answers {
            paymentStates[firstArg<String>()]
        }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentStates[it.id] = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate("order-1") } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        val persistence = PaymentPersistenceService(repositories.payment, repositories.order, repositories.log)

        val first = persistence.applyProviderResult(
            "payment-1",
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-1",
                providerTransactionId = "txn-1",
                amountMinor = 40_000,
                currency = "USD"
            )
        )
        val firstActivationTime = orderState.serviceActivatedAt
        val second = persistence.applyProviderResult(
            "payment-2",
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-2",
                providerTransactionId = "txn-2",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, first.status)
        assertNotNull(firstActivationTime)
        assertEquals(PaymentStatus.SUCCEEDED.name, second.status)
        assertNotNull(second.paidAt)
        assertEquals("alipay-2", second.providerPaymentId)
        assertEquals("txn-2", second.providerTransactionId)
        assertEquals("DUPLICATE_PAYMENT_SUCCEEDED", second.failureCode)
        assertEquals(firstActivationTime, orderState.serviceActivatedAt)
        verify(exactly = 1) { repositories.order.save(any()) }
        verify(exactly = 1) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `cancel first then verified success keeps provider truth for manual refund`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns travelOrder("CANCELLED")

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-cancelled",
                providerTransactionId = "txn-cancelled",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertNotNull(result.paidAt)
        assertEquals("alipay-cancelled", result.providerPaymentId)
        assertEquals("txn-cancelled", result.providerTransactionId)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", result.failureCode)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `soft deleted order verified success keeps provider truth for manual refund`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns travelOrder().copy(
            deletedAt = LocalDateTime.of(2026, 8, 22, 13, 0)
        )

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-deleted",
                providerTransactionId = "txn-deleted",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertNotNull(result.paidAt)
        assertEquals("alipay-deleted", result.providerPaymentId)
        assertEquals("txn-deleted", result.providerTransactionId)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", result.failureCode)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing order verified success keeps provider truth for manual refund`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns null

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-missing-order",
                providerTransactionId = "txn-missing-order",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertNotNull(result.paidAt)
        assertEquals("alipay-missing-order", result.providerPaymentId)
        assertEquals("txn-missing-order", result.providerTransactionId)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", result.failureCode)
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

    private fun travelService(
        repositories: PersistenceRepositories,
        gateway: PaymentGateway
    ) = PaymentService(
        repositories.payment,
        repositories.order,
        repositories.log,
        PaymentGatewayRegistry(listOf(gateway)),
        PaymentPersistenceService(repositories.payment, repositories.order, repositories.log)
    )

    private fun travelOrder(status: String = "PENDING_SERVICE_FEE") = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        totalAmountMinor = 40_000,
        currency = "USD",
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        travelGroundServiceFeeMinor = 40_000,
        status = status
    )

    private fun travelPayment(
        status: String,
        idempotencyKey: String = "client-key-123",
        expiresAt: LocalDateTime = LocalDateTime.of(2026, 8, 22, 12, 30),
        id: String = "payment-1"
    ) = PaymentEntity(
        id = id,
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
