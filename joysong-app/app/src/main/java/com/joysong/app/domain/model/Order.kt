package com.joysong.app.domain.model

enum class OrderStatus(val displayName: String) {
    PENDING_PAYMENT("待支付面诊金"),
    CONSULTATION_PAID("面诊金已付"),
    VERIFIED("已核验"),
    BALANCE_PAID("全款已付"),
    PENDING_COMPLETION("待确认完成"),
    COMPLETED("已完成"),
    PENDING_SETTLEMENT("待结算"),
    SETTLED("已结算"),
    DISPUTE_MEDIATION("纠纷调解"),
    CANCELLED("已取消"),
    REFUNDED("已退款"),
    // Legacy aliases (mapped from old server values)
    PENDING_REMAINING("待支付尾款"),
    PAID("已支付"),
    TO_USE("待使用"),
    REFUNDING("退款中");
}

data class Order(
    val id: String,
    val projectName: String,
    val institutionName: String,
    val coverImage: String = "",
    val price: Double,
    val paidAmount: Double = 0.0,
    val status: OrderStatus,
    val createdAt: String,
    val appointmentTime: String = "",
    val qrCode: String = "",
    val projectId: String = "",
    val institutionId: String = "",
    val doctorId: String = "",
    val consultationFee: Double = 0.0,
    val remainingAmount: Double = 0.0,
    val transactionMethod: String = "",
    val userPhone: String = "",
    val paymentTime: String = "",
    val refundStatus: String = "NONE",
    val refundAmount: Double = 0.0,
    val doctorName: String = "",
    val orderNo: String = "",
    val verifyCode: String = "",
    val verifiedAt: String = "",
    val couponDiscount: Double = 0.0,
    val completedAt: String = "",
    val hasReview: Boolean = false
)

data class Settlement(
    val id: Long = 0,
    val orderId: String = "",
    val totalAmount: Double = 0.0,
    val platformAmount: Double = 0.0,
    val institutionAmount: Double = 0.0,
    val consultantAmount: Double = 0.0,
    val doctorAmount: Double = 0.0,
    val status: String = "",
    val settledAt: Long? = null
)
