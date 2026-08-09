package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

private val SUPPORTED_IDENTITY_ROLES = setOf(
    "DOCTOR",
    "CONSULTANT",
    "INSTITUTION_LEGAL_REPRESENTATIVE",
    "INSTITUTION_CUSTOMER_SERVICE"
)

private val INSTITUTION_MEMBER_ROLES = SUPPORTED_IDENTITY_ROLES - "DOCTOR"
private val APPLICATION_STATUSES = setOf("PENDING", "APPROVED", "REJECTED", "WITHDRAWN")
private val ROLE_STATUSES = setOf("ACTIVE", "REVOKED")
private val RELATION_STATUSES = setOf("PENDING", "APPROVED", "REVOKED")

@Service
class AdminIdentityService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {
    private val namedJdbcTemplate = NamedParameterJdbcTemplate(jdbcTemplate)

    fun listApplications(
        status: String?,
        roleCode: String?,
        keyword: String?
    ): List<IdentityApplicationAdminView> {
        val normalizedStatus = status.normalizedFilter(APPLICATION_STATUSES, "申请状态")
        val normalizedRole = roleCode.normalizedRoleFilter()
        val normalizedKeyword = keyword?.trim()?.takeIf(String::isNotEmpty)
        val sql = StringBuilder(
            """
            SELECT ia.id, ia.user_id, u.nickname AS user_name, u.phone AS user_phone,
                   ia.role_code, ia.status, ia.application_data, ia.review_note,
                   ia.reviewed_by, reviewer.nickname AS reviewer_name,
                   ia.submitted_at, ia.reviewed_at, ia.updated_at
            FROM identity_applications ia
            JOIN users u ON u.id = ia.user_id
            LEFT JOIN users reviewer ON reviewer.id = ia.reviewed_by
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        if (normalizedStatus != null) {
            sql.append(" AND ia.status = :status")
            params.addValue("status", normalizedStatus)
        }
        if (normalizedRole != null) {
            sql.append(" AND ia.role_code = :roleCode")
            params.addValue("roleCode", normalizedRole)
        }
        if (normalizedKeyword != null) {
            sql.append(" AND (u.nickname LIKE CONCAT('%', :keyword, '%') OR u.phone LIKE CONCAT('%', :keyword, '%') OR ia.user_id = :keyword OR ia.id = :keyword)")
            params.addValue("keyword", normalizedKeyword)
        }
        sql.append(" ORDER BY CASE ia.status WHEN 'PENDING' THEN 0 ELSE 1 END, ia.submitted_at DESC")

        return namedJdbcTemplate.query(sql.toString(), params) { rs, _ ->
            val applicationId = rs.getString("id")
            IdentityApplicationAdminView(
                id = applicationId,
                userId = rs.getString("user_id"),
                userName = rs.getString("user_name"),
                userPhone = rs.getString("user_phone"),
                roleCode = rs.getString("role_code"),
                status = rs.getString("status"),
                applicationData = parseJson(rs.getString("application_data")),
                reviewNote = rs.getString("review_note"),
                reviewedBy = rs.getString("reviewed_by"),
                reviewerName = rs.getString("reviewer_name"),
                submittedAt = rs.getTimestamp("submitted_at")?.toLocalDateTime(),
                reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime(),
                documents = documentsFor(applicationId)
            )
        }
    }

    @Transactional
    fun reviewApplication(applicationId: String, reviewerId: String, decision: String, reviewNote: String) {
        val normalizedDecision = decision.trim().uppercase()
        require(normalizedDecision == "APPROVED" || normalizedDecision == "REJECTED") { "审核结果只能是 APPROVED 或 REJECTED" }
        if (normalizedDecision == "REJECTED") require(reviewNote.isNotBlank()) { "驳回时必须填写审核说明" }

        val target = jdbcTemplate.query(
            "SELECT user_id, role_code, status, application_data FROM identity_applications WHERE id = ? FOR UPDATE",
            { rs, _ -> ReviewTarget(
                rs.getString("user_id"),
                rs.getString("role_code"),
                rs.getString("status"),
                parseJson(rs.getString("application_data"))
            ) },
            applicationId
        ).firstOrNull() ?: throw IllegalArgumentException("身份申请不存在")

        require(target.status == "PENDING") { "只有待审核申请可以处理" }
        require(target.roleCode in SUPPORTED_IDENTITY_ROLES) { "不支持的身份类型" }
        val updated = jdbcTemplate.update(
            """
            UPDATE identity_applications
            SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = NOW()
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            normalizedDecision,
            reviewNote.trim(),
            reviewerId,
            applicationId
        )
        check(updated == 1) { "申请状态已变化，请刷新后重试" }

        if (normalizedDecision == "APPROVED") {
            jdbcTemplate.update(
                """
                INSERT INTO user_roles (user_id, role_code, status, source_application_id, activated_at)
                VALUES (?, ?, 'ACTIVE', ?, NOW())
                ON DUPLICATE KEY UPDATE
                    status = 'ACTIVE', source_application_id = ?, activated_at = NOW(),
                    revoked_at = NULL, revoked_by = NULL, revoke_reason = ''
                """.trimIndent(),
                target.userId,
                target.roleCode,
                applicationId,
                applicationId
            )
            if (target.roleCode == "DOCTOR") {
                provisionDoctorProfile(target)
            }
            if (target.roleCode == "INSTITUTION_LEGAL_REPRESENTATIVE") {
                provisionInstitutionForLegalRepresentative(target, reviewerId)
            }
        }
    }

    @Transactional
    fun bindConsultant(userId: String, institutionId: String, confirmerId: String): ConsultantBindingAdminView {
        val normalizedUserId = userId.trim().also { require(it.isNotEmpty()) { "用户 ID 不能为空" } }
        val normalizedInstitutionId = institutionId.trim().also { require(it.isNotEmpty()) { "机构不能为空" } }
        val normalizedConfirmerId = confirmerId.trim().also { require(it.isNotEmpty()) { "确认人不能为空" } }

        require(
            count(
                "SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NULL AND role = 'USER'",
                normalizedUserId
            ) == 1L
        ) { "用户不存在、已注销或不是普通用户" }
        require(
            count("SELECT COUNT(*) FROM institutions WHERE id = ? AND deleted_at IS NULL", normalizedInstitutionId) == 1L
        ) { "机构不存在或已删除" }

        jdbcTemplate.update(
            """
            INSERT INTO user_roles (user_id, role_code, status, activated_at)
            VALUES (?, ?, 'ACTIVE', NOW())
            ON DUPLICATE KEY UPDATE
                status = 'ACTIVE', activated_at = NOW(),
                revoked_at = NULL, revoked_by = NULL, revoke_reason = ''
            """.trimIndent(),
            normalizedUserId,
            "CONSULTANT"
        )

        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, ?, 'APPROVED', ?, NOW())
            ON DUPLICATE KEY UPDATE
                status = 'APPROVED', confirmed_by = VALUES(confirmed_by), confirmed_at = NOW(), revoked_at = NULL
            """.trimIndent(),
            UUID.randomUUID().toString(),
            normalizedUserId,
            normalizedInstitutionId,
            "CONSULTANT",
            normalizedConfirmerId
        )

        return jdbcTemplate.query(
            """
            SELECT im.id, im.user_id, u.nickname AS user_name, im.institution_id, i.name AS institution_name,
                   im.member_role, im.status
            FROM institution_memberships im
            JOIN users u ON u.id = im.user_id
            JOIN institutions i ON i.id = im.institution_id
            WHERE im.user_id = ? AND im.institution_id = ? AND im.member_role = ?
            """.trimIndent(),
            { rs, _ ->
                ConsultantBindingAdminView(
                    userId = rs.getString("user_id"),
                    userName = rs.getString("user_name"),
                    institutionId = rs.getString("institution_id"),
                    institutionName = rs.getString("institution_name"),
                    membershipId = rs.getString("id"),
                    roleCode = rs.getString("member_role"),
                    status = rs.getString("status")
                )
            },
            normalizedUserId,
            normalizedInstitutionId,
            "CONSULTANT"
        ).firstOrNull() ?: throw IllegalStateException("咨询师绑定关系写入失败")
    }

    fun listRoles(status: String?, roleCode: String?, keyword: String?): List<UserRoleAdminView> {
        val normalizedStatus = status.normalizedFilter(ROLE_STATUSES, "身份状态")
        val normalizedRole = roleCode.normalizedRoleFilter()
        val normalizedKeyword = keyword?.trim()?.takeIf(String::isNotEmpty)
        val sql = StringBuilder(
            """
            SELECT ur.user_id, u.nickname AS user_name, u.phone AS user_phone, u.deleted_at,
                   ur.role_code, ur.status, ur.source_application_id, ur.activated_at,
                   ur.revoked_at, ur.revoked_by, revoker.nickname AS revoker_name, ur.revoke_reason
            FROM user_roles ur
            JOIN users u ON u.id = ur.user_id
            LEFT JOIN users revoker ON revoker.id = ur.revoked_by
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        if (normalizedStatus != null) {
            sql.append(" AND ur.status = :status")
            params.addValue("status", normalizedStatus)
        }
        if (normalizedRole != null) {
            sql.append(" AND ur.role_code = :roleCode")
            params.addValue("roleCode", normalizedRole)
        }
        if (normalizedKeyword != null) {
            sql.append(" AND (u.nickname LIKE CONCAT('%', :keyword, '%') OR u.phone LIKE CONCAT('%', :keyword, '%') OR ur.user_id = :keyword)")
            params.addValue("keyword", normalizedKeyword)
        }
        sql.append(" ORDER BY CASE ur.status WHEN 'ACTIVE' THEN 0 ELSE 1 END, ur.updated_at DESC")

        return namedJdbcTemplate.query(sql.toString(), params) { rs, _ ->
            UserRoleAdminView(
                userId = rs.getString("user_id"),
                userName = rs.getString("user_name"),
                userPhone = rs.getString("user_phone"),
                accountActive = rs.getTimestamp("deleted_at") == null,
                roleCode = rs.getString("role_code"),
                status = rs.getString("status"),
                sourceApplicationId = rs.getString("source_application_id"),
                activatedAt = rs.getTimestamp("activated_at")?.toLocalDateTime(),
                revokedAt = rs.getTimestamp("revoked_at")?.toLocalDateTime(),
                revokedBy = rs.getString("revoked_by"),
                revokerName = rs.getString("revoker_name"),
                revokeReason = rs.getString("revoke_reason")
            )
        }
    }

    @Transactional
    fun revokeRole(userId: String, roleCode: String, reviewerId: String, reason: String) {
        val normalizedRole = roleCode.requiredRole()
        require(reason.isNotBlank()) { "撤销身份时必须填写原因" }
        val updated = jdbcTemplate.update(
            """
            UPDATE user_roles
            SET status = 'REVOKED', revoked_at = NOW(), revoked_by = ?, revoke_reason = ?
            WHERE user_id = ? AND role_code = ? AND status = 'ACTIVE'
            """.trimIndent(),
            reviewerId,
            reason.trim(),
            userId,
            normalizedRole
        )
        require(updated == 1) { "有效身份不存在或已被撤销" }

        if (normalizedRole == "DOCTOR") {
            jdbcTemplate.update("UPDATE doctors SET is_verified = 0 WHERE id = ?", userId)
            jdbcTemplate.update(
                "UPDATE doctor_institutions SET status = 'REVOKED', is_primary = 0, revoked_at = NOW() WHERE doctor_id = ? AND status <> 'REVOKED'",
                userId
            )
        } else {
            jdbcTemplate.update(
                "UPDATE institution_memberships SET status = 'REVOKED', revoked_at = NOW() WHERE user_id = ? AND member_role = ? AND status <> 'REVOKED'",
                userId,
                normalizedRole
            )
        }
        fallbackSessions(userId, normalizedRole)
    }

    fun listMemberships(
        institutionId: String?,
        status: String?,
        memberRole: String?,
        keyword: String?
    ): List<InstitutionMembershipAdminView> {
        val normalizedStatus = status.normalizedFilter(RELATION_STATUSES, "成员状态")
        val normalizedRole = memberRole?.trim()?.uppercase()?.takeIf(String::isNotEmpty)?.also {
            require(it in INSTITUTION_MEMBER_ROLES) { "不支持的机构成员身份" }
        }
        val normalizedInstitutionId = institutionId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedKeyword = keyword?.trim()?.takeIf(String::isNotEmpty)
        val sql = StringBuilder(
            """
            SELECT im.id, im.user_id, u.nickname AS user_name, u.phone AS user_phone,
                   im.institution_id, i.name AS institution_name, im.member_role, im.status,
                   im.confirmed_by, confirmer.nickname AS confirmer_name, im.confirmed_at,
                   im.revoked_at, im.created_at, im.updated_at
            FROM institution_memberships im
            JOIN users u ON u.id = im.user_id
            JOIN institutions i ON i.id = im.institution_id
            LEFT JOIN users confirmer ON confirmer.id = im.confirmed_by
            WHERE 1 = 1
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        if (normalizedInstitutionId != null) {
            sql.append(" AND im.institution_id = :institutionId")
            params.addValue("institutionId", normalizedInstitutionId)
        }
        if (normalizedStatus != null) {
            sql.append(" AND im.status = :status")
            params.addValue("status", normalizedStatus)
        }
        if (normalizedRole != null) {
            sql.append(" AND im.member_role = :memberRole")
            params.addValue("memberRole", normalizedRole)
        }
        if (normalizedKeyword != null) {
            sql.append(" AND (u.nickname LIKE CONCAT('%', :keyword, '%') OR u.phone LIKE CONCAT('%', :keyword, '%') OR im.user_id = :keyword)")
            params.addValue("keyword", normalizedKeyword)
        }
        sql.append(" ORDER BY CASE im.status WHEN 'PENDING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END, im.updated_at DESC")

        return namedJdbcTemplate.query(sql.toString(), params) { rs, _ ->
            InstitutionMembershipAdminView(
                id = rs.getString("id"),
                userId = rs.getString("user_id"),
                userName = rs.getString("user_name"),
                userPhone = rs.getString("user_phone"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                memberRole = rs.getString("member_role"),
                status = rs.getString("status"),
                confirmedBy = rs.getString("confirmed_by"),
                confirmerName = rs.getString("confirmer_name"),
                confirmedAt = rs.getTimestamp("confirmed_at")?.toLocalDateTime(),
                revokedAt = rs.getTimestamp("revoked_at")?.toLocalDateTime(),
                createdAt = rs.getTimestamp("created_at")?.toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()
            )
        }
    }

    @Transactional
    fun createMembership(userId: String, institutionId: String, memberRole: String): String {
        val normalizedUserId = userId.trim().also { require(it.isNotEmpty()) { "用户 ID 不能为空" } }
        val normalizedInstitutionId = institutionId.trim().also { require(it.isNotEmpty()) { "机构不能为空" } }
        val normalizedRole = memberRole.trim().uppercase().also {
            require(it in INSTITUTION_MEMBER_ROLES) { "医生执业关系请在医生执业审核中管理" }
        }
        requireActiveRole(normalizedUserId, normalizedRole)
        require(count("SELECT COUNT(*) FROM users WHERE id = ? AND deleted_at IS NULL", normalizedUserId) == 1L) { "用户不存在或已注销" }
        require(count("SELECT COUNT(*) FROM institutions WHERE id = ? AND deleted_at IS NULL", normalizedInstitutionId) == 1L) { "机构不存在或已删除" }

        val existing = jdbcTemplate.query(
            "SELECT id, status FROM institution_memberships WHERE user_id = ? AND institution_id = ? AND member_role = ? FOR UPDATE",
            { rs, _ -> rs.getString("id") to rs.getString("status") },
            normalizedUserId,
            normalizedInstitutionId,
            normalizedRole
        ).firstOrNull()
        if (existing != null) {
            require(existing.second == "REVOKED") { "该机构成员关系已存在" }
            jdbcTemplate.update(
                "UPDATE institution_memberships SET status = 'PENDING', confirmed_by = NULL, confirmed_at = NULL, revoked_at = NULL WHERE id = ?",
                existing.first
            )
            return existing.first
        }

        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO institution_memberships (id, user_id, institution_id, member_role, status) VALUES (?, ?, ?, ?, 'PENDING')",
            id,
            normalizedUserId,
            normalizedInstitutionId,
            normalizedRole
        )
        return id
    }

    @Transactional
    fun approveMembership(id: String, reviewerId: String) {
        val target = membershipTarget(id)
        require(target.status == "PENDING") { "只有待审核成员关系可以通过" }
        requireActiveRole(target.userId, target.roleCode)
        val updated = jdbcTemplate.update(
            "UPDATE institution_memberships SET status = 'APPROVED', confirmed_by = ?, confirmed_at = NOW(), revoked_at = NULL WHERE id = ? AND status = 'PENDING'",
            reviewerId,
            id
        )
        check(updated == 1) { "成员关系状态已变化，请刷新后重试" }
    }

    @Transactional
    fun revokeMembership(id: String) {
        val target = membershipTarget(id)
        require(target.status != "REVOKED") { "成员关系已撤销" }
        jdbcTemplate.update(
            "UPDATE institution_memberships SET status = 'REVOKED', revoked_at = NOW() WHERE id = ?",
            id
        )
        jdbcTemplate.update(
            "UPDATE auth_sessions SET active_role = 'USER', active_institution_id = NULL WHERE user_id = ? AND active_role = ? AND active_institution_id = ?",
            target.userId,
            target.roleCode,
            target.institutionId
        )
    }

    fun listDoctorPractices(
        institutionId: String?,
        status: String?,
        keyword: String?
    ): List<DoctorPracticeAdminView> {
        val normalizedStatus = status.normalizedFilter(RELATION_STATUSES, "执业状态")
        val normalizedInstitutionId = institutionId?.trim()?.takeIf(String::isNotEmpty)
        val normalizedKeyword = keyword?.trim()?.takeIf(String::isNotEmpty)
        val sql = StringBuilder(
            """
            SELECT di.id, di.doctor_id, d.name AS doctor_name, u.phone AS doctor_phone,
                   di.institution_id, i.name AS institution_name, di.is_primary, di.status,
                   di.registration_no, di.registration_file_id, di.confirmed_by,
                   confirmer.nickname AS confirmer_name, di.confirmed_at, di.revoked_at,
                   di.created_at, di.updated_at
            FROM doctor_institutions di
            JOIN doctors d ON d.id = di.doctor_id
            JOIN users u ON u.id = di.doctor_id
            JOIN institutions i ON i.id = di.institution_id
            LEFT JOIN users confirmer ON confirmer.id = di.confirmed_by
            WHERE di.deleted_at IS NULL
            """.trimIndent()
        )
        val params = MapSqlParameterSource()
        if (normalizedInstitutionId != null) {
            sql.append(" AND di.institution_id = :institutionId")
            params.addValue("institutionId", normalizedInstitutionId)
        }
        if (normalizedStatus != null) {
            sql.append(" AND di.status = :status")
            params.addValue("status", normalizedStatus)
        }
        if (normalizedKeyword != null) {
            sql.append(" AND (d.name LIKE CONCAT('%', :keyword, '%') OR u.phone LIKE CONCAT('%', :keyword, '%') OR di.doctor_id = :keyword)")
            params.addValue("keyword", normalizedKeyword)
        }
        sql.append(" ORDER BY CASE di.status WHEN 'PENDING' THEN 0 WHEN 'APPROVED' THEN 1 ELSE 2 END, di.updated_at DESC")

        return namedJdbcTemplate.query(sql.toString(), params) { rs, _ ->
            DoctorPracticeAdminView(
                id = rs.getString("id"),
                doctorId = rs.getString("doctor_id"),
                doctorName = rs.getString("doctor_name"),
                doctorPhone = rs.getString("doctor_phone"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                primary = rs.getBoolean("is_primary"),
                status = rs.getString("status"),
                registrationNo = rs.getString("registration_no"),
                registrationFileId = rs.getString("registration_file_id"),
                confirmedBy = rs.getString("confirmed_by"),
                confirmerName = rs.getString("confirmer_name"),
                confirmedAt = rs.getTimestamp("confirmed_at")?.toLocalDateTime(),
                revokedAt = rs.getTimestamp("revoked_at")?.toLocalDateTime(),
                createdAt = rs.getTimestamp("created_at")?.toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at")?.toLocalDateTime()
            )
        }
    }

    @Transactional
    fun approveDoctorPractice(id: String, reviewerId: String) {
        val target = doctorPracticeTarget(id)
        require(target.status == "PENDING") { "只有待审核执业关系可以通过" }
        requireActiveRole(target.userId, "DOCTOR")
        val updated = jdbcTemplate.update(
            "UPDATE doctor_institutions SET status = 'APPROVED', confirmed_by = ?, confirmed_at = NOW(), revoked_at = NULL WHERE id = ? AND status = 'PENDING'",
            reviewerId,
            id
        )
        check(updated == 1) { "执业关系状态已变化，请刷新后重试" }
    }

    @Transactional
    fun revokeDoctorPractice(id: String) {
        val target = doctorPracticeTarget(id)
        require(target.status != "REVOKED") { "执业关系已撤销" }
        jdbcTemplate.update(
            "UPDATE doctor_institutions SET status = 'REVOKED', is_primary = 0, revoked_at = NOW() WHERE id = ?",
            id
        )
        jdbcTemplate.update(
            "UPDATE auth_sessions SET active_role = 'USER', active_institution_id = NULL WHERE user_id = ? AND active_role = 'DOCTOR' AND active_institution_id = ?",
            target.userId,
            target.institutionId
        )
    }

    private fun documentsFor(applicationId: String): List<IdentityDocumentAdminView> = jdbcTemplate.query(
        """
        SELECT iad.file_id, iad.document_type, pf.original_name, pf.content_type, pf.size_bytes, pf.status
        FROM identity_application_documents iad
        JOIN private_files pf ON pf.id = iad.file_id
        WHERE iad.application_id = ?
        ORDER BY iad.created_at ASC
        """.trimIndent(),
        { rs, _ ->
            IdentityDocumentAdminView(
                fileId = rs.getString("file_id"),
                documentType = rs.getString("document_type"),
                originalName = rs.getString("original_name"),
                contentType = rs.getString("content_type"),
                sizeBytes = rs.getLong("size_bytes"),
                status = rs.getString("status")
            )
        },
        applicationId
    )

    private fun membershipTarget(id: String): RelationTarget = jdbcTemplate.query(
        "SELECT user_id, institution_id, member_role, status FROM institution_memberships WHERE id = ? FOR UPDATE",
        { rs, _ -> RelationTarget(rs.getString("user_id"), rs.getString("institution_id"), rs.getString("member_role"), rs.getString("status")) },
        id
    ).firstOrNull() ?: throw IllegalArgumentException("机构成员关系不存在")

    private fun doctorPracticeTarget(id: String): RelationTarget = jdbcTemplate.query(
        "SELECT doctor_id, institution_id, 'DOCTOR' AS member_role, status FROM doctor_institutions WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
        { rs, _ -> RelationTarget(rs.getString("doctor_id"), rs.getString("institution_id"), rs.getString("member_role"), rs.getString("status")) },
        id
    ).firstOrNull() ?: throw IllegalArgumentException("医生执业关系不存在")

    private fun requireActiveRole(userId: String, roleCode: String) {
        require(count("SELECT COUNT(*) FROM user_roles WHERE user_id = ? AND role_code = ? AND status = 'ACTIVE'", userId, roleCode) == 1L) {
            "用户尚未取得有效职业身份"
        }
    }

    private fun fallbackSessions(userId: String, roleCode: String) {
        jdbcTemplate.update(
            "UPDATE auth_sessions SET active_role = 'USER', active_institution_id = NULL WHERE user_id = ? AND active_role = ?",
            userId,
            roleCode
        )
    }

    private fun provisionDoctorProfile(target: ReviewTarget) {
        val data = target.applicationData
        val realName = data.path("realName").asText().trim().ifBlank { "已认证医生" }
        val title = data.path("title").asText().trim()
        val bio = data.path("reason").asText().trim()
        val credentials = listOf(
            data.path("qualificationNo").asText().trim(),
            data.path("practiceNo").asText().trim()
        ).filter(String::isNotBlank).joinToString(" / ")
        // 主页证书图由医生通过公开资料上传独立维护，绝不复制 private_files 中的审核材料。
        jdbcTemplate.update(
            """
            INSERT INTO doctors (id, name, title, bio, credentials, credential_images, rating, review_count, is_verified)
            VALUES (?, ?, ?, ?, ?, '', 0, 0, 1)
            ON DUPLICATE KEY UPDATE is_verified = 1, deleted_at = NULL
            """.trimIndent(),
            target.userId,
            realName,
            title,
            bio,
            credentials
        )
    }

    private fun provisionInstitutionForLegalRepresentative(target: ReviewTarget, reviewerId: String) {
        val existingMembership = jdbcTemplate.query(
            """
            SELECT id, institution_id
            FROM institution_memberships
            WHERE user_id = ?
              AND member_role IN ('INSTITUTION_LEGAL_REPRESENTATIVE', 'LEGAL_REPRESENTATIVE')
            ORDER BY CASE status WHEN 'APPROVED' THEN 0 ELSE 1 END, updated_at DESC
            LIMIT 1
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("id") to rs.getString("institution_id") },
            target.userId
        ).firstOrNull()
        if (existingMembership != null) {
            jdbcTemplate.update(
                """
                UPDATE institution_memberships
                SET status = 'APPROVED', confirmed_by = ?, confirmed_at = NOW(), revoked_at = NULL
                WHERE id = ?
                """.trimIndent(),
                reviewerId,
                existingMembership.first
            )
            jdbcTemplate.update(
                "UPDATE institutions SET is_verified = 1, certification_time = CURRENT_DATE WHERE id = ?",
                existingMembership.second
            )
            return
        }

        val data = target.applicationData
        val institutionId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO institutions
                (id, name, address, city, description, rating, review_count, is_verified,
                 credentials, contact_phone, certification_time)
            VALUES (?, ?, ?, ?, ?, 0, 0, 1, ?, ?, CURRENT_DATE)
            """.trimIndent(),
            institutionId,
            data.path("institutionName").asText().trim(),
            data.path("address").asText().trim(),
            data.path("region").asText().trim(),
            "机构法人认证创建，后续由机构完善资料",
            "统一社会信用代码：${data.path("businessLicenseNo").asText().trim()}",
            data.path("phone").asText().trim()
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'APPROVED', ?, NOW())
            """.trimIndent(),
            UUID.randomUUID().toString(),
            target.userId,
            institutionId,
            reviewerId
        )
    }

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)

    private fun parseJson(value: String): JsonNode = objectMapper.readTree(value)

    private fun String?.normalizedFilter(allowed: Set<String>, label: String): String? =
        this?.trim()?.uppercase()?.takeIf(String::isNotEmpty)?.also { require(it in allowed) { "$label 不正确" } }

    private fun String?.normalizedRoleFilter(): String? =
        this?.trim()?.uppercase()?.takeIf(String::isNotEmpty)?.also { require(it in SUPPORTED_IDENTITY_ROLES) { "不支持的身份类型" } }

    private fun String.requiredRole(): String = trim().uppercase().also {
        require(it in SUPPORTED_IDENTITY_ROLES) { "不支持的身份类型" }
    }
}

private data class ReviewTarget(
    val userId: String,
    val roleCode: String,
    val status: String,
    val applicationData: JsonNode
)
private data class RelationTarget(val userId: String, val institutionId: String, val roleCode: String, val status: String)

data class IdentityDocumentAdminView(
    val fileId: String,
    val documentType: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
    val status: String
)

data class IdentityApplicationAdminView(
    val id: String,
    val userId: String,
    val userName: String,
    val userPhone: String?,
    val roleCode: String,
    val status: String,
    val applicationData: JsonNode,
    val reviewNote: String,
    val reviewedBy: String?,
    val reviewerName: String?,
    val submittedAt: LocalDateTime?,
    val reviewedAt: LocalDateTime?,
    val updatedAt: LocalDateTime?,
    val documents: List<IdentityDocumentAdminView>
)

data class UserRoleAdminView(
    val userId: String,
    val userName: String,
    val userPhone: String?,
    val accountActive: Boolean,
    val roleCode: String,
    val status: String,
    val sourceApplicationId: String?,
    val activatedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
    val revokedBy: String?,
    val revokerName: String?,
    val revokeReason: String
)

data class InstitutionMembershipAdminView(
    val id: String,
    val userId: String,
    val userName: String,
    val userPhone: String?,
    val institutionId: String,
    val institutionName: String,
    val memberRole: String,
    val status: String,
    val confirmedBy: String?,
    val confirmerName: String?,
    val confirmedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)

data class ConsultantBindingAdminView(
    val userId: String,
    val userName: String,
    val institutionId: String,
    val institutionName: String,
    val membershipId: String,
    val roleCode: String,
    val status: String
)

data class DoctorPracticeAdminView(
    val id: String,
    val doctorId: String,
    val doctorName: String,
    val doctorPhone: String?,
    val institutionId: String,
    val institutionName: String,
    val primary: Boolean,
    val status: String,
    val registrationNo: String,
    val registrationFileId: String?,
    val confirmedBy: String?,
    val confirmerName: String?,
    val confirmedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?,
    val createdAt: LocalDateTime?,
    val updatedAt: LocalDateTime?
)
