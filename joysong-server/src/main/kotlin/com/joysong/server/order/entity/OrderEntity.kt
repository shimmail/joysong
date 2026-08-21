package com.joysong.server.order.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime
import com.joysong.server.common.money.CurrencyCode

@Entity
@Table(name = "orders")
@SQLDelete(sql = "UPDATE orders SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
data class OrderEntity(
    @Id val id: String,
    @Column(name = "user_id") val userId: String,
    @Column(name = "project_name") val projectName: String,
    @Column(name = "institution_name") val institutionName: String = "",
    @Column(name = "cover_image") val coverImage: String = "",
    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)") val currency: String = CurrencyCode.DEFAULT_CODE,
    val price: BigDecimal,
    @Column(name = "total_amount_minor") val totalAmountMinor: Long? = null,

    /** 累计已付金额 */
    @Column(name = "paid_amount") val paidAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "paid_amount_minor") val paidAmountMinor: Long? = null,

    /** 使用的优惠券ID */
    @Column(name = "coupon_id") val couponId: Long? = null,

    /** 用户优惠券记录ID */
    @Column(name = "user_coupon_id") val userCouponId: Long? = null,

    /** 优惠金额 */
    @Column(name = "discount_amount") val discountAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "discount_amount_minor") val discountAmountMinor: Long? = null,

    val status: String,
    @Column(name = "payment_flow", nullable = false, length = 40) val paymentFlow: String = "LEGACY_MEDICAL",
    @Column(name = "medical_list_price_minor") val medicalListPriceMinor: Long? = null,
    @Column(name = "platform_service_rate_bps") val platformServiceRateBps: Int? = null,
    @Column(name = "travel_ground_service_fee_minor") val travelGroundServiceFeeMinor: Long? = null,
    @Column(name = "created_at") val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "appointment_time") val appointmentTime: LocalDateTime? = null,
    @Column(name = "qr_code") val qrCode: String = "",
    @Column(name = "has_review") val hasReview: Boolean = false,
    @Column(name = "project_id") val projectId: String = "",
    @Column(name = "institution_id") val institutionId: String = "",
    @Column(name = "consultant_id") val consultantId: String = "",
    @Column(name = "consultant_name") val consultantName: String = "",
    @Column(name = "consultant_avatar") val consultantAvatar: String? = null,
    @Column(name = "doctor_id") val doctorId: String = "",
    @Column(name = "consultation_fee") val consultationFee: BigDecimal = BigDecimal.ZERO,
    @Column(name = "consultation_fee_minor") val consultationFeeMinor: Long? = null,
    @Column(name = "remaining_amount") val remainingAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "remaining_amount_minor") val remainingAmountMinor: Long? = null,
    @Column(name = "pricing_country", length = 2, columnDefinition = "char(2)") val pricingCountry: String? = null,
    @Column(name = "transaction_method") val transactionMethod: String = "",
    @Column(name = "user_phone") val userPhone: String = "",
    @Column(name = "payment_time") val paymentTime: LocalDateTime? = null,
    @Column(name = "service_activated_at") val serviceActivatedAt: LocalDateTime? = null,
    @Column(name = "refund_status") val refundStatus: String = "NONE",
    @Column(name = "refund_amount") val refundAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "doctor_name") val doctorName: String = "",
    @Column(name = "order_no") val orderNo: String? = null,
    val quantity: Int = 1,
    val remark: String = "",
    @Column(name = "evidence_url") val evidenceUrl: String = "",
    @Column(name = "institution_project_id") val institutionProjectId: String = "",
    @Column(name = "updated_at") val updatedAt: LocalDateTime? = null,

    /** 到店核验时间 */
    @Column(name = "verified_at") val verifiedAt: LocalDateTime? = null,

    /** 核销码 */
    @Column(name = "verify_code") val verifyCode: String? = null,

    /** 尾款支付时间 */
    @Column(name = "balance_paid_at") val balancePaidAt: LocalDateTime? = null,

    /** 机构申请完成时间 */
    @Column(name = "completion_requested_at") val completionRequestedAt: LocalDateTime? = null,

    /** 用户确认完成时间（用于自动好评超时计算） */
    @Column(name = "completed_at") val completedAt: LocalDateTime? = null,

    /** 结算到期时间 */
    @Column(name = "settlement_at") val settlementAt: LocalDateTime? = null,

    @Column(name = "deleted_at") var deletedAt: LocalDateTime? = null
)
