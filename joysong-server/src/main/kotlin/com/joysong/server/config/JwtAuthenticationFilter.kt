package com.joysong.server.config

import com.joysong.server.auth.service.RefreshTokenService
import com.joysong.server.user.repository.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository,
    private val refreshTokenServiceProvider: ObjectProvider<RefreshTokenService>
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val token = resolveToken(request)
        if (token != null && jwtTokenProvider.validateToken(token)) {
            val userId = jwtTokenProvider.getUserIdFromToken(token)
            val role = jwtTokenProvider.getRoleFromToken(token)
            // 所有令牌实时核对账号与凭据版本；管理员令牌还必须绑定当前有效的 refresh 会话。
            val issuedAt = LocalDateTime.ofInstant(
                jwtTokenProvider.getIssuedAtFromToken(token).toInstant(),
                ZoneId.systemDefault()
            )
            val activeUser = userRepository.findById(userId)
                .filter { user ->
                    val roleStillValid = role != "ADMIN" || user.role == "ADMIN"
                    roleStillValid && !issuedAt.isBefore(user.credentialsUpdatedAt)
                }
                .isPresent
            val activeAdminSession = role != "ADMIN" || activeUser && jwtTokenProvider
                .getSessionIdFromToken(token)
                ?.takeIf { sessionId -> CURRENT_ADMIN_SESSION_ID.matches(sessionId) }
                ?.let { sessionId ->
                    refreshTokenServiceProvider.getIfAvailable()
                        ?.isActiveSession(sessionId, userId)
                } == true
            if (!activeUser || !activeAdminSession) {
                filterChain.doFilter(request, response)
                return
            }
            val authorities = listOf(SimpleGrantedAuthority("ROLE_$role"))
            val authentication = UsernamePasswordAuthenticationToken(userId, null, authorities)
            SecurityContextHolder.getContext().authentication = authentication
        }
        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }

    private companion object {
        val CURRENT_ADMIN_SESSION_ID = Regex("a1-[0-9a-f]{32}")
    }
}
