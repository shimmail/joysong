package com.joysong.server.identity.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

data class InstitutionConsultant(
    val id: String,
    val name: String
)

@Service
class InstitutionConsultantService(
    private val jdbcTemplate: JdbcTemplate
) {
    private val eligibleConsultantFromWhere = """
        FROM institution_memberships im
        JOIN users u ON u.id = im.user_id
        JOIN institutions i ON i.id = im.institution_id
        WHERE im.member_role = 'CONSULTANT'
          AND im.status = 'APPROVED'
          AND im.revoked_at IS NULL
          AND u.deleted_at IS NULL
          AND i.deleted_at IS NULL
          AND u.nickname IS NOT NULL
          AND TRIM(u.nickname) <> ''
    """.trimIndent()

    fun requireApprovedConsultant(institutionId: String, consultantId: String): InstitutionConsultant =
        listApprovedConsultants(institutionId)
            .firstOrNull { it.id == consultantId }
            ?: throw IllegalArgumentException("所选医美顾问未加入该机构或尚未确认")

    fun listApprovedConsultants(institutionId: String): List<InstitutionConsultant> =
        jdbcTemplate.query(
            """
            SELECT u.id, u.nickname
            $eligibleConsultantFromWhere
              AND im.institution_id = ?
            ORDER BY u.nickname, u.id
            """.trimIndent(),
            { rs, _ -> InstitutionConsultant(rs.getString("id"), rs.getString("nickname")) },
            institutionId
        )

    fun listConsultableInstitutionIds(): Set<String> =
        jdbcTemplate.queryForList(
            """
            SELECT DISTINCT im.institution_id
            $eligibleConsultantFromWhere
              AND i.is_verified = TRUE
            ORDER BY im.institution_id
            """.trimIndent(),
            String::class.java
        ).toSet()
}
