package com.joysong.server.order.consultant

import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.BDDMockito.given
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.time.LocalDateTime
import java.util.Date
import java.util.Optional

@WebMvcTest(controllers = [ConsultantOrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
class ConsultantOrderHttpSecurityTest @Autowired constructor(
    private val mvc: MockMvc,
) {
    @MockBean lateinit var service: ConsultantOrderQueryService
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository

    @Test
    fun `missing bearer token returns HTTP 401 before controller`() {
        mvc.perform(get("/api/consultant/orders"))
            .andExpect(status().isUnauthorized)

        verifyNoInteractions(service)
    }

    @ParameterizedTest
    @EnumSource(
        value = AccountState::class,
        names = ["ADMIN_SUSPENDED", "ERASED"],
    )
    fun `inactive account token returns HTTP 401 before controller`(accountState: AccountState) {
        val token = "token-${accountState.name.lowercase()}"
        val user = UserEntity(
            id = "consultant-1",
            passwordHash = "test-only",
            role = "USER",
            credentialsUpdatedAt = LocalDateTime.of(2026, 8, 28, 0, 0),
            accountState = accountState,
        )
        given(tokenProvider.validateToken(token)).willReturn(true)
        given(tokenProvider.getUserIdFromToken(token)).willReturn(user.id)
        given(tokenProvider.getRoleFromToken(token)).willReturn(user.role)
        given(tokenProvider.getIssuedAtFromToken(token))
            .willReturn(Date.from(Instant.parse("2026-08-29T00:00:00Z")))
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))

        mvc.perform(
            get("/api/consultant/orders")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isUnauthorized)

        verifyNoInteractions(service)
    }
}
