package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class IdentityAuthorizationService(
    private val jdbcTemplate: JdbcTemplate
) {
    /**
     * user_roles 只保存审核通过的职业身份，普通 USER 不会写入该表。
     */
    fun hasActiveProfessionalRole(userId: String): Boolean {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM user_roles
            WHERE user_id = ? AND status = 'ACTIVE'
            """.trimIndent(),
            Long::class.java,
            userId
        )
        return count > 0
    }

    fun hasActiveRole(userId: String, roleCode: String): Boolean {
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
        return count > 0
    }

    fun requireActiveRole(userId: String, roleCode: String) {
        require(hasActiveRole(userId, roleCode)) { "用户尚未通过${roleCode}身份审核" }
    }
}
