package com.joysong.app.data.repository

import com.google.gson.Gson
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.ApiResponse
import com.joysong.app.data.remote.dto.GoogleLoginRequestDto
import com.joysong.app.data.remote.dto.LoginRequestDto
import com.joysong.app.data.remote.dto.LoginWithCodeRequestDto
import com.joysong.app.data.remote.dto.LogoutRequestDto
import com.joysong.app.data.remote.dto.RegisterRequestDto
import com.joysong.app.data.remote.dto.ResetPasswordRequestDto
import com.joysong.app.data.remote.dto.SendCodeRequestDto
import com.joysong.app.domain.model.User
import com.joysong.app.domain.repository.AuthRepository
import retrofit2.HttpException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : AuthRepository {

    /** 从 HTTP 异常中提取服务端返回的错误信息 */
    private fun extractErrorMessage(e: Exception): String {
        if (e is HttpException) {
            try {
                val errorBody = e.response()?.errorBody()?.string()
                if (!errorBody.isNullOrBlank()) {
                    val resp = Gson().fromJson(errorBody, ApiResponse::class.java)
                    if (resp?.message != null) return resp.message
                }
            } catch (_: Exception) { }
        }
        return e.message ?: "网络异常，请检查网络连接"
    }

    private suspend fun persistLogin(response: com.joysong.app.data.remote.dto.LoginResponseDto): User {
        val accessToken = response.accessToken.ifBlank { response.token }
        if (response.refreshToken.isNotBlank()) {
            tokenManager.saveTokens(accessToken, response.refreshToken)
        } else {
            tokenManager.saveToken(accessToken)
        }
        tokenManager.saveUserId(response.user.id)
        return response.user.toDomain(accessToken)
    }

    override suspend fun sendVerificationCode(phone: String): Result<Unit> {
        return try {
            val response = apiService.sendCode(SendCodeRequestDto(phone))
            if (response.code == 200) {
                Result.success(Unit)
            }
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun loginWithCode(phone: String, code: String): Result<User> {
        return try {
            val response = apiService.loginWithCode(LoginWithCodeRequestDto(phone, code))
            if (response.code == 200 && response.data != null) {
                Result.success(persistLogin(response.data))
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun login(phone: String, password: String): Result<User> {
        return try {
            val response = apiService.login(LoginRequestDto(phone, password))
            if (response.code == 200 && response.data != null) {
                Result.success(persistLogin(response.data))
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun loginWithGoogle(idToken: String): Result<User> {
        return try {
            val response = apiService.loginWithGoogle(GoogleLoginRequestDto(idToken))
            if (response.code == 200 && response.data != null) {
                Result.success(persistLogin(response.data))
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun register(phone: String, code: String, password: String): Result<User> {
        return try {
            val response = apiService.register(RegisterRequestDto(phone, code, password))
            if (response.code == 200 && response.data != null) {
                Result.success(persistLogin(response.data))
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun resetPassword(phone: String, code: String, newPassword: String): Result<Unit> {
        return try {
            val response = apiService.resetPassword(ResetPasswordRequestDto(phone, code, newPassword))
            if (response.code == 200) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun checkPhoneRegistered(phone: String): Result<Boolean> {
        return try {
            val response = apiService.checkPhoneRegistered(phone)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data["registered"] ?: false)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun isLoggedIn(): Boolean = tokenManager.isLoggedIn()

    override suspend fun getUserProfile(): Result<User> {
        return try {
            val response = apiService.getUserProfile()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }

    override suspend fun logout() {
        tokenManager.getRefreshToken()?.takeIf { it.isNotBlank() }?.let { refreshToken ->
            runCatching { apiService.logout(LogoutRequestDto(refreshToken)) }
        }
        tokenManager.clear()
    }

    override suspend fun deleteAccount(): Result<Unit> {
        return try {
            val response = apiService.deleteAccount()
            if (response.code == 200) {
                tokenManager.clear()
                Result.success(Unit)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: Exception) {
            Result.failure(Exception(extractErrorMessage(e)))
        }
    }
}

