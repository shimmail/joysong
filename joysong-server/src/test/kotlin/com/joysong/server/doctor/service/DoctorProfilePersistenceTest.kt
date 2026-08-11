package com.joysong.server.doctor.service

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal
import java.nio.file.Paths

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
    DoctorService::class,
    DoctorProfileService::class,
    DoctorProfilePersistenceTestConfig::class
)
class DoctorProfilePersistenceTest {

    @Autowired
    private lateinit var repository: DoctorRepository

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var service: DoctorProfileService

    @Test
    fun `profile update changes nine editable fields without overwriting newer platform fields`() {
        jdbcTemplate.update(
            "INSERT INTO users (id, password_hash) VALUES (?, ?)",
            DOCTOR_ID,
            "test-only-password-hash"
        )
        repository.saveAndFlush(staleDoctor())
        val staleEntity = repository.findById(DOCTOR_ID).orElseThrow()
        jdbcTemplate.update(
            """
            UPDATE doctors
            SET institution_id = ?, institution_name = ?, rating = ?, review_count = ?,
                is_verified = ?, consultation_count = ?, case_count = ?
            WHERE id = ?
            """.trimIndent(),
            "institution-new",
            "新机构",
            BigDecimal("4.9"),
            31,
            true,
            44,
            17,
            DOCTOR_ID
        )
        assertEquals(BigDecimal("3.1"), staleEntity.rating)

        val updated = service.update(
            actor,
            DoctorProfileUpdateCommand(
                name = "  新姓名  ",
                title = "  主任医师  ",
                bio = "  新简介  ",
                avatar = "  new-avatar.png  ",
                contactPhone = "  13800138000  ",
                specialties = " 皮肤 , , 修复 ",
                credentials = "  新资历  ",
                credentialImages = " new-a.png, ,new-b.png ",
                certificationTags = " 主任医师, 十年经验 "
            )
        )

        assertEquals("新姓名", updated.name)
        assertEquals("主任医师", updated.title)
        assertEquals("新简介", updated.bio)
        assertEquals("new-avatar.png", updated.avatar)
        assertEquals("13800138000", updated.contactPhone)
        assertEquals("皮肤,修复", updated.specialties)
        assertEquals("新资历", updated.credentials)
        assertEquals("new-a.png,new-b.png", updated.credentialImages)
        assertEquals("主任医师,十年经验", updated.certificationTags)
        assertEquals("institution-new", updated.institutionId)
        assertEquals("新机构", updated.institutionName)
        assertEquals(BigDecimal("4.9"), updated.rating)
        assertEquals(31, updated.reviewCount)
        assertTrue(updated.isVerified)
        assertEquals(44, updated.consultationCount)
        assertEquals(17, updated.caseCount)
    }

    private fun staleDoctor() = DoctorEntity(
        id = DOCTOR_ID,
        name = "旧姓名",
        title = "旧职称",
        bio = "旧简介",
        avatar = "old-avatar.png",
        contactPhone = "13900139000",
        institutionId = "institution-old",
        institutionName = "旧机构",
        rating = BigDecimal("3.1"),
        reviewCount = 2,
        specialties = "旧专长",
        isVerified = false,
        consultationCount = 3,
        credentials = "旧资历",
        credentialImages = "old.png",
        caseCount = 4,
        certificationTags = "旧标签"
    )

    companion object {
        private const val DOCTOR_ID = "doctor-profile-persistence-test"
        private val actor = ManagementActor(
            userId = DOCTOR_ID,
            isAdmin = false,
            activeRoles = setOf("DOCTOR"),
            doctorId = DOCTOR_ID,
            managedInstitutionIds = emptySet(),
            doctorInstitutionIds = emptySet(),
            manageableDoctorIds = setOf(DOCTOR_ID)
        )

        @Container
        @ServiceConnection
        @JvmField
        val mysql = DoctorProfileMySqlContainer("mysql:8.0.39")
            .withDatabaseName(worktreeDatabaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))

        private fun worktreeDatabaseName(): String {
            val worktreeName = Paths.get(System.getProperty("user.dir")).parent.fileName.toString()
            return "myapp_worktree_${worktreeName.replace(Regex("[^A-Za-z0-9]+"), "_")}".lowercase()
        }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class DoctorProfilePersistenceTestConfig {
    @Bean
    fun doctorInstitutionService(): DoctorInstitutionService = mockk {
        every { institutionsFor(any()) } returns emptyList()
    }
}

class DoctorProfileMySqlContainer(imageName: String) :
    MySQLContainer<DoctorProfileMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        println("DOCTOR_PROFILE_TEST_DB_HOST=$host")
        println("DOCTOR_PROFILE_TEST_DB_NAME=$databaseName")
    }
}
