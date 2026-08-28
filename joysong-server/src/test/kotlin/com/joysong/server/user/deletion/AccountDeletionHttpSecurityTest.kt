package com.joysong.server.user.deletion

import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.config.JwtTokenProvider
import com.joysong.server.config.SecurityConfig
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.Date
import java.util.Optional

@WebMvcTest(controllers = [AccountDeletionController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class, GlobalExceptionHandler::class)
class AccountDeletionHttpSecurityTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockBean lateinit var stepUpService: AccountDeletionStepUpService
    @MockBean lateinit var coordinator: AccountDeletionCoordinator
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository

    @Test
    fun `preflight and step up require authentication with real security filters`() {
        val requests = listOf(
            post("/api/user/account-deletion/preflight"),
            post("/api/user/account-deletion/step-up")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"requestId":"request-1","googleIdToken":"token"}"""),
        )

        requests.forEach { request ->
            mockMvc.perform(request)
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_REQUEST_FAILED"))
        }
    }

    @Test
    fun `anonymous confirm reaches only the read-only terminal replay boundary`() {
        val command = ConfirmAccountDeletionRequest(
            requestId = "request-1",
            policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
            confirmation = "DELETE",
        )
        given(
            coordinator.confirm(
                null,
                "idempotency-key-0001",
                "a".repeat(64),
                command,
            ),
        ).willThrow(AccountDeletionException(AccountDeletionErrorCode.UNAUTHENTICATED))

        mockMvc.perform(
            post("/api/user/account-deletion/confirm")
                .header("Idempotency-Key", "idempotency-key-0001")
                .header("X-Account-Deletion-Authorization", "a".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"requestId":"request-1","policyVersion":"$ACCOUNT_DELETION_POLICY_VERSION","confirmation":"DELETE"}""",
                ),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_REQUEST_FAILED"))
    }

    @Test
    fun `a bearer token belonging to an erased account is rejected before preflight`() {
        val token = "stale-token"
        val user = UserEntity(
            id = "erased-user",
            passwordHash = "",
            accountState = AccountState.ERASED,
        )
        given(tokenProvider.validateToken(token)).willReturn(true)
        given(tokenProvider.getUserIdFromToken(token)).willReturn(user.id)
        given(tokenProvider.getRoleFromToken(token)).willReturn("USER")
        given(tokenProvider.getIssuedAtFromToken(token)).willReturn(Date.from(Instant.parse("2026-08-28T00:00:00Z")))
        given(userRepository.findById(user.id)).willReturn(Optional.of(user))

        mockMvc.perform(
            post("/api/user/account-deletion/preflight")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_REQUEST_FAILED"))
    }
}
