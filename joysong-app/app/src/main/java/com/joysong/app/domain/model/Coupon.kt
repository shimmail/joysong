package com.joysong.app.domain.model

data class Coupon(
    val id: Long,
    val name: String,
    val type: String, // FIXED / PERCENTAGE
    val discountValue: Double,
    val minAmount: Double,
    val applicableProjectIds: String?,
    val applicableInstitutionIds: String?,
    val startTime: String,
    val endTime: String,
    val status: String
)

data class UserCoupon(
    val id: Long,
    val couponId: Long,
    val couponName: String,
    val couponType: String,
    val discountValue: Double,
    val minAmount: Double,
    val status: String, // UNUSED / USED / EXPIRED
    val expireAt: String,
    val usedAt: String?,
    val orderId: String?
)
