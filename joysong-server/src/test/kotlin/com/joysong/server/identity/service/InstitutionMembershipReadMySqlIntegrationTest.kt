package com.joysong.server.identity.service

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.Timestamp
import java.time.Duration
import javax.sql.DataSource

@Tag("mysql-integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstitutionMembershipReadMySqlIntegrationTest {
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun migrateFreshIsolatedDatabase() {
        createIsolatedDatabase()
        jdbc = JdbcTemplate(dataSource())
        val database = jdbc.queryForObject("SELECT DATABASE()", String::class.java)
        val host = "${mysql.host}:${mysql.getMappedPort(3306)}"
        println("Migration database host=$host, database=$database")
        require(database == DATABASE_NAME)
        require(database.startsWith("myapp_worktree_"))
        Flyway.configure()
            .dataSource(serviceJdbcUrl(), "root", mysql.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        seedIdentities()
    }

    private fun dataSource(): DataSource = DriverManagerDataSource(serviceJdbcUrl(), "root", mysql.password)

    private fun createIsolatedDatabase() {
        require(DATABASE_NAME.startsWith("myapp_worktree_"))
        val rootUrl = mysql.jdbcUrl.replace("/$BOOTSTRAP_DATABASE", "/mysql")
        DriverManager.getConnection(rootUrl, "root", mysql.password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE DATABASE `$DATABASE_NAME` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
                )
            }
        }
    }

    private fun serviceJdbcUrl() = mysql.jdbcUrl.replace("/$BOOTSTRAP_DATABASE", "/$DATABASE_NAME")

    @Test
    fun `candidate JDBC filters all four branches and applies escaping ordering and pagination`() {
        seedCandidateFixtures()
        val service = InstitutionMembershipCandidateService(JdbcInstitutionMembershipCandidateStore(jdbc))

        assertEquals(
            listOf("dj-a", "dj-b"),
            candidates(service, doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
                "Doctor Join Scope").items.map { it.id }
        )
        val secondDoctorJoin = candidates(
            service, doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
            "Doctor Join Scope", offset = 1, limit = 1
        )
        assertEquals(listOf("dj-b"), secondDoctorJoin.items.map { it.id })
        assertFalse(secondDoctorJoin.hasMore)
        assertEquals(
            listOf("dj-literal"),
            candidates(service, doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN,
                "50%_\\中心").items.map { it.id }
        )
        assertEquals(
            listOf("dl-active"),
            candidates(service, doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.LEAVE,
                "Doctor Leave Scope").items.map { it.id }
        )
        assertEquals(
            listOf("cj-eligible"),
            candidates(service, consultantActor(), MembershipRequestType.CONSULTANT, InstitutionMembershipAction.JOIN,
                "Consultant Join Scope").items.map { it.id }
        )
        assertEquals(
            listOf("cl-active"),
            candidates(service, consultantActor(), MembershipRequestType.CONSULTANT, InstitutionMembershipAction.LEAVE,
                "Consultant Leave Scope").items.map { it.id }
        )

        val allDoctorJoin = candidates(
            service, doctorActor(), MembershipRequestType.DOCTOR, InstitutionMembershipAction.JOIN, "", limit = 100
        )
        assertTrue(allDoctorJoin.items.none { it.name.isBlank() || it.name == it.id })
    }

    @Test
    fun `query JDBC enforces owned and reviewable scope and maps current relationship status`() {
        seedQueryFixtures()
        val store = JdbcInstitutionMembershipRequestQueryStore(jdbc)

        val doctorOwned = store.listOwned(MembershipRequestType.DOCTOR, DOCTOR_ID)
        assertEquals(listOf("query-doctor-hidden", "query-doctor-managed"), doctorOwned.map { it.id })
        assertEquals("医生一", doctorOwned.single { it.id == "query-doctor-managed" }.applicantName)
        assertEquals("APPROVED", doctorOwned.single { it.id == "query-doctor-managed" }.relationshipStatus)
        assertEquals("NONE", doctorOwned.single { it.id == "query-doctor-hidden" }.relationshipStatus)

        val consultantOwned = store.listOwned(MembershipRequestType.CONSULTANT, CONSULTANT_ID)
        assertEquals(listOf("query-consultant-managed"), consultantOwned.map { it.id })
        assertEquals("顾问一", consultantOwned.single().applicantName)
        assertEquals("APPROVED", consultantOwned.single().relationshipStatus)

        assertEquals(
            listOf("query-other-doctor", "query-doctor-managed"),
            store.listReviewable(MembershipRequestType.DOCTOR, setOf("query-managed"), false).map { it.id }
        )
        assertEquals(
            listOf("query-consultant-managed"),
            store.listReviewable(MembershipRequestType.CONSULTANT, setOf("query-managed"), false).map { it.id }
        )
        assertEquals(
            listOf("query-foreign-consultant", "query-consultant-managed"),
            store.listReviewable(MembershipRequestType.CONSULTANT, emptySet(), true).map { it.id }
        )
    }

    private fun candidates(
        service: InstitutionMembershipCandidateService,
        actor: ManagementActor,
        type: MembershipRequestType,
        action: InstitutionMembershipAction,
        query: String,
        offset: Int = 0,
        limit: Int = 20
    ) = service.list(actor, type, action, query, offset, limit)

    private fun seedIdentities() {
        listOf(
            Triple(DOCTOR_ID, "医生用户", "DOCTOR"),
            Triple(CONSULTANT_ID, "顾问一", "CONSULTANT"),
            Triple(OTHER_DOCTOR_ID, "其他医生用户", "DOCTOR"),
            Triple(FOREIGN_CONSULTANT_ID, "外部顾问", "CONSULTANT")
        ).forEach { (id, nickname, role) ->
            jdbc.update(
                "INSERT INTO users (id, password_hash, nickname, role) VALUES (?, 'hash', ?, ?)",
                id,
                nickname,
                role
            )
        }
        jdbc.update("INSERT INTO doctors (id, name, is_verified) VALUES (?, '医生一', 1)", DOCTOR_ID)
        jdbc.update("INSERT INTO doctors (id, name, is_verified) VALUES (?, '医生二', 1)", OTHER_DOCTOR_ID)
    }

    private fun seedCandidateFixtures() {
        listOf(
            InstitutionSeed("dj-a", "Doctor Join Scope", true),
            InstitutionSeed("dj-b", "Doctor Join Scope", true),
            InstitutionSeed("dj-active", "Doctor Join Scope", true),
            InstitutionSeed("dj-pending", "Doctor Join Scope", true),
            InstitutionSeed("dj-unverified", "Doctor Join Scope", false),
            InstitutionSeed("dj-deleted", "Doctor Join Scope", true, true),
            InstitutionSeed("dj-literal", "50%_\\中心", true),
            InstitutionSeed("dj-wildcard", "50XX中心", true),
            InstitutionSeed("dj-blank", "", true),
            InstitutionSeed("dl-active", "Doctor Leave Scope", true),
            InstitutionSeed("dl-pending", "Doctor Leave Scope", true),
            InstitutionSeed("dl-revoked", "Doctor Leave Scope", true),
            InstitutionSeed("cj-eligible", "Consultant Join Scope", true),
            InstitutionSeed("cj-active", "Consultant Join Scope", true),
            InstitutionSeed("cj-pending", "Consultant Join Scope", true),
            InstitutionSeed("cj-unverified", "Consultant Join Scope", false),
            InstitutionSeed("cj-deleted", "Consultant Join Scope", true, true),
            InstitutionSeed("cl-active", "Consultant Leave Scope", true),
            InstitutionSeed("cl-pending", "Consultant Leave Scope", true),
            InstitutionSeed("cl-revoked", "Consultant Leave Scope", true)
        ).forEach(::seedInstitution)

        seedDoctorRelationship("dj-active", "APPROVED")
        seedDoctorRelationship("dl-active", "APPROVED")
        seedDoctorRelationship("dl-pending", "APPROVED")
        seedDoctorRelationship("dl-revoked", "REVOKED", revoked = true)
        seedDoctorRequest("candidate-doctor-join-pending", "dj-pending", "JOIN")
        seedDoctorRequest("candidate-doctor-leave-pending", "dl-pending", "LEAVE")

        seedConsultantRelationship("cj-active", "APPROVED")
        seedConsultantRelationship("cl-active", "APPROVED")
        seedConsultantRelationship("cl-pending", "APPROVED")
        seedConsultantRelationship("cl-revoked", "REVOKED", revoked = true)
        seedConsultantRequest("candidate-consultant-join-pending", "cj-pending", "JOIN")
        seedConsultantRequest("candidate-consultant-leave-pending", "cl-pending", "LEAVE")
    }

    private fun seedQueryFixtures() {
        seedInstitution(InstitutionSeed("query-managed", "Query Managed", true))
        seedInstitution(InstitutionSeed("query-hidden", "Query Hidden", true))
        seedDoctorRelationship("query-managed", "APPROVED")
        seedConsultantRelationship("query-managed", "APPROVED")

        seedDoctorRequest("query-doctor-managed", "query-managed", "JOIN", "APPROVED", "2026-08-14 10:00:00")
        seedDoctorRequest("query-doctor-hidden", "query-hidden", "JOIN", "APPROVED", "2026-08-14 12:00:00")
        seedDoctorRequest(
            "query-other-doctor", "query-managed", "JOIN", "APPROVED", "2026-08-14 11:00:00", OTHER_DOCTOR_ID
        )
        seedConsultantRequest(
            "query-consultant-managed", "query-managed", "JOIN", "APPROVED", "2026-08-14 10:00:00"
        )
        seedConsultantRequest(
            "query-foreign-consultant", "query-hidden", "JOIN", "APPROVED", "2026-08-14 11:00:00",
            FOREIGN_CONSULTANT_ID
        )
    }

    private fun seedInstitution(seed: InstitutionSeed) {
        jdbc.update(
            "INSERT INTO institutions (id, name, is_verified, deleted_at) VALUES (?, ?, ?, ?)",
            seed.id,
            seed.name,
            if (seed.verified) 1 else 0,
            if (seed.deleted) Timestamp.valueOf("2026-08-14 00:00:00") else null
        )
    }

    private fun seedDoctorRelationship(institutionId: String, status: String, revoked: Boolean = false) {
        jdbc.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, status, revoked_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent(),
            "doctor-rel-$institutionId",
            DOCTOR_ID,
            institutionId,
            status,
            if (revoked) Timestamp.valueOf("2026-08-14 00:00:00") else null
        )
    }

    private fun seedConsultantRelationship(institutionId: String, status: String, revoked: Boolean = false) {
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, revoked_at)
            VALUES (?, ?, ?, 'CONSULTANT', ?, ?)
            """.trimIndent(),
            "consultant-rel-$institutionId",
            CONSULTANT_ID,
            institutionId,
            status,
            if (revoked) Timestamp.valueOf("2026-08-14 00:00:00") else null
        )
    }

    private fun seedDoctorRequest(
        id: String,
        institutionId: String,
        action: String,
        status: String = "PENDING",
        submittedAt: String = "2026-08-14 09:00:00",
        doctorId: String = DOCTOR_ID
    ) {
        jdbc.update(
            """
            INSERT INTO doctor_institution_change_requests
                (id, doctor_id, institution_id, action, status, request_note, submitted_by, submitted_at)
            VALUES (?, ?, ?, ?, ?, '', ?, ?)
            """.trimIndent(),
            id,
            doctorId,
            institutionId,
            action,
            status,
            doctorId,
            Timestamp.valueOf(submittedAt)
        )
    }

    private fun seedConsultantRequest(
        id: String,
        institutionId: String,
        action: String,
        status: String = "PENDING",
        submittedAt: String = "2026-08-14 09:00:00",
        consultantId: String = CONSULTANT_ID
    ) {
        jdbc.update(
            """
            INSERT INTO consultant_institution_change_requests
                (id, consultant_id, institution_id, action, status, request_note, submitted_by, submitted_at)
            VALUES (?, ?, ?, ?, ?, '', ?, ?)
            """.trimIndent(),
            id,
            consultantId,
            institutionId,
            action,
            status,
            consultantId,
            Timestamp.valueOf(submittedAt)
        )
    }

    private fun doctorActor() = ManagementActor(
        DOCTOR_ID, false, setOf("DOCTOR"), DOCTOR_ID, emptySet(), emptySet(), setOf(DOCTOR_ID)
    )

    private fun consultantActor() = ManagementActor(
        CONSULTANT_ID, false, setOf("CONSULTANT"), null, emptySet(), emptySet(), emptySet()
    )

    private data class InstitutionSeed(
        val id: String,
        val name: String,
        val verified: Boolean,
        val deleted: Boolean = false
    )

    companion object {
        private const val DATABASE_NAME = "myapp_worktree_institution_membership_application_review_query"
        private const val BOOTSTRAP_DATABASE = "myapp_worktree_imar_bootstrap"
        private const val DOCTOR_ID = "read-doctor"
        private const val CONSULTANT_ID = "read-consultant"
        private const val OTHER_DOCTOR_ID = "read-other-doctor"
        private const val FOREIGN_CONSULTANT_ID = "read-foreign-consultant"

        @Container
        @JvmField
        val mysql = MembershipReadMySqlContainer("mysql:8.0.39")
            .withDatabaseName(BOOTSTRAP_DATABASE)
            .withStartupTimeout(Duration.ofSeconds(60))
    }
}

class MembershipReadMySqlContainer(imageName: String) : MySQLContainer<MembershipReadMySqlContainer>(imageName)
