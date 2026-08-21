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
    fun `service fee refund request requires active service`() {
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value)

        val error = assertThrows(IllegalArgumentException::class.java) {
            service().applyRefund("order-1", "user-1", "行程取消", "不再来华")
        }

        assertTrue(error.message!!.contains("不允许申请退款"))
        verify(exactly = 0) {
            paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc(any(), any())
        }
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
    fun `rejected review restores active service without invoking provider`() {
        val execution = mockk<RefundExecutionService>()
        val savedOrder = slot<OrderEntity>()
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
        every { refundRepository.findByIdForUpdate("refund-1") } returns pending
        every { orderRepository.findByIdForUpdate("order-1") } returns
            serviceOrder(status = OrderStatusEnum.REFUND_REVIEW.value)
        every { refundRepository.save(any()) } answers { firstArg() }
        every { orderRepository.save(capture(savedOrder)) } answers { firstArg() }
        every { orderStatusLogService.logTransition(any(), any(), any(), any(), any(), any()) } returns Unit

        val result = service(execution = execution)
            .adminUpdateStatus("refund-1", "REJECTED", "admin-1", "服务已安排")!!

        assertEquals(RefundWorkflowPersistenceService.REJECTED, result.status)
        assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, savedOrder.captured.status)
        verify(exactly = 0) { execution.execute(any()) }
    }

    @Test
    fun `user cancellation locks refund before order and restores active service`() {
        val pending = refund(status = RefundWorkflowPersistenceService.PENDING)
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
        assertEquals(OrderStatusEnum.SERVICE_ACTIVE.value, savedOrder.captured.status)
        verifyOrder {
            refundRepository.findFirstByOrderIdOrderByCreatedAtDesc("order-1")
            refundRepository.findByIdForUpdate("refund-1")
            orderRepository.findByIdForUpdate("order-1")
        }
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
        every { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) } answers {
            firstArg<List<RefundItemEntity>>().also { persisted = it }
        }

        val first = persistence.prepareItems(processing)
        val second = persistence.prepareItems(processing)

        assertEquals(first, second)
        verify(exactly = 1) { refundItemRepository.saveAllAndFlush(any<List<RefundItemEntity>>()) }
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
        settlementReversalService = reversal
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
        currency: String = "USD"
    ) = payment(id, PaymentType.TRAVEL_GROUND_SERVICE_FEE, amountMinor, currency)

    private fun payment(
        id: String,
        type: PaymentType,
        amountMinor: Long,
        currency: String = "USD"
    ) = PaymentEntity(
        id = id,
        orderId = "order-1",
        userId = "user-1",
        amount = BigDecimal.valueOf(amountMinor, 2),
        method = "ALIPAY_PLUS_CASHIER",
        status = PaymentStatus.SUCCEEDED.name,
        paymentType = type.name,
        provider = PaymentProvider.ALIPAY_PLUS.name,
        currency = currency,
        amountMinor = amountMinor,
        providerPaymentId = "provider-$id"
    )

    private fun refundItem() = RefundItemEntity(
        id = "item-1",
        refundId = "refund-1",
        paymentId = "payment-1",
        provider = PaymentProvider.ALIPAY_PLUS.name,
        currency = "USD",
        amountMinor = 40_000L
    )
}
