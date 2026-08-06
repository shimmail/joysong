package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.BindPhoneRequestDto
import com.joysong.app.data.remote.dto.ChangePasswordRequestDto
import com.joysong.app.data.remote.dto.SetPasswordRequestDto
import com.joysong.app.data.remote.dto.UpdateProfileRequestDto
import com.joysong.app.domain.model.User
import com.joysong.app.domain.repository.UserRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : UserRepository {

    override suspend fun getUserProfile(): Result<User> {
        return try {
            val response = apiService.getUserProfile()
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateProfile(
        nickname: String,
        avatar: String,
        city: String,
        bio: String,
        gender: String,
        birthday: String
    ): Result<User> {
        return try {
            val response = apiService.updateProfile(
                UpdateProfileRequestDto(nickname, avatar, city, bio, gender, birthday)
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun bindPhone(phone: String, code: String): Result<Unit> {
        return try {
            val response = apiService.bindPhone(BindPhoneRequestDto(phone, code))
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun bindEmail(email: String, code: String): Result<Unit> {
        return Result.failure(IllegalStateException("邮箱验证码服务尚未开放"))
    }

    override suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> {
        return try {
            val response = apiService.changePassword(
                ChangePasswordRequestDto(oldPassword, newPassword)
            )
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun setPassword(phone: String, code: String, newPassword: String): Result<Unit> {
        return try {
            val response = apiService.setPassword(
                SetPasswordRequestDto(phone, code, newPassword)
            )
            if (response.code == 200) Result.success(Unit)
            else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
