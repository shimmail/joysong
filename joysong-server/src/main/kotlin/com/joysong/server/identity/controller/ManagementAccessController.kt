package com.joysong.server.identity.controller

import com.joysong.server.auth.dto.LoginRequest
import com.joysong.server.auth.dto.UserDto
import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementContextView
import com.joysong.server.user.repository.UserRepository
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import jakarta.validation.Valid

@RestController
@RequestMapping("/api/management")
class ManagementAccessController(
    private val authenticationService: AuthenticationService,
    private val userRepository: UserRepository,
    private val managementAccessService: ManagementAccessService
) {
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): BaseResponse<*> {
        val phone = request.phone.trim()
        val account = userRepository.findByPhone(phone).orElse(null)
        val result = if (account?.role == "ADMIN") {
            authenticationService.loginAdmin(phone, request.password)
        } else {
            authenticationService.login(phone, request.password)
        }
        val context = managementAccessService.contextFor(result.user.id, result.user.role)
        return BaseResponse.success(
            ManagementLoginView(
                token = result.token,
                accessToken = result.accessToken,
                refreshToken = result.refreshToken,
                expiresIn = result.expiresIn,
                user = result.user,
                context = context
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
