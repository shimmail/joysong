package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.util.UUID

interface ConsultantInstitutionRelationshipOperations {
    fun lockUser(consultantId: String)
    fun lockPair(consultantId: String, institutionId: String)
    fun requireActiveConsultant(consultantId: String)
    fun requireActiveInstitution(institutionId: String)

    fun validateForReview(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction
    )

    fun applyApproved(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction,
        reviewerId: String
    )

    fun forceRevoke(consultantId: String, institutionId: String, reviewerId: String)
}

@Service
class ConsultantInstitutionRelationshipService(
    private val jdbcTemplate: JdbcTemplate
) : ConsultantInstitutionRelationshipOperations {
    override fun lockUser(consultantId: String) {
        val userId = jdbcTemplate.queryForList(
            "SELECT id FROM users WHERE id = ? FOR UPDATE",
            String::class.java,
            consultantId
        ).firstOrNull()
        if (userId == null) {
            throw ConsultantInstitutionRequestNotFoundException("顾问不存在")
        }
    }

    override fun lockPair(consultantId: String, institutionId: String) {
        lockUser(consultantId)
        val lockedInstitutionId = jdbcTemplate.queryForList(
            "SELECT id FROM institutions WHERE id = ? FOR UPDATE",
            String::class.java,
            institutionId
        ).firstOrNull()
        if (lockedInstitutionId == null) {
            throw ConsultantInstitutionRequestNotFoundException("机构不存在")
        }
    }

    override fun requireActiveConsultant(consultantId: String) {
        val activeRole = jdbcTemplate.queryForList(
            """
            SELECT role_code FROM user_roles
            WHERE user_id = ? AND role_code = 'CONSULTANT' AND status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
            String::class.java,
            consultantId
        ).firstOrNull()
        if (activeRole == null) {
            conflict("顾问身份已失效")
        }
    }

    override fun requireActiveInstitution(institutionId: String) {
        if (activeInstitution(institutionId, lock = true) == null) {
            conflict("机构不存在、未认证或已删除")
        }
    }

    override fun validateForReview(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction
    ) {
        requireLockedInstitution(institutionId)
        val activeMembershipId = lockActiveMembership(consultantId, institutionId)
        when (action) {
            ConsultantInstitutionAction.JOIN -> if (activeMembershipId != null) {
                conflict("顾问已加入该机构")
            }

            ConsultantInstitutionAction.LEAVE -> if (activeMembershipId == null) {
                conflict("顾问已不具备该机构的有效关系")
            }
        }
    }

    override fun applyApproved(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction,
        reviewerId: String
    ) {
        requireLockedInstitution(institutionId)
        val activeMembershipId = lockActiveMembership(consultantId, institutionId)
        when (action) {
            ConsultantInstitutionAction.JOIN -> approveJoin(
                consultantId,
                institutionId,
                reviewerId,
                activeMembershipId
            )

            ConsultantInstitutionAction.LEAVE -> approveLeave(
                consultantId,
                institutionId,
                activeMembershipId
            )
        }
    }

    override fun forceRevoke(consultantId: String, institutionId: String, reviewerId: String) {
        lockPair(consultantId, institutionId)
        approveLeave(consultantId, institutionId, lockActiveMembership(consultantId, institutionId))
    }

    private fun approveJoin(
        consultantId: String,
        institutionId: String,
        reviewerId: String,
        activeMembershipId: String?
    ) {
        if (activeMembershipId != null) conflict("顾问已加入该机构")
        val existingId = lockMembership(consultantId, institutionId)
        if (existingId == null) {
            jdbcTemplate.update(
                """
                INSERT INTO institution_memberships
                    (id, user_id, institution_id, member_role, status, request_note, review_note,
                     confirmed_by, confirmed_at, revoked_at)
                VALUES (?, ?, ?, 'CONSULTANT', 'APPROVED', '', '', ?, NOW(), NULL)
                """.trimIndent(),
                UUID.randomUUID().toString(),
                consultantId,
                institutionId,
                reviewerId
            )
            return
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE institution_memberships
            SET status = 'APPROVED', confirmed_by = ?, confirmed_at = NOW(),
                revoked_at = NULL, updated_at = NOW()
            WHERE id = ? AND member_role = 'CONSULTANT'
              AND NOT (status = 'APPROVED' AND revoked_at IS NULL)
            """.trimIndent(),
            reviewerId,
            existingId
        )
        if (updated != 1) conflict("顾问机构关系已被其他操作处理")
    }

    private fun approveLeave(
        consultantId: String,
        institutionId: String,
        activeMembershipId: String?
    ) {
        val membershipId = activeMembershipId ?: conflict("顾问已不具备该机构的有效关系")
        val updated = jdbcTemplate.update(
            """
            UPDATE institution_memberships
            SET status = 'REVOKED', revoked_at = NOW(), updated_at = NOW()
            WHERE id = ? AND user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
              AND status = 'APPROVED' AND revoked_at IS NULL
            """.trimIndent(),
            membershipId,
            consultantId,
            institutionId
        )
        if (updated != 1) conflict("顾问机构关系已被其他操作处理")
        jdbcTemplate.update(
            """
            UPDATE auth_sessions
            SET active_role = 'USER', active_institution_id = NULL, updated_at = NOW()
            WHERE user_id = ? AND active_role = 'CONSULTANT' AND active_institution_id = ?
            """.trimIndent(),
            consultantId,
            institutionId
        )
    }

    private fun requireLockedInstitution(institutionId: String) = requireActiveInstitution(institutionId)

    private fun activeInstitution(institutionId: String, lock: Boolean): String? = jdbcTemplate.queryForList(
        """
        SELECT id FROM institutions
        WHERE id = ? AND is_verified = 1 AND deleted_at IS NULL${if (lock) " FOR UPDATE" else ""}
        """.trimIndent(),
        String::class.java,
        institutionId
    ).firstOrNull()

    private fun lockActiveMembership(consultantId: String, institutionId: String): String? =
        jdbcTemplate.queryForList(
            """
            SELECT id FROM institution_memberships
            WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
              AND status = 'APPROVED' AND revoked_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            String::class.java,
            consultantId,
            institutionId
        ).firstOrNull()

    private fun lockMembership(consultantId: String, institutionId: String): String? = jdbcTemplate.queryForList(
        """
        SELECT id FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
        FOR UPDATE
        """.trimIndent(),
        String::class.java,
        consultantId,
        institutionId
    ).firstOrNull()

    private fun conflict(message: String): Nothing = throw ConsultantInstitutionRequestConflictException(message)
}
