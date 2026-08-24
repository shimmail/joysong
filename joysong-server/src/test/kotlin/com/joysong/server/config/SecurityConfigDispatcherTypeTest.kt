package com.joysong.server.config

import com.joysong.server.user.repository.UserRepository
import io.mockk.mockk
import jakarta.servlet.DispatcherType
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@WebMvcTest
@ContextConfiguration(classes = [SecurityConfigDispatcherTypeTestConfig::class, SecurityConfig::class])
class SecurityConfigDispatcherTypeTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @Test
    fun `initial request to protected endpoint still requires authentication`() {
        mvc.perform(get("/test/protected"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `async and error internal dispatches are permitted`() {
        listOf(DispatcherType.ASYNC, DispatcherType.ERROR).forEach { dispatcherType ->
            mvc.perform(
                get("/test/protected").with { request ->
                    request.dispatcherType = dispatcherType
                    request
                }
            )
                .andExpect(status().isOk)
        }
    }
}

@TestConfiguration
@Import(SecurityConfigDispatcherTypeTestController::class, JwtAuthenticationFilter::class)
class SecurityConfigDispatcherTypeTestConfig {
    @Bean
    fun tokenProvider(): JwtTokenProvider = mockk(relaxed = true)

    @Bean
    fun userRepository(): UserRepository = mockk(relaxed = true)
}

@RestController
class SecurityConfigDispatcherTypeTestController {
    @GetMapping("/test/protected")
    fun protectedEndpoint(): String = "ok"
}
