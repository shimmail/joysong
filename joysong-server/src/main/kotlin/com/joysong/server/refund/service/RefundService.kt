package com.joysong.server.refund.service

import com.joysong.server.coupon.service.CouponService
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.dto.RefundDetailResponse
import com.joysong.server.refund.entity.RefundItemEntity
import com.joysong.server.refund.repository.RefundItemRepository
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.settlement.service.SettlementReversalService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.math.BigDecimal
import java.time.LocalDateTime

@Service
class RefundService(
    private val refundRepository: RefundRepository,
    private val orderRepository: OrderRepository,
    private val orderStatusLogService: OrderStatusLogService,
    @Lazy private val couponService: CouponService,
    private val workflowPersistenceService: RefundWorkflowPersistenceService,
    private val refundEvidenceFileService: RefundEvidenceFileService,
    private val refundExecutionService: RefundExecutionService? = null,
    private val settlementReversalService: SettlementReversalService? = null,
    private val refundItemRepository: RefundItemRepository? = null
) {
    companion object {
        private val log = LoggerFactory.getLogger(RefundService::class.java)
    }

    /** Creates the local request first and invokes the provider only after it is committed. */
    fun applyRefund(
        orderId: String,
        userId: String,
        reason: String,
        description: String,
        evidenceUrl: String = "",
        reasonCode: String? = null
    ): RefundEntity {
        val preparation = workflowPersistenceService.prepareApplication(
            orderId,
            userId,
            reason,
            description,
            evidenceUrl,
            reasonCode
        )
        if (!preparation.automatic) return preparation.refund

        val executor = refundExecutionService ?: return preparation.refund
        val outcome = executor.execute(preparation.refund)
        if (!outcome.completed) {
            return refundRepository.findById(preparation.refund.id).orElse(preparation.refund)
        }
        val finalized = workflowPersistenceService.finalizeSuccess(
            preparation.refund.id,
            outcome,
            userId,
            "USER",
            "面诊金自动退款完成: $reason"
        )
        afterApproved(finalized)
        log.info("用户[{}]自动退款完成, orderId={}, refundId={}", userId, orderId, finalized.refund.id)
        return finalized.refund
    }

    fun applyTravelServiceRefundWithEvidence(
        orderId: String,
        userId: String,
        reason: String,
        description: String,
        reasonCode: String?,
        evidenceFiles: List<MultipartFile>,
    ): RefundDetailResponse {
        val preparation = workflowPersistenceService.prepareTravelServiceApplicationWithEvidence(
            orderId,
            userId,
            reason,
            description,
            reasonCode,
            evidenceFiles,
        )
        return RefundDetailResponse(
            refund = preparation.refund,
            evidenceFiles = refundEvidenceFileService.listForRefund(preparation.refund.id),
        )
    }

    fun getRefundByOrderId(orderId: String, userId: String): RefundEntity? {
        val order = orderRepository.findById(orderId).orElse(null) ?: return null
        if (order.userId != userId) return null
        return refundRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
    }

    fun getRefundDetailByOrderId(orderId: String, userId: String): RefundDetailResponse? {
        val refund = getRefundByOrderId(orderId, userId) ?: return null
        return RefundDetailResponse(
            refund = refund,
            evidenceFiles = refundEvidenceFileService.listForRefund(refund.id),
        )
    }

    @Transactional(rollbackFor = [Exception::class])
    fun cancelRefund(orderId: String, userId: String): String {
        val candidate = refundRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
            ?: throw IllegalArgumentException("未找到退款记录")
        val refund = refundRepository.findByIdForUpdate(candidate.id)
            ?: throw IllegalArgumentException("未找到退款记录")
        require(refund.userId == userId) { "无权操作该订单" }
        require(refund.status == RefundWorkflowPersistenceService.PENDING) { "当前退款记录不允许取消" }
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        require(order.userId == userId) { "无权操作该订单" }
        require(order.refundStatus == RefundWorkflowPersistenceService.PENDING) { "当前退款状态不允许取消" }
        val isTravelGroundService = order.paymentFlow == RefundWorkflowPersistenceService.TRAVEL_GROUND_SERVICE_ONLY
        if (isTravelGroundService) {
            require(order.status == com.joysong.server.order.dto.OrderStatusEnum.REFUND_REVIEW.value) {
                "INVALID_ORDER_REFUND_STATUS"
            }
            require(refund.originalStatus in setOf(
                com.joysong.server.order.dto.OrderStatusEnum.SERVICE_ACTIVE.value,
                com.joysong.server.order.dto.OrderStatusEnum.COMPLETED.value
            )) { "INVALID_TRAVEL_REFUND_ORIGINAL_STATUS" }
        }
        val now = LocalDateTime.now()
        refundRepository.save(
            refund.copy(
                status = RefundWorkflowPersistenceService.CANCELLED,
                processedAt = now,
                updatedAt = now
            )
        )
        orderRepository.save(
            order.copy(
                refundStatus = "NONE",
                refundAmount = BigDecimal.ZERO,
                status = refund.originalStatus,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            orderId,
            order.status,
            refund.originalStatus,
            userId,
            "USER",
            "取消退款申请"
        )
        return "退款已取消"
    }

    fun adminListAll(): List<Map<String, Any?>> {
        val refunds = refundRepository.findAll()
        val orders = orderRepository.findAllById(refunds.map { it.orderId }).associateBy { it.id }
        return refunds.map { refund ->
            val order = orders[refund.orderId]
            val items = refundItemRepository
                ?.findAllByRefundIdOrderByCreatedAtAsc(refund.id)
                .orEmpty()
            mapOf(
                "id" to refund.id,
                "orderId" to refund.orderId,
                "userId" to refund.userId,
                "amount" to refund.amount,
                "reason" to refund.reason,
                "description" to refund.description,
                "status" to refund.status,
                "createdAt" to refund.createdAt,
                "processedAt" to refund.processedAt,
                "evidenceUrl" to refund.evidenceUrl,
                "evidenceFiles" to refundEvidenceFileService.listForRefund(refund.id),
                "refundType" to refund.refundType,
                "refundAmount" to refund.refundAmount,
                "userPhone" to refund.userPhone,
                "orderNo" to refund.orderNo,
                "projectName" to refund.projectName,
                "paymentAmount" to refund.paymentAmount,
                "paymentTime" to refund.paymentTime,
                "updatedAt" to refund.updatedAt,
                "originalStatus" to refund.originalStatus,
                "refundNo" to refund.refundNo,
                "currency" to refund.currency,
                "requestedAmountMinor" to refund.requestedAmountMinor,
                "refundedAmountMinor" to refund.refundedAmountMinor,
                "reasonCode" to refund.reasonCode,
                "reviewedBy" to refund.reviewedBy,
                "reviewedAt" to refund.reviewedAt,
                "rejectReason" to refund.rejectReason,
                "completedAt" to refund.completedAt,
                "items" to items.map(::adminRefundItem),
                "paymentFlow" to order?.paymentFlow,
                "doctorName" to (order?.doctorName ?: ""),
                "institutionName" to (order?.institutionName ?: "")
            )
        }
    }

    private fun adminRefundItem(item: RefundItemEntity): Map<String, Any?> = mapOf(
        "id" to item.id,
        "paymentId" to item.paymentId,
        "provider" to item.provider,
        "currency" to item.currency,
        "amountMinor" to item.amountMinor,
        "providerRefundId" to item.providerRefundId,
        "status" to item.status,
        "failureCode" to item.failureCode,
        "failureMessage" to item.failureMessage,
        "requestedAt" to item.requestedAt,
        "completedAt" to item.completedAt,
        "createdAt" to item.createdAt,
        "updatedAt" to item.updatedAt
    )

    fun adminUpdateStatus(
        id: String,
        status: String,
        adminId: String,
        rejectReason: String = ""
    ): RefundEntity? {
        val target = status.trim().uppercase()
        require(target == RefundWorkflowPersistenceService.APPROVED ||
            target == RefundWorkflowPersistenceService.REJECTED
        ) { "退款审核状态只能是 APPROVED 或 REJECTED" }
        if (target == RefundWorkflowPersistenceService.REJECTED) {
            require(rejectReason.isNotBlank()) { "拒绝退款时必须填写原因" }
            return workflowPersistenceService.reject(id, adminId, rejectReason)?.refund
        }

        val approval = workflowPersistenceService.beginApproval(id, adminId) ?: return null
        val processing = approval.refund
        if (!approval.executeProvider) return processing
        val executor = refundExecutionService ?: return processing
        val outcome = executor.execute(processing)
        if (!outcome.completed) return refundRepository.findById(id).orElse(processing)
        val finalized = workflowPersistenceService.finalizeSuccess(
            id,
            outcome,
            adminId,
            "ADMIN",
            "管理员批准退款，渠道退款完成"
        )
        afterApproved(finalized)
        log.info("管理员[{}]批准退款完成, refundId={}", adminId, id)
        return finalized.refund
    }

    /** Internal compensation entry point for REFUND_PROCESSING records. */
    fun retryProcessingRefund(id: String): RefundEntity {
        val refund = refundRepository.findById(id)
            .orElseThrow { IllegalArgumentException("REFUND_NOT_FOUND") }
        if (refund.status != RefundWorkflowPersistenceService.PROCESSING) return refund
        val executor = refundExecutionService ?: return refund
        val outcome = executor.execute(refund)
        if (!outcome.completed) return refundRepository.findById(id).orElse(refund)
        val finalized = workflowPersistenceService.finalizeSuccess(
            id,
            outcome,
            "SYSTEM",
            "SYSTEM",
            "退款补偿任务确认渠道退款完成"
        )
        afterApproved(finalized)
        return finalized.refund
    }

    /** Admin-only retry entry point: only failed items are made executable again. */
    fun retryFailedProcessingRefund(id: String, adminId: String): RefundEntity {
        val refund = refundRepository.findById(id)
            .orElseThrow { IllegalArgumentException("REFUND_NOT_FOUND") }
        require(refund.status == RefundWorkflowPersistenceService.PROCESSING) {
            "INVALID_REFUND_STATUS"
        }
        val executor = refundExecutionService ?: throw IllegalStateException("REFUND_EXECUTOR_UNAVAILABLE")
        val outcome = executor.retryFailed(refund)
        if (!outcome.completed) return refundRepository.findById(id).orElse(refund)
        val finalized = workflowPersistenceService.finalizeSuccess(
            id,
            outcome,
            adminId,
            "ADMIN",
            "管理员重试失败退款项，渠道退款完成"
        )
        afterApproved(finalized)
        return finalized.refund
    }

    private fun afterApproved(finalized: FinalizedRefund) {
        if (finalized.order.paymentFlow != RefundWorkflowPersistenceService.TRAVEL_GROUND_SERVICE_ONLY) {
            settlementReversalService?.reverseCompletedRefund(finalized.refund.id)
            finalized.order.userCouponId?.let { returnCouponSafely(it, finalized.order.id) }
        }
        notifyRefundApplied(finalized.order, finalized.refund)
    }

    private fun returnCouponSafely(userCouponId: Long, orderId: String) {
        try {
            couponService.returnCoupon(userCouponId)
            log.info("退还优惠券成功[orderId={}, userCouponId={}]", orderId, userCouponId)
        } catch (error: Exception) {
            log.warn("退还优惠券失败[orderId={}, userCouponId={}]: {}", orderId, userCouponId, error.message)
        }
    }

    private fun notifyRefundApplied(order: OrderEntity, refund: RefundEntity) {
        log.info(
            "退款通知: 订单[{}]退款已批准, 退款金额={}, 原因={}, 需通知医生和机构",
            order.orderNo,
            refund.amount,
            refund.reason
        )
    }
}
