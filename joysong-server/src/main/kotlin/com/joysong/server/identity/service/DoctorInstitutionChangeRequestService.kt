package com.joysong.server.identity.service

import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    fun hasActiveRelationship(doctorId: String, institutionId: String): Boolean
    fun hasPending(doctorId: String, institutionId: String): Boolean
    fun create(
        doctorId: String,
        institutionId: String,
        action: DoctorInstitutionAction,
        requestNote: String
    ): DoctorInstitutionChangeRequestView

    fun listVisible(doctorId: String, managedInstitutionIds: Set<String>): List<DoctorInstitutionChangeRequestView>
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
    private val store: DoctorInstitutionChangeRequestStore
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
        val activeRelationship = store.hasActiveRelationship(doctorId, normalizedInstitutionId)
        when (action) {
            DoctorInstitutionAction.JOIN -> require(!activeRelationship) { "医生已加入该机构" }
            DoctorInstitutionAction.LEAVE -> require(activeRelationship) { "医生尚未加入该机构" }
        }
        if (store.hasPending(doctorId, normalizedInstitutionId)) duplicatePending()
        return try {
            store.create(doctorId, normalizedInstitutionId, action, requestNote.trim())
        } catch (_: DuplicateKeyException) {
            duplicatePending()
        }
    }

    fun list(actor: ManagementActor): List<DoctorInstitutionChangeRequestView> =
        store.listVisible(actor.doctorId ?: actor.userId, actor.managedInstitutionIds)

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
}

@Repository
class JdbcDoctorInstitutionChangeRequestStore(
    private val jdbcTemplate: JdbcTemplate
) : DoctorInstitutionChangeRequestStore {
    override fun isCertifiedDoctor(doctorId: String): Boolean = count(
        "SELECT COUNT(*) FROM doctors WHERE id = ? AND is_verified = 1 AND deleted_at IS NULL",
        doctorId
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
        doctorId: String,
        managedInstitutionIds: Set<String>
    ): List<DoctorInstitutionChangeRequestView> {
        val institutionIds = managedInstitutionIds.sorted()
        val managedFilter = if (institutionIds.isEmpty()) "" else {
            " OR r.institution_id IN (${institutionIds.joinToString(",") { "?" }})"
        }
        val args = mutableListOf<Any>(doctorId).apply { addAll(institutionIds) }
        return jdbcTemplate.query(
            selectSql() + " WHERE (r.doctor_id = ?$managedFilter) ORDER BY r.submitted_at DESC, r.id DESC",
            rowMapper,
            *args.toTypedArray()
        )
    }

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
