package com.joysong.server.identity.controller

import com.joysong.server.admin.service.AdminLoginAttemptService
import com.joysong.server.auth.dto.LoginRequest
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementContextView
import com.joysong.server.user.repository.UserRepository
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/management")
class ManagementAccessController(
    private val authenticationService: AuthenticationService,
    private val userRepository: UserRepository,
    private val managementAccessService: ManagementAccessService,
    private val adminLoginAttemptService: AdminLoginAttemptService
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody request: LoginRequest,
        servletRequest: HttpServletRequest
    ): ResponseEntity<BaseResponse<*>> {
        val phone = request.phone.trim()
        val account = userRepository.findByPhone(phone).orElse(null)
        val result = if (account?.role == "ADMIN") {
            val clientAddress = servletRequest.remoteAddr ?: "unknown"
            val retryAfterSeconds = adminLoginAttemptService.retryAfterSeconds(phone, clientAddress)
            if (retryAfterSeconds != null) {
                return ResponseEntity
                    .status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, retryAfterSeconds.toString())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(BaseResponse.error<Nothing>("登录尝试过于频繁，请稍后再试", code = 429))
            }
            try {
                authenticationService.loginAdmin(phone, request.password).also {
                    adminLoginAttemptService.recordSuccess(phone, clientAddress)
                }
            } catch (_: BadCredentialsException) {
                adminLoginAttemptService.recordFailure(phone, clientAddress)
                return ResponseEntity
                    .status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(BaseResponse.error<Nothing>("管理员账号或密码错误", code = 401))
            }
        } else {
            authenticationService.login(phone, request.password)
        }
        val context = managementAccessService.contextFor(result.user.id, result.user.role)
        return ResponseEntity
            .ok()
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(
                BaseResponse.success(
                    ManagementLoginView(
                        token = result.token,
                        accessToken = result.accessToken,
                        refreshToken = result.refreshToken,
                        expiresIn = result.expiresIn,
                        user = result.user,
                        context = context
                    )
                )
            )
    }

    @GetMapping("/context")
    fun context(authentication: Authentication): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        return BaseResponse.success(
            managementAccessService.contextFor(actor.userId, if (actor.isAdmin) "ADMIN" else "USER")
        )
    }
}

data class ManagementLoginView(
    val token: String,
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
    val user: UserDto,
    val context: ManagementContextView
)
