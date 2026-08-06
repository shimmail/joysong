package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.domain.model.UserCoupon
import com.joysong.app.domain.repository.CouponRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CouponRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : CouponRepository {

    override suspend fun getAvailableCoupons(): Result<List<UserCoupon>> {
        return try {
            val response = apiService.getAvailableCoupons()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getMyCoupons(status: String?): Result<List<UserCoupon>> {
        return try {
            val response = apiService.getMyCoupons(status)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun calculateDiscount(couponId: Long, originalPrice: Double): Result<Map<String, Any>> {
        return try {
            val response = apiService.calculateDiscount(couponId, originalPrice)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
