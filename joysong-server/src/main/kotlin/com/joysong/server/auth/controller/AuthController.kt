package com.joysong.server.auth.controller

import com.joysong.server.auth.dto.ChangePasswordRequest
import com.joysong.server.auth.dto.GoogleLoginRequest
import com.joysong.server.auth.dto.LoginRequest
import com.joysong.server.auth.dto.LoginWithCodeRequest
import com.joysong.server.auth.dto.LogoutRequest
import com.joysong.server.auth.dto.RefreshTokenRequest
import com.joysong.server.auth.dto.RegisterRequest
import com.joysong.server.auth.dto.ResetPasswordRequest
import com.joysong.server.auth.dto.SendCodeRequest
import com.joysong.server.auth.dto.SetPasswordRequest
import com.joysong.server.auth.dto.UpdateProfileRequest
import com.joysong.server.auth.dto.VerifyCodeRequest
import com.joysong.server.auth.dto.ChangePhoneRequest
import com.joysong.server.auth.service.AliyunSmsService
import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.common.BaseResponse
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.user.service.UserProfileService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import jakarta.validation.Valid

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val authenticationService: AuthenticationService,
    private val userProfileService: UserProfileService,
    private val verificationCodeService: VerificationCodeService,
    private val aliyunSmsService: AliyunSmsService,
    private val userRepository: UserRepository
) {

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): BaseResponse<*> {
        return BaseResponse.success(authenticationService.login(request.phone, request.password))
    }

    @PostMapping("/register")
    fun register(@Valid @RequestBody request: RegisterRequest): BaseResponse<*> {
        return BaseResponse.success(authenticationService.register(request.phone, request.code, request.password))
    }

    @PostMapping("/send-code")
    fun sendCode(@Valid @RequestBody request: SendCodeRequest): BaseResponse<*> {
        verificationCodeService.generate(request.phone)
        return BaseResponse.success(mapOf("message" to "验证码已发送"))
    }

    @PostMapping("/login-with-code")
    fun loginWithCode(@Valid @RequestBody request: LoginWithCodeRequest): BaseResponse<*> {
        return BaseResponse.success(authenticationService.loginWithCode(request.phone, request.code))
    }

    @PostMapping("/login-with-google")
    fun loginWithGoogle(@Valid @RequestBody request: GoogleLoginRequest): BaseResponse<*> {
        return BaseResponse.success(authenticationService.loginWithGoogle(request.idToken))
    }

    @PostMapping("/reset-password")
    fun resetPassword(@Valid @RequestBody request: ResetPasswordRequest): BaseResponse<*> {
        userProfileService.resetPassword(request.phone, request.code, request.newPassword)
        return BaseResponse.success(mapOf("message" to "密码重置成功"))
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshTokenRequest): BaseResponse<*> =
        BaseResponse.success(authenticationService.refresh(request.refreshToken))

    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: LogoutRequest): BaseResponse<*> {
        authenticationService.logout(request.refreshToken)
        return BaseResponse.success(mapOf("message" to "已退出登录"))
    }

    @GetMapping("/check-phone-registered")
    fun checkPhoneRegistered(@RequestParam phone: String): BaseResponse<*> {
        val registered = userRepository.existsByPhone(phone)
        return BaseResponse.success(mapOf("registered" to registered))
    }

}

@RestController
@RequestMapping("/api/user")
class UserController(private val userProfileService: UserProfileService) {

    @GetMapping("/profile")
    fun getProfile(authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(userProfileService.getUserProfile(userId))
    }

    @PutMapping("/profile")
    fun updateProfile(
        authentication: Authentication,
        @Valid @RequestBody request: UpdateProfileRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(userProfileService.updateProfile(userId, request))
    }

    /**
     * 单独更新头像（方便测试）
     */
    @PutMapping("/avatar")
    fun updateAvatar(
        authentication: Authentication,
        @RequestBody request: Map<String, String>
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val avatarUrl = request["avatar"] ?: throw IllegalArgumentException("avatar URL is required")
        return BaseResponse.success(userProfileService.updateProfile(userId, UpdateProfileRequest(avatar = avatarUrl)))
    }

    /** 仅用于尚未绑定手机号的第三方登录账号；已绑定账号必须走 phone-change 流程。 */
    @PostMapping("/bind-phone")
    fun bindPhone(
        authentication: Authentication,
        @Valid @RequestBody request: ChangePhoneRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        userProfileService.bindPhone(userId, request.phone, request.code)
        return BaseResponse.success(mapOf("message" to "手机号绑定成功"))
    }

    /** 旧端点已停用，等待分阶段注销流程接管。 */
    @DeleteMapping("/account")
    fun deleteAccount(authentication: Authentication): BaseResponse<*> {
        throw ResponseStatusException(HttpStatus.GONE, "账号注销流程正在升级")
    }

    /** 修改密码 */
    @PutMapping("/password")
    fun changePassword(
        authentication: Authentication,
        @Valid @RequestBody request: ChangePasswordRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        userProfileService.changePassword(userId, request.oldPassword, request.newPassword)
        return BaseResponse.success(mapOf("message" to "密码修改成功"))
    }

    /** 未设置密码的用户通过手机号验证码设置密码 */
    @PutMapping("/password/set")
    fun setPassword(
        authentication: Authentication,
        @Valid @RequestBody request: SetPasswordRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        userProfileService.setPasswordForUser(userId, request.phone, request.code, request.newPassword)
        return BaseResponse.success(mapOf("message" to "密码设置成功"))
    }

    @PostMapping("/phone-change/send-current-code")
    fun sendCurrentPhoneChangeCode(authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(userProfileService.sendCurrentPhoneChangeCode(userId))
    }

    @PostMapping("/phone-change/verify-current-code")
    fun verifyCurrentPhoneChangeCode(
        authentication: Authentication,
        @Valid @RequestBody request: VerifyCodeRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        userProfileService.verifyCurrentPhoneChangeCode(userId, request.code)
        return BaseResponse.success(mapOf("message" to "身份验证成功"))
    }

    @PostMapping("/phone-change/send-new-code")
    fun sendNewPhoneChangeCode(
        authentication: Authentication,
        @Valid @RequestBody request: SendCodeRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(userProfileService.sendNewPhoneChangeCode(userId, request.phone))
    }

    @PutMapping("/phone-change")
    fun changePhone(
        authentication: Authentication,
        @Valid @RequestBody request: ChangePhoneRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        userProfileService.changePhone(userId, request.phone, request.code)
        return BaseResponse.success(mapOf("message" to "手机号修改成功"))
    }
}
