package com.joysong.app.domain.repository

import com.joysong.app.domain.model.UserCoupon

interface CouponRepository {
    suspend fun getAvailableCoupons(): Result<List<UserCoupon>>
    suspend fun getMyCoupons(status: String? = null): Result<List<UserCoupon>>
    suspend fun calculateDiscount(couponId: Long, originalPrice: Double): Result<Map<String, Any>>
}
