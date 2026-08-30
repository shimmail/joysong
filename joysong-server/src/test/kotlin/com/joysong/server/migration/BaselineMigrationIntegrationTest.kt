package com.joysong.server.migration

import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.math.BigDecimal

@Tag("mysql-integration")
@Testcontainers
@DataJpaTest(
    properties = [
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.baseline-on-migrate=false",
        "spring.flyway.validate-on-migrate=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.sql.init.mode=never",
    ],
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class BaselineMigrationIntegrationTest {
    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var doctorRepository: DoctorRepository

    @Autowired
    private lateinit var institutionRepository: InstitutionRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var institutionProjectRepository: InstitutionProjectRepository

    @Test
    fun `fresh database applies B33 baseline through V37 case counters`() {
        val history = jdbcTemplate.query(
            """
            SELECT version, type, script
            FROM flyway_schema_history
            WHERE success = 1 AND version IS NOT NULL
            ORDER BY installed_rank
            """.trimIndent(),
        ) { rs, _ -> Triple(rs.getString("version"), rs.getString("type"), rs.getString("script")) }

        assertEquals(
            listOf(
                Triple("33", "SQL_BASELINE", "B33__current_schema.sql"),
                Triple("34", "SQL", "V34__harden_admin_account_lifecycle.sql"),
                Triple("35", "SQL", "V35__snapshot_order_pricing_policy_revision.sql"),
                Triple("36", "SQL", "V36__add_account_lifecycle_foundation.sql"),
                Triple("37", "SQL", "V37__add_project_case_counters.sql"),
            ),
            history,
        )
        assertEquals(
            listOf("account_deletion_requests", "user_media_assets"),
            jdbcTemplate.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name IN ('account_deletion_requests', 'user_media_assets') ORDER BY table_name",
                String::class.java,
            ),
        )
        assertEquals(
            listOf("account_state", "erased_at", "erased_email_digest", "erased_phone_digest"),
            jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'users' AND column_name IN ('account_state', 'erased_at', 'erased_phone_digest', 'erased_email_digest') ORDER BY column_name",
                String::class.java,
            ),
        )
        assertEquals(
            listOf(
                listOf("institution_projects", "int", "NO", "0"),
                listOf("projects", "int", "NO", "0"),
            ),
            jdbcTemplate.query(
                """
                SELECT table_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND column_name = 'case_count'
                  AND table_name IN ('projects', 'institution_projects')
                ORDER BY table_name
                """.trimIndent(),
            ) { rs, _ ->
                listOf(
                    rs.getString("table_name"),
                    rs.getString("data_type"),
                    rs.getString("is_nullable"),
                    rs.getString("column_default"),
                )
            },
        )
        assertEquals(
            listOf(
                "institution_projects:chk_institution_projects_case_count",
                "projects:chk_projects_case_count",
            ),
            jdbcTemplate.queryForList(
                """
                SELECT CONCAT(table_name, ':', constraint_name)
                FROM information_schema.table_constraints
                WHERE constraint_schema = DATABASE()
                  AND constraint_type = 'CHECK'
                  AND constraint_name IN (
                    'chk_projects_case_count',
                    'chk_institution_projects_case_count'
                  )
                ORDER BY table_name
                """.trimIndent(),
                String::class.java,
            ),
        )
    }

    @Test
    fun `counter repositories atomically persist increments and institution project version`() {
        insertCounterFixtures()

        assertEquals(1, doctorRepository.incrementConsultationCount(DOCTOR_ID))
        assertEquals(1, institutionRepository.incrementCaseCount(INSTITUTION_ID))
        assertEquals(1, projectRepository.incrementCaseCount(PROJECT_ID))
        assertEquals(1, institutionProjectRepository.incrementCaseCount(INSTITUTION_PROJECT_ID))

        assertEquals(
            1,
            doctorRepository.findById(DOCTOR_ID).orElseThrow().consultationCount,
        )
        assertEquals(
            1,
            institutionRepository.findById(INSTITUTION_ID).orElseThrow().caseCount,
        )
        assertEquals(
            1,
            projectRepository.findById(PROJECT_ID).orElseThrow().caseCount,
        )
        institutionProjectRepository.findById(INSTITUTION_PROJECT_ID).orElseThrow().also { project ->
            assertEquals(1, project.caseCount)
            assertEquals(1L, project.version)
        }
    }

    @Test
    fun `saving stale editable entities preserves newer automatic counters`() {
        insertCounterFixtures()
        val staleDoctor = doctorRepository.findById(DOCTOR_ID).orElseThrow()
        val staleInstitution = institutionRepository.findById(INSTITUTION_ID).orElseThrow()
        val staleProject = projectRepository.findById(PROJECT_ID).orElseThrow()

        jdbcTemplate.update(
            "UPDATE doctors SET consultation_count = ? WHERE id = ?",
            11,
            DOCTOR_ID,
        )
        jdbcTemplate.update(
            "UPDATE institutions SET case_count = ? WHERE id = ?",
            12,
            INSTITUTION_ID,
        )
        jdbcTemplate.update(
            "UPDATE projects SET case_count = ? WHERE id = ?",
            13,
            PROJECT_ID,
        )

        doctorRepository.save(staleDoctor.copy(name = "Updated Doctor"))
        institutionRepository.save(staleInstitution.copy(name = "Updated Institution"))
        projectRepository.save(staleProject.copy(name = "Updated Project"))
        projectRepository.flush()

        assertEquals(
            "Updated Doctor",
            jdbcTemplate.queryForObject(
                "SELECT name FROM doctors WHERE id = ?",
                String::class.java,
                DOCTOR_ID,
            ),
        )
        assertEquals(
            11,
            jdbcTemplate.queryForObject(
                "SELECT consultation_count FROM doctors WHERE id = ?",
                Int::class.java,
                DOCTOR_ID,
            ),
        )
        assertEquals(
            "Updated Institution",
            jdbcTemplate.queryForObject(
                "SELECT name FROM institutions WHERE id = ?",
                String::class.java,
                INSTITUTION_ID,
            ),
        )
        assertEquals(
            12,
            jdbcTemplate.queryForObject(
                "SELECT case_count FROM institutions WHERE id = ?",
                Int::class.java,
                INSTITUTION_ID,
            ),
        )
        assertEquals(
            "Updated Project",
            jdbcTemplate.queryForObject(
                "SELECT name FROM projects WHERE id = ?",
                String::class.java,
                PROJECT_ID,
            ),
        )
        assertEquals(
            13,
            jdbcTemplate.queryForObject(
                "SELECT case_count FROM projects WHERE id = ?",
                Int::class.java,
                PROJECT_ID,
            ),
        )
    }

    private fun insertCounterFixtures() {
        jdbcTemplate.update(
            "INSERT INTO users (id, password_hash, nickname) VALUES (?, ?, ?)",
            DOCTOR_ID,
            "test-password-hash",
            "Counter Doctor",
        )
        doctorRepository.saveAndFlush(DoctorEntity(id = DOCTOR_ID, name = "Counter Doctor"))
        institutionRepository.saveAndFlush(
            InstitutionEntity(id = INSTITUTION_ID, name = "Counter Institution"),
        )
        projectRepository.saveAndFlush(ProjectEntity(id = PROJECT_ID, name = "Counter Project"))
        institutionProjectRepository.saveAndFlush(
            InstitutionProjectEntity(
                id = INSTITUTION_PROJECT_ID,
                institutionId = INSTITUTION_ID,
                projectId = PROJECT_ID,
                price = BigDecimal("100.00"),
            ),
        )
    }

    companion object {
        private const val DOCTOR_ID = "migration-counter-doctor"
        private const val INSTITUTION_ID = "migration-counter-institution"
        private const val PROJECT_ID = "migration-counter-project"
        private const val INSTITUTION_PROJECT_ID = "counter-institution-project"

        @Container
        @ServiceConnection
        @JvmField
        val mysql = IsolatedBaselineMySqlContainer("mysql:8.0.39")
            .withDatabaseName(WorktreeTestDatabase.databaseName())
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class IsolatedBaselineMySqlContainer(imageName: String) :
    MySQLContainer<IsolatedBaselineMySqlContainer>(imageName) {
    override fun start() {
        super.start()
        WorktreeTestDatabase.validateAndPrint(this)
    }
}
