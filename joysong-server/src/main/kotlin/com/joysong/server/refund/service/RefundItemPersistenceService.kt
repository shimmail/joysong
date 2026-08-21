package com.joysong.server.refund.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.provider.ProviderRefundResult
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class RefundItemPersistenceService(
    private val paymentRepository: PaymentRepository,
    private val refundItemRepository: RefundItemRepository,
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository
) {
    /** Short transaction: freezes the allocation before any provider request is sent. */
    @Transactional(rollbackFor = [Exception::class])
    fun prepareItems(refund: RefundEntity): List<RefundItemEntity> {
        val lockedRefund = refundRepository.findByIdForUpdate(refund.id)
            ?: throw IllegalArgumentException("REFUND_NOT_FOUND")
        val order = orderRepository.findByIdForUpdate(lockedRefund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
        val existing = refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(lockedRefund.id)
        val isTravelGroundService = order.paymentFlow ==
            RefundWorkflowPersistenceService.TRAVEL_GROUND_SERVICE_ONLY
        if (!isTravelGroundService && existing.isNotEmpty()) return existing

        val target = requireNotNull(lockedRefund.requestedAmountMinor) { "REFUND_AMOUNT_SNAPSHOT_MISSING" }
        require(target > 0) { "REFUND_AMOUNT_NOT_POSITIVE" }
        if (isTravelGroundService) {
            require(lockedRefund.status == RefundWorkflowPersistenceService.PROCESSING) {
                "INVALID_REFUND_STATUS"
            }
            require(order.status == OrderStatusEnum.REFUND_PROCESSING.value) {
                "INVALID_ORDER_REFUND_STATUS"
            }
            require(order.currency == "USD" && lockedRefund.currency == "USD") {
                "SERVICE_FEE_CURRENCY_NOT_USD"
            }
            val expectedAmount = requireNotNull(order.travelGroundServiceFeeMinor) {
                "SERVICE_FEE_SNAPSHOT_MISSING"
            }
            require(target == expectedAmount) { "SERVICE_FEE_REFUND_AMOUNT_MISMATCH" }
            require(lockedRefund.currency == order.currency) { "SERVICE_FEE_REFUND_CURRENCY_MISMATCH" }
            if (existing.isNotEmpty()) {
                return validateExistingServiceFeeItem(existing, lockedRefund, order, target)
            }
            val servicePayments = paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc(
                lockedRefund.orderId,
                PaymentStatus.successfulDatabaseValues
            ).filter { it.paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE.name }
            require(servicePayments.size == 1) { "SERVICE_FEE_PAYMENT_NOT_UNIQUE" }
            val payment = servicePayments.single()
            require(payment.currency == "USD") { "SERVICE_FEE_PAYMENT_CURRENCY_MISMATCH" }
            require(payment.provider == PaymentProvider.ALIPAY_PLUS.name) {
                "SERVICE_FEE_PAYMENT_PROVIDER_NOT_ALIPAY_PLUS"
            }
            require(payment.amountMinor == expectedAmount) { "SERVICE_FEE_PAYMENT_AMOUNT_MISMATCH" }
            require(payment.refundedAmountMinor == 0L) { "SERVICE_FEE_PAYMENT_ALREADY_REFUNDED" }
            val now = LocalDateTime.now()
            return refundItemRepository.saveAllAndFlush(
                listOf(
                    RefundItemEntity(
                        id = UUID.randomUUID().toString(),
                        refundId = lockedRefund.id,
                        paymentId = payment.id,
                        provider = payment.provider,
                        currency = payment.currency,
                        amountMinor = target,
                        requestedAt = now,
                        createdAt = now,
                        updatedAt = now
                    )
                )
            )
        }
        val payments = paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc(
            lockedRefund.orderId,
            PaymentStatus.successfulDatabaseValues + PaymentStatus.PARTIALLY_REFUNDED.name
        )
        var remaining = target
        val now = LocalDateTime.now()
        val items = mutableListOf<RefundItemEntity>()
        for (payment in payments) {
            if (remaining == 0L) break
            val paidMinor = payment.amountMinor ?: continue
            val refundable = paidMinor - payment.refundedAmountMinor
            if (refundable <= 0) continue
            val itemAmount = minOf(refundable, remaining)
            items += RefundItemEntity(
                id = UUID.randomUUID().toString(),
                refundId = lockedRefund.id,
                paymentId = payment.id,
                provider = payment.provider,
                currency = payment.currency,
                amountMinor = itemAmount,
                requestedAt = now,
                createdAt = now,
                updatedAt = now
            )
            remaining -= itemAmount
        }
        require(remaining == 0L) { "REFUNDABLE_PAYMENT_AMOUNT_INSUFFICIENT" }
        return refundItemRepository.saveAllAndFlush(items)
    }

    private fun validateExistingServiceFeeItem(
        items: List<RefundItemEntity>,
        refund: RefundEntity,
        order: OrderEntity,
        target: Long
    ): List<RefundItemEntity> {
        require(items.size == 1) { "SERVICE_FEE_REFUND_ITEMS_INVALID" }
        val item = items.single()
        require(item.refundId == refund.id) { "SERVICE_FEE_REFUND_ITEM_REFUND_MISMATCH" }
        require(item.amountMinor == target) { "SERVICE_FEE_REFUND_ITEM_AMOUNT_MISMATCH" }
        require(item.currency == "USD") { "SERVICE_FEE_REFUND_ITEM_CURRENCY_MISMATCH" }
        require(item.provider == PaymentProvider.ALIPAY_PLUS.name) {
            "SERVICE_FEE_REFUND_ITEM_PROVIDER_NOT_ALIPAY_PLUS"
        }
        require(item.status in setOf(
            PaymentStatus.CREATED.name,
            PaymentStatus.PROCESSING.name,
            PaymentStatus.FAILED.name,
            PaymentStatus.SUCCEEDED.name
        )) { "SERVICE_FEE_REFUND_ITEM_STATUS_INVALID" }

        val payment = paymentRepository.findByIdForUpdate(item.paymentId)
            ?: throw IllegalArgumentException("SERVICE_FEE_REFUND_PAYMENT_NOT_FOUND")
        require(payment.orderId == order.id) { "SERVICE_FEE_REFUND_PAYMENT_ORDER_MISMATCH" }
        require(payment.paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE.name) {
            "SERVICE_FEE_REFUND_ITEM_PAYMENT_TYPE_MISMATCH"
        }
        require(payment.currency == "USD") { "SERVICE_FEE_PAYMENT_CURRENCY_MISMATCH" }
        require(payment.provider == PaymentProvider.ALIPAY_PLUS.name) {
            "SERVICE_FEE_PAYMENT_PROVIDER_NOT_ALIPAY_PLUS"
        }
        require(payment.amountMinor == target) { "SERVICE_FEE_PAYMENT_AMOUNT_MISMATCH" }
        require(item.provider == payment.provider) { "SERVICE_FEE_REFUND_ITEM_PROVIDER_MISMATCH" }
        if (item.status == PaymentStatus.SUCCEEDED.name) {
            require(payment.status == PaymentStatus.REFUNDED.name) {
                "SERVICE_FEE_REFUND_PAYMENT_STATUS_MISMATCH"
            }
            require(payment.refundedAmountMinor == target) { "SERVICE_FEE_PAYMENT_REFUND_AMOUNT_MISMATCH" }
        } else {
            require(payment.status in PaymentStatus.successfulDatabaseValues) {
                "SERVICE_FEE_REFUND_PAYMENT_STATUS_MISMATCH"
            }
            require(payment.refundedAmountMinor == 0L) { "SERVICE_FEE_PAYMENT_ALREADY_REFUNDED" }
        }
        return items
    }

    @Transactional(rollbackFor = [Exception::class])
    fun applyProviderResult(itemId: String, result: ProviderRefundResult): RefundItemEntity {
        require(result.status in setOf(
            PaymentStatus.PROCESSING,
            PaymentStatus.SUCCEEDED,
            PaymentStatus.FAILED
        )) { "UNSUPPORTED_PROVIDER_REFUND_STATUS" }
        require(result.providerRefundId.isNotBlank()) { "PROVIDER_REFUND_ID_MISSING" }
        val item = refundItemRepository.findByIdForUpdate(itemId)
            ?: throw IllegalArgumentException("REFUND_ITEM_NOT_FOUND")
        if (item.status == PaymentStatus.SUCCEEDED.name) return item
        val payment = paymentRepository.findByIdForUpdate(item.paymentId)
            ?: throw IllegalArgumentException("PAYMENT_NOT_FOUND")
        val now = LocalDateTime.now()
        val updatedItem = refundItemRepository.save(
            item.copy(
                providerRefundId = result.providerRefundId,
                status = result.status.name,
                failureCode = result.failureCode,
                failureMessage = result.failureMessage?.take(500),
                completedAt = if (result.status == PaymentStatus.SUCCEEDED) now else item.completedAt,
                updatedAt = now
            )
        )
        if (result.status == PaymentStatus.SUCCEEDED) {
            val paidMinor = requireNotNull(payment.amountMinor) { "PAYMENT_AMOUNT_MISSING" }
            val refundedMinor = payment.refundedAmountMinor + item.amountMinor
            require(refundedMinor <= paidMinor) { "PAYMENT_REFUND_AMOUNT_EXCEEDED" }
            paymentRepository.save(
                payment.copy(
                    refundedAmountMinor = refundedMinor,
                    status = if (refundedMinor == paidMinor) PaymentStatus.REFUNDED.name
                    else PaymentStatus.PARTIALLY_REFUNDED.name,
                    updatedAt = now
                )
            )
        }
        return updatedItem
    }

    @Transactional(rollbackFor = [Exception::class])
    fun markProviderError(itemId: String, status: PaymentStatus, code: String, message: String?) {
        require(status == PaymentStatus.PROCESSING || status == PaymentStatus.FAILED) {
            "INVALID_REFUND_ERROR_STATUS"
        }
        val item = refundItemRepository.findByIdForUpdate(itemId)
            ?: throw IllegalArgumentException("REFUND_ITEM_NOT_FOUND")
        if (item.status == PaymentStatus.SUCCEEDED.name) return
        refundItemRepository.save(
            item.copy(
                status = status.name,
                failureCode = code.take(100),
                failureMessage = message?.take(500),
                updatedAt = LocalDateTime.now()
            )
        )
    }

    fun summarize(refundId: String, target: Long): RefundExecutionOutcome {
        val completed = refundItemRepository.findAllByRefundIdOrderByCreatedAtAsc(refundId)
            .filter { it.status == PaymentStatus.SUCCEEDED.name }
            .sumOf { it.amountMinor }
        return RefundExecutionOutcome(completed, completed == target)
    }
}
