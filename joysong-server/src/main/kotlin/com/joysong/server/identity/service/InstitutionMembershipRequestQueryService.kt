package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service

interface InstitutionMembershipRequestQueryStore {
    fun listOwned(
        requestType: MembershipRequestType,
        applicantId: String
    ): List<InstitutionMembershipRequestView>

    fun listReviewable(
        requestType: MembershipRequestType,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<InstitutionMembershipRequestView>
}

@Service
class InstitutionMembershipRequestQueryService(
    private val store: InstitutionMembershipRequestQueryStore
) {
    fun listOwned(actor: ManagementActor): List<InstitutionMembershipRequestView> {
        val rows = ownedRows(actor)
        if (rows == null) {
            throw AccessDeniedException("只有本人已激活的医生或顾问可以查看机构关系申请")
        }
        return sorted(rows)
    }

    fun listReviewable(actor: ManagementActor): List<InstitutionMembershipRequestView> {
        if (!actor.isAdmin && actor.managedInstitutionIds.isEmpty()) {
            throw AccessDeniedException("无权审核机构关系申请")
        }
        return sorted(reviewableRows(actor))
    }

    fun listCompatibility(actor: ManagementActor): List<InstitutionMembershipRequestView> {
        val owned = ownedRows(actor).orEmpty()
        val reviewable = if (actor.isAdmin || actor.managedInstitutionIds.isNotEmpty()) {
            reviewableRows(actor)
        } else {
            emptyList()
        }
        return sorted((owned + reviewable).distinctBy { it.requestType to it.id })
    }

    private fun ownedRows(actor: ManagementActor): List<InstitutionMembershipRequestView>? {
        val identities = buildList {
            if (DOCTOR_ROLE in actor.activeRoles && actor.doctorId == actor.userId) {
                add(MembershipRequestType.DOCTOR to actor.userId)
            }
            if (CONSULTANT_ROLE in actor.activeRoles) {
                add(MembershipRequestType.CONSULTANT to actor.userId)
            }
        }
        if (identities.isEmpty()) return null
        return identities.flatMap { (type, applicantId) -> store.listOwned(type, applicantId) }
    }

    private fun reviewableRows(actor: ManagementActor): List<InstitutionMembershipRequestView> =
        MembershipRequestType.entries.flatMap { type ->
            store.listReviewable(type, actor.managedInstitutionIds, actor.isAdmin)
        }

    private fun sorted(rows: List<InstitutionMembershipRequestView>) = rows.sortedWith(
        compareByDescending<InstitutionMembershipRequestView> { it.submittedAt }
            .thenByDescending { it.id }
    )

    private companion object {
        const val DOCTOR_ROLE = "DOCTOR"
        const val CONSULTANT_ROLE = "CONSULTANT"
    }
}

@Repository
class JdbcInstitutionMembershipRequestQueryStore(
    private val jdbcTemplate: JdbcTemplate
) : InstitutionMembershipRequestQueryStore {
    override fun listOwned(
        requestType: MembershipRequestType,
        applicantId: String
    ): List<InstitutionMembershipRequestView> = jdbcTemplate.query(
        selectSql(requestType) + " WHERE request.${applicantColumn(requestType)} = ?" + ORDER,
        rowMapper,
        applicantId
    )

    override fun listReviewable(
        requestType: MembershipRequestType,
        managedInstitutionIds: Set<String>,
        includeAll: Boolean
    ): List<InstitutionMembershipRequestView> {
        if (includeAll) return jdbcTemplate.query(selectSql(requestType) + ORDER, rowMapper)
        val ids = managedInstitutionIds.sorted()
        if (ids.isEmpty()) return emptyList()
        return jdbcTemplate.query(
            selectSql(requestType) +
                " WHERE request.institution_id IN (${ids.joinToString(",") { "?" }})" + ORDER,
            rowMapper,
            *ids.toTypedArray()
        )
    }

    private fun selectSql(requestType: MembershipRequestType): String = when (requestType) {
        MembershipRequestType.DOCTOR ->
            """
            SELECT request.id, 'DOCTOR' AS request_type,
                   request.doctor_id AS applicant_id, applicant.name AS applicant_name,
                   request.institution_id, institution.name AS institution_name,
                   request.action, request.status,
                   COALESCE(relationship.status, 'NONE') AS relationship_status,
                   request.request_note, request.review_note,
                   request.submitted_by, request.reviewed_by,
                   request.submitted_at, request.reviewed_at,
                   request.created_at, request.updated_at
            FROM doctor_institution_change_requests request
            JOIN doctors applicant ON applicant.id = request.doctor_id
            JOIN institutions institution ON institution.id = request.institution_id
            LEFT JOIN doctor_institutions relationship
              ON relationship.doctor_id = request.doctor_id
             AND relationship.institution_id = request.institution_id
             AND relationship.status = 'APPROVED'
             AND relationship.revoked_at IS NULL
             AND relationship.deleted_at IS NULL
            """.trimIndent()

        MembershipRequestType.CONSULTANT ->
            """
            SELECT request.id, 'CONSULTANT' AS request_type,
                   request.consultant_id AS applicant_id,
                   COALESCE(NULLIF(TRIM(applicant.nickname), ''),
                            NULLIF(TRIM(applicant.phone), ''),
                            NULLIF(TRIM(applicant.email), ''), '顾问') AS applicant_name,
                   request.institution_id, institution.name AS institution_name,
                   request.action, request.status,
                   COALESCE(relationship.status, 'NONE') AS relationship_status,
                   request.request_note, request.review_note,
                   request.submitted_by, request.reviewed_by,
                   request.submitted_at, request.reviewed_at,
                   request.created_at, request.updated_at
            FROM consultant_institution_change_requests request
            JOIN users applicant ON applicant.id = request.consultant_id
            JOIN institutions institution ON institution.id = request.institution_id
            LEFT JOIN institution_memberships relationship
              ON relationship.user_id = request.consultant_id
             AND relationship.institution_id = request.institution_id
             AND relationship.member_role = 'CONSULTANT'
             AND relationship.status = 'APPROVED'
             AND relationship.revoked_at IS NULL
            """.trimIndent()
    }

    private fun applicantColumn(requestType: MembershipRequestType) = when (requestType) {
        MembershipRequestType.DOCTOR -> "doctor_id"
        MembershipRequestType.CONSULTANT -> "consultant_id"
    }

    private val rowMapper = RowMapper { rs, _ ->
        InstitutionMembershipRequestView(
            id = rs.getString("id"),
            requestType = MembershipRequestType.valueOf(rs.getString("request_type")),
            applicantId = rs.getString("applicant_id"),
            applicantName = rs.getString("applicant_name"),
            institutionId = rs.getString("institution_id"),
            institutionName = rs.getString("institution_name"),
            action = rs.getString("action"),
            status = rs.getString("status"),
            relationshipStatus = rs.getString("relationship_status"),
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

    private companion object {
        const val ORDER = " ORDER BY request.submitted_at DESC, request.id DESC"
    }
}
