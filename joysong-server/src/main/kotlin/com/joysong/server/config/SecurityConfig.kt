package com.joysong.server.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.common.BaseResponse
import jakarta.servlet.DispatcherType
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import com.joysong.server.user.deletion.AccountDeletionErrorCode

@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val objectMapper: ObjectMapper
) {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // API 仅接受 Authorization Bearer，不依赖浏览器 Cookie，因此关闭 CSRF。
            .csrf { it.disable() }
            .cors { }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    .requestMatchers("/api/auth/**").permitAll()
                    .requestMatchers(HttpMethod.DELETE, "/api/user/account").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/user/account-deletion/confirm").permitAll()
                    .requestMatchers("/api/home/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/payment-webhooks/**").permitAll()
                    .requestMatchers("/api/discover/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/public/diary-shares/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/s/diary/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/public/legal-documents/**", "/legal/**").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/comments", "/api/comments/replies").permitAll()
                    .requestMatchers("/api/users/*/profile").permitAll()
                    .requestMatchers("/api/users/*/diaries").permitAll()
                    .requestMatchers("/h2-console/**").permitAll()
                    .requestMatchers("/actuator/health").permitAll()
                    .requestMatchers("/actuator/**").hasRole("ADMIN")
                    .requestMatchers("/images/**").permitAll()
                    .requestMatchers("/api/admin/login").permitAll()
                    .requestMatchers("/api/management/login").permitAll()
                    .requestMatchers(
                        "/api/management/institutions",
                        "/api/management/institutions/**",
                        "/api/management/consultant-memberships",
                        "/api/management/projects"
                    ).authenticated()
                    .requestMatchers(HttpMethod.GET,
                        "/api/admin/doctors",
                        "/api/admin/institutions",
                        "/api/admin/institutions/**",
                        "/api/admin/articles",
                        "/api/admin/projects",
                        "/api/admin/institution-projects",
                        "/api/admin/institution-project-requests",
                        "/api/admin/institution-project-requests/profile-update-targets"
                    ).authenticated()
                    .requestMatchers(HttpMethod.PUT,
                        "/api/admin/doctors/*",
                        "/api/admin/articles/*"
                    ).authenticated()
                    .requestMatchers(HttpMethod.PUT, "/api/admin/institutions/*").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST,
                        "/api/admin/articles",
                        "/api/admin/institution-project-requests",
                        "/api/admin/institution-project-requests/*/review",
                        "/api/admin/institution-project-requests/*/withdraw"
                    ).authenticated()
                    .requestMatchers(
                        "/api/v2/admin/institution-project-requests",
                        "/api/v2/admin/institution-project-requests/**"
                    ).authenticated()
                    .requestMatchers(HttpMethod.DELETE,
                        "/api/admin/articles/*"
                    ).authenticated()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers("/api/cs/**").authenticated()
                    .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { exceptions ->
                exceptions.authenticationEntryPoint { request, response, _ ->
                    val errorCode = if (request.requestURI.startsWith("/api/user/account-deletion")) {
                        AccountDeletionErrorCode.UNAUTHENTICATED.wireCode
                    } else null
                    writeSecurityError(response, 401, "登录状态已失效，请重新登录", errorCode)
                }
                exceptions.accessDeniedHandler { _, response, _ ->
                    writeSecurityError(response, 403, "无权执行此操作")
                }
            }
            .headers { headers ->
                headers.frameOptions { it.sameOrigin() }
                headers.referrerPolicy { it.policy(ReferrerPolicy.NO_REFERRER) }
                headers.permissionsPolicy { it.policy("camera=(), microphone=(), geolocation=()") }
            }
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
        return http.build()
    }

    private fun writeSecurityError(
        response: jakarta.servlet.http.HttpServletResponse,
        status: Int,
        message: String,
        errorCode: String? = null,
    ) {
        response.status = status
        response.characterEncoding = Charsets.UTF_8.name()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.setHeader("Cache-Control", "no-store")
        objectMapper.writeValue(response.writer, BaseResponse.error<Nothing>(message, status, errorCode))
    }

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()
}
