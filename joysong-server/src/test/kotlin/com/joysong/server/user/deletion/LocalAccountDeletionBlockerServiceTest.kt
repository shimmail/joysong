package com.joysong.server.user.deletion

import com.joysong.server.user.entity.UserEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import java.util.UUID

class LocalAccountDeletionBlockerServiceTest {
    private lateinit var jdbcTemplate: JdbcTemplate
    private lateinit var service: LocalAccountDeletionBlockerService

    @BeforeEach
    fun setUp() {
        val dataSource = DriverManagerDataSource(
            "jdbc:h2:mem:account-deletion-${UUID.randomUUID()};MODE=MySQL;DB_CLOSE_DELAY=-1",
            "sa",
            "",
        )
        jdbcTemplate = JdbcTemplate(dataSource)
        service = LocalAccountDeletionBlockerService(jdbcTemplate)

        jdbcTemplate.execute("CREATE TABLE identity_applications (user_id VARCHAR(36), status VARCHAR(20))")
        jdbcTemplate.execute("CREATE TABLE institution_memberships (user_id VARCHAR(36), status VARCHAR(20))")
        jdbcTemplate.execute("CREATE TABLE user_roles (user_id VARCHAR(36), status VARCHAR(20))")
        jdbcTemplate.execute("CREATE TABLE doctors (id VARCHAR(36), is_verified TINYINT)")
        jdbcTemplate.execute("CREATE TABLE doctor_institutions (doctor_id VARCHAR(36), status VARCHAR(20))")
        jdbcTemplate.execute("CREATE TABLE platform_cooperation_agreements (user_id VARCHAR(36), status VARCHAR(20))")
        jdbcTemplate.execute(
            "CREATE TABLE consultant_institution_change_requests (consultant_id VARCHAR(36), status VARCHAR(20))",
        )
        jdbcTemplate.execute(
            "CREATE TABLE doctor_institution_change_requests (doctor_id VARCHAR(36), status VARCHAR(20))",
        )
    }

    @ParameterizedTest
    @ValueSource(strings = ["APPROVED", "REJECTED", "WITHDRAWN"])
    fun `inactive doctor identity history no longer blocks account deletion`(applicationStatus: String) {
        jdbcTemplate.update("INSERT INTO identity_applications VALUES (?, ?)", "doctor-1", applicationStatus)
        jdbcTemplate.update("INSERT INTO user_roles VALUES (?, 'REVOKED')", "doctor-1")
        jdbcTemplate.update("INSERT INTO doctors VALUES (?, 0)", "doctor-1")
        jdbcTemplate.update("INSERT INTO doctor_institutions VALUES (?, 'REVOKED')", "doctor-1")
        jdbcTemplate.update("INSERT INTO platform_cooperation_agreements VALUES (?, 'TERMINATED')", "doctor-1")

        val blockers = service.evaluate(UserEntity(id = "doctor-1", passwordHash = "hash"))

        assertTrue(blockers.isEmpty(), "revoked professional history must not remain a deletion blocker: $blockers")
    }

    @Test
    fun `pending and active professional state still blocks account deletion`() {
        jdbcTemplate.update("INSERT INTO identity_applications VALUES (?, 'PENDING')", "doctor-1")
        jdbcTemplate.update("INSERT INTO user_roles VALUES (?, 'ACTIVE')", "doctor-1")
        jdbcTemplate.update("INSERT INTO doctors VALUES (?, 1)", "doctor-1")
        jdbcTemplate.update("INSERT INTO doctor_institutions VALUES (?, 'APPROVED')", "doctor-1")
        jdbcTemplate.update("INSERT INTO platform_cooperation_agreements VALUES (?, 'PENDING')", "doctor-1")

        val blockers = service.evaluate(UserEntity(id = "doctor-1", passwordHash = "hash"))

        assertEquals(
            setOf(
                "IDENTITY_APPLICATION",
                "PROFESSIONAL_ROLE",
                "PROFESSIONAL_PROFILE",
                "DOCTOR_INSTITUTION_RELATIONSHIP",
                "PLATFORM_COOPERATION_AGREEMENT",
            ),
            blockers.mapTo(linkedSetOf()) { it.type },
        )
    }
}
