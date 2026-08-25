package com.joysong.server.notification.service

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource

class BusinessNotificationServiceLegalRepresentativeResolverTest {

    @Test
    fun `resolver returns only distinct active approved non-revoked legal representatives of the requested institution`() {
        val jdbcTemplate = JdbcTemplate(
            DriverManagerDataSource("jdbc:h2:mem:business_notification_resolver;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "")
        )
        createSchema(jdbcTemplate)
        insertUser(jdbcTemplate, "valid")
        insertUser(jdbcTemplate, "inactive-role")
        insertUser(jdbcTemplate, "pending-membership")
        insertUser(jdbcTemplate, "revoked-membership")
        insertUser(jdbcTemplate, "deleted-user", deleted = true)
        insertUser(jdbcTemplate, "wrong-role-code")
        insertUser(jdbcTemplate, "wrong-member-role")
        insertUser(jdbcTemplate, "other-institution")
        insertRole(jdbcTemplate, "valid")
        insertRole(jdbcTemplate, "inactive-role", status = "INACTIVE")
        insertRole(jdbcTemplate, "pending-membership")
        insertRole(jdbcTemplate, "revoked-membership")
        insertRole(jdbcTemplate, "deleted-user")
        insertRole(jdbcTemplate, "wrong-role-code", roleCode = "CONSULTANT")
        insertRole(jdbcTemplate, "wrong-member-role")
        insertRole(jdbcTemplate, "other-institution")
        insertMembership(jdbcTemplate, "valid")
        insertMembership(jdbcTemplate, "valid")
        insertMembership(jdbcTemplate, "inactive-role")
        insertMembership(jdbcTemplate, "pending-membership", status = "PENDING")
        insertMembership(jdbcTemplate, "revoked-membership", revoked = true)
        insertMembership(jdbcTemplate, "deleted-user")
        insertMembership(jdbcTemplate, "wrong-role-code")
        insertMembership(jdbcTemplate, "wrong-member-role", memberRole = "CONSULTANT")
        insertMembership(jdbcTemplate, "other-institution", institutionId = "institution-2")

        val recipients = BusinessNotificationService(mockk(relaxed = true), jdbcTemplate)
            .currentLegalRepresentativeIds("institution-1")

        assertEquals(setOf("valid"), recipients)
    }

    private fun createSchema(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.execute("CREATE TABLE users (id VARCHAR(64) PRIMARY KEY, deleted_at TIMESTAMP NULL)")
        jdbcTemplate.execute("CREATE TABLE user_roles (user_id VARCHAR(64), role_code VARCHAR(64), status VARCHAR(32))")
        jdbcTemplate.execute(
            "CREATE TABLE institution_memberships (user_id VARCHAR(64), institution_id VARCHAR(64), member_role VARCHAR(64), status VARCHAR(32), revoked_at TIMESTAMP NULL)"
        )
    }

    private fun insertUser(jdbcTemplate: JdbcTemplate, id: String, deleted: Boolean = false) {
        jdbcTemplate.update("INSERT INTO users (id, deleted_at) VALUES (?, ?)", id, if (deleted) java.sql.Timestamp(1) else null)
    }

    private fun insertRole(
        jdbcTemplate: JdbcTemplate,
        userId: String,
        roleCode: String = "INSTITUTION_LEGAL_REPRESENTATIVE",
        status: String = "ACTIVE"
    ) {
        jdbcTemplate.update("INSERT INTO user_roles (user_id, role_code, status) VALUES (?, ?, ?)", userId, roleCode, status)
    }

    private fun insertMembership(
        jdbcTemplate: JdbcTemplate,
        userId: String,
        institutionId: String = "institution-1",
        memberRole: String = "INSTITUTION_LEGAL_REPRESENTATIVE",
        status: String = "APPROVED",
        revoked: Boolean = false
    ) {
        jdbcTemplate.update(
            "INSERT INTO institution_memberships (user_id, institution_id, member_role, status, revoked_at) VALUES (?, ?, ?, ?, ?)",
            userId,
            institutionId,
            memberRole,
            status,
            if (revoked) java.sql.Timestamp(1) else null
        )
    }
}
