package com.joysong.app.domain.repository

import com.joysong.app.domain.model.User

interface UserRepository {
    suspend fun getUserProfile(): Result<User>
    suspend fun updateProfile(nickname: String, avatar: String, city: String, bio: String, gender: String, birthday: String): Result<User>
    suspend fun bindPhone(phone: String, code: String): Result<Unit>
    suspend fun bindEmail(email: String, code: String): Result<Unit>
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit>
    suspend fun setPassword(phone: String, code: String, newPassword: String): Result<Unit>
}
