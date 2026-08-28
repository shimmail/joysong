package com.joysong.server.user.deletion

import com.joysong.server.auth.controller.AuthController
import com.joysong.server.auth.controller.UserController
import com.joysong.server.auth.service.AliyunSmsService
import com.joysong.server.auth.service.AuthenticationService
import com.joysong.server.auth.service.VerificationCodeService
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.JwtAuthenticationFilter
import com.joysong.server.user.repository.UserRepository
import com.joysong.server.user.service.UserProfileService
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDateTime

@WebMvcTest(controllers = [AccountDeletionController::class, AuthController::class, UserController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class AccountDeletionHttpTest @Autowired constructor(
    private val mockMvc: MockMvc,
) {
    @MockBean lateinit var stepUpService: AccountDeletionStepUpService
    @MockBean lateinit var coordinator: AccountDeletionCoordinator
    @MockBean lateinit var authenticationService: AuthenticationService
    @MockBean lateinit var userProfileService: UserProfileService
    @MockBean lateinit var verificationCodeService: VerificationCodeService
    @MockBean lateinit var aliyunSmsService: AliyunSmsService
    @MockBean lateinit var userRepository: UserRepository
    @MockBean lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @Test
    fun `preflight business blockers use 200 with eligible false`() {
        given(stepUpService.preflight("user-1")).willReturn(
            AccountDeletionPreflightResponse(
                requestId = "request-1",
                eligible = false,
                stepUpMethod = AccountDeletionStepUpMethod.SMS,
                maskedCredential = "+86****00",
                policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
                blockers = listOf(AccountDeletionBlocker("IDENTITY_APPLICATION", 1, "VIEW_IDENTITY_APPLICATION")),
            ),
        )

        mockMvc.perform(
            post("/api/user/account-deletion/preflight")
                .principal(UsernamePasswordAuthenticationToken("user-1", null, emptyList())),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.eligible").value(false))
            .andExpect(jsonPath("$.data.blockers[0].type").value("IDENTITY_APPLICATION"))
            .andExpect(jsonPath("$.data.blockers[0].id").doesNotExist())
    }

    @Test
    fun `expired authorization uses 410 and stable wire error code`() {
        given(
            stepUpService.stepUp(
                "user-1",
                AccountDeletionStepUpRequest(requestId = "request-1", googleIdToken = "token"),
            ),
        ).willThrow(AccountDeletionException(AccountDeletionErrorCode.AUTHORIZATION_EXPIRED))

        mockMvc.perform(
            post("/api/user/account-deletion/step-up")
                .principal(UsernamePasswordAuthenticationToken("user-1", null, emptyList()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"requestId":"request-1","googleIdToken":"token"}"""),
        )
            .andExpect(status().isGone)
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_AUTHORIZATION_EXPIRED"))
            .andExpect(jsonPath("$.message").value("注销授权已过期或已使用"))
    }

    @Test
    fun `final blockers use 409 and return only safe blocker metadata`() {
        val response = AccountDeletionConfirmResponse(
            requestId = "request-1",
            outcome = AccountDeletionTerminalOutcome.BLOCKED,
            completedAt = LocalDateTime.parse("2026-08-28T00:00:00"),
            blockers = listOf(AccountDeletionBlocker("PROFESSIONAL_ROLE", 1, "CONTACT_SUPPORT")),
        )
        given(
            coordinator.confirm(
                "user-1",
                "idempotency-key-0001",
                "a".repeat(64),
                ConfirmAccountDeletionRequest(
                    requestId = "request-1",
                    policyVersion = ACCOUNT_DELETION_POLICY_VERSION,
                    confirmation = "DELETE",
                ),
            ),
        ).willReturn(response)

        mockMvc.perform(
            post("/api/user/account-deletion/confirm")
                .principal(UsernamePasswordAuthenticationToken("user-1", null, emptyList()))
                .header("Idempotency-Key", "idempotency-key-0001")
                .header("X-Account-Deletion-Authorization", "a".repeat(64))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"requestId":"request-1","policyVersion":"$ACCOUNT_DELETION_POLICY_VERSION","confirmation":"DELETE"}""",
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_BLOCKED"))
            .andExpect(jsonPath("$.data.blockers[0].type").value("PROFESSIONAL_ROLE"))
            .andExpect(jsonPath("$.data.blockers[0].id").doesNotExist())
    }

    @Test
    fun `both legacy deletion endpoints always return exact 410 retirement code`() {
        listOf("/api/auth/account", "/api/user/account").forEach { endpoint ->
            mockMvc.perform(delete(endpoint))
                .andExpect(status().isGone)
                .andExpect(jsonPath("$.errorCode").value("ACCOUNT_DELETION_LEGACY_ENDPOINT_RETIRED"))
        }
    }
}
