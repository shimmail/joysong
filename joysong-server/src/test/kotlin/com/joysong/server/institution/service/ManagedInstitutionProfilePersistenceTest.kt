package com.joysong.server.institution.service

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.nio.file.Paths
import java.time.LocalDate

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never"
    ]
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    ManagedInstitutionProfileService::class,
    ManagementAccessService::class,
    InstitutionService::class
)
class ManagedInstitutionProfilePersistenceTest {

    @Autowired
    private lateinit var repository: InstitutionRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var managementAccessService: ManagementAccessService

    @Autowired
    private lateinit var service: ManagedInstitutionProfileService

    @Test
    fun `professional update changes all editable fields without overwriting platform fields`() {
        jdbcTemplate.update("INSERT INTO users (id, password_hash) VALUES (?, ?)", USER_ID, "test-only-password-hash")
        repository.saveAndFlush(staleInstitution())
        jdbcTemplate.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, ?, 'ACTIVE')",
            USER_ID,
            "INSTITUTION_LEGAL_REPRESENTATIVE"
        )
        jdbcTemplate.update(
            """
            INSERT INTO institution_memberships (id, user_id, institution_id, member_role, status)
            VALUES (?, ?, ?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'APPROVED')
            """.trimIndent(),
            MEMBERSHIP_ID,
            USER_ID,
            INSTITUTION_ID
        )

        val actor = managementAccessService.actor(
            UsernamePasswordAuthenticationToken(USER_ID, "", listOf(SimpleGrantedAuthority("ROLE_USER")))
        )
        assertEquals(setOf(INSTITUTION_ID), actor.managedInstitutionIds)
        val staleEntity = repository.findById(INSTITUTION_ID).orElseThrow()
        jdbcTemplate.update(
            """
            UPDATE institutions
            SET rating = ?, review_count = ?, is_verified = ?, certification_time = ?, project_count = ?,
                doctor_count = ?, consultation_count = ?, user_count = ?, case_count = ?
            WHERE id = ?
            """.trimIndent(),
            BigDecimal("4.9"),
            31,
            true,
            LocalDate.of(2026, 1, 2),
            9,
            5,
            80,
            70,
            60,
            INSTITUTION_ID
        )
        assertEquals(BigDecimal("3.1"), staleEntity.rating)

        val updated = service.update(
            actor,
            INSTITUTION_ID,
            ManagedInstitutionProfileUpdateCommand(
                name = "  新机构  ",
                address = "  新地址  ",
                city = "  上海  ",
                description = "  新简介  ",
                coverImage = "  new-cover.png  ",
                images = listOf(" new-a.png ", "new-b.png", "new-a.png", ""),
                establishedYear = 2020,
                credentials = "  新资质  ",
                credentialImages = listOf(" license-a.png ", "license-b.png", "license-a.png"),
                specialties = listOf(" 皮肤 ", "激光", "皮肤"),
                tags = listOf(" 高端 ", "认证", "高端"),
                contactPhone = " 13800138000 ",
                businessHours = " 9:00-18:00 "
            )
        )

        assertEquals("新机构", updated.name)
        assertEquals("新地址", updated.address)
        assertEquals("上海", updated.city)
        assertEquals("新简介", updated.description)
        assertEquals("new-cover.png", updated.coverImage)
        assertEquals(listOf("new-a.png", "new-b.png"), updated.images)
        assertEquals(2020, updated.establishedYear)
        assertEquals("新资质", updated.credentials)
        assertEquals(listOf("license-a.png", "license-b.png"), updated.credentialImages)
        assertEquals(listOf("皮肤", "激光"), updated.specialties)
        assertEquals(listOf("高端", "认证"), updated.tags)
        assertEquals("13800138000", updated.contactPhone)
        assertEquals("9:00-18:00", updated.businessHours)
        assertEquals(BigDecimal("4.9"), updated.rating)
        assertEquals(31, updated.reviewCount)
        assertTrue(updated.isVerified)
        assertEquals(LocalDate.of(2026, 1, 2), updated.certificationTime)
        assertEquals(9, updated.projectCount)
        assertEquals(5, updated.doctorCount)
        assertEquals(80, updated.consultationCount)
        assertEquals(70, updated.userCount)
        assertEquals(60, updated.caseCount)
    }

    private fun staleInstitution() = InstitutionEntity(
        id = INSTITUTION_ID,
        name = "旧机构",
        address = "旧地址",
        city = "旧城市",
        description = "旧简介",
        coverImage = "old-cover.png",
        images = "old-a.png",
        establishedYear = 2000,
        credentials = "旧资质",
        credentialImages = "old-license.png",
        specialties = "旧专长",
        tags = "旧标签",
        contactPhone = "13900139000",
        businessHours = "10:00-17:00",
        rating = BigDecimal("3.1"),
        reviewCount = 2,
        isVerified = false,
        projectCount = 3,
        doctorCount = 4,
        consultationCount = 5,
        userCount = 6,
        caseCount = 7
    )

    companion object {
        private const val USER_ID = "institution-profile-legal-user"
        private const val INSTITUTION_ID = "institution-profile-test"
        private const val MEMBERSHIP_ID = "institution-profile-membership"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = ManagedInstitutionProfileMySqlContainer("mysql:8.0.39")
            .withDatabaseName(worktreeDatabaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        private fun worktreeDatabaseName(): String {
            val worktreeName = Paths.get(System.getProperty("user.dir")).parent.fileName.toString()
            return "myapp_worktree_${worktreeName.replace(Regex("[^A-Za-z0-9]+"), "_")}".lowercase()
        }
    }
}

class ManagedInstitutionProfileMySqlContainer(imageName: String) :
    MySQLContainer<ManagedInstitutionProfileMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        println("MANAGED_INSTITUTION_PROFILE_TEST_DB_HOST=$host")
        println("MANAGED_INSTITUTION_PROFILE_TEST_DB_NAME=$databaseName")
    }
}
