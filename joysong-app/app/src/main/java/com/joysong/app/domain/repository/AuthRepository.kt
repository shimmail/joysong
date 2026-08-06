package com.joysong.app.domain.repository

import com.joysong.app.domain.model.User

interface AuthRepository {
    suspend fun sendVerificationCode(phone: String): Result<Unit>
    suspend fun login(phone: String, password: String): Result<User>
    suspend fun loginWithCode(phone: String, code: String): Result<User>
    suspend fun loginWithGoogle(idToken: String): Result<User>
    suspend fun register(phone: String, code: String, password: String): Result<User>
    suspend fun resetPassword(phone: String, code: String, newPassword: String): Result<Unit>
    suspend fun checkPhoneRegistered(phone: String): Result<Boolean>
    suspend fun getUserProfile(): Result<User>
    suspend fun isLoggedIn(): Boolean
    suspend fun logout()
    suspend fun deleteAccount(): Result<Unit>
}
