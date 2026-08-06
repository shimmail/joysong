package com.joysong.server.admin.controller

import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.admin.service.AdminLoginAttemptService
import com.joysong.server.common.BaseResponse
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.web.bind.annotation.*

data class AdminLoginRequest(
    @field:Pattern(regexp = "^1\\d{10}$", message = "请输入正确的手机号")
    val phone: String,
    @field:Size(min = 8, max = 128, message = "密码长度应为 8-128 位")
    val password: String
)

@RestController
@RequestMapping("/api/admin")
class AdminAuthController(
    private val authenticationService: AuthenticationService,
    private val loginAttemptService: AdminLoginAttemptService
) {

    @PostMapping("/login")
    fun adminLogin(
        @Valid @RequestBody request: AdminLoginRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<BaseResponse<*>> {
        val phone = request.phone.trim()
        val clientAddress = servletRequest.remoteAddr ?: "unknown"
        val retryAfterSeconds = loginAttemptService.retryAfterSeconds(phone, clientAddress)
        if (retryAfterSeconds != null) {
            return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BaseResponse.error<Nothing>("登录尝试过于频繁，请稍后再试", code = 429))
        }

        return try {
            val loginResponse = authenticationService.loginAdmin(phone, request.password)
            loginAttemptService.recordSuccess(phone, clientAddress)
            ResponseEntity
                .ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BaseResponse.success(loginResponse))
        } catch (_: BadCredentialsException) {
            loginAttemptService.recordFailure(phone, clientAddress)
            ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BaseResponse.error<Nothing>("管理员账号或密码错误", code = 401))
        }
    }
}
