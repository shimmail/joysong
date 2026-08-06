package com.joysong.server.config

import com.joysong.server.user.repository.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userRepository: UserRepository
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
            // 所有令牌都与账号状态及凭据版本实时核对；改密、换绑、降权或注销后旧令牌立即失效。
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
            if (!activeUser) {
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
}
