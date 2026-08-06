package com.joysong.server.refund.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.payment.domain.Money
import com.joysong.server.refund.domain.RefundReasonCode
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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

@Service
class RefundWorkflowPersistenceService(
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository,
    private val orderStatusLogService: OrderStatusLogService
) {
    companion object {
        const val PENDING = "PENDING"
        const val PROCESSING = "REFUND_PROCESSING"
        const val APPROVED = "APPROVED"
        const val REJECTED = "REJECTED"
        const val CANCELLED = "CANCELLED"
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
        require(current in setOf(
            OrderStatusEnum.CONSULTATION_PAID,
            OrderStatusEnum.VERIFIED,
            OrderStatusEnum.BALANCE_PAID,
            OrderStatusEnum.PENDING_COMPLETION,
            OrderStatusEnum.COMPLETED,
            OrderStatusEnum.PENDING_SETTLEMENT
        )) { "当前状态[${current.value}]不允许申请退款" }
        require(refundRepository.findAllByOrderIdAndStatusIn(
            orderId,
            listOf(PENDING, PROCESSING, APPROVED)
        ).isEmpty()) { "该订单已有进行中的退款申请，请勿重复提交" }

        val automatic = current == OrderStatusEnum.CONSULTATION_PAID
        val now = LocalDateTime.now()
        val amountMinor = order.paidAmountMinor ?: Money.toMinor(order.paidAmount, order.currency)
        require(amountMinor > 0) { "REFUND_AMOUNT_NOT_POSITIVE" }
        val refund = refundRepository.saveAndFlush(
            RefundEntity(
                id = UUID.randomUUID().toString(),
                refundNo = generateRefundNo(),
                orderId = orderId,
                userId = userId,
                currency = order.currency,
                amount = order.paidAmount,
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
                updatedAt = now
            )
        )
        val orderAfterRequest = orderRepository.save(
            order.copy(
                status = if (automatic) order.status else OrderStatusEnum.DISPUTE_MEDIATION.value,
                refundStatus = refund.status,
                refundAmount = order.paidAmount,
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
        return RefundPreparation(refund, orderAfterRequest, automatic)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun beginApproval(id: String, adminId: String): RefundEntity? {
        val refund = refundRepository.findByIdForUpdate(id) ?: return null
        require(refund.status == PENDING) { "退款申请已处理，不能重复审核" }
        val now = LocalDateTime.now()
        return refundRepository.save(
            refund.copy(
                status = PROCESSING,
                reviewedBy = adminId,
                reviewedAt = now,
                processedAt = now,
                updatedAt = now
            )
        )
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
        if (refund.status == APPROVED) {
            val existingOrder = orderRepository.findById(refund.orderId)
                .orElseThrow { IllegalArgumentException("ORDER_NOT_FOUND") }
            return FinalizedRefund(refund, existingOrder)
        }
        require(refund.status == PROCESSING) { "INVALID_REFUND_STATUS" }
        val order = orderRepository.findByIdForUpdate(refund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
        val now = LocalDateTime.now()
        val completedRefund = refundRepository.save(
            refund.copy(
                status = APPROVED,
                refundedAmountMinor = outcome.refundedAmountMinor,
                refundAmount = Money.fromMinor(outcome.refundedAmountMinor, refund.currency),
                processedAt = refund.processedAt ?: now,
                completedAt = now,
                updatedAt = now
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
        return FinalizedRefund(completedRefund, completedOrder)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun reject(id: String, adminId: String, rejectReason: String): FinalizedRefund? {
        val refund = refundRepository.findByIdForUpdate(id) ?: return null
        require(refund.status == PENDING) { "退款申请已处理，不能重复审核" }
        val order = orderRepository.findByIdForUpdate(refund.orderId)
            ?: throw IllegalArgumentException("ORDER_NOT_FOUND")
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
        return FinalizedRefund(rejected, restored)
    }

    private fun generateRefundNo(): String =
        "RFD${DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(LocalDateTime.now())}${UUID.randomUUID().toString().take(6).uppercase()}"
}
