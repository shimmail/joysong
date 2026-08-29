package com.joysong.server.user.deletion

import com.joysong.server.user.entity.UserEntity
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

sealed interface AccountDeletionCommerceEvaluation {
    data object Eligible : AccountDeletionCommerceEvaluation
    data object Unavailable : AccountDeletionCommerceEvaluation
    data class Blocked(val blockers: List<AccountDeletionBlocker>) : AccountDeletionCommerceEvaluation
}

fun interface AccountDeletionBlockerPort {
    fun evaluate(userId: String): AccountDeletionCommerceEvaluation
}

@Configuration
class AccountDeletionBlockerConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountDeletionBlockerPort::class)
    fun unavailableAccountDeletionBlockerPort(): AccountDeletionBlockerPort =
        AccountDeletionBlockerPort { AccountDeletionCommerceEvaluation.Unavailable }
}

@Service
class LocalAccountDeletionBlockerService(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun evaluate(user: UserEntity): List<AccountDeletionBlocker> = buildList {
        if (user.role == "ADMIN") add(blocker("ADMIN_ACCOUNT", 1, "CONTACT_SUPPORT"))
        addCount(
            "IDENTITY_APPLICATION",
            count(
                "SELECT COUNT(*) FROM identity_applications WHERE user_id = ? AND status = 'PENDING'",
                user.id,
            ),
            "VIEW_IDENTITY_APPLICATION",
        )
        addCount(
            "INSTITUTION_MEMBERSHIP",
            count(
                "SELECT COUNT(*) FROM institution_memberships WHERE user_id = ? AND status IN ('PENDING', 'APPROVED')",
                user.id,
            ),
            "MANAGE_INSTITUTION_RELATIONSHIP",
        )
        addCount(
            "PROFESSIONAL_ROLE",
            count("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND status = 'ACTIVE'", user.id),
            "CONTACT_SUPPORT",
        )
        addCount(
            "PROFESSIONAL_PROFILE",
            count("SELECT COUNT(*) FROM doctors WHERE id = ? AND is_verified = 1", user.id),
            "CONTACT_SUPPORT",
        )
        addCount(
            "PLATFORM_COOPERATION_AGREEMENT",
            count(
                "SELECT COUNT(*) FROM platform_cooperation_agreements WHERE user_id = ? AND status <> 'TERMINATED'",
                user.id,
            ),
            "CONTACT_SUPPORT",
        )
        addCount(
            "DOCTOR_INSTITUTION_RELATIONSHIP",
            count(
                "SELECT COUNT(*) FROM doctor_institutions WHERE doctor_id = ? AND status = 'APPROVED'",
                user.id,
            ),
            "MANAGE_INSTITUTION_RELATIONSHIP",
        )
        addCount(
            "PENDING_RELATIONSHIP_CHANGE",
            count(
                "SELECT COUNT(*) FROM consultant_institution_change_requests WHERE consultant_id = ? AND status = 'PENDING'",
                user.id,
            ) + count(
                "SELECT COUNT(*) FROM doctor_institution_change_requests WHERE doctor_id = ? AND status = 'PENDING'",
                user.id,
            ),
            "RESOLVE_PENDING_RELATIONSHIP",
        )
    }

    private fun MutableList<AccountDeletionBlocker>.addCount(type: String, count: Long, action: String) {
        if (count > 0) add(blocker(type, count, action))
    }

    private fun blocker(type: String, count: Long, action: String) =
        AccountDeletionBlocker(type = type, count = count, action = action)

    private fun count(sql: String, userId: String): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, userId)
}
