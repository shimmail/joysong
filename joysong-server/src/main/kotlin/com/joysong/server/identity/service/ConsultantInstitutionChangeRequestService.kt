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

interface ConsultantInstitutionChangeRequestStore {
    fun create(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction,
        requestNote: String
    ): ConsultantInstitutionChangeRequestView

    fun listOwned(consultantId: String): List<ConsultantInstitutionChangeRequestView>
    fun listReviewable(
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<ConsultantInstitutionChangeRequestView>

    fun lock(id: String): ConsultantInstitutionChangeRequestView?
    fun changeStatus(
        id: String,
        status: ConsultantInstitutionRequestStatus,
        actorId: String,
        reviewNote: String
    ): Boolean

    fun hasPending(consultantId: String, institutionId: String): Boolean
    fun hasActiveRelationship(consultantId: String, institutionId: String): Boolean
}

@Service
class ConsultantInstitutionChangeRequestService(
    private val store: ConsultantInstitutionChangeRequestStore,
    private val relationships: ConsultantInstitutionRelationshipOperations
) {
    @Transactional
    fun submit(
        actor: ManagementActor,
        institutionId: String,
        action: ConsultantInstitutionAction,
        requestNote: String
    ): ConsultantInstitutionChangeRequestView {
        requireConsultant(actor)
        val normalizedInstitutionId = institutionId.trim().also {
            require(it.isNotEmpty()) { "机构不能为空" }
        }
        val normalizedNote = requestNote.trim().also {
            require(it.length <= MAX_NOTE_LENGTH) { "申请说明不能超过1000个字符" }
        }
        relationships.requireActiveInstitution(normalizedInstitutionId)
        val activeRelationship = store.hasActiveRelationship(actor.userId, normalizedInstitutionId)
        when (action) {
            ConsultantInstitutionAction.JOIN -> if (activeRelationship) conflict("顾问已加入该机构")
            ConsultantInstitutionAction.LEAVE -> if (!activeRelationship) conflict("顾问尚未加入该机构")
        }
        if (store.hasPending(actor.userId, normalizedInstitutionId)) pendingConflict()
        return try {
            store.create(actor.userId, normalizedInstitutionId, action, normalizedNote)
        } catch (error: DuplicateKeyException) {
            if (error.isPendingRequestConflict()) pendingConflict()
            throw error
        }
    }

    fun listOwned(actor: ManagementActor): List<ConsultantInstitutionChangeRequestView> {
        requireConsultant(actor)
        return store.listOwned(actor.userId)
    }

    fun listReviewable(actor: ManagementActor): List<ConsultantInstitutionChangeRequestView> {
        if (!actor.isAdmin && actor.managedInstitutionIds.isEmpty()) {
            throw AccessDeniedException("无权审核机构关系申请")
        }
        return store.listReviewable(actor.managedInstitutionIds, actor.isAdmin)
    }

    @Transactional
    fun withdraw(actor: ManagementActor, id: String): ConsultantInstitutionChangeRequestView {
        val request = locked(id)
        if (request.consultantId != actor.userId || request.submittedBy != actor.userId) {
            throw AccessDeniedException("只能撤回本人提交的关系申请")
        }
        if (request.status != ConsultantInstitutionRequestStatus.PENDING) closedConflict()
        if (!store.changeStatus(request.id, ConsultantInstitutionRequestStatus.WITHDRAWN, actor.userId, "")) {
            closedConflict()
        }
        return request.copy(
            status = ConsultantInstitutionRequestStatus.WITHDRAWN,
            updatedAt = LocalDateTime.now()
        )
    }

    @Transactional
    fun review(
        actor: ManagementActor,
        id: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): ConsultantInstitutionChangeRequestView {
        val request = locked(id)
        if (!actor.isAdmin && request.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("无权审核其他机构的关系申请")
        }
        if (request.status != ConsultantInstitutionRequestStatus.PENDING) closedConflict()
        val normalizedReviewNote = reviewNote.trim().also {
            require(it.length <= MAX_NOTE_LENGTH) { "审核意见不能超过1000个字符" }
        }
        if (decision == MembershipRequestDecision.REJECTED) {
            require(normalizedReviewNote.isNotEmpty()) { "驳回时必须填写审核意见" }
            relationships.validateForReview(request.consultantId, request.institutionId, request.action)
        } else {
            relationships.applyApproved(
                request.consultantId,
                request.institutionId,
                request.action,
                actor.userId
            )
        }
        val status = ConsultantInstitutionRequestStatus.valueOf(decision.name)
        if (!store.changeStatus(request.id, status, actor.userId, normalizedReviewNote)) closedConflict()
        val now = LocalDateTime.now()
        return request.copy(
            status = status,
            reviewNote = normalizedReviewNote,
            reviewedBy = actor.userId,
            reviewedAt = now,
            updatedAt = now
        )
    }

    private fun requireConsultant(actor: ManagementActor) {
        if (CONSULTANT_ROLE !in actor.activeRoles) {
            throw AccessDeniedException("只有本人已激活的顾问可以提交机构关系申请")
        }
    }

    private fun locked(id: String): ConsultantInstitutionChangeRequestView {
        val normalizedId = id.trim().also { require(it.isNotEmpty()) { "关系申请不能为空" } }
        return store.lock(normalizedId)
            ?: throw ConsultantInstitutionRequestNotFoundException("顾问机构关系申请不存在")
    }

    private fun DuplicateKeyException.isPendingRequestConflict(): Boolean = generateSequence<Throwable>(this) {
        it.cause
    }.filterIsInstance<SQLException>().any { error ->
        error.errorCode == MYSQL_DUPLICATE_KEY && error.sqlState == INTEGRITY_SQL_STATE &&
            error.message.orEmpty().contains(PENDING_UNIQUE_KEY)
    }

    private fun pendingConflict(): Nothing = conflict("该机构已有待处理的关系申请")

    private fun closedConflict(): Nothing = conflict("机构关系申请已被其他操作处理")

    private fun conflict(message: String): Nothing = throw ConsultantInstitutionRequestConflictException(message)

    private companion object {
        const val CONSULTANT_ROLE = "CONSULTANT"
        const val MAX_NOTE_LENGTH = 1000
        const val MYSQL_DUPLICATE_KEY = 1062
        const val INTEGRITY_SQL_STATE = "23000"
        const val PENDING_UNIQUE_KEY = "uk_consultant_institution_change_requests_pending"
    }
}

@Repository
class JdbcConsultantInstitutionChangeRequestStore(
    private val jdbcTemplate: JdbcTemplate
) : ConsultantInstitutionChangeRequestStore {
    override fun create(
        consultantId: String,
        institutionId: String,
        action: ConsultantInstitutionAction,
        requestNote: String
    ): ConsultantInstitutionChangeRequestView {
        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO consultant_institution_change_requests
                (id, consultant_id, institution_id, action, status, request_note, submitted_by)
            VALUES (?, ?, ?, ?, 'PENDING', ?, ?)
            """.trimIndent(),
            id,
            consultantId,
            institutionId,
            action.name,
            requestNote,
            consultantId
        )
        return find(id, lock = false) ?: error("顾问机构关系申请创建失败")
    }

    override fun listOwned(consultantId: String): List<ConsultantInstitutionChangeRequestView> = jdbcTemplate.query(
        selectSql() + " WHERE r.consultant_id = ? ORDER BY r.submitted_at DESC, r.id DESC",
        rowMapper,
        consultantId
    )

    override fun listReviewable(
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<ConsultantInstitutionChangeRequestView> {
        if (includeAll) {
            return jdbcTemplate.query(selectSql() + REVIEW_ORDER, rowMapper)
        }
        val ids = managedInstitutionIds.sorted()
        if (ids.isEmpty()) return emptyList()
        return jdbcTemplate.query(
            selectSql() + " WHERE r.institution_id IN (${ids.joinToString(",") { "?" }})$REVIEW_ORDER",
            rowMapper,
            *ids.toTypedArray()
        )
    }

    override fun lock(id: String): ConsultantInstitutionChangeRequestView? = find(id, lock = true)

    override fun changeStatus(
        id: String,
        status: ConsultantInstitutionRequestStatus,
        actorId: String,
        reviewNote: String
    ): Boolean {
        val updated = if (status == ConsultantInstitutionRequestStatus.WITHDRAWN) {
            jdbcTemplate.update(
                """
                UPDATE consultant_institution_change_requests
                SET status = 'WITHDRAWN', updated_at = NOW()
                WHERE id = ? AND status = 'PENDING'
                """.trimIndent(),
                id
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE consultant_institution_change_requests
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

    override fun hasPending(consultantId: String, institutionId: String): Boolean = count(
        """
        SELECT COUNT(*) FROM consultant_institution_change_requests
        WHERE consultant_id = ? AND institution_id = ? AND status = 'PENDING'
        """.trimIndent(),
        consultantId,
        institutionId
    ) > 0L

    override fun hasActiveRelationship(consultantId: String, institutionId: String): Boolean = count(
        """
        SELECT COUNT(*) FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
          AND status = 'APPROVED' AND revoked_at IS NULL
        """.trimIndent(),
        consultantId,
        institutionId
    ) > 0L

    private fun find(id: String, lock: Boolean): ConsultantInstitutionChangeRequestView? = jdbcTemplate.query(
        selectSql() + " WHERE r.id = ?" + if (lock) " FOR UPDATE" else "",
        rowMapper,
        id
    ).firstOrNull()

    private fun selectSql() =
        """
        SELECT r.id, r.consultant_id,
               COALESCE(NULLIF(u.nickname, ''), u.phone, u.email, u.id) AS consultant_name,
               r.institution_id, i.name AS institution_name,
               r.action, r.status, r.request_note, r.review_note,
               r.submitted_by, r.reviewed_by, r.submitted_at, r.reviewed_at,
               r.created_at, r.updated_at
        FROM consultant_institution_change_requests r
        JOIN users u ON u.id = r.consultant_id
        JOIN institutions i ON i.id = r.institution_id
        """.trimIndent()

    private val rowMapper = RowMapper { rs, _ ->
        ConsultantInstitutionChangeRequestView(
            id = rs.getString("id"),
            consultantId = rs.getString("consultant_id"),
            consultantName = rs.getString("consultant_name"),
            institutionId = rs.getString("institution_id"),
            institutionName = rs.getString("institution_name"),
            action = ConsultantInstitutionAction.valueOf(rs.getString("action")),
            status = ConsultantInstitutionRequestStatus.valueOf(rs.getString("status")),
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

    private companion object {
        const val REVIEW_ORDER = " ORDER BY r.submitted_at DESC, r.id DESC"
    }
}
