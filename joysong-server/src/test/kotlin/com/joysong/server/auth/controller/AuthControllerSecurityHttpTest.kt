package com.joysong.server.auth.controller

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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(controllers = [AuthController::class])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class AuthControllerSecurityHttpTest @Autowired constructor(
    private val mockMvc: MockMvc
) {

    @MockBean lateinit var authenticationService: AuthenticationService
    @MockBean lateinit var userProfileService: UserProfileService
    @MockBean lateinit var verificationCodeService: VerificationCodeService
    @MockBean lateinit var aliyunSmsService: AliyunSmsService
    @MockBean lateinit var userRepository: UserRepository
    @MockBean lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @Test
    fun `login with code maps the rejected administrator to the public generic envelope`() {
        given(authenticationService.loginWithCode("+8613800000000", "123456"))
            .willThrow(IllegalArgumentException("验证码无效或已过期"))

        mockMvc.perform(
            post("/api/auth/login-with-code")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"phone":"+8613800000000","code":"123456"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.message").value("验证码无效或已过期"))
            .andExpect(jsonPath("$.data").doesNotExist())
    }
}
