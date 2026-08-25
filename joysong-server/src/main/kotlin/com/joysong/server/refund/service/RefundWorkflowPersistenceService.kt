package com.joysong.server.refund.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.Money
import com.joysong.server.payment.domain.PaymentProvider
import com.joysong.server.payment.domain.PaymentCompensation
import com.joysong.server.payment.domain.PaymentStatus
import com.joysong.server.payment.domain.PaymentType
import com.joysong.server.payment.repository.PaymentRepository
import com.joysong.server.refund.domain.RefundReasonCode
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

data class RefundPreparation(
    val refund: RefundEntity,
    val order: OrderEntity,
    val automatic: Boolean
)

data class FinalizedRefund(
    val refund: RefundEntity,
    val order: OrderEntity
)

data class RefundApprovalPreparation(
    val refund: RefundEntity,
    val executeProvider: Boolean
)

@Service
class RefundWorkflowPersistenceService(
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository,
    private val orderStatusLogService: OrderStatusLogService,
    private val paymentRepository: PaymentRepository,
    private val businessNotificationDispatcher: RefundBusinessNotificationDispatcher
) {
    companion object {
        private val log = LoggerFactory.getLogger(RefundWorkflowPersistenceService::class.java)
        const val PENDING = "PENDING"
        const val PROCESSING = "REFUND_PROCESSING"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        const val CANCELLED = "CANCELLED"
        const val TRAVEL_GROUND_SERVICE_ONLY = "TRAVEL_GROUND_SERVICE_ONLY"
        const val REVERSAL_NOT_REQUIRED = "NOT_REQUIRED"
    }

    @Transactional(rollbackFor = [Exception::class])
    fun prepareApplication(
        orderId: String,
        userId: String,
        reason: String,
        description: String,
        evidenceUrl: String,
        reasonCode: String?
    ): RefundPreparation {
        require(reason.isNotBlank()) { "退款原因不能为空" }
        require(description.length <= 1000) { "退款说明不能超过1000字" }
        require(evidenceUrl.split(',').map(String::trim).count(String::isNotEmpty) <= 5) {
            "退款证据最多上传5张"
        }
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        require(order.userId == userId) { "无权操作该订单" }
        val current = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        val isTravelGroundService = order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY
        if (isTravelGroundService) {
            require(current in setOf(OrderStatusEnum.SERVICE_ACTIVE, OrderStatusEnum.COMPLETED)) {
                "当前状态[${current.value}]不允许申请退款"
            }
        } else {
            require(current in setOf(
                OrderStatusEnum.CONSULTATION_PAID,
                OrderStatusEnum.VERIFIED,
                OrderStatusEnum.BALANCE_PAID,
                OrderStatusEnum.PENDING_COMPLETION,
                OrderStatusEnum.COMPLETED,
                OrderStatusEnum.PENDING_SETTLEMENT
            )) { "当前状态[${current.value}]不允许申请退款" }
        }
        require(refundRepository.findAllByOrderIdAndStatusIn(
            orderId,
            listOf(PENDING, PROCESSING, APPROVED)
        ).isEmpty()) { "该订单已有进行中的退款申请，请勿重复提交" }

        val automatic = !isTravelGroundService && current == OrderStatusEnum.CONSULTATION_PAID
        val now = LocalDateTime.now()
        val amountMinor = if (isTravelGroundService) {
            validateServiceFeePayment(order)
            requireNotNull(order.travelGroundServiceFeeMinor) { "SERVICE_FEE_SNAPSHOT_MISSING" }
        } else {
            order.paidAmountMinor ?: Money.toMinor(order.paidAmount, order.currency)
        }
        require(amountMinor > 0) { "REFUND_AMOUNT_NOT_POSITIVE" }
        val amount = if (isTravelGroundService) Money.fromMinor(amountMinor, order.currency) else order.paidAmount
        val refund = refundRepository.saveAndFlush(
            RefundEntity(
                id = UUID.randomUUID().toString(),
                refundNo = generateRefundNo(),
                orderId = orderId,
                userId = userId,
                currency = order.currency,
                amount = amount,
                requestedAmountMinor = amountMinor,
                reason = reason,
                reasonCode = RefundReasonCode.normalize(reasonCode).name,
                description = description,
                status = if (automatic) PROCESSING else PENDING,
                evidenceUrl = evidenceUrl,
                originalStatus = order.status,
                orderNo = order.orderNo ?: "",
                projectName = order.projectName,
                paymentAmount = order.price,
                paymentTime = order.paymentTime,
                userPhone = order.userPhone,
                requestedAt = now,
                createdAt = now,
                updatedAt = now,
                revenueReversalStatus = if (isTravelGroundService) REVERSAL_NOT_REQUIRED else "PENDING",
                revenueReversedAt = if (isTravelGroundService) now else null
            )
        )
        val orderAfterRequest = orderRepository.save(
            order.copy(
                status = when {
                    automatic -> order.status
                    isTravelGroundService -> OrderStatusEnum.REFUND_REVIEW.value
                    else -> OrderStatusEnum.DISPUTE_MEDIATION.value
                },
                refundStatus = refund.status,
                refundAmount = amount,
                updatedAt = now
            )
        )
        if (!automatic) {
            orderStatusLogService.logTransition(
                orderId,
                current.value,
                orderAfterRequest.status,
                userId,
                "USER",
                "申请退款: $reason"
            )
        }
        notifyAfterCommitSafely("ORDER_REFUND_REQUESTED", orderAfterRequest.id) {
            businessNotificationDispatcher.orderRefundRequested(
                orderAfterRequest.id,
                orderAfterRequest.userId,
                orderAfterRequest.consultantId,
                orderAfterRequest.doctorId
            )
        }
        return RefundPreparation(refund, orderAfterRequest, automatic)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun beginApproval(id: String, adminId: String): RefundApprovalPreparation? {
        val refund = refundRepository.findByIdForUpdate(id) ?: return null
        val order = orderRepository.findByIdForUpdate(refund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
        val isTravelGroundService = order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY
        if (isTravelGroundService) {
            require(order.currency == "USD" && refund.currency == "USD") {
                "SERVICE_FEE_CURRENCY_NOT_USD"
            }
            val expectedAmount = requireNotNull(order.travelGroundServiceFeeMinor) {
                "SERVICE_FEE_SNAPSHOT_MISSING"
            }
            require(refund.requestedAmountMinor == expectedAmount) {
                "SERVICE_FEE_REFUND_AMOUNT_MISMATCH"
            }
            require(refund.status in setOf(PENDING, PROCESSING, APPROVED)) {
                "退款申请已处理，不能重复审核"
            }
            if (refund.status == APPROVED) {
                require(order.status == OrderStatusEnum.REFUNDED.value) { "INVALID_ORDER_REFUND_STATUS" }
                return RefundApprovalPreparation(refund, executeProvider = false)
            }
            if (refund.status == PROCESSING) {
                require(order.status == OrderStatusEnum.REFUND_PROCESSING.value) { "INVALID_ORDER_REFUND_STATUS" }
                return RefundApprovalPreparation(refund, executeProvider = false)
            }
            require(order.status == OrderStatusEnum.REFUND_REVIEW.value) { "INVALID_ORDER_REFUND_STATUS" }
        } else {
            require(refund.status == PENDING) { "退款申请已处理，不能重复审核" }
        }
        val now = LocalDateTime.now()
        val processing = refundRepository.save(
            refund.copy(
                status = PROCESSING,
                reviewedBy = adminId,
                reviewedAt = now,
                processedAt = now,
                updatedAt = now
            )
        )
        if (isTravelGroundService) {
            val processingOrder = orderRepository.save(
                order.copy(
                    status = OrderStatusEnum.REFUND_PROCESSING.value,
                    refundStatus = PROCESSING,
                    updatedAt = now
                )
            )
            orderStatusLogService.logTransition(
                order.id,
                order.status,
                processingOrder.status,
                adminId,
                "ADMIN",
                "管理员批准旅游地接服务费退款，提交原渠道处理"
            )
        }
        notifyAfterCommitSafely("ORDER_REFUND_APPROVED", order.id) {
            businessNotificationDispatcher.orderRefundApproved(
                order.id,
                order.userId,
                order.consultantId,
                order.doctorId
            )
        }
        return RefundApprovalPreparation(processing, executeProvider = true)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun finalizeSuccess(
        refundId: String,
        outcome: RefundExecutionOutcome,
        operatorId: String,
        operatorType: String,
        remark: String
    ): FinalizedRefund {
        require(outcome.completed) { "REFUND_PROVIDER_PROCESSING" }
        val refund = refundRepository.findByIdForUpdate(refundId)
            ?: throw IllegalArgumentException("REFUND_NOT_FOUND")
        val order = orderRepository.findByIdForUpdate(refund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
        if (refund.status == APPROVED) {
            return FinalizedRefund(refund, order)
        }
        require(refund.status == PROCESSING) { "INVALID_REFUND_STATUS" }
        val isTravelGroundService = order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY
        if (isTravelGroundService) {
            require(order.status == OrderStatusEnum.REFUND_PROCESSING.value) { "INVALID_ORDER_REFUND_STATUS" }
            require(outcome.refundedAmountMinor == refund.requestedAmountMinor) { "SERVICE_FEE_REFUND_NOT_FULL" }
        }
        val now = LocalDateTime.now()
        val completedRefund = refundRepository.save(
            refund.copy(
                status = APPROVED,
                refundedAmountMinor = outcome.refundedAmountMinor,
                refundAmount = Money.fromMinor(outcome.refundedAmountMinor, refund.currency),
                processedAt = refund.processedAt ?: now,
                completedAt = now,
                updatedAt = now,
                revenueReversalStatus = if (isTravelGroundService) REVERSAL_NOT_REQUIRED
                else refund.revenueReversalStatus,
                revenueReversedAt = if (isTravelGroundService) now else refund.revenueReversedAt
            )
        )
        val completedOrder = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.REFUNDED.value,
                refundStatus = APPROVED,
                refundAmount = completedRefund.refundAmount,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            order.id,
            order.status,
            completedOrder.status,
            operatorId,
            operatorType,
            remark
        )
        notifyAfterCommitSafely("ORDER_REFUNDED", completedOrder.id) {
            businessNotificationDispatcher.orderRefunded(
                completedOrder.id,
                completedOrder.userId,
                completedOrder.consultantId,
                completedOrder.doctorId
            )
        }
        return FinalizedRefund(completedRefund, completedOrder)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun reject(id: String, adminId: String, rejectReason: String): FinalizedRefund? {
        val refund = refundRepository.findByIdForUpdate(id) ?: return null
        require(refund.status == PENDING) { "退款申请已处理，不能重复审核" }
        val order = orderRepository.findByIdForUpdate(refund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
        val isTravelGroundService = order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY
        if (isTravelGroundService) {
            require(order.status == OrderStatusEnum.REFUND_REVIEW.value) { "INVALID_ORDER_REFUND_STATUS" }
            require(refund.originalStatus in setOf(
                OrderStatusEnum.SERVICE_ACTIVE.value,
                OrderStatusEnum.COMPLETED.value
            )) { "INVALID_TRAVEL_REFUND_ORIGINAL_STATUS" }
        }
        val now = LocalDateTime.now()
        val rejected = refundRepository.save(
            refund.copy(
                status = REJECTED,
                reviewedBy = adminId,
                reviewedAt = now,
                processedAt = now,
                rejectReason = rejectReason,
                description = "${refund.description}${if (refund.description.isNotBlank()) "\n" else ""}[拒绝原因] $rejectReason",
                updatedAt = now
            )
        )
        val restored = orderRepository.save(
            order.copy(
                status = refund.originalStatus,
                refundStatus = REJECTED,
                refundAmount = BigDecimal.ZERO,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            order.id,
            order.status,
            restored.status,
            adminId,
            "ADMIN",
            "管理员拒绝退款"
        )
        notifyAfterCommitSafely("ORDER_REFUND_REJECTED", restored.id) {
            businessNotificationDispatcher.orderRefundRejected(
                restored.id,
                restored.userId,
                restored.consultantId,
                restored.doctorId,
                rejectReason
            )
        }
        return FinalizedRefund(rejected, restored)
    }

    private fun notifyAfterCommitSafely(eventType: String, orderId: String, notification: () -> Unit) {
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            notifySafely(eventType, orderId, notification)
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                notifySafely(eventType, orderId, notification)
            }
        })
    }

    private fun notifySafely(eventType: String, orderId: String, notification: () -> Unit) {
        try {
            notification()
        } catch (error: Exception) {
            log.error("退款订单通知发送失败: type={}, orderId={}", eventType, orderId, error)
        }
    }

    private fun validateServiceFeePayment(order: OrderEntity) {
        require(order.currency == "USD") { "SERVICE_FEE_ORDER_CURRENCY_INVALID" }
        val expectedAmount = requireNotNull(order.travelGroundServiceFeeMinor) {
            "SERVICE_FEE_SNAPSHOT_MISSING"
        }
        require(expectedAmount > 0) { "REFUND_AMOUNT_NOT_POSITIVE" }
        val matches = paymentRepository.findAllByOrderIdAndStatusInOrderByCreatedAtAsc(
            order.id,
            PaymentStatus.successfulDatabaseValues
        ).filter {
            it.paymentType == PaymentType.TRAVEL_GROUND_SERVICE_FEE.name &&
                !PaymentCompensation.isRequired(it.failureCode)
        }
        require(matches.size == 1) { "SERVICE_FEE_PAYMENT_NOT_UNIQUE" }
        val payment = matches.single()
        require(payment.currency == "USD") { "SERVICE_FEE_PAYMENT_CURRENCY_MISMATCH" }
        require(payment.provider == PaymentProvider.ALIPAY_PLUS.name) {
            "SERVICE_FEE_PAYMENT_PROVIDER_NOT_ALIPAY_PLUS"
        }
        require(payment.amountMinor == expectedAmount) { "SERVICE_FEE_PAYMENT_AMOUNT_MISMATCH" }
        require(payment.refundedAmountMinor == 0L) { "SERVICE_FEE_PAYMENT_ALREADY_REFUNDED" }
    }

    private fun generateRefundNo(): String =
        "RFD${DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())}${UUID.randomUUID().toString().take(6).uppercase()}"
}
