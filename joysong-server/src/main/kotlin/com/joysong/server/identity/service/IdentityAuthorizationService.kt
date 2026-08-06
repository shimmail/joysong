package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class IdentityAuthorizationService(
    private val jdbcTemplate: JdbcTemplate
) {
    fun requireActiveRole(userId: String, roleCode: String) {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM user_roles
            WHERE user_id = ? AND role_code = ? AND status = 'ACTIVE'
            """.trimIndent(),
            Long::class.java,
            userId,
            roleCode
        )
        require(count > 0) { "用户尚未通过${roleCode}身份审核" }
    }
}
