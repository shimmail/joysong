package com.joysong.server.refund.service

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.entity.PaymentEntity
import com.joysong.server.payment.provider.PaymentGateway
import com.joysong.server.payment.provider.PaymentGatewayRegistry
import com.joysong.server.payment.provider.ProviderRefundResult
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.service.SettlementReversalService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Optional

class RefundServiceTest {
    private val refundRepository = mockk<RefundRepository>()
    private val refundItemRepository = mockk<RefundItemRepository>()
    private val orderRepository = mockk<OrderRepository>()
    private val paymentRepository = mockk<PaymentRepository>()
    private val orderStatusLogService = mockk<OrderStatusLogService>()
    private val couponService = mockk<CouponService>()

    @Test
    fun `admin refund list exposes the joined order payment flow for new and legacy orders`() {
        val serviceRefund = refund(status = RefundWorkflowPersistenceService.PENDING)
        val legacyRefund = serviceRefund.copy(id = "refund-2", orderId = "order-2")
        val failedItem = refundItem().copy(
            providerRefundId = "provider-refund-1",
            status = PaymentStatus.FAILED.name,
            failureCode = "DECLINED",
            failureMessage = "provider declined"
        )
        every { refundRepository.findAll() } returns listOf(serviceRefund, legacyRefund)
        every { orderRepository.findAllById(listOf("order-1", "order-2")) } returns listOf(
            serviceOrder(),
            legacyOrder(status = OrderStatusEnum.CONSULTATION_PAID.value).copy(id = "order-2")
        )
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } returns listOf(failedItem)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-2") } returns emptyList()

        val rows = service().adminListAll()

        assertEquals(RefundWorkflowPersistenceService.TRAVEL_GROUND_SERVICE_ONLY, rows[0]["paymentFlow"])
        assertEquals("LEGACY_MEDICAL", rows[1]["paymentFlow"])
        val item = (rows[0]["items"] as List<Map<String, Any?>>).single()
        assertEquals("DECLINED", item["failureCode"])
        assertEquals("provider declined", item["failureMessage"])
        assertEquals("provider-refund-1", item["providerRefundId"])
    }

    @Test
    fun `service fee refund request is pending full value manual review without provider execution`() {
        val execution = mockk<RefundExecutionService>()
        val orderSlot = slot<OrderEntity>()
        stubServiceApplication()
        every { orderRepository.save(capture(orderSlot)) } answers { firstArg() }

        val refund = service(execution = execution)
            .applyRefund("order-1", "user-1", "行程取消", "不再来华")

        assertEquals(RefundWorkflowPersistenceService.PENDING, refund.status)
        assertEquals(40_000L, refund.requestedAmountMinor)
        assertEquals(BigDecimal("400.00"), refund.amount)
        assertEquals("FULL", refund.refundType)
        assertEquals(RefundWorkflowPersistenceService.REVERSAL_NOT_REQUIRED, refund.revenueReversalStatus)
        assertEquals(OrderStatusEnum.REFUND_REVIEW.value, orderSlot.captured.status)
        verify(exactly = 0) { execution.execute(any()) }
    }

    @Test
    fun `service fee refund request accepts completed service and preserves original status`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.COMPLETED.value)
        every { refundRepository.findAllByOrderIdAndStatusIn("order-1", any()) } returns emptyList()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment())
        every { refundRepository.saveAndFlush(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val refund = service().applyRefund("order-1", "user-1", "行程取消", "不再来华")

        assertEquals(OrderStatusEnum.COMPLETED.value, refund.originalStatus)
        verify { orderRepository.save(match { it.status == OrderStatusEnum.REFUND_REVIEW.value }) }
    }

    @Test
    fun `service fee refund request rejects a mismatched successful payment`() {
        stubApplicationBase()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment(amountMinor = 39_999L))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().applyRefund("order-1", "user-1", "行程取消", "不再来华")
        }

        assertEquals("SERVICE_FEE_PAYMENT_AMOUNT_MISMATCH", error.message)
        verify(exactly = 0) { refundRepository.saveAndFlush(any()) }
    }

    @Test
    fun `service fee refund request requires exactly one successful service fee payment`() {
        stubApplicationBase()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment(), serviceFeePayment(id = "payment-2"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().applyRefund("order-1", "user-1", "重复扣款", "请处理")
        }

        assertEquals("SERVICE_FEE_PAYMENT_NOT_UNIQUE", error.message)
        verify(exactly = 0) { refundRepository.saveAndFlush(any()) }
    }

    @Test
    fun `service fee refund request requires the successful payment currency to match`() {
        stubApplicationBase()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment(currency = "CNY"))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().applyRefund("order-1", "user-1", "行程取消", "不再来华")
        }

        assertEquals("SERVICE_FEE_PAYMENT_CURRENCY_MISMATCH", error.message)
        verify(exactly = 0) { refundRepository.saveAndFlush(any()) }
    }

    @Test
    fun `service fee refund request rejects a non Alipay Plus payment`() {
        stubApplicationBase()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment(provider = PaymentProvider.STRIPE))
        every { refundRepository.saveAndFlush(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().applyRefund("order-1", "user-1", "行程取消", "不再来华")
        }

        assertEquals("SERVICE_FEE_PAYMENT_PROVIDER_NOT_ALIPAY_PLUS", error.message)
        verify(exactly = 0) { refundRepository.saveAndFlush(any()) }
    }

    @Test
    fun `rejected review restores completed service without invoking provider`() {
        val execution = mockk<RefundExecutionService>()
        val savedOrder = slot<OrderEntity>()
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
            .copy(originalStatus = OrderStatusEnum.COMPLETED.value)
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value)
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(capture(savedOrder)) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val result = service(execution = execution)
            .adminUpdateStatus("refund-1", "REJECTED", "admin-1", "服务已安排")!!

        assertEquals(RefundWorkflowPersistenceService.REJECTED, result.status)
        assertEquals(OrderStatusEnum.COMPLETED.value, savedOrder.captured.status)
        verify(exactly = 0) { execution.execute(any()) }
    }

    @Test
    fun `user cancellation locks refund before order and restores completed service`() {
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
            .copy(originalStatus = OrderStatusEnum.COMPLETED.value)
        val savedOrder = slot<OrderEntity>()
        every { refundRepository.findFirstByOrderIdOrderByCreatedAtDesc("order-1") } returns pending
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value, refundStatus = RefundWorkflowPersistenceService.PENDING)
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(capture(savedOrder)) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val message = service().cancelRefund("order-1", "user-1")

        assertEquals("退款已取消", message)
        assertEquals(OrderStatusEnum.COMPLETED.value, savedOrder.captured.status)
        verifyOrder {
            refundRepository.findFirstByOrderIdOrderByCreatedAtDesc("order-1")
            refundRepository.findByIdForUpdate("refund-1")
            orderRepository.findByIdForUpdate("order-1")
        }
    }

    @Test
    fun `travel cancellation rejects an invalid original status before saving`() {
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
            .copy(originalStatus = OrderStatusEnum.VERIFIED.value)
        every { refundRepository.findFirstByOrderIdOrderByCreatedAtDesc("order-1") } returns pending
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value, refundStatus = RefundWorkflowPersistenceService.PENDING)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().cancelRefund("order-1", "user-1")
        }

        assertEquals("INVALID_TRAVEL_REFUND_ORIGINAL_STATUS", error.message)
        verify(exactly = 0) { refundRepository.save(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `travel cancellation rejects a blank original status before saving`() {
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING).copy(originalStatus = "")
        every { refundRepository.findFirstByOrderIdOrderByCreatedAtDesc("order-1") } returns pending
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value, refundStatus = RefundWorkflowPersistenceService.PENDING)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().cancelRefund("order-1", "user-1")
        }

        assertEquals("INVALID_TRAVEL_REFUND_ORIGINAL_STATUS", error.message)
        verify(exactly = 0) { refundRepository.save(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `travel rejection rejects an invalid original status before saving`() {
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
            .copy(originalStatus = OrderStatusEnum.PENDING_SETTLEMENT.value)
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().adminUpdateStatus("refund-1", "REJECTED", "admin-1", "资料不全")
        }

        assertEquals("INVALID_TRAVEL_REFUND_ORIGINAL_STATUS", error.message)
        verify(exactly = 0) { refundRepository.save(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `approval persists processing order before invoking provider`() {
        val execution = mockk<RefundExecutionService>()
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value)
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every { execution.execute(any()) } returns RefundExecutionOutcome(0, completed = false)
        every { refundRepository.findById("refund-1") } returns
            Optional.of(refund(status = RefundWorkflowPersistenceService.PROCESSING))

        val result = service(execution = execution)
            .adminUpdateStatus("refund-1", "APPROVED", "admin-1")!!

        assertEquals(RefundWorkflowPersistenceService.PROCESSING, result.status)
        verifyOrder {
            refundRepository.findByIdForUpdate("refund-1")
            orderRepository.findByIdForUpdate("order-1")
            orderRepository.save(match { it.status == OrderStatusEnum.REFUND_PROCESSING.value })
            execution.execute(match { it.status == RefundWorkflowPersistenceService.PROCESSING })
        }
    }

    @Test
    fun `approval revalidates the locked service refund as USD before provider execution`() {
        val execution = mockk<RefundExecutionService>()
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING).copy(currency = "CNY")
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value).copy(currency = "CNY")
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every { execution.execute(any()) } returns RefundExecutionOutcome(0, completed = false)
        every { refundRepository.findById("refund-1") } returns Optional.of(pending)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service(execution = execution).adminUpdateStatus("refund-1", "APPROVED", "admin-1")
        }

        assertEquals("SERVICE_FEE_CURRENCY_NOT_USD", error.message)
        verify(exactly = 0) { execution.execute(any()) }
    }

    @Test
    fun `provider success finalizes service order without settlement or coupon side effects`() {
        val execution = mockk<RefundExecutionService>()
        val reversal = mockk<SettlementReversalService>()
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
        val processing = pending.copy(status = RefundWorkflowPersistenceService.PROCESSING)
        every { refundRepository.findByIdForUpdate("refund-1") } returnsMany listOf(pending, processing)
        every { orderRepository.findByIdForUpdate("order-1") } returnsMany listOf(
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value, userCouponId = 9L),
            serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value, userCouponId = 9L)
        )
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
        every { execution.execute(any()) } returns RefundExecutionOutcome(40_000L, completed = true)

        val result = service(execution = execution, reversal = reversal)
            .adminUpdateStatus("refund-1", "APPROVED", "admin-1")!!

        assertEquals(RefundWorkflowPersistenceService.APPROVED, result.status)
        verify { orderRepository.save(match { it.status == OrderStatusEnum.REFUNDED.value }) }
        verify(exactly = 0) { reversal.reverseCompletedRefund(any()) }
        verify(exactly = 0) { couponService.returnCoupon(any()) }
    }

    @Test
    fun `repeated approval of completed refund is idempotent`() {
        val execution = mockk<RefundExecutionService>()
        val approved = refund(status = RefundWorkflowPersistenceService.APPROVED)
        every { refundRepository.findByIdForUpdate("refund-1") } returns approved
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUNDED.value)

        val result = service(execution = execution)
            .adminUpdateStatus("refund-1", "APPROVED", "admin-1")!!

        assertEquals(RefundWorkflowPersistenceService.APPROVED, result.status)
        verify(exactly = 0) { execution.execute(any()) }
        verify(exactly = 0) { refundRepository.save(any()) }
    }

    @Test
    fun `repeated approval of processing service refund does not execute provider again`() {
        val execution = mockk<RefundExecutionService>()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value)

        val result = service(execution = execution)
            .adminUpdateStatus("refund-1", "APPROVED", "admin-1")!!

        assertEquals(RefundWorkflowPersistenceService.PROCESSING, result.status)
        verify(exactly = 0) { execution.execute(any()) }
        verify(exactly = 0) { refundRepository.save(any()) }
        verify(exactly = 0) { orderRepository.save(any()) }
    }

    @Test
    fun `legacy repeated approval remains rejected`() {
        val approved = refund(
            status = RefundWorkflowPersistenceService.APPROVED,
            revenueReversalStatus = "PENDING"
        )
        every { refundRepository.findByIdForUpdate("refund-1") } returns approved
        every { orderRepository.findByIdForUpdate("order-1") } returns
            legacyOrder(status = OrderStatusEnum.REFUNDED.value)

        assertThrows(IllegalArgumentException::class.java) {
            service().adminUpdateStatus("refund-1", "APPROVED", "admin-1")
        }
    }

    @Test
    fun `mixed successful payments create exactly one full service fee refund item`() {
        val persistence = itemPersistence()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        stubItemPreparation(processing)
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(
            payment(id = "consultation", type = PaymentType.CONSULTATION_FEE, amountMinor = 10_000L),
            serviceFeePayment(),
            payment(id = "balance", type = PaymentType.BALANCE, amountMinor = 90_000L)
        )
        every { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) } answers { firstArg() }

        val items = persistence.prepareItems(processing)

        assertEquals(1, items.size)
        assertEquals("payment-1", items.single().paymentId)
        assertEquals(40_000L, items.single().amountMinor)
        assertEquals("USD", items.single().currency)
    }

    @Test
    fun `refund item preparation rechecks under refund lock and does not duplicate items`() {
        val persistence = itemPersistence()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        var persisted = emptyList<RefundItemEntity>()
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } answers { persisted }
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment())
        every { paymentRepository.findByIdForUpdate("payment-1") } returns serviceFeePayment()
        every { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) } answers {
            firstArg<List<RefundItemEntity>>().also { persisted = it }
        }

        val first = persistence.prepareItems(processing)
        val second = persistence.prepareItems(processing)

        assertEquals(first, second)
        verify(exactly = 1) { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) }
    }

    @Test
    fun `pre-existing medical refund item is rejected before any provider call`() {
        val error = executeWithExistingItems(
            listOf(refundItem(paymentId = "consultation-payment")),
            listOf(payment("consultation-payment", PaymentType.CONSULTATION_FEE, 40_000L))
        )

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("SERVICE_FEE_REFUND_ITEM_PAYMENT_TYPE_MISMATCH", error?.message)
    }

    @Test
    fun `multiple pre-existing service refund items are rejected before any provider call`() {
        val error = executeWithExistingItems(
            listOf(
                refundItem(id = "item-1", paymentId = "payment-1"),
                refundItem(id = "item-2", paymentId = "payment-2")
            ),
            listOf(serviceFeePayment(), serviceFeePayment(id = "payment-2"))
        )

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("SERVICE_FEE_REFUND_ITEMS_INVALID", error?.message)
    }

    @Test
    fun `pre-existing service refund item must match the full amount and currency`() {
        listOf(
            refundItem(amountMinor = 39_999L) to "SERVICE_FEE_REFUND_ITEM_AMOUNT_MISMATCH",
            refundItem(currency = "CNY") to "SERVICE_FEE_REFUND_ITEM_CURRENCY_MISMATCH"
        ).forEach { (item, expectedMessage) ->
            val error = executeWithExistingItems(listOf(item), listOf(serviceFeePayment()))

            assertEquals(IllegalArgumentException::class.java, error?.javaClass)
            assertEquals(expectedMessage, error?.message)
        }
    }

    @Test
    fun `internally consistent CNY service refund data is rejected before any provider call`() {
        val error = executeWithExistingItems(
            items = listOf(refundItem(currency = "CNY")),
            payments = listOf(serviceFeePayment(currency = "CNY")),
            processing = refund(status = RefundWorkflowPersistenceService.PROCESSING).copy(currency = "CNY"),
            order = serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value).copy(currency = "CNY")
        )

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("SERVICE_FEE_CURRENCY_NOT_USD", error?.message)
    }

    @Test
    fun `pre-existing non Alipay Plus service item is rejected before any provider call`() {
        val error = executeWithExistingItems(
            items = listOf(refundItem(provider = PaymentProvider.STRIPE)),
            payments = listOf(serviceFeePayment(provider = PaymentProvider.STRIPE))
        )

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("SERVICE_FEE_REFUND_ITEM_PROVIDER_NOT_ALIPAY_PLUS", error?.message)
    }

    @Test
    fun `new service refund item rejects a non Alipay Plus payment before provider call`() {
        val error = executeWithExistingItems(
            items = emptyList(),
            payments = listOf(serviceFeePayment(provider = PaymentProvider.STRIPE))
        )

        assertEquals(IllegalArgumentException::class.java, error?.javaClass)
        assertEquals("SERVICE_FEE_PAYMENT_PROVIDER_NOT_ALIPAY_PLUS", error?.message)
    }

    @Test
    fun `provider processing and failure leave the payment amount untouched`() {
        val persistence = itemPersistence()
        val item = refundItem()
        every { refundItemRepository.findByIdForUpdate("item-1") } returns item
        every { paymentRepository.findByIdForUpdate("payment-1") } returns serviceFeePayment()
        every { refundItemRepository.save(any()) } answers { firstArg() }

        val processing = persistence.applyProviderResult(
            "item-1",
            ProviderRefundResult(PaymentStatus.PROCESSING, "provider-refund-1")
        )
        val failed = persistence.applyProviderResult(
            "item-1",
            ProviderRefundResult(PaymentStatus.FAILED, "provider-refund-1", "DECLINED")
        )

        assertEquals(PaymentStatus.PROCESSING.name, processing.status)
        assertEquals(PaymentStatus.FAILED.name, failed.status)
        verify(exactly = 0) { paymentRepository.save(any()) }
    }

    @Test
    fun `provider success is idempotent and marks the service fee payment refunded once`() {
        val persistence = itemPersistence()
        val created = refundItem()
        val succeeded = created.copy(status = PaymentStatus.SUCCEEDED.name, providerRefundId = "provider-refund-1")
        every { refundItemRepository.findByIdForUpdate("item-1") } returnsMany listOf(created, succeeded)
        every { paymentRepository.findByIdForUpdate("payment-1") } returns serviceFeePayment()
        every { refundItemRepository.save(any()) } answers { firstArg() }
        every { paymentRepository.save(any()) } answers { firstArg() }

        val first = persistence.applyProviderResult(
            "item-1",
            ProviderRefundResult(PaymentStatus.SUCCEEDED, "provider-refund-1")
        )
        val replay = persistence.applyProviderResult(
            "item-1",
            ProviderRefundResult(PaymentStatus.SUCCEEDED, "provider-refund-1")
        )

        assertEquals(PaymentStatus.SUCCEEDED.name, first.status)
        assertEquals(succeeded, replay)
        verify(exactly = 1) {
            paymentRepository.save(match {
                it.status == PaymentStatus.REFUNDED.name && it.refundedAmountMinor == 40_000L
            })
        }
    }

    @Test
    fun `processing refund item queries the existing provider refund instead of submitting again`() {
        val itemPersistence = mockk<RefundItemPersistenceService>()
        val gatewayRegistry = mockk<PaymentGatewayRegistry>()
        val gateway = mockk<PaymentGateway>()
        val execution = RefundExecutionService(
            paymentRepository,
            refundItemRepository,
            gatewayRegistry,
            itemPersistence
        )
        val processingRefund = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        val processingItem = refundItem().copy(
            status = PaymentStatus.PROCESSING.name,
            providerRefundId = "provider-refund-1"
        )
        val providerSuccess = ProviderRefundResult(PaymentStatus.SUCCEEDED, "provider-refund-1")
        val completed = RefundExecutionOutcome(40_000L, completed = true)
        every { itemPersistence.prepareItems(processingRefund) } returns listOf(processingItem)
        every { paymentRepository.findById("payment-1") } returns Optional.of(serviceFeePayment())
        every { gatewayRegistry.require(PaymentProvider.ALIPAY_PLUS) } returns gateway
        every { gateway.queryRefund("provider-refund-1") } returns providerSuccess
        every { itemPersistence.applyProviderResult("item-1", providerSuccess) } returns
            processingItem.copy(status = PaymentStatus.SUCCEEDED.name)
        every { itemPersistence.summarize("refund-1", 40_000L) } returns completed

        assertEquals(completed, execution.execute(processingRefund))
        verify(exactly = 1) { gateway.queryRefund("provider-refund-1") }
        verify(exactly = 0) { gateway.refund(any()) }
    }

    @Test
    fun `manual retry executes only the items that were failed when retry started`() {
        val persistence = mockk<RefundItemPersistenceService>()
        val gatewayRegistry = mockk<PaymentGatewayRegistry>()
        val gateway = mockk<PaymentGateway>()
        val execution = RefundExecutionService(paymentRepository, refundItemRepository, gatewayRegistry, persistence)
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        val failedWithProvider = refundItem(id = "failed-query").copy(
            status = PaymentStatus.PROCESSING.name,
            providerRefundId = "provider-failed"
        )
        val failedWithoutProvider = refundItem(id = "failed-resend", paymentId = "retry-payment").copy(
            status = PaymentStatus.CREATED.name
        )
        val unrelatedProcessing = refundItem(id = "already-processing", paymentId = "other-payment").copy(
            status = PaymentStatus.PROCESSING.name,
            providerRefundId = "provider-other"
        )
        val succeeded = refundItem(id = "succeeded", paymentId = "done-payment").copy(
            status = PaymentStatus.SUCCEEDED.name,
            providerRefundId = "provider-done"
        )
        val queried = ProviderRefundResult(PaymentStatus.PROCESSING, "provider-failed")
        val resent = ProviderRefundResult(PaymentStatus.PROCESSING, "provider-retried")
        every { persistence.requeueFailedItems("refund-1") } returns listOf(failedWithProvider, failedWithoutProvider)
        every { persistence.prepareItems(processing) } returns listOf(
            failedWithProvider,
            failedWithoutProvider,
            unrelatedProcessing,
            succeeded
        )
        every { paymentRepository.findById("payment-1") } returns Optional.of(serviceFeePayment())
        every { paymentRepository.findById("retry-payment") } returns Optional.of(serviceFeePayment(id = "retry-payment"))
        every { gatewayRegistry.require(PaymentProvider.ALIPAY_PLUS) } returns gateway
        every { gateway.queryRefund("provider-failed") } returns queried
        every { gateway.refund(any()) } returns resent
        every { persistence.applyProviderResult("failed-query", queried) } returns failedWithProvider
        every { persistence.applyProviderResult("failed-resend", resent) } returns failedWithoutProvider
        every { persistence.summarize("refund-1", 40_000L) } returns RefundExecutionOutcome(0, completed = false)

        execution.retryFailed(processing)

        verify(exactly = 1) { gateway.queryRefund("provider-failed") }
        verify(exactly = 1) {
            gateway.refund(match {
                it.refundItemId == "failed-resend" &&
                    it.idempotencyKey == "refund-refund-1-retry-payment"
            })
        }
        verify(exactly = 0) { gateway.queryRefund("provider-other") }
        verify(exactly = 0) { paymentRepository.findById("other-payment") }
        verify(exactly = 0) { paymentRepository.findById("done-payment") }
    }

    @Test
    fun `manual retry requeues only failed items and preserves successful and processing items`() {
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        val failedWithProvider = refundItem(id = "failed-query").copy(
            status = PaymentStatus.FAILED.name,
            providerRefundId = "provider-failed",
            failureCode = "DECLINED"
        )
        val failedWithoutProvider = refundItem(id = "failed-resend", paymentId = "retry-payment").copy(
            status = PaymentStatus.FAILED.name,
            failureMessage = "network"
        )
        val existingProcessing = refundItem(id = "already-processing", paymentId = "other-payment").copy(
            status = PaymentStatus.PROCESSING.name,
            providerRefundId = "provider-other"
        )
        val succeeded = refundItem(id = "succeeded", paymentId = "done-payment").copy(
            status = PaymentStatus.SUCCEEDED.name,
            providerRefundId = "provider-done"
        )
        val items = listOf(failedWithProvider, failedWithoutProvider, existingProcessing, succeeded)
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } returns items
        every { refundItemRepository.findByIdForUpdate(any()) } answers {
            items.firstOrNull { it.id == firstArg<String>() }
        }
        every { refundItemRepository.save(any()) } answers { firstArg() }

        val requeued = itemPersistence().requeueFailedItems("refund-1")

        assertEquals(listOf("failed-query", "failed-resend"), requeued.map { it.id })
        assertEquals(PaymentStatus.PROCESSING.name, requeued[0].status)
        assertEquals(PaymentStatus.CREATED.name, requeued[1].status)
        verify(exactly = 0) { refundItemRepository.findByIdForUpdate("already-processing") }
        verify(exactly = 0) { refundItemRepository.findByIdForUpdate("succeeded") }
        verify(exactly = 2) { refundItemRepository.save(any()) }
    }

    @Test
    fun `legacy manual retry does not require the travel refund processing order status`() {
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
            .copy(revenueReversalStatus = "PENDING")
        val failed = refundItem().copy(status = PaymentStatus.FAILED.name)
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns
            legacyOrder(status = OrderStatusEnum.DISPUTE_MEDIATION.value)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } returns listOf(failed)
        every { refundItemRepository.findByIdForUpdate("item-1") } returns failed
        every { refundItemRepository.save(any()) } answers { firstArg() }

        val requeued = itemPersistence().requeueFailedItems("refund-1")

        assertEquals(PaymentStatus.CREATED.name, requeued.single().status)
    }

    @Test
    fun `adminUpdateStatus rejects unsupported target state before loading data`() {
        assertThrows(IllegalArgumentException::class.java) {
            service().adminUpdateStatus("refund-1", "CANCELLED", "admin-1")
        }
        verify(exactly = 0) { refundRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `adminUpdateStatus requires a rejection reason`() {
        assertThrows(IllegalArgumentException::class.java) {
            service().adminUpdateStatus("refund-1", "REJECTED", "admin-1", "  ")
        }
        verify(exactly = 0) { refundRepository.findByIdForUpdate(any()) }
    }

    @Test
    fun `legacy retry still invokes settlement reversal after completion`() {
        val execution = mockk<RefundExecutionService>()
        val workflow = mockk<RefundWorkflowPersistenceService>()
        val reversal = mockk<SettlementReversalService>()
        val processing = refund(
            status = RefundWorkflowPersistenceService.PROCESSING,
            revenueReversalStatus = "PENDING"
        )
        val finalized = FinalizedRefund(
            processing.copy(status = RefundWorkflowPersistenceService.APPROVED),
            legacyOrder(status = OrderStatusEnum.REFUNDED.value)
        )
        val completed = RefundExecutionOutcome(refundedAmountMinor = 40_000L, completed = true)
        every { refundRepository.findById("refund-1") } returns Optional.of(processing)
        every { execution.execute(processing) } returns completed
        every { workflow.finalizeSuccess("refund-1", completed, "SYSTEM", "SYSTEM", any()) } returns finalized
        every { reversal.reverseCompletedRefund("refund-1") } returns Unit

        service(workflow = workflow, execution = execution, reversal = reversal)
            .retryProcessingRefund("refund-1")

        verify { reversal.reverseCompletedRefund("refund-1") }
    }

    @Test
    fun `legacy retry does not invoke reversal while provider refund is incomplete`() {
        val execution = mockk<RefundExecutionService>()
        val workflow = mockk<RefundWorkflowPersistenceService>()
        val reversal = mockk<SettlementReversalService>()
        val processing = refund(
            status = RefundWorkflowPersistenceService.PROCESSING,
            revenueReversalStatus = "PENDING"
        )
        every { refundRepository.findById("refund-1") } returns Optional.of(processing)
        every { execution.execute(processing) } returns RefundExecutionOutcome(0, completed = false)

        service(workflow = workflow, execution = execution, reversal = reversal)
            .retryProcessingRefund("refund-1")

        verify(exactly = 0) { workflow.finalizeSuccess(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { reversal.reverseCompletedRefund(any()) }
    }

    @Test
    fun `manual retry only invokes the failed-item executor for a processing refund`() {
        val execution = mockk<RefundExecutionService>()
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        every { refundRepository.findById("refund-1") } returns Optional.of(processing)
        every { execution.retryFailed(processing) } returns RefundExecutionOutcome(0, completed = false)
        every { refundRepository.findById("refund-1") } returns Optional.of(processing)

        val result = service(execution = execution)
            .retryFailedProcessingRefund("refund-1", "admin-1")

        assertEquals(RefundWorkflowPersistenceService.PROCESSING, result.status)
        verify(exactly = 1) { execution.retryFailed(processing) }
        verify(exactly = 0) { execution.execute(any()) }
    }

    @Test
    fun `manual retry fails explicitly when the refund executor is unavailable`() {
        val processing = refund(status = RefundWorkflowPersistenceService.PROCESSING)
        every { refundRepository.findById("refund-1") } returns Optional.of(processing)

        val error = assertThrows(IllegalStateException::class.java) {
            service().retryFailedProcessingRefund("refund-1", "admin-1")
        }

        assertEquals("REFUND_EXECUTOR_UNAVAILABLE", error.message)
    }

    @Test
    fun `legacy consultation refund remains automatic and keeps successful payment allocation`() {
        val legacyOrder = legacyOrder(status = OrderStatusEnum.CONSULTATION_PAID.value).copy(
            currency = "USD",
            paidAmount = BigDecimal("100.00"),
            paidAmountMinor = 10_000L
        )
        every { orderRepository.findByIdForUpdate("order-1") } returns legacyOrder
        every { refundRepository.findAllByOrderIdAndStatusIn("order-1", any()) } returns emptyList()
        every { refundRepository.saveAndFlush(any()) } answers { firstArg() }
        every { orderRepository.save(any()) } answers { firstArg() }

        val preparation = workflow().prepareApplication(
            "order-1",
            "user-1",
            "不再到店",
            "取消预约",
            "",
            null
        )
        every { refundRepository.findByIdForUpdate(preparation.refund.id) } returns preparation.refund
        every {
            refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(preparation.refund.id)
        } returns emptyList()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(
            payment("consultation-payment", PaymentType.CONSULTATION_FEE, 10_000L),
            payment("balance-payment", PaymentType.BALANCE, 30_000L)
        )
        every { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) } answers { firstArg() }

        val items = itemPersistence().prepareItems(preparation.refund)

        assertTrue(preparation.automatic)
        assertEquals(RefundWorkflowPersistenceService.PROCESSING, preparation.refund.status)
        assertEquals(1, items.size)
        assertEquals("consultation-payment", items.single().paymentId)
        assertEquals(10_000L, items.single().amountMinor)
    }

    private fun service(
        workflow: RefundWorkflowPersistenceService = workflow(),
        execution: RefundExecutionService? = null,
        reversal: SettlementReversalService? = null
    ) = RefundService(
        refundRepository = refundRepository,
        orderRepository = orderRepository,
        orderStatusLogService = orderStatusLogService,
        couponService = couponService,
        workflowPersistenceService = workflow,
        refundExecutionService = execution,
        settlementReversalService = reversal,
        refundItemRepository = refundItemRepository
    )

    private fun workflow() = RefundWorkflowPersistenceService(
        refundRepository,
        orderRepository,
        orderStatusLogService,
        paymentRepository
    )

    private fun itemPersistence() = RefundItemPersistenceService(
        paymentRepository,
        refundItemRepository,
        refundRepository,
        orderRepository
    )

    private fun stubServiceApplication() {
        stubApplicationBase()
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns listOf(serviceFeePayment())
        every { refundRepository.saveAndFlush(any()) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit
    }

    private fun stubApplicationBase() {
        every { orderRepository.findByIdForUpdate("order-1") } returns serviceOrder()
        every { refundRepository.findAllByOrderIdAndStatusIn("order-1", any()) } returns emptyList()
    }

    private fun stubItemPreparation(processing: RefundEntity) {
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value)
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } returns emptyList()
    }

    private fun executeWithExistingItems(
        items: List<RefundItemEntity>,
        payments: List<PaymentEntity>,
        processing: RefundEntity = refund(status = RefundWorkflowPersistenceService.PROCESSING),
        order: OrderEntity = serviceOrder(status = OrderStatusEnum.REFUND_PROCESSING.value)
    ): Throwable? {
        val gatewayRegistry = mockk<PaymentGatewayRegistry>()
        val gateway = mockk<PaymentGateway>()
        var storedItems = items
        every { refundRepository.findByIdForUpdate("refund-1") } returns processing
        every { orderRepository.findByIdForUpdate("order-1") } returns order
        every { refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc("refund-1") } answers { storedItems }
        every {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc("order-1", any())
        } returns payments
        every { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) } answers {
            firstArg<List<RefundItemEntity>>().also { storedItems = it }
        }
        payments.forEach { payment ->
            every { paymentRepository.findById(payment.id) } returns Optional.of(payment)
            every { paymentRepository.findByIdForUpdate(payment.id) } returns payment
        }
        every { gatewayRegistry.require(PaymentProvider.ALIPAY_PLUS) } returns gateway
        every { gateway.refund(any()) } returns
            ProviderRefundResult(PaymentStatus.PROCESSING, "provider-refund")
        every { refundItemRepository.findByIdForUpdate(any()) } answers {
            storedItems.firstOrNull { it.id == firstArg<String>() }
        }
        every { refundItemRepository.save(any()) } answers { firstArg() }
        val execution = RefundExecutionService(
            paymentRepository,
            refundItemRepository,
            gatewayRegistry,
            itemPersistence()
        )

        val error = runCatching { execution.execute(processing) }.exceptionOrNull()

        verify(exactly = 0) { gatewayRegistry.require(any()) }
        return error
    }

    private fun refund(
        status: String,
        revenueReversalStatus: String = RefundWorkflowPersistenceService.REVERSAL_NOT_REQUIRED
    ) = RefundEntity(
        id = "refund-1",
        orderId = "order-1",
        userId = "user-1",
        currency = "USD",
        amount = BigDecimal("400.00"),
        requestedAmountMinor = 40_000L,
        reason = "测试退款",
        status = status,
        originalStatus = OrderStatusEnum.SERVICE_ACTIVE.value,
        revenueReversalStatus = revenueReversalStatus
    )

    private fun serviceOrder(
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value,
        refundStatus: String = "NONE",
        userCouponId: Long? = null
    ) = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        currency = "USD",
        price = BigDecimal("400.00"),
        paidAmount = BigDecimal("400.00"),
        paidAmountMinor = 40_000L,
        status = status,
        paymentFlow = RefundWorkflowPersistenceService.TRAVEL_GROUND_SERVICE_ONLY,
        medicalListPriceMinor = 100_000L,
        platformServiceRateBps = 4_000,
        travelGroundServiceFeeMinor = 40_000L,
        refundStatus = refundStatus,
        userCouponId = userCouponId
    )

    private fun legacyOrder(status: String) = OrderEntity(
        id = "order-1",
        userId = "user-1",
        projectName = "项目",
        price = BigDecimal("400.00"),
        status = status
    )

    private fun serviceFeePayment(
        id: String = "payment-1",
        amountMinor: Long = 40_000L,
        currency: String = "USD",
        provider: PaymentProvider = PaymentProvider.ALIPAY_PLUS
    ) = payment(id, PaymentType.TRAVEL_GROUND_SERVICE_FEE, amountMinor, currency, provider)

    private fun payment(
        id: String,
        type: PaymentType,
        amountMinor: Long,
        currency: String = "USD",
        provider: PaymentProvider = PaymentProvider.ALIPAY_PLUS
    ) = PaymentEntity(
        id = id,
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal.valueOf(amountMinor, 2),
        method = "ALIPAY_PLUS_CASHIER",
        status = PaymentStatus.SUCCEEDED.name,
        paymentType = type.name,
        provider = provider.name,
        currency = currency,
        amountMinor = amountMinor,
        providerPaymentId = "provider-$id"
    )

    private fun refundItem(
        id: String = "item-1",
        paymentId: String = "payment-1",
        amountMinor: Long = 40_000L,
        currency: String = "USD",
        provider: PaymentProvider = PaymentProvider.ALIPAY_PLUS
    ) = RefundItemEntity(
        id = id,
        refundId = "refund-1",
        paymentId = paymentId,
        provider = provider.name,
        currency = currency,
        amountMinor = amountMinor
    )
}
