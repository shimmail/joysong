package com.joysong.server.identity.service

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.UUID

enum class DoctorInstitutionAction {
    JOIN,
    LEAVE;

    companion object {
        fun parse(value: String): DoctorInstitutionAction = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: throw IllegalArgumentException("不支持的机构关系申请类型")
    }
}

enum class DoctorInstitutionRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}

class DoctorInstitutionRequestConflictException(message: String) : RuntimeException(message)

data class DoctorInstitutionChangeRequestView(
    val id: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String,
    val institutionName: String,
    val action: DoctorInstitutionAction,
    val status: DoctorInstitutionRequestStatus,
    val requestNote: String,
    val reviewNote: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

interface DoctorInstitutionChangeRequestStore {
    fun isCertifiedDoctor(doctorId: String): Boolean
    fun isActiveInstitution(institutionId: String): Boolean
    fun hasActiveRelationship(doctorId: String, institutionId: String): Boolean
    fun hasPending(doctorId: String, institutionId: String): Boolean
    fun create(
        doctorId: String,
        institutionId: String,
        action: DoctorInstitutionAction,
        requestNote: String
    ): DoctorInstitutionChangeRequestView

    fun listVisible(
        doctorId: String?,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<DoctorInstitutionChangeRequestView>
    fun exists(id: String): Boolean
    fun lock(id: String): DoctorInstitutionChangeRequestView?
    fun changeStatus(
        id: String,
        status: DoctorInstitutionRequestStatus,
        actorId: String,
        reviewNote: String
    ): Boolean
}

@Service
class DoctorInstitutionChangeRequestService(
    private val store: DoctorInstitutionChangeRequestStore,
    private val relationshipService: DoctorInstitutionRelationshipService
) {
    @Transactional
    fun submit(
        actor: ManagementActor,
        institutionId: String,
        action: DoctorInstitutionAction,
        requestNote: String
    ): DoctorInstitutionChangeRequestView {
        val doctorId = requireCertifiedDoctor(actor)
        val normalizedInstitutionId = institutionId.trim().also {
            require(it.isNotEmpty()) { "机构不能为空" }
        }
        require(store.isActiveInstitution(normalizedInstitutionId)) { "机构不存在或已删除" }
        val activeRelationship = store.hasActiveRelationship(doctorId, normalizedInstitutionId)
        when (action) {
            DoctorInstitutionAction.JOIN -> require(!activeRelationship) { "医生已加入该机构" }
            DoctorInstitutionAction.LEAVE -> require(activeRelationship) { "医生尚未加入该机构" }
        }
        if (store.hasPending(doctorId, normalizedInstitutionId)) duplicatePending()
        return try {
            store.create(doctorId, normalizedInstitutionId, action, requestNote.trim())
        } catch (error: DuplicateKeyException) {
            if (error.isPendingRequestConflict()) duplicatePending()
            throw error
        }
    }

    fun list(actor: ManagementActor): List<DoctorInstitutionChangeRequestView> =
        store.listVisible(actor.doctorId, actor.managedInstitutionIds, actor.isAdmin)

    fun exists(id: String): Boolean = store.exists(id.trim())

    @Transactional
    fun withdraw(actor: ManagementActor, id: String): DoctorInstitutionChangeRequestView {
        val request = locked(id)
        if (request.doctorId != actor.doctorId || request.submittedBy != actor.userId) {
            throw AccessDeniedException("只能撤回本人提交的关系申请")
        }
        require(request.status == DoctorInstitutionRequestStatus.PENDING) { "只有待审核的关系申请可以撤回" }
        check(store.changeStatus(request.id, DoctorInstitutionRequestStatus.WITHDRAWN, actor.userId, "")) {
            "关系申请已被其他操作处理"
        }
        return request.copy(
            status = DoctorInstitutionRequestStatus.WITHDRAWN,
            updatedAt = LocalDateTime.now()
        )
    }

    @Transactional
    fun review(
        actor: ManagementActor,
        id: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): DoctorInstitutionChangeRequestView {
        require(decision == MembershipRequestDecision.APPROVED || decision == MembershipRequestDecision.REJECTED) {
            "不支持的审核决定"
        }
        val status = DoctorInstitutionRequestStatus.valueOf(decision.name)
        val request = locked(id)
        if (!actor.isAdmin && request.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("无权审核其他机构的关系申请")
        }
        require(request.status == DoctorInstitutionRequestStatus.PENDING) { "只有待审核的关系申请可以审核" }
        val normalizedReviewNote = reviewNote.trim()
        if (decision == MembershipRequestDecision.REJECTED) {
            require(normalizedReviewNote.isNotEmpty()) { "驳回时必须填写审核意见" }
        }
        if (decision == MembershipRequestDecision.APPROVED) {
            when (request.action) {
                DoctorInstitutionAction.JOIN -> relationshipService.approveJoin(
                    request.doctorId,
                    request.institutionId,
                    actor.userId
                )

                DoctorInstitutionAction.LEAVE -> relationshipService.approveLeave(
                    request.doctorId,
                    request.institutionId,
                    actor.userId
                )
            }
        }
        check(store.changeStatus(request.id, status, actor.userId, normalizedReviewNote)) {
            "关系申请已被其他操作处理"
        }
        return request.copy(
            status = status,
            reviewNote = normalizedReviewNote,
            reviewedBy = actor.userId,
            reviewedAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
    }

    private fun requireCertifiedDoctor(actor: ManagementActor): String {
        val doctorId = actor.doctorId
        if ("DOCTOR" !in actor.activeRoles || doctorId == null || doctorId != actor.userId ||
            !store.isCertifiedDoctor(doctorId)
        ) {
            throw AccessDeniedException("只有已认证医生本人可以提交机构关系申请")
        }
        return doctorId
    }

    private fun locked(id: String): DoctorInstitutionChangeRequestView = store.lock(
        id.trim().also { require(it.isNotEmpty()) { "关系申请不能为空" } }
    ) ?: throw IllegalArgumentException("机构关系申请不存在")

    private fun duplicatePending(): Nothing =
        throw IllegalStateException("该机构已有待处理的关系申请")

    private fun DuplicateKeyException.isPendingRequestConflict(): Boolean {
        val sqlError = generateSequence(mostSpecificCause) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
            ?: return false
        return sqlError.errorCode == 1062 &&
            sqlError.sqlState == "23000" &&
            sqlError.message.orEmpty().contains("uk_doctor_institution_change_requests_pending")
    }
}

@Repository
class JdbcDoctorInstitutionChangeRequestStore(
    private val jdbcTemplate: JdbcTemplate
) : DoctorInstitutionChangeRequestStore {
    override fun isCertifiedDoctor(doctorId: String): Boolean = count(
        "SELECT COUNT(*) FROM doctors WHERE id = ? AND is_verified = 1 AND deleted_at IS NULL",
        doctorId
    ) == 1L

    override fun isActiveInstitution(institutionId: String): Boolean = count(
        "SELECT COUNT(*) FROM institutions WHERE id = ? AND deleted_at IS NULL",
        institutionId
    ) == 1L

    override fun hasActiveRelationship(doctorId: String, institutionId: String): Boolean = count(
        """
        SELECT COUNT(*) FROM doctor_institutions
        WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
          AND revoked_at IS NULL AND deleted_at IS NULL
        """.trimIndent(),
        doctorId,
        institutionId
    ) > 0L

    override fun hasPending(doctorId: String, institutionId: String): Boolean = count(
        """
        SELECT COUNT(*) FROM doctor_institution_change_requests
        WHERE doctor_id = ? AND institution_id = ? AND status = 'PENDING'
        """.trimIndent(),
        doctorId,
        institutionId
    ) > 0L

    override fun create(
        doctorId: String,
        institutionId: String,
        action: DoctorInstitutionAction,
        requestNote: String
    ): DoctorInstitutionChangeRequestView {
        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO doctor_institution_change_requests
                (id, doctor_id, institution_id, action, status, request_note, submitted_by)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
            """.trimIndent(),
            id,
            doctorId,
            institutionId,
            action.name,
            requestNote,
            doctorId
        )
        return find(id, lock = false) ?: error("机构关系申请创建失败")
    }

    override fun listVisible(
        doctorId: String?,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<DoctorInstitutionChangeRequestView> {
        if (includeAll) {
            return jdbcTemplate.query(
                selectSql() + " ORDER BY r.submitted_at DESC, r.id DESC",
                rowMapper
            )
        }
        val institutionIds = managedInstitutionIds.sorted()
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        doctorId?.let {
            conditions += "r.doctor_id = ?"
            args += it
        }
        if (institutionIds.isNotEmpty()) {
            conditions += "r.institution_id IN (${institutionIds.joinToString(",") { "?" }})"
            args.addAll(institutionIds)
        }
        if (conditions.isEmpty()) return emptyList()
        return jdbcTemplate.query(
            selectSql() + " WHERE (${conditions.joinToString(" OR ")}) ORDER BY r.submitted_at DESC, r.id DESC",
            rowMapper,
            *args.toTypedArray()
        )
    }

    override fun exists(id: String): Boolean = count(
        "SELECT COUNT(*) FROM doctor_institution_change_requests WHERE id = ?",
        id
    ) == 1L

    override fun lock(id: String): DoctorInstitutionChangeRequestView? = find(id, lock = true)

    override fun changeStatus(
        id: String,
        status: DoctorInstitutionRequestStatus,
        actorId: String,
        reviewNote: String
    ): Boolean {
        val updated = if (status == DoctorInstitutionRequestStatus.WITHDRAWN) {
            jdbcTemplate.update(
                """
                UPDATE doctor_institution_change_requests
                SET status = 'WITHDRAWN', updated_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """.trimIndent(),
                id
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE doctor_institution_change_requests
                SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = NOW(), updated_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """.trimIndent(),
                status.name,
                reviewNote,
                actorId,
                id
            )
        }
        return updated == 1
    }

    private fun find(id: String, lock: Boolean): DoctorInstitutionChangeRequestView? = jdbcTemplate.query(
        selectSql() + " WHERE r.id = ?" + if (lock) " FOR UPDATE" else "",
        rowMapper,
        id
    ).firstOrNull()

    private fun selectSql(): String =
        """
        SELECT r.id, r.doctor_id, d.name AS doctor_name,
               r.institution_id, i.name AS institution_name,
               r.action, r.status, r.request_note, r.review_note,
               r.submitted_by, r.reviewed_by, r.submitted_at, r.reviewed_at,
               r.created_at, r.updated_at
        FROM doctor_institution_change_requests r
        JOIN doctors d ON d.id = r.doctor_id
        JOIN institutions i ON i.id = r.institution_id
        """.trimIndent()

    private val rowMapper = RowMapper { rs, _ ->
        DoctorInstitutionChangeRequestView(
            id = rs.getString("id"),
            doctorId = rs.getString("doctor_id"),
            doctorName = rs.getString("doctor_name"),
            institutionId = rs.getString("institution_id"),
            institutionName = rs.getString("institution_name"),
            action = DoctorInstitutionAction.valueOf(rs.getString("action")),
            status = DoctorInstitutionRequestStatus.valueOf(rs.getString("status")),
            requestNote = rs.getString("request_note"),
            reviewNote = rs.getString("review_note"),
            submittedBy = rs.getString("submitted_by"),
            reviewedBy = rs.getString("reviewed_by"),
            submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
            reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
        )
    }

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}

@Service
class DoctorInstitutionRelationshipService(
    private val jdbcTemplate: JdbcTemplate
) {
    fun requireActiveRelationshipForUpdate(doctorId: String, institutionId: String) {
        val relationshipId = jdbcTemplate.queryForList(
            """
            SELECT id FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
              AND revoked_at IS NULL AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            String::class.java,
            doctorId,
            institutionId
        ).firstOrNull()
        if (relationshipId == null) {
            throw AccessDeniedException("医生与机构的有效执业关系已失效")
        }
    }

    @Transactional
    fun approveJoin(doctorId: String, institutionId: String, reviewerId: String) {
        if (!lockCertifiedDoctor(doctorId)) {
            throw DoctorInstitutionRequestConflictException("医生身份已失效")
        }
        requireNotNull(lockActiveInstitution(institutionId)) { "机构不存在、未认证或已删除" }
        val activeRelationshipCount = count(
            """
            SELECT COUNT(*) FROM doctor_institutions
            WHERE doctor_id = ? AND status = 'APPROVED' AND revoked_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            doctorId
        )
        val primary = if (activeRelationshipCount == 0L) 1 else 0
        val existingId = jdbcTemplate.queryForList(
            """
            SELECT id FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ? FOR UPDATE
            """.trimIndent(),
            String::class.java,
            doctorId,
            institutionId
        ).firstOrNull()
        if (existingId == null) {
            jdbcTemplate.update(
                """
                INSERT INTO doctor_institutions
                    (id, doctor_id, institution_id, is_primary, status, confirmed_by, confirmed_at)
                VALUES (?, ?, ?, ?, 'APPROVED', ?, NOW())
                """.trimIndent(),
                UUID.randomUUID().toString(),
                doctorId,
                institutionId,
                primary,
                reviewerId
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE doctor_institutions
                SET is_primary = ?, status = 'APPROVED', confirmed_by = ?, confirmed_at = NOW(),
                    revoked_at = NULL, deleted_at = NULL, updated_at = NOW()
                WHERE id = ?
                """.trimIndent(),
                primary,
                reviewerId,
                existingId
            )
        }
        synchronizePrimary(doctorId)
    }

    @Transactional
    fun approveLeave(doctorId: String, institutionId: String, reviewerId: String) {
        revokeInternal(doctorId, institutionId, reviewerId, requireApproved = true)
    }

    @Transactional
    fun revoke(doctorId: String, institutionId: String, reviewerId: String?) {
        revokeInternal(doctorId, institutionId, reviewerId, requireApproved = false)
    }

    @Transactional
    fun revokeAll(doctorId: String, reviewerId: String?) {
        val institutionIds = jdbcTemplate.queryForList(
            """
            SELECT institution_id FROM doctor_institutions
            WHERE doctor_id = ? AND status <> 'REVOKED' AND deleted_at IS NULL
            ORDER BY institution_id FOR UPDATE
            """.trimIndent(),
            String::class.java,
            doctorId
        )
        institutionIds.forEach { revokeInternal(doctorId, it, reviewerId, requireApproved = false) }
    }

    private fun revokeInternal(
        doctorId: String,
        institutionId: String,
        reviewerId: String?,
        requireApproved: Boolean
    ) {
        val statusCondition = if (requireApproved) "status = 'APPROVED'" else "status <> 'REVOKED'"
        val updated = jdbcTemplate.update(
            """
            UPDATE doctor_institutions
            SET status = 'REVOKED', is_primary = 0, revoked_at = NOW(), updated_at = NOW()
            WHERE doctor_id = ? AND institution_id = ? AND $statusCondition AND deleted_at IS NULL
            """.trimIndent(),
            doctorId,
            institutionId
        )
        require(updated == 1) { "医生已不具备该机构的有效执业关系" }

        jdbcTemplate.update(
            """
            DELETE dp FROM doctor_projects dp
            JOIN institution_projects ip ON ip.id = dp.institution_project_id
            WHERE dp.doctor_id = ? AND ip.institution_id = ?
            """.trimIndent(),
            doctorId,
            institutionId
        )
        jdbcTemplate.update(
            """
            UPDATE doctor_institution_project_configs c
            JOIN institution_projects ip ON ip.id = c.institution_project_id
            SET c.deleted_at = NOW(), c.updated_at = NOW()
            WHERE c.doctor_id = ? AND ip.institution_id = ? AND c.deleted_at IS NULL
            """.trimIndent(),
            doctorId,
            institutionId
        )
        jdbcTemplate.update(
            """
            UPDATE doctor_project_change_requests
            SET status = 'WITHDRAWN', reviewed_by = ?, reviewed_at = NOW(),
                review_note = '医生已退出机构', updated_at = NOW()
            WHERE doctor_id = ? AND institution_id = ? AND status = 'PENDING'
            """.trimIndent(),
            reviewerId,
            doctorId,
            institutionId
        )
        jdbcTemplate.update(
            """
            UPDATE split_config_proposals sp
            JOIN institution_projects ip ON ip.id = sp.institution_project_id
            SET sp.status = 'WITHDRAWN', sp.decided_by = ?, sp.decided_at = NOW(),
                sp.decision_note = '医生已退出机构', sp.updated_at = NOW()
            WHERE sp.doctor_id = ? AND ip.institution_id = ? AND sp.status = 'PENDING'
            """.trimIndent(),
            reviewerId,
            doctorId,
            institutionId
        )
        jdbcTemplate.update(
            """
            UPDATE professional_project_requests
            SET status = 'REJECTED', reviewed_by = ?, reviewed_at = NOW(),
                review_note = '医生已退出机构', updated_at = NOW()
            WHERE request_type = 'INSTITUTION' AND doctor_id = ? AND institution_id = ? AND status = 'PENDING'
            """.trimIndent(),
            reviewerId,
            doctorId,
            institutionId
        )
        jdbcTemplate.update(
            """
            UPDATE auth_sessions
            SET active_role = 'USER', active_institution_id = NULL
            WHERE user_id = ? AND active_role = 'DOCTOR' AND active_institution_id = ?
            """.trimIndent(),
            doctorId,
            institutionId
        )
        synchronizePrimary(doctorId)
    }

    private fun lockCertifiedDoctor(doctorId: String): Boolean = jdbcTemplate.queryForList(
        """
        SELECT d.id FROM doctors d
        JOIN user_roles ur ON ur.user_id = d.id AND ur.role_code = 'DOCTOR' AND ur.status = 'ACTIVE'
        WHERE d.id = ? AND d.is_verified = 1 AND d.deleted_at IS NULL FOR UPDATE
        """.trimIndent(),
        String::class.java,
        doctorId
    ).isNotEmpty()

    private fun lockActiveInstitution(institutionId: String): String? = jdbcTemplate.queryForList(
        """
        SELECT name FROM institutions
        WHERE id = ? AND is_verified = 1 AND deleted_at IS NULL FOR UPDATE
        """.trimIndent(),
        String::class.java,
        institutionId
    ).firstOrNull()

    private fun synchronizePrimary(doctorId: String) {
        val institutionId = jdbcTemplate.queryForList(
            """
            SELECT institution_id FROM doctor_institutions
            WHERE doctor_id = ? AND status = 'APPROVED' AND revoked_at IS NULL AND deleted_at IS NULL
            ORDER BY is_primary DESC, created_at ASC, id ASC LIMIT 1 FOR UPDATE
            """.trimIndent(),
            String::class.java,
            doctorId
        ).firstOrNull()
        if (institutionId == null) {
            jdbcTemplate.update(
                "UPDATE doctors SET institution_id = '', institution_name = '' WHERE id = ?",
                doctorId
            )
            return
        }
        jdbcTemplate.update(
            "UPDATE doctor_institutions SET is_primary = 0 WHERE doctor_id = ? AND status = 'APPROVED'",
            doctorId
        )
        jdbcTemplate.update(
            """
            UPDATE doctor_institutions SET is_primary = 1
            WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
            """.trimIndent(),
            doctorId,
            institutionId
        )
        val institutionName = jdbcTemplate.queryForList(
            "SELECT name FROM institutions WHERE id = ?",
            String::class.java,
            institutionId
        ).first()
        syncDoctorInstitution(doctorId, institutionId, institutionName)
    }

    private fun syncDoctorInstitution(doctorId: String, institutionId: String, institutionName: String) {
        jdbcTemplate.update(
            "UPDATE doctors SET institution_id = ?, institution_name = ? WHERE id = ?",
            institutionId,
            institutionName,
            doctorId
        )
    }

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}
