package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConsultationFeeRepository @Inject constructor(
    private val apiService: ApiService
) {

    suspend fun getConsultationFee(doctorId: String, institutionProjectId: String): Result<Double> {
        return try {
            val response = apiService.getConsultationFee(doctorId, institutionProjectId)
            if (response.code == 200 && response.data != null) {
                val fee = (response.data["consultationFee"] as? Number)?.toDouble() ?: 0.0
                Result.success(fee)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
