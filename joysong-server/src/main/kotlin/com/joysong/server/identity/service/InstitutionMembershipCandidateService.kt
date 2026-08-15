package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class InstitutionMembershipCandidateView(
    val id: String,
    val name: String
)

data class InstitutionMembershipCandidatePage(
    val items: List<InstitutionMembershipCandidateView>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

interface InstitutionMembershipCandidateStore {
    fun search(
        requestType: MembershipRequestType,
        applicantId: String,
        action: InstitutionMembershipAction,
        queryPattern: String?,
        offset: Int,
        limit: Int
    ): List<InstitutionMembershipCandidateView>
}

@Service
class InstitutionMembershipCandidateService(
    private val store: InstitutionMembershipCandidateStore,
    private val doctorRelationships: DoctorInstitutionRelationshipOperations
) {
    @Transactional
    fun list(
        actor: ManagementActor,
        requestType: MembershipRequestType,
        action: InstitutionMembershipAction,
        query: String,
        offset: Int,
        limit: Int
    ): InstitutionMembershipCandidatePage {
        require(offset >= 0) { "offset不能小于0" }
        require(limit > 0) { "limit必须大于0" }
        val boundedLimit = limit.coerceAtMost(MAX_LIMIT)
        val applicantId = applicantId(actor, requestType)
        if (requestType == MembershipRequestType.DOCTOR &&
            !doctorRelationships.hasActiveCertifiedDoctorForUpdate(applicantId)
        ) {
            throw AccessDeniedException("只有本人已认证且激活的医生可以查询候选机构")
        }
        val queryPattern = query.trim().takeIf { it.isNotEmpty() }?.let(::likePattern)
        val rows = store.search(
            requestType,
            applicantId,
            action,
            queryPattern,
            offset,
            boundedLimit + 1
        )
        return InstitutionMembershipCandidatePage(
            items = rows.take(boundedLimit),
            offset = offset,
            limit = boundedLimit,
            hasMore = rows.size > boundedLimit
        )
    }

    private fun applicantId(actor: ManagementActor, requestType: MembershipRequestType): String =
        when (requestType) {
            MembershipRequestType.DOCTOR -> {
                if (DOCTOR_ROLE !in actor.activeRoles || actor.doctorId != actor.userId) {
                    throw AccessDeniedException("只有本人已激活的医生可以查询候选机构")
                }
                actor.userId
            }

            MembershipRequestType.CONSULTANT -> {
                if (CONSULTANT_ROLE !in actor.activeRoles) {
                    throw AccessDeniedException("只有本人已激活的顾问可以查询候选机构")
                }
                actor.userId
            }
        }

    private fun likePattern(query: String): String = "%" + query
        .replace("\\", "\\\\")
        .replace("%", "\\%")
        .replace("_", "\\_") + "%"

    private companion object {
        const val MAX_LIMIT = 100
        const val DOCTOR_ROLE = "DOCTOR"
        const val CONSULTANT_ROLE = "CONSULTANT"
    }
}

@Repository
class JdbcInstitutionMembershipCandidateStore(
    private val jdbcTemplate: JdbcTemplate
) : InstitutionMembershipCandidateStore {
    override fun search(
        requestType: MembershipRequestType,
        applicantId: String,
        action: InstitutionMembershipAction,
        queryPattern: String?,
        offset: Int,
        limit: Int
    ): List<InstitutionMembershipCandidateView> {
        val args = mutableListOf<Any>(applicantId, applicantId)
        val queryFilter = if (queryPattern == null) {
            ""
        } else {
            args += queryPattern
            " AND LOWER(institution.name) LIKE LOWER(?) ESCAPE '\\\\'"
        }
        args += limit
        args += offset
        return jdbcTemplate.query(
            eligibilitySql(requestType, action) + queryFilter + ORDER_AND_PAGE,
            rowMapper,
            *args.toTypedArray()
        )
    }

    private fun eligibilitySql(
        requestType: MembershipRequestType,
        action: InstitutionMembershipAction
    ): String = when (requestType to action) {
        MembershipRequestType.DOCTOR to InstitutionMembershipAction.JOIN ->
            """
            SELECT institution.id, institution.name
            FROM institutions institution
            WHERE institution.is_verified = 1
              AND institution.deleted_at IS NULL
              AND NULLIF(TRIM(institution.name), '') IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM doctor_institutions relationship
                  WHERE relationship.doctor_id = ?
                    AND relationship.institution_id = institution.id
                    AND relationship.status = 'APPROVED'
                    AND relationship.revoked_at IS NULL
                    AND relationship.deleted_at IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1 FROM doctor_institution_change_requests pending
                  WHERE pending.doctor_id = ?
                    AND pending.institution_id = institution.id
                    AND pending.status = 'PENDING'
              )
            """.trimIndent()

        MembershipRequestType.DOCTOR to InstitutionMembershipAction.LEAVE ->
            """
            SELECT institution.id, institution.name
            FROM institutions institution
            JOIN doctor_institutions relationship
              ON relationship.institution_id = institution.id
             AND relationship.doctor_id = ?
             AND relationship.status = 'APPROVED'
             AND relationship.revoked_at IS NULL
             AND relationship.deleted_at IS NULL
            WHERE NULLIF(TRIM(institution.name), '') IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM doctor_institution_change_requests pending
                  WHERE pending.doctor_id = ?
                    AND pending.institution_id = institution.id
                    AND pending.status = 'PENDING'
              )
            """.trimIndent()

        MembershipRequestType.CONSULTANT to InstitutionMembershipAction.JOIN ->
            """
            SELECT institution.id, institution.name
            FROM institutions institution
            WHERE institution.is_verified = 1
              AND institution.deleted_at IS NULL
              AND NULLIF(TRIM(institution.name), '') IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM institution_memberships relationship
                  WHERE relationship.user_id = ?
                    AND relationship.institution_id = institution.id
                    AND relationship.member_role = 'CONSULTANT'
                    AND relationship.status = 'APPROVED'
                    AND relationship.revoked_at IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1 FROM consultant_institution_change_requests pending
                  WHERE pending.consultant_id = ?
                    AND pending.institution_id = institution.id
                    AND pending.status = 'PENDING'
              )
            """.trimIndent()

        MembershipRequestType.CONSULTANT to InstitutionMembershipAction.LEAVE ->
            """
            SELECT institution.id, institution.name
            FROM institutions institution
            JOIN institution_memberships relationship
              ON relationship.institution_id = institution.id
             AND relationship.user_id = ?
             AND relationship.member_role = 'CONSULTANT'
             AND relationship.status = 'APPROVED'
             AND relationship.revoked_at IS NULL
            WHERE NULLIF(TRIM(institution.name), '') IS NOT NULL
              AND NOT EXISTS (
                  SELECT 1 FROM consultant_institution_change_requests pending
                  WHERE pending.consultant_id = ?
                    AND pending.institution_id = institution.id
                    AND pending.status = 'PENDING'
              )
            """.trimIndent()

        else -> error("不支持的机构关系候选查询")
    }

    private val rowMapper = RowMapper { rs, _ ->
        InstitutionMembershipCandidateView(
            id = rs.getString("id"),
            name = rs.getString("name")
        )
    }

    private companion object {
        const val ORDER_AND_PAGE =
            " ORDER BY institution.name ASC, institution.id ASC LIMIT ? OFFSET ?"
    }
}
