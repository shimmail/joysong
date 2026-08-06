package com.joysong.server.coupon.dto

import com.joysong.server.coupon.entity.CouponEntity
import com.joysong.server.coupon.entity.UserCouponEntity
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 用户优惠券响应DTO
 *
 * @author joysong
 * @since 2026-07-30
 */
data class UserCouponResponse(
    val id: Long,
    val couponId: Long,
    val couponName: String,
    val couponType: String,
    val discountValue: BigDecimal,
    val minAmount: BigDecimal,
    val status: String,
    val expireAt: LocalDateTime,
    val usedAt: LocalDateTime?,
    val orderId: String?
) {
    companion object {
        /**
         * 从用户优惠券实体和优惠券实体组合构建响应DTO
         *
         * @param userCoupon 用户优惠券实体
         * @param coupon     优惠券定义实体
         * @return 用户优惠券响应DTO
         */
        fun from(userCoupon: UserCouponEntity, coupon: CouponEntity): UserCouponResponse =
            UserCouponResponse(
                id = userCoupon.id,
                couponId = userCoupon.couponId,
                couponName = coupon.name,
                couponType = coupon.type,
                discountValue = coupon.discountValue,
                minAmount = coupon.minAmount,
                status = userCoupon.status,
                expireAt = userCoupon.expireAt,
                usedAt = userCoupon.usedAt,
                orderId = userCoupon.orderId
            )
    }
}
