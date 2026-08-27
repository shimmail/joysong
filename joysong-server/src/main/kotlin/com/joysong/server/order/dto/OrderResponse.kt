package com.joysong.server.order.dto

import com.fasterxml.jackson.annotation.JsonGetter
import com.joysong.server.order.entity.OrderEntity
import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderResponse(
    val id: String,
    val orderNo: String,
    val userId: String,
    val projectId: String,
    val institutionId: String?,
    val consultantId: String?,
    val doctorId: String,
    val institutionProjectId: String = "",
    val projectName: String,
    val institutionName: String?,
    val consultantName: String?,
    val consultantAvatar: String? = null,
    val coverImage: String,
    val amount: BigDecimal,
    val currency: String = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,
    val paidAmount: BigDecimal = BigDecimal.ZERO,
    val couponId: Long? = null,
    val userCouponId: Long? = null,
    val discountAmount: BigDecimal = BigDecimal.ZERO,
    val status: String,
    val paymentFlow: String = "LEGACY_MEDICAL",
    val medicalListPriceMinor: Long? = null,
    val platformServiceRateBps: Int? = null,
    val pricingPolicyRevision: String? = null,
    val travelGroundServiceFeeMinor: Long? = null,
    val consultantBound: Boolean = false,
    val serviceActivated: Boolean = false,
    val consultantDetailsVisible: Boolean = false,
    val serviceConversationReadable: Boolean = false,
    val serviceMessagingEnabled: Boolean = false,
    val quantity: Int,
    val remark: String,
    val consultationFee: BigDecimal = BigDecimal.ZERO,
    val remainingAmount: BigDecimal = BigDecimal.ZERO,
    val transactionMethod: String = "",
    val userPhone: String = "",
    val appointmentTime: LocalDateTime? = null,
    val paymentTime: LocalDateTime? = null,
    val verifyCode: String? = null,
    val qrCode: String = "",
    val evidenceUrl: String? = null,
    val hasReview: Boolean = false,
    val refundStatus: String = "NONE",
    val refundAmount: BigDecimal = BigDecimal.ZERO,
    val doctorName: String = "",
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val completedAt: LocalDateTime? = null,
    val canVerify: Boolean = false,
    val canRequestCompletion: Boolean = false
) {
    /**
     * Android 端使用 price 字段名，与 amount 值相同，保持向后兼容
     */
    @JsonGetter("price")
    fun getPrice(): BigDecimal = amount

    companion object {
        /** 管理侧可查看订单，但不能读取由用户出示的核销码。 */
        fun forManagement(entity: OrderEntity): OrderResponse =
            fromEntity(entity, entity.status, exposeInternalSnapshots = true).copy(
                verifyCode = null,
                canVerify = entity.status == OrderStatusEnum.CONSULTATION_PAID.value,
                canRequestCompletion = entity.status == OrderStatusEnum.BALANCE_PAID.value
            )

        /** 用户端不暴露内部结算阶段，统一显示为已完成。 */
        fun from(entity: OrderEntity): OrderResponse {
            val userStatus = when (entity.status) {
                OrderStatusEnum.PENDING_SETTLEMENT.value,
                OrderStatusEnum.SETTLED.value -> OrderStatusEnum.COMPLETED.value
                else -> entity.status
            }
            return fromEntity(entity, userStatus, exposeInternalSnapshots = false)
        }

        private fun fromEntity(
            entity: OrderEntity,
            responseStatus: String,
            exposeInternalSnapshots: Boolean
        ): OrderResponse {
            val isTravelGroundService = entity.paymentFlow == "TRAVEL_GROUND_SERVICE_ONLY"
            val activatedStatuses = setOf(
                OrderStatusEnum.SERVICE_ACTIVE.value,
                OrderStatusEnum.COMPLETED.value,
                OrderStatusEnum.REFUND_REVIEW.value,
                OrderStatusEnum.REFUND_PROCESSING.value,
                OrderStatusEnum.REFUNDED.value
            )
            val serviceActivated = isTravelGroundService &&
                entity.serviceActivatedAt != null &&
                entity.status in activatedStatuses
            val consultantDetailsVisible = serviceActivated && entity.status != OrderStatusEnum.REFUNDED.value
            val exposeFulfillment = !isTravelGroundService || exposeInternalSnapshots || consultantDetailsVisible
            val conversationReadable = serviceActivated
            val messagingEnabled = serviceActivated && entity.status == OrderStatusEnum.SERVICE_ACTIVE.value
            return OrderResponse(
            id = entity.id,
            orderNo = entity.orderNo ?: "",
            userId = entity.userId,
            projectId = entity.projectId,
            institutionId = entity.institutionId.takeIf { exposeFulfillment },
            consultantId = entity.consultantId.takeIf { exposeFulfillment },
            doctorId = entity.doctorId,
            institutionProjectId = entity.institutionProjectId,
            projectName = entity.projectName,
            institutionName = entity.institutionName.takeIf { exposeFulfillment },
            consultantName = entity.consultantName.takeIf { exposeFulfillment },
            consultantAvatar = entity.consultantAvatar?.takeIf { exposeFulfillment },
            coverImage = entity.coverImage,
            amount = entity.price,
            currency = entity.currency,
            paidAmount = entity.paidAmount,
            couponId = entity.couponId,
            userCouponId = entity.userCouponId,
            discountAmount = entity.discountAmount,
            status = responseStatus,
            paymentFlow = entity.paymentFlow,
            medicalListPriceMinor = entity.medicalListPriceMinor,
            platformServiceRateBps = entity.platformServiceRateBps,
            pricingPolicyRevision = entity.pricingPolicyRevision,
            travelGroundServiceFeeMinor = entity.travelGroundServiceFeeMinor,
            consultantBound = isTravelGroundService && entity.consultantId.isNotBlank(),
            serviceActivated = serviceActivated,
            consultantDetailsVisible = consultantDetailsVisible,
            serviceConversationReadable = conversationReadable,
            serviceMessagingEnabled = messagingEnabled,
            quantity = entity.quantity,
            remark = entity.remark,
            consultationFee = entity.consultationFee,
            remainingAmount = entity.remainingAmount,
            transactionMethod = entity.transactionMethod,
            userPhone = entity.userPhone,
            appointmentTime = entity.appointmentTime,
            paymentTime = entity.paymentTime,
            verifyCode = entity.verifyCode,
            qrCode = entity.qrCode,
            evidenceUrl = entity.evidenceUrl,
            hasReview = entity.hasReview,
            refundStatus = entity.refundStatus,
            refundAmount = entity.refundAmount,
            doctorName = entity.doctorName,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt,
            completedAt = entity.completedAt
        )
        }
    }
}
