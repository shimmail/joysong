package com.joysong.server.payment.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.application.DevelopmentOrderAutoPaymentService
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentCompensationCaseEntity
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.PaymentProviderException
import com.joysong.server.payment.provider.SimulatedAlipayPlusPaymentGateway
import com.joysong.server.payment.provider.ProviderCreatePaymentRequest
import com.joysong.server.payment.provider.ProviderPaymentResult
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.payment.repository.PaymentCompensationCaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.aop.framework.ProxyFactory
import org.springframework.aop.support.AopUtils
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.TransactionManager
import org.junit.jupiter.api.Test
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class PaymentOrchestrationTest {
    private val paymentRepository = mockk<PaymentRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val orderStatusLogService = mockk<OrderStatusLogService>()
    private val businessNotificationService = mockk<BusinessNotificationService>(relaxed = true)
    private val persistence = mockk<PaymentPersistenceService>()
    private val gateway = mockk<PaymentGateway>()

    @Test
    fun `development adapter creates one succeeded payment and activates the order idempotently`() {
        val repositories = persistenceRepositories()
        val payments = mutableListOf<PaymentEntity>()
        var orderState = travelOrder()
        every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", any()) } answers {
            val key = secondArg<String>()
            payments.firstOrNull { it.userId == "user-1" && it.idempotencyKey == key }
        }
        every { repositories.order.findByIdForUpdate("order-1") } answers { orderState }
        every {
            repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtDesc(
                "order-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                PaymentStatus.successfulDatabaseValues
            )
        } answers {
            payments.lastOrNull { it.status in PaymentStatus.successfulDatabaseValues }
        }
        every {
            repositories.payment.findFirstByOrderIdAndPaymentTypeAndStatusInOrderByCreatedAtAsc(
                "order-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE.name,
                any()
            )
        } answers {
            val statuses = thirdArg<Collection<String>>()
            payments.firstOrNull { it.status in statuses }
        }
        every { repositories.payment.saveAndFlush(any()) } answers {
            firstArg<PaymentEntity>().also(payments::add)
        }
        every { repositories.payment.findByIdForUpdate(any()) } answers {
            payments.firstOrNull { it.id == firstArg<String>() }
        }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { updated ->
                val index = payments.indexOfFirst { it.id == updated.id }
                if (index >= 0) payments[index] = updated else payments.add(updated)
            }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate("order-1") } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        val adapter = DevelopmentOrderAutoPaymentService(
            travelService(repositories, SimulatedAlipayPlusPaymentGateway())
        )

        val first = adapter.attempt("order-1", "user-1")
        val replay = adapter.attempt("order-1", "user-1")

        assertTrue(first.successful)
        assertTrue(replay.successful)
        assertEquals(1, payments.size)
        assertEquals(PaymentStatus.SUCCEEDED.name, payments.single().status)
        assertEquals("dev-order-autopay-order-1", payments.single().idempotencyKey)
        assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, orderState.status)
        assertNotNull(orderState.serviceActivatedAt)
        verify(exactly = 1) { repositories.payment.saveAndFlush(any()) }
        verify(exactly = 1) { repositories.order.save(any()) }
    }

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
    fun `reconciliation expires an unsubmitted due attempt without creating a provider payment`() {
        val due = payment(status = PaymentStatus.CREATED.name).copy(
            expiresAt = LocalDateTime.now().minusSeconds(1)
        )
        val providerSuccess = due.copy(
            status = PaymentStatus.SUCCEEDED.name,
            providerPaymentId = "provider-created-too-late"
        )
        every { gateway.provider } returns PaymentProvider.STRIPE
        every { paymentRepository.findById(due.id) } returns Optional.of(due)
        every { paymentRepository.findByIdForUpdate(due.id) } returns due
        every { paymentRepository.save(any()) } answers { firstArg() }
        every { gateway.createPayment(any()) } returns ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "provider-created-too-late",
            amountMinor = due.amountMinor,
            currency = due.currency
        )
        every { persistence.applyProviderResult(due.id, any()) } returns providerSuccess

        val result = service().reconcilePayment(due.id)

        assertEquals(PaymentStatus.EXPIRED.name, result.status)
        verify(exactly = 1) { paymentRepository.findByIdForUpdate(due.id) }
        verify(exactly = 1) {
            paymentRepository.save(match { it.id == due.id && it.status == PaymentStatus.EXPIRED.name })
        }
        verify(exactly = 0) { gateway.createPayment(any()) }
        verify(exactly = 0) { persistence.applyProviderResult(any(), any()) }
    }

    @Test
    fun `reconciliation does not expire an attempt that gained a provider id under lock`() {
        val staleRead = payment(status = PaymentStatus.CREATED.name).copy(
            expiresAt = LocalDateTime.now().minusSeconds(1)
        )
        val providerAccepted = staleRead.copy(
            status = PaymentStatus.REQUIRES_ACTION.name,
            providerPaymentId = "provider-accepted"
        )
        val queried = providerAccepted.copy(status = PaymentStatus.PROCESSING.name)
        every { gateway.provider } returns PaymentProvider.STRIPE
        every { paymentRepository.findById(staleRead.id) } returns Optional.of(staleRead)
        every { paymentRepository.findByIdForUpdate(staleRead.id) } returns providerAccepted
        every { gateway.queryPayment("provider-accepted") } returns ProviderPaymentResult(
            status = PaymentStatus.PROCESSING,
            providerPaymentId = "provider-accepted"
        )
        every { persistence.applyProviderResult(staleRead.id, any()) } returns queried

        val result = service().reconcilePayment(staleRead.id)

        assertSame(queried, result)
        verify(exactly = 0) { paymentRepository.save(any()) }
        verify(exactly = 0) { gateway.createPayment(any()) }
        verify(exactly = 1) { gateway.queryPayment("provider-accepted") }
    }

    @Test
    fun `expired unknown outcome is recovered by request id without creating a new provider payment`() {
        val unknown = payment(status = PaymentStatus.PROCESSING.name).copy(
            expiresAt = LocalDateTime.now().minusSeconds(1)
        )
        var paymentState = unknown
        val recoveryRequest = slot<ProviderCreatePaymentRequest>()
        every { gateway.provider } returns PaymentProvider.STRIPE
        every { paymentRepository.findById(unknown.id) } answers { Optional.of(paymentState) }
        every { paymentRepository.findByIdForUpdate(unknown.id) } answers { paymentState }
        every { paymentRepository.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { gateway.recoverPayment(capture(recoveryRequest)) } returns ProviderPaymentResult(
            status = PaymentStatus.REQUIRES_ACTION,
            providerPaymentId = "provider-recovered",
            amountMinor = unknown.amountMinor,
            currency = unknown.currency
        )
        every { gateway.queryPayment("provider-recovered") } returns ProviderPaymentResult(
            status = PaymentStatus.PROCESSING,
            providerPaymentId = "provider-recovered",
            amountMinor = unknown.amountMinor,
            currency = unknown.currency
        )
        val realPersistence = PaymentPersistenceService(
            paymentRepository,
            orderRepository,
            orderStatusLogService
        )
        val realService = PaymentService(
            paymentRepository,
            orderRepository,
            orderStatusLogService,
            PaymentGatewayRegistry(listOf(gateway)),
            realPersistence,
            PaymentAttemptExpiryService(paymentRepository)
        )

        val recovered = realService.reconcilePayment(unknown.id)
        val queried = realService.reconcilePayment(unknown.id)

        assertEquals(PaymentStatus.REQUIRES_ACTION.name, recovered.status)
        assertEquals("provider-recovered", recovered.providerPaymentId)
        assertEquals(PaymentStatus.PROCESSING.name, queried.status)
        assertEquals(unknown.id, recoveryRequest.captured.paymentId)
        assertEquals(unknown.expiresAt, recoveryRequest.captured.expiresAt)
        verify(exactly = 1) { gateway.recoverPayment(any()) }
        verify(exactly = 1) { gateway.queryPayment("provider-recovered") }
        verify(exactly = 0) { gateway.createPayment(any()) }
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
    fun `service fee attempt uses the order thirty minute payment deadline`() {
        val repositories = persistenceRepositories()
        val saved = slot<PaymentEntity>()
        val orderCreatedAt = LocalDateTime.now().minusMinutes(20)
        every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123") } returns null
        every { repositories.order.findByIdForUpdate("order-1") } returns travelOrder(createdAt = orderCreatedAt)
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
        assertEquals(orderCreatedAt.plusMinutes(30), attempt.expiresAt)
        check(!attempt.createdAt.isBefore(before) && !attempt.createdAt.isAfter(after))
    }

    @Test
    fun `service fee attempt is rejected after the order payment deadline`() {
        val repositories = persistenceRepositories()
        every { repositories.payment.findByUserIdAndIdempotencyKey("user-1", "client-key-123") } returns null
        every { repositories.order.findByIdForUpdate("order-1") } returns
            travelOrder(createdAt = LocalDateTime.now().minusMinutes(31))

        val error = assertThrows(IllegalArgumentException::class.java) {
            PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).prepareAttempt(
                "order-1",
                "user-1",
                PaymentType.TRAVEL_GROUND_SERVICE_FEE,
                PaymentProvider.ALIPAY_PLUS,
                "ALIPAY_PLUS_CASHIER",
                "client-key-123"
            )
        }

        assertEquals("ORDER_PAYMENT_EXPIRED", error.message)
        verify(exactly = 0) { repositories.payment.saveAndFlush(any()) }
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
    fun `processing payment ignores stale earlier provider states without persistence`() {
        listOf(PaymentStatus.CREATED, PaymentStatus.REQUIRES_ACTION).forEach { staleStatus ->
            val repositories = persistenceRepositories()
            val processing = travelPayment(status = PaymentStatus.PROCESSING.name).copy(
                providerPaymentId = "alipay-1"
            )
            every { repositories.payment.findByIdForUpdate(processing.id) } returns processing
            every { repositories.payment.save(any()) } answers { firstArg() }

            val result = PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).applyProviderResult(
                processing.id,
                ProviderPaymentResult(
                    status = staleStatus,
                    providerPaymentId = "alipay-1",
                    amountMinor = 40_000,
                    currency = "USD"
                )
            )

            assertSame(processing, result, staleStatus.name)
            verify(exactly = 0) { repositories.payment.save(any()) }
        }
    }

    @Test
    fun `requires action payment ignores stale created provider state without persistence`() {
        val repositories = persistenceRepositories()
        val requiresAction = travelPayment(status = PaymentStatus.REQUIRES_ACTION.name).copy(
            providerPaymentId = "alipay-1"
        )
        every { repositories.payment.findByIdForUpdate(requiresAction.id) } returns requiresAction
        every { repositories.payment.save(any()) } answers { firstArg() }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            requiresAction.id,
            ProviderPaymentResult(
                status = PaymentStatus.CREATED,
                providerPaymentId = "alipay-1",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertSame(requiresAction, result)
        verify(exactly = 0) { repositories.payment.save(any()) }
    }

    @Test
    fun `late provider error cannot overwrite processing payment`() {
        val repositories = persistenceRepositories()
        val processing = travelPayment(status = PaymentStatus.PROCESSING.name)
        every { repositories.payment.findByIdForUpdate(processing.id) } returns processing
        every { repositories.payment.save(any()) } answers { firstArg() }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).markProviderError(
            processing.id,
            PaymentStatus.FAILED,
            "PROVIDER_TIMEOUT",
            "late timeout after provider accepted the payment"
        )

        assertSame(processing, result)
        verify(exactly = 0) { repositories.payment.save(any()) }
    }

    @Test
    fun `provider error cannot overwrite explicit terminal payment states`() {
        listOf(
            PaymentStatus.FAILED to PaymentStatus.PROCESSING,
            PaymentStatus.CANCELLED to PaymentStatus.FAILED,
            PaymentStatus.EXPIRED to PaymentStatus.PROCESSING,
            PaymentStatus.PARTIALLY_REFUNDED to PaymentStatus.FAILED
        ).forEach { (currentStatus, errorStatus) ->
            val repositories = persistenceRepositories()
            val terminal = travelPayment(status = currentStatus.name)
            every { repositories.payment.findByIdForUpdate(terminal.id) } returns terminal
            every { repositories.payment.save(any()) } answers { firstArg() }

            val result = PaymentPersistenceService(
                repositories.payment,
                repositories.order,
                repositories.log
            ).markProviderError(
                terminal.id,
                errorStatus,
                "LATE_PROVIDER_ERROR",
                "late local error"
            )

            assertSame(terminal, result, currentStatus.name)
            verify(exactly = 0) { repositories.payment.save(any()) }
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
        every {
            businessNotificationService.orderServiceActivated(any(), any(), any(), any())
        } throws IllegalStateException("notification persistence unavailable")

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
        verify(exactly = 1) {
            businessNotificationService.orderServiceActivated(
                "order-1", "user-1", "consultant-1", "doctor-1"
            )
        }

        val replayed = persistence.applyProviderResult(prepared.id, providerResult)
        assertEquals(updated.paidAt, replayed.paidAt)
        assertEquals(activatedAt, orderState.serviceActivatedAt)
        verify(exactly = 1) { repositories.order.save(any()) }
        verify(exactly = 1) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) }
    }

    @Test
    fun `service activation notification runs only after the business transaction commits`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        var orderState = travelOrder()
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        val transactionManager = RecordingTransactionManager()
        val persistence = PaymentPersistenceService(repositories.payment, repositories.order, repositories.log)
        val providerResult = ProviderPaymentResult(
            status = PaymentStatus.SUCCEEDED,
            providerPaymentId = "alipay-after-commit",
            amountMinor = 40_000,
            currency = "USD"
        )

        TransactionTemplate(transactionManager).executeWithoutResult {
            persistence.applyProviderResult(prepared.id, providerResult)
            persistence.applyProviderResult(prepared.id, providerResult)

            assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, orderState.status)
            verify(exactly = 0) { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) }
        }

        assertEquals(1, transactionManager.commits)
        verify(exactly = 1) {
            businessNotificationService.orderServiceActivated(
                "order-1", "user-1", "consultant-1", "doctor-1"
            )
        }
    }

    @Test
    fun `failed after commit notification does not roll back service activation`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var orderState = travelOrder()
        every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every {
            businessNotificationService.orderServiceActivated(any(), any(), any(), any())
        } throws IllegalStateException("notification persistence unavailable")
        val transactionManager = RecordingTransactionManager()
        val persistence = PaymentPersistenceService(repositories.payment, repositories.order, repositories.log)

        val result = TransactionTemplate(transactionManager).execute {
            persistence.applyProviderResult(
                prepared.id,
                ProviderPaymentResult(
                    status = PaymentStatus.SUCCEEDED,
                    providerPaymentId = "alipay-notification-failure",
                    amountMinor = 40_000,
                    currency = "USD"
                )
            )
        }

        assertEquals(PaymentStatus.SUCCEEDED.name, result?.status)
        assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, orderState.status)
        assertEquals(1, transactionManager.commits)
        verify(exactly = 1) { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) }
    }

    @Test
    fun `after commit activation notification uses a new database transaction`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:payment_dispatcher;DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE business_events (id INT PRIMARY KEY)")
        jdbc.execute("CREATE TABLE notification_events (id INT PRIMARY KEY)")
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var orderState = travelOrder()
        var paymentState = prepared
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) } answers {
            jdbc.update("INSERT INTO notification_events (id) VALUES (1)")
            Unit
        }
        val transactionManager = CountingDataSourceTransactionManager(dataSource)
        val dispatcher = proxiedDispatcher(transactionManager)
        assertTrue(AopUtils.isAopProxy(dispatcher))
        val transactional = PaymentBusinessNotificationDispatcher::class.java
            .getMethod("orderServiceActivated", String::class.java, String::class.java, String::class.java, String::class.java)
            .getAnnotation(Transactional::class.java)
        assertNotNull(transactional)
        assertEquals(Propagation.REQUIRES_NEW, transactional.propagation)
        val persistence = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            businessNotificationDispatcher = dispatcher
        )

        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.update("INSERT INTO business_events (id) VALUES (1)")
            persistence.applyProviderResult(
                prepared.id,
                ProviderPaymentResult(
                    status = PaymentStatus.SUCCEEDED,
                    providerPaymentId = "alipay-requires-new",
                    amountMinor = 40_000,
                    currency = "USD"
                )
            )
            persistence.applyProviderResult(
                prepared.id,
                ProviderPaymentResult(
                    status = PaymentStatus.SUCCEEDED,
                    providerPaymentId = "alipay-requires-new",
                    amountMinor = 40_000,
                    currency = "USD"
                )
            )
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_events", Int::class.java))
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM notification_events", Int::class.java))
        assertEquals(2, transactionManager.begins)
    }

    @Test
    fun `failed independent activation notification leaves outer business commit intact`() {
        val dataSource = DriverManagerDataSource("jdbc:h2:mem:payment_dispatcher_failure;DB_CLOSE_DELAY=-1", "sa", "")
        val jdbc = JdbcTemplate(dataSource)
        jdbc.execute("CREATE TABLE business_events (id INT PRIMARY KEY)")
        jdbc.execute("CREATE TABLE notification_events (id INT PRIMARY KEY)")
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var orderState = travelOrder()
        every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) } answers {
            jdbc.update("INSERT INTO notification_events (id) VALUES (1)")
            throw IllegalStateException("notification persistence unavailable")
        }
        val transactionManager = CountingDataSourceTransactionManager(dataSource)
        val persistence = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            businessNotificationDispatcher = proxiedDispatcher(transactionManager)
        )

        TransactionTemplate(transactionManager).executeWithoutResult {
            jdbc.update("INSERT INTO business_events (id) VALUES (1)")
            persistence.applyProviderResult(
                prepared.id,
                ProviderPaymentResult(
                    status = PaymentStatus.SUCCEEDED,
                    providerPaymentId = "alipay-requires-new-failure",
                    amountMinor = 40_000,
                    currency = "USD"
                )
            )
        }

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM business_events", Int::class.java))
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM notification_events", Int::class.java))
        assertEquals(2, transactionManager.begins)
        verify(exactly = 1) { businessNotificationService.orderServiceActivated(any(), any(), any(), any()) }
    }

    @Test
    fun `service fee success after the order deadline is compensated instead of activating service`() {
        val repositories = persistenceRepositories()
        val orderCreatedAt = LocalDateTime.now().minusMinutes(31)
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name).copy(
            createdAt = orderCreatedAt.plusMinutes(20),
            expiresAt = orderCreatedAt.plusMinutes(30)
        )
        val compensation = slot<PaymentCompensationCaseEntity>()
        every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns
            travelOrder(createdAt = orderCreatedAt)
        every { repositories.compensation.findByPaymentId(prepared.id) } returns null
        every { repositories.compensation.save(capture(compensation)) } answers { compensation.captured }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-after-deadline",
                providerTransactionId = "txn-after-deadline",
                amountMinor = 40_000,
                currency = "USD",
                paidAt = orderCreatedAt.plusMinutes(30)
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", result.failureCode)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", compensation.captured.reasonCode)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `pending service fee order activates when verified paid time was before the deadline`() {
        val repositories = persistenceRepositories()
        val orderCreatedAt = LocalDateTime.now().minusMinutes(31)
        val providerPaidAt = orderCreatedAt.plusMinutes(29)
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name).copy(
            createdAt = orderCreatedAt.plusMinutes(20),
            expiresAt = orderCreatedAt.plusMinutes(30)
        )
        var orderState = travelOrder(createdAt = orderCreatedAt)
        every { repositories.payment.findByIdForUpdate(prepared.id) } returns prepared
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } answers { orderState }
        every { repositories.order.save(any()) } answers {
            firstArg<OrderEntity>().also { orderState = it }
        }
        every { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-before-deadline",
                providerTransactionId = "txn-before-deadline",
                amountMinor = 40_000,
                currency = "USD",
                paidAt = providerPaidAt
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertEquals(providerPaidAt, result.paidAt)
        assertNull(result.failureCode)
        assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, orderState.status)
        verify(exactly = 0) { repositories.compensation.save(any()) }
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
            ) to "PAYMENT_CURRENCY_MISSING"
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
    fun `verified mismatched service fee remains a persisted success awaiting compensation`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(status = PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        val compensation = slot<PaymentCompensationCaseEntity>()
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns travelOrder()
        every { repositories.compensation.findByPaymentId(prepared.id) } returns null
        every { repositories.compensation.save(capture(compensation)) } answers { compensation.captured }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-wrong-amount",
                providerTransactionId = "txn-wrong-amount",
                amountMinor = 39_999,
                currency = "USD"
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertEquals("PAYMENT_SUCCEEDED_AMOUNT_MISMATCH", result.failureCode)
        assertNotNull(result.paidAt)
        assertEquals("alipay-wrong-amount", result.providerPaymentId)
        assertEquals(39_999L, compensation.captured.amountMinor)
        assertEquals("USD", compensation.captured.currency)
        assertEquals("payment-compensation-payment-1", compensation.captured.idempotencyKey)
        assertEquals("PENDING_REVIEW", compensation.captured.status)
        verify(exactly = 0) { repositories.order.save(any()) }
    }

    @Test
    fun `partially refunded payment ignores late provider success without persistence`() {
        val repositories = persistenceRepositories()
        val partiallyRefunded = travelPayment(PaymentStatus.PARTIALLY_REFUNDED.name).copy(
            providerPaymentId = "alipay-1",
            refundedAmountMinor = 10_000
        )
        every { repositories.payment.findByIdForUpdate(partiallyRefunded.id) } returns partiallyRefunded
        every { repositories.payment.save(any()) } answers { firstArg() }
        every { repositories.order.findByIdIncludeDeletedForUpdate(partiallyRefunded.orderId) } returns
            travelOrder("SERVICE_ACTIVE").copy(serviceActivatedAt = LocalDateTime.of(2026, 8, 22, 13, 0))

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log
        ).applyProviderResult(
            partiallyRefunded.id,
            ProviderPaymentResult(
                status = PaymentStatus.SUCCEEDED,
                providerPaymentId = "alipay-1",
                providerTransactionId = "txn-late",
                amountMinor = 40_000,
                currency = "USD"
            )
        )

        assertSame(partiallyRefunded, result)
        verify(exactly = 0) { repositories.payment.save(any()) }
        verify(exactly = 0) { repositories.order.findByIdIncludeDeletedForUpdate(any()) }
    }

    @Test
    fun `late second provider success keeps channel truth and flags duplicate without reactivation`() {
        val repositories = persistenceRepositories()
        val paymentStates = mutableMapOf(
            "payment-1" to travelPayment(PaymentStatus.PROCESSING.name, id = "payment-1"),
            "payment-2" to travelPayment(PaymentStatus.PROCESSING.name, id = "payment-2")
        )
        var orderState = travelOrder()
        val compensationCases = mutableMapOf<String, PaymentCompensationCaseEntity>()
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
        every { repositories.compensation.findByPaymentId(any()) } answers {
            compensationCases[firstArg<String>()]
        }
        every { repositories.compensation.save(any()) } answers {
            firstArg<PaymentCompensationCaseEntity>().also { compensationCases[it.paymentId] = it }
        }
        val persistence = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
        )

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
        assertEquals("payment-2", compensationCases.getValue("payment-2").paymentId)
        assertEquals(40_000L, compensationCases.getValue("payment-2").amountMinor)
        assertEquals("PENDING_REVIEW", compensationCases.getValue("payment-2").status)
        assertEquals(firstActivationTime, orderState.serviceActivatedAt)
        verify(exactly = 1) { repositories.order.save(any()) }
        verify(exactly = 1) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `timeout cancelled order stays cancelled even when verified paid time was before the deadline`() {
        val repositories = persistenceRepositories()
        val orderCreatedAt = LocalDateTime.now().minusMinutes(31)
        val prepared = travelPayment(PaymentStatus.PROCESSING.name).copy(
            createdAt = orderCreatedAt.plusMinutes(20),
            expiresAt = orderCreatedAt.plusMinutes(30)
        )
        var paymentState = prepared
        val compensation = slot<PaymentCompensationCaseEntity>()
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns
            travelOrder("CANCELLED", createdAt = orderCreatedAt)
        every { repositories.compensation.findByPaymentId(prepared.id) } returns null
        every { repositories.compensation.save(capture(compensation)) } answers { compensation.captured }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
        ).applyProviderResult(
            prepared.id,
            ProviderPaymentResult(
                PaymentStatus.SUCCEEDED,
                "alipay-cancelled",
                providerTransactionId = "txn-cancelled",
                amountMinor = 40_000,
                currency = "USD",
                paidAt = orderCreatedAt.plusMinutes(29)
            )
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, result.status)
        assertNotNull(result.paidAt)
        assertEquals("alipay-cancelled", result.providerPaymentId)
        assertEquals("txn-cancelled", result.providerTransactionId)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", result.failureCode)
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", compensation.captured.reasonCode)
        assertEquals(40_000L, compensation.captured.amountMinor)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `soft deleted order verified success keeps provider truth for manual refund`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        val compensation = slot<PaymentCompensationCaseEntity>()
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns travelOrder().copy(
            deletedAt = LocalDateTime.of(2026, 8, 22, 13, 0)
        )
        every { repositories.compensation.findByPaymentId(prepared.id) } returns null
        every { repositories.compensation.save(capture(compensation)) } answers { compensation.captured }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
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
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", compensation.captured.reasonCode)
        verify(exactly = 0) { repositories.order.save(any()) }
        verify(exactly = 0) { repositories.log.logTransition(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `missing order verified success keeps provider truth for manual refund`() {
        val repositories = persistenceRepositories()
        val prepared = travelPayment(PaymentStatus.PROCESSING.name)
        var paymentState = prepared
        val compensation = slot<PaymentCompensationCaseEntity>()
        every { repositories.payment.findByIdForUpdate(prepared.id) } answers { paymentState }
        every { repositories.payment.save(any()) } answers {
            firstArg<PaymentEntity>().also { paymentState = it }
        }
        every { repositories.order.findByIdIncludeDeletedForUpdate(prepared.orderId) } returns null
        every { repositories.compensation.findByPaymentId(prepared.id) } returns null
        every { repositories.compensation.save(capture(compensation)) } answers { compensation.captured }

        val result = PaymentPersistenceService(
            repositories.payment,
            repositories.order,
            repositories.log,
            repositories.compensation
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
        assertEquals("PAYMENT_SUCCEEDED_ORDER_NOT_ACTIVATABLE", compensation.captured.reasonCode)
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
        val log: OrderStatusLogService,
        val compensation: PaymentCompensationCaseRepository
    )

    private fun persistenceRepositories() = PersistenceRepositories(
        payment = mockk(),
        order = mockk(),
        log = mockk(),
        compensation = mockk(relaxed = true)
    )

    private fun PaymentPersistenceService(
        paymentRepository: PaymentRepository,
        orderRepository: OrderRepository,
        orderStatusLogService: OrderStatusLogService,
        compensationRepository: PaymentCompensationCaseRepository? = null,
        businessNotificationDispatcher: PaymentBusinessNotificationDispatcher =
            PaymentBusinessNotificationDispatcher(businessNotificationService)
    ): PaymentPersistenceService = com.joysong.server.payment.service.PaymentPersistenceService(
        paymentRepository,
        orderRepository,
        orderStatusLogService,
        compensationRepository,
        businessNotificationDispatcher
    )

    private fun proxiedDispatcher(
        transactionManager: DataSourceTransactionManager
    ): PaymentBusinessNotificationDispatcher {
        val transactionAdvice = TransactionInterceptor(
            transactionManager as TransactionManager,
            AnnotationTransactionAttributeSource()
        )
        return (ProxyFactory(PaymentBusinessNotificationDispatcher(businessNotificationService)).apply {
            isProxyTargetClass = true
            addAdvice(transactionAdvice)
        }.proxy as PaymentBusinessNotificationDispatcher)
    }

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

    private fun travelOrder(
        status: String = "PENDING_SERVICE_FEE",
        createdAt: LocalDateTime = LocalDateTime.now()
    ) = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        totalAmountMinor = 40_000,
        currency = "USD",
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        travelGroundServiceFeeMinor = 40_000,
        status = status,
        createdAt = createdAt,
        consultantId = "consultant-1",
        doctorId = "doctor-1"
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

    private class RecordingTransactionManager : AbstractPlatformTransactionManager() {
        var commits = 0

        override fun doGetTransaction(): Any = Any()

        override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit

        override fun doCommit(status: DefaultTransactionStatus) {
            commits += 1
        }

        override fun doRollback(status: DefaultTransactionStatus) = Unit
    }

    private class CountingDataSourceTransactionManager(dataSource: javax.sql.DataSource) :
        DataSourceTransactionManager(dataSource) {
        var begins = 0

        override fun doBegin(transaction: Any, definition: TransactionDefinition) {
            begins += 1
            super.doBegin(transaction, definition)
        }
    }
}
