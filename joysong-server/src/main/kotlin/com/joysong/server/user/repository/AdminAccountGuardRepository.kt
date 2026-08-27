package com.joysong.server.user.repository

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class AdminAccountGuardRepository(private val jdbcTemplate: JdbcTemplate) {

    fun lock() {
        val lockedKey = jdbcTemplate.queryForObject(
            "SELECT guard_key FROM admin_account_guard WHERE guard_key = ? FOR UPDATE",
            String::class.java,
            GUARD_KEY,
        )
        check(lockedKey == GUARD_KEY) { "管理员账号生命周期锁未初始化" }
    }

    private companion object {
        const val GUARD_KEY = "ACTIVE_ADMIN"
    }
}
