package com.joysong.server.auth.dto

import java.time.LocalDate
import com.joysong.server.user.entity.AccountState
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class LoginRequest(
    @field:Pattern(regexp = "^\\+?[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:NotBlank(message = "密码不能为空") @field:Size(max = 128, message = "密码长度不能超过 128 位") val password: String
)
data class LoginWithCodeRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String
)
data class RegisterRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String,
    @field:Size(min = 8, max = 128, message = "密码长度应为 8-128 位") val password: String
)
data class SendCodeRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String
)
data class VerifyCodeRequest(
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String
)
data class ChangePhoneRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String
)
data class GoogleLoginRequest(
    @field:NotBlank(message = "Google ID Token 不能为空") @field:Size(max = 8192, message = "Google ID Token 过长") val idToken: String
)
data class ChangePasswordRequest(
    @field:NotBlank(message = "原密码不能为空") val oldPassword: String,
    @field:Size(min = 8, max = 128, message = "新密码长度应为 8-128 位") val newPassword: String
)
data class ResetPasswordRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String,
    @field:Size(min = 8, max = 128, message = "新密码长度应为 8-128 位") val newPassword: String
)
data class SetPasswordRequest(
    @field:Pattern(regexp = "^\\+[1-9]\\d{6,14}$", message = "手机号格式不正确") val phone: String,
    @field:Pattern(regexp = "^\\d{6}$", message = "请输入 6 位验证码") val code: String,
    @field:Size(min = 8, max = 128, message = "新密码长度应为 8-128 位") val newPassword: String
)
data class RefreshTokenRequest(
    @field:NotBlank(message = "刷新令牌不能为空") @field:Size(max = 512, message = "刷新令牌过长") val refreshToken: String
)
data class LogoutRequest(
    @field:NotBlank(message = "刷新令牌不能为空") @field:Size(max = 512, message = "刷新令牌过长") val refreshToken: String
)
data class LoginResponse(
    /** 兼容现有 Android 与管理端；新客户端优先读取 accessToken。 */
    val token: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto
)
data class UpdateProfileRequest(
    @field:Size(max = 100, message = "昵称不能超过 100 字") val nickname: String? = null,
    @field:Size(max = 500, message = "头像地址过长") val avatar: String? = null,
    @field:Pattern(regexp = "^(|MALE|FEMALE|OTHER)$", message = "性别取值不正确") val gender: String? = null,
    @field:Size(max = 100, message = "城市不能超过 100 字") val city: String? = null,
    @field:Size(max = 500, message = "个人简介不能超过 500 字") val bio: String? = null,
    val birthday: LocalDate? = null
)
data class UserDto(
    val id: String,
    val phone: String?,
    val email: String?,
    val nickname: String,
    val avatar: String,
    val gender: String,
    val city: String,
    val bio: String,
    val birthday: LocalDate?,
    val role: String = "USER",
    val accountState: AccountState = AccountState.ACTIVE,
    val hasPassword: Boolean = true
)
