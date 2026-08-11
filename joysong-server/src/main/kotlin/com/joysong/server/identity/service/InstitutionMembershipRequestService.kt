package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

enum class MembershipRequestType {
    DOCTOR,
    CONSULTANT;

    companion object {
        fun parse(value: String): MembershipRequestType = entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw IllegalArgumentException("不支持的加入申请类型")
    }
}

enum class MembershipRequestDecision {
    APPROVED,
    REJECTED;

    companion object {
        fun parse(value: String): MembershipRequestDecision = entries.firstOrNull { it.name == value.trim().uppercase() }
            ?: throw IllegalArgumentException("不支持的审核决定")
    }
}

data class InstitutionMembershipRequestView(
    val id: String,
    val requestType: MembershipRequestType,
    val userId: String,
    val institutionId: String,
    val status: String,
    val requestNote: String,
    val reviewNote: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val deleted: Boolean = false
)

interface InstitutionMembershipRequestStore {
    fun find(
        type: MembershipRequestType,
        userId: String,
        institutionId: String
    ): InstitutionMembershipRequestView?

    fun create(
        type: MembershipRequestType,
        userId: String,
        institutionId: String,
        requestNote: String
    ): InstitutionMembershipRequestView

    fun resubmit(
        request: InstitutionMembershipRequestView,
        requestNote: String
    ): InstitutionMembershipRequestView

    fun listVisible(userId: String, managedInstitutionIds: Set<String>): List<InstitutionMembershipRequestView>
    fun listAll(): List<InstitutionMembershipRequestView>

    fun findById(type: MembershipRequestType, id: String): InstitutionMembershipRequestView?

    fun review(
        request: InstitutionMembershipRequestView,
        reviewerId: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): InstitutionMembershipRequestView
}

@Service
class InstitutionMembershipRequestService(
    private val store: InstitutionMembershipRequestStore,
    private val relationshipService: DoctorInstitutionRelationshipService
) {
    @Transactional
    fun submit(
        actor: ManagementActor,
        type: MembershipRequestType,
        institutionId: String,
        requestNote: String
    ): InstitutionMembershipRequestView {
        require(type != MembershipRequestType.DOCTOR) {
            "医生机构关系申请请使用新的关系申请接口"
        }
        requireApplicantRole(actor, type)
        val normalizedInstitutionId = institutionId.trim()
        require(normalizedInstitutionId.isNotEmpty()) { "机构不能为空" }
        val normalizedRequestNote = requestNote.trim()
        val existing = store.find(type, actor.userId, normalizedInstitutionId)
            ?: return store.create(type, actor.userId, normalizedInstitutionId, normalizedRequestNote)
        require(existing.deleted || existing.status in RESUBMITTABLE_STATUSES) { "该机构加入申请当前不可重新提交" }
        return store.resubmit(existing, normalizedRequestNote)
    }

    fun list(actor: ManagementActor): List<InstitutionMembershipRequestView> = if (actor.isAdmin) {
        store.listAll()
    } else {
        store.listVisible(actor.userId, actor.managedInstitutionIds)
    }

    @Transactional
    fun review(
        actor: ManagementActor,
        type: MembershipRequestType,
        id: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): InstitutionMembershipRequestView {
        val request = store.findById(type, id.trim())
            ?: throw IllegalArgumentException("机构加入申请不存在")
        if (!actor.isAdmin && request.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("无权审核其他机构的加入申请")
        }
        require(request.status == PENDING) { "只有待审核的机构加入申请可以审核" }
        val normalizedReviewNote = reviewNote.trim()
        if (decision == MembershipRequestDecision.REJECTED) {
            require(normalizedReviewNote.isNotEmpty()) { "驳回时必须填写审核意见" }
        }
        val reviewed = store.review(request, actor.userId, decision, normalizedReviewNote)
        if (type == MembershipRequestType.DOCTOR && decision == MembershipRequestDecision.APPROVED) {
            relationshipService.approveJoin(request.userId, request.institutionId, actor.userId)
        }
        return reviewed
    }

    private fun requireApplicantRole(actor: ManagementActor, type: MembershipRequestType) {
        val allowed = when (type) {
            MembershipRequestType.DOCTOR -> "DOCTOR" in actor.activeRoles && actor.doctorId == actor.userId
            MembershipRequestType.CONSULTANT -> "CONSULTANT" in actor.activeRoles
        }
        if (!allowed) throw AccessDeniedException("只能以本人已认证的医生或顾问身份提交机构加入申请")
    }

    private companion object {
        const val PENDING = "PENDING"
        val RESUBMITTABLE_STATUSES = setOf("REJECTED")
    }
}

@Repository
class JdbcInstitutionMembershipRequestStore(
    private val jdbcTemplate: JdbcTemplate
) : InstitutionMembershipRequestStore {
    override fun find(
        type: MembershipRequestType,
        userId: String,
        institutionId: String
    ): InstitutionMembershipRequestView? = jdbcTemplate.query(
        selectSql(type) + " AND ${userColumn(type)} = ? AND institution_id = ?",
        rowMapper,
        userId,
        institutionId
    ).firstOrNull()

    override fun create(
        type: MembershipRequestType,
        userId: String,
        institutionId: String,
        requestNote: String
    ): InstitutionMembershipRequestView {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now()
        when (type) {
            MembershipRequestType.DOCTOR -> jdbcTemplate.update(
                """
                INSERT INTO doctor_institutions
                    (id, doctor_id, institution_id, is_primary, status, request_note, review_note, created_at, updated_at)
                VALUES (?, ?, ?, 0, 'PENDING', ?, '', ?, ?)
                """.trimIndent(),
                id,
                userId,
                institutionId,
                requestNote,
                now,
                now
            )

            MembershipRequestType.CONSULTANT -> jdbcTemplate.update(
                """
                INSERT INTO institution_memberships
                    (id, user_id, institution_id, member_role, status, request_note, review_note, created_at, updated_at)
                VALUES (?, ?, ?, 'CONSULTANT', 'PENDING', ?, '', ?, ?)
                """.trimIndent(),
                id,
                userId,
                institutionId,
                requestNote,
                now,
                now
            )
        }
        return InstitutionMembershipRequestView(
            id = id,
            requestType = type,
            userId = userId,
            institutionId = institutionId,
            status = "PENDING",
            requestNote = requestNote,
            reviewNote = "",
            createdAt = now,
            updatedAt = now
        )
    }

    override fun resubmit(
        request: InstitutionMembershipRequestView,
        requestNote: String
    ): InstitutionMembershipRequestView {
        val table = table(request.requestType)
        val restoreDeleted = if (request.requestType == MembershipRequestType.DOCTOR) ", deleted_at = NULL" else ""
        val updated = jdbcTemplate.update(
            """
            UPDATE $table
            SET status = 'PENDING', request_note = ?, review_note = '',
                confirmed_by = NULL, confirmed_at = NULL, revoked_at = NULL$restoreDeleted,
                updated_at = NOW()
            WHERE id = ?
            """.trimIndent(),
            requestNote,
            request.id
        )
        check(updated == 1) { "机构加入申请已被其他操作处理" }
        return request.copy(
            status = "PENDING",
            requestNote = requestNote,
            reviewNote = "",
            updatedAt = LocalDateTime.now()
        )
    }

    override fun listVisible(
        userId: String,
        managedInstitutionIds: Set<String>
    ): List<InstitutionMembershipRequestView> {
        val managedIds = managedInstitutionIds.sorted()
        val institutionFilter = if (managedIds.isEmpty()) "" else {
            " OR institution_id IN (${managedIds.joinToString(",") { "?" }})"
        }
        val args = mutableListOf<Any>(userId).apply { addAll(managedIds) }
        return jdbcTemplate.query(
            """
            SELECT *
            FROM (
                ${selectSql(MembershipRequestType.DOCTOR)}
                UNION ALL
                ${selectSql(MembershipRequestType.CONSULTANT)}
            ) membership_requests
            WHERE user_id = ?$institutionFilter
            ORDER BY created_at DESC, id DESC
            """.trimIndent(),
            rowMapper,
            *args.toTypedArray()
        )
    }

    override fun listAll(): List<InstitutionMembershipRequestView> = jdbcTemplate.query(
        """
        SELECT *
        FROM (
            ${selectSql(MembershipRequestType.DOCTOR)}
            UNION ALL
            ${selectSql(MembershipRequestType.CONSULTANT)}
        ) membership_requests
        ORDER BY created_at DESC, id DESC
        """.trimIndent(),
        rowMapper
    )

    override fun findById(
        type: MembershipRequestType,
        id: String
    ): InstitutionMembershipRequestView? = jdbcTemplate.query(
        selectSql(type) + " AND id = ? FOR UPDATE",
        rowMapper,
        id
    ).firstOrNull()

    override fun review(
        request: InstitutionMembershipRequestView,
        reviewerId: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): InstitutionMembershipRequestView {
        val updated = jdbcTemplate.update(
            """
            UPDATE ${table(request.requestType)}
            SET status = ?, review_note = ?,
                confirmed_by = CASE WHEN ? = 'APPROVED' THEN ? ELSE NULL END,
                confirmed_at = CASE WHEN ? = 'APPROVED' THEN NOW() ELSE NULL END,
                updated_at = NOW()
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            decision.name,
            reviewNote,
            decision.name,
            reviewerId,
            decision.name,
            request.id
        )
        check(updated == 1) { "机构加入申请已被其他审核人处理" }
        return request.copy(
            status = decision.name,
            reviewNote = reviewNote,
            updatedAt = LocalDateTime.now()
        )
    }

    private fun selectSql(type: MembershipRequestType): String = when (type) {
        MembershipRequestType.DOCTOR ->
            """
            SELECT id, 'DOCTOR' AS request_type, doctor_id AS user_id, institution_id,
                   status, request_note, review_note, created_at, updated_at,
                   (deleted_at IS NOT NULL) AS is_deleted
            FROM doctor_institutions
            WHERE 1 = 1
            """.trimIndent()

        MembershipRequestType.CONSULTANT ->
            """
            SELECT id, 'CONSULTANT' AS request_type, user_id, institution_id,
                   status, request_note, review_note, created_at, updated_at,
                   FALSE AS is_deleted
            FROM institution_memberships
            WHERE member_role = 'CONSULTANT'
            """.trimIndent()
    }

    private fun table(type: MembershipRequestType) = when (type) {
        MembershipRequestType.DOCTOR -> "doctor_institutions"
        MembershipRequestType.CONSULTANT -> "institution_memberships"
    }

    private fun userColumn(type: MembershipRequestType) = when (type) {
        MembershipRequestType.DOCTOR -> "doctor_id"
        MembershipRequestType.CONSULTANT -> "user_id"
    }

    private val rowMapper = RowMapper { rs, _ ->
        InstitutionMembershipRequestView(
            id = rs.getString("id"),
            requestType = MembershipRequestType.valueOf(rs.getString("request_type")),
            userId = rs.getString("user_id"),
            institutionId = rs.getString("institution_id"),
            status = rs.getString("status"),
            requestNote = rs.getString("request_note"),
            reviewNote = rs.getString("review_note"),
            createdAt = rs.getTimestamp("created_at").toLocalDateTime(),
            updatedAt = rs.getTimestamp("updated_at").toLocalDateTime(),
            deleted = rs.getBoolean("is_deleted")
        )
    }
}
