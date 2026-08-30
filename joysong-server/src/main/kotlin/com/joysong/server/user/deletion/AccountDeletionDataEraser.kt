package com.joysong.server.user.deletion

import com.joysong.server.user.entity.UserEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDateTime

interface AccountDeletionDataEraser {
    fun erase(user: UserEntity, erasedAt: LocalDateTime)
}

@Component
class JdbcAccountDeletionDataEraser(
    private val jdbcTemplate: JdbcTemplate,
    private val crypto: AccountDeletionCrypto,
    private val userMediaAssetService: UserMediaAssetService,
) : AccountDeletionDataEraser {
    override fun erase(user: UserEntity, erasedAt: LocalDateTime) {
        val userId = user.id
        revokeSessions(userId)
        revokeDiaryShares(userId, erasedAt)
        eraseDiaries(userId)
        eraseCommentsAndSocialGraph(userId)
        anonymizeReports(userId)
        eraseAiData(userId)
        eraseInactiveIdentityData(userId)
        erasePrivateDrafts(userId)
        userMediaAssetService.markPending(userId)
        eraseUser(user, erasedAt)
    }

    private fun revokeSessions(userId: String) {
        jdbcTemplate.update(
            "UPDATE refresh_tokens SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = ?",
            userId,
        )
        jdbcTemplate.update(
            "UPDATE auth_sessions SET revoked_at = COALESCE(revoked_at, NOW()) WHERE user_id = ?",
            userId,
        )
    }

    private fun revokeDiaryShares(userId: String, erasedAt: LocalDateTime) {
        jdbcTemplate.update(
            """
            UPDATE diary_shares
            SET revoked_at = COALESCE(revoked_at, ?)
            WHERE diary_id IN (SELECT id FROM diaries WHERE user_id = ?)
            """.trimIndent(),
            erasedAt,
            userId,
        )
    }

    private fun eraseDiaries(userId: String) {
        jdbcTemplate.update(
            """
            UPDATE diaries
            SET status = 'deleted', title = '', author_name = '', author_avatar = '', content = '',
                images = '', before_images = '', after_images = '', tags = '', user_id = ''
            WHERE user_id = ?
            """.trimIndent(),
            userId,
        )
    }

    private fun eraseCommentsAndSocialGraph(userId: String) {
        val affectedTargets = findAffectedSocialTargets(userId)
        jdbcTemplate.update(
            "DELETE FROM likes WHERE target_type = 'comment' AND target_id IN (SELECT id FROM comments WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update(
            "UPDATE comments SET parent_id = NULL WHERE parent_id IN (SELECT id FROM (SELECT id FROM comments WHERE user_id = ?) erased_comments)",
            userId,
        )
        jdbcTemplate.update("UPDATE comments SET reply_to_user_id = NULL WHERE reply_to_user_id = ?", userId)
        jdbcTemplate.update("DELETE FROM comments WHERE user_id = ?", userId)
        jdbcTemplate.update("DELETE FROM likes WHERE user_id = ?", userId)
        jdbcTemplate.update("DELETE FROM favorites WHERE user_id = ?", userId)
        jdbcTemplate.update(
            "DELETE FROM follows WHERE user_id = ? OR (target_type = 'user' AND target_id = ?)",
            userId,
            userId,
        )
        jdbcTemplate.update("DELETE FROM notifications WHERE user_id = ?", userId)
        recomputeSocialCounters(affectedTargets)
    }

    private fun findAffectedSocialTargets(userId: String): AffectedSocialTargets {
        val diaryIds = linkedSetOf<String>()
        diaryIds += jdbcTemplate.queryForList(
            "SELECT DISTINCT diary_id FROM comments WHERE user_id = ?",
            String::class.java,
            userId,
        )
        diaryIds += jdbcTemplate.queryForList(
            "SELECT DISTINCT target_id FROM likes WHERE user_id = ? AND target_type = 'diary'",
            String::class.java,
            userId,
        )
        diaryIds += jdbcTemplate.queryForList(
            "SELECT DISTINCT target_id FROM favorites WHERE user_id = ? AND target_type = 'DIARY'",
            String::class.java,
            userId,
        )
        val commentIds = jdbcTemplate.queryForList(
            "SELECT DISTINCT target_id FROM likes WHERE user_id = ? AND target_type = 'comment'",
            String::class.java,
            userId,
        ).toCollection(linkedSetOf())
        return AffectedSocialTargets(diaryIds, commentIds)
    }

    private fun recomputeSocialCounters(targets: AffectedSocialTargets) {
        if (targets.diaryIds.isNotEmpty()) {
            val placeholders = targets.diaryIds.joinToString(", ") { "?" }
            jdbcTemplate.update(
                """
                UPDATE diaries d
                SET comment_count = (
                        SELECT COUNT(*) FROM comments c
                        WHERE c.diary_id = d.id AND c.deleted_at IS NULL
                    ),
                    like_count = (
                        SELECT COUNT(*) FROM likes l
                        WHERE l.target_type = 'diary' AND l.target_id = d.id
                    ),
                    favorite_count = (
                        SELECT COUNT(*) FROM favorites f
                        WHERE f.target_type = 'DIARY' AND f.target_id = d.id
                    )
                WHERE d.id IN ($placeholders)
                """.trimIndent(),
                *targets.diaryIds.toTypedArray(),
            )
        }
        if (targets.commentIds.isNotEmpty()) {
            val placeholders = targets.commentIds.joinToString(", ") { "?" }
            jdbcTemplate.update(
                """
                UPDATE comments c
                SET like_count = (
                    SELECT COUNT(*) FROM likes l
                    WHERE l.target_type = 'comment' AND l.target_id = c.id
                )
                WHERE c.id IN ($placeholders)
                """.trimIndent(),
                *targets.commentIds.toTypedArray(),
            )
        }
    }

    private fun anonymizeReports(userId: String) {
        val auditSubject = auditSubject(userId)
        jdbcTemplate.update(
            """
            UPDATE reports
            SET user_id = ?, description = NULL, target_summary = NULL
            WHERE user_id = ?
            """.trimIndent(),
            auditSubject,
            userId,
        )
    }

    private fun eraseAiData(userId: String) {
        jdbcTemplate.update(
            "DELETE FROM agent_messages WHERE session_id IN (SELECT id FROM agent_sessions WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update(
            "DELETE FROM agent_turns WHERE session_id IN (SELECT id FROM agent_sessions WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update("DELETE FROM agent_sessions WHERE user_id = ?", userId)
        jdbcTemplate.update(
            "DELETE FROM agent_plan_items WHERE plan_id IN (SELECT id FROM agent_plans WHERE user_id = ?)",
            userId,
        )
        jdbcTemplate.update("DELETE FROM agent_plans WHERE user_id = ?", userId)
        jdbcTemplate.update(
            """
            UPDATE agent_safety_events
            SET user_id = ?, assessment_id = NULL, evidence_json = JSON_OBJECT()
            WHERE user_id = ?
            """.trimIndent(),
            auditSubject(userId),
            userId,
        )
        jdbcTemplate.update("DELETE FROM agent_assessments WHERE user_id = ?", userId)
        jdbcTemplate.update("DELETE FROM agent_user_profiles WHERE user_id = ?", userId)
    }

    private fun eraseInactiveIdentityData(userId: String) {
        jdbcTemplate.update(
            "DELETE FROM consultant_institution_change_requests WHERE consultant_id = ? AND status <> 'PENDING'",
            userId,
        )
        jdbcTemplate.update(
            "DELETE FROM doctor_institution_change_requests WHERE doctor_id = ? AND status <> 'PENDING'",
            userId,
        )
        jdbcTemplate.update(
            """
            DELETE FROM identity_application_documents
            WHERE application_id IN (SELECT id FROM identity_applications WHERE user_id = ?)
            """.trimIndent(),
            userId,
        )
        jdbcTemplate.update(
            """
            UPDATE identity_applications
            SET application_data = JSON_OBJECT(), review_note = ''
            WHERE user_id = ?
            """.trimIndent(),
            userId,
        )
        jdbcTemplate.update(
            "DELETE FROM doctor_institutions WHERE doctor_id = ? AND status <> 'APPROVED'",
            userId,
        )
        jdbcTemplate.update(
            "DELETE FROM platform_cooperation_agreements WHERE user_id = ? AND status = 'TERMINATED'",
            userId,
        )
        jdbcTemplate.update(
            "DELETE FROM institution_memberships WHERE user_id = ? AND status NOT IN ('PENDING', 'APPROVED')",
            userId,
        )
        jdbcTemplate.update("DELETE FROM user_roles WHERE user_id = ? AND status <> 'ACTIVE'", userId)
        jdbcTemplate.update(
            """
            UPDATE doctors
            SET name = '', title = '', bio = '', avatar = '', institution_id = '', institution_name = '',
                specialties = '', credentials = '', credential_images = '', certification_tags = '',
                contact_phone = '', is_verified = 0, deleted_at = COALESCE(deleted_at, NOW())
            WHERE id = ?
            """.trimIndent(),
            userId,
        )
    }

    private fun erasePrivateDrafts(userId: String) {
        jdbcTemplate.update(
            """
            UPDATE user_media_assets uma
            JOIN private_files pf ON pf.storage_key = uma.storage_key
            SET uma.delete_status = 'PENDING', uma.retry_after = NULL, uma.last_delete_error = NULL
            WHERE pf.owner_user_id = ?
              AND NOT EXISTS (SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = pf.id)
              AND NOT EXISTS (
                  SELECT 1 FROM platform_cooperation_agreements pca
                  WHERE pca.agreement_file_id = pf.id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM refund_evidence_files ref
                  WHERE ref.file_id = pf.id
              )
            """.trimIndent(),
            userId,
        )
        jdbcTemplate.update(
            """
            DELETE FROM private_files
            WHERE owner_user_id = ?
              AND NOT EXISTS (SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = private_files.id)
              AND NOT EXISTS (
                  SELECT 1 FROM platform_cooperation_agreements pca
                  WHERE pca.agreement_file_id = private_files.id
              )
              AND NOT EXISTS (
                  SELECT 1 FROM refund_evidence_files ref
                  WHERE ref.file_id = private_files.id
              )
            """.trimIndent(),
            userId,
        )
    }

    private fun eraseUser(user: UserEntity, erasedAt: LocalDateTime) {
        jdbcTemplate.update(
            """
            UPDATE users
            SET phone = NULL, email = NULL, password_hash = '', nickname = '', avatar = '', gender = '',
                city = '', bio = '', birthday = NULL, account_state = 'ERASED', erased_at = ?,
                erased_phone_digest = ?, erased_email_digest = ?, credentials_updated_at = ?, updated_at = ?
            WHERE id = ? AND account_state = 'ACTIVE'
            """.trimIndent(),
            erasedAt,
            user.phone?.takeIf(String::isNotBlank)?.let(crypto::hash),
            user.email?.takeIf(String::isNotBlank)?.let(crypto::hash),
            erasedAt,
            erasedAt,
            user.id,
        ).also { updated -> require(updated == 1) { "account state changed during deletion" } }
    }

    private fun auditSubject(userId: String): String = "erased_" + crypto.hash(userId).take(28)

    private data class AffectedSocialTargets(
        val diaryIds: LinkedHashSet<String>,
        val commentIds: LinkedHashSet<String>,
    )
}
