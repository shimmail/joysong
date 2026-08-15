package com.joysong.server.identity.service

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.access.AccessDeniedException
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.Timestamp
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
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
        val service = InstitutionMembershipCandidateService(
            JdbcInstitutionMembershipCandidateStore(jdbc),
            DoctorInstitutionRelationshipService(jdbc)
        )

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
    fun `active role without current verified doctor profile cannot query candidates`() {
        val doctorId = "candidate-unverified-doctor"
        jdbc.update(
            "INSERT INTO users (id, password_hash, nickname, role) VALUES (?, 'hash', '未认证医生', 'DOCTOR')",
            doctorId
        )
        jdbc.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, 'DOCTOR', 'ACTIVE')",
            doctorId
        )
        jdbc.update(
            "INSERT INTO doctors (id, name, is_verified) VALUES (?, '未认证医生', 0)",
            doctorId
        )
        jdbc.update(
            "INSERT INTO institutions (id, name, is_verified) VALUES ('candidate-unverified-target', '候选机构', 1)"
        )
        val actor = doctorActor().copy(
            userId = doctorId,
            doctorId = doctorId,
            manageableDoctorIds = setOf(doctorId)
        )
        val service = InstitutionMembershipCandidateService(
            JdbcInstitutionMembershipCandidateStore(jdbc),
            DoctorInstitutionRelationshipService(jdbc)
        )

        assertThrows(AccessDeniedException::class.java) {
            service.list(
                actor,
                MembershipRequestType.DOCTOR,
                InstitutionMembershipAction.JOIN,
                "候选机构",
                0,
                20
            )
        }
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

    @Test
    fun `legacy compatibility is scoped pair deduplicated and absent from normalized reads`() {
        seedLegacyCompatibilityFixtures()
        val service = InstitutionMembershipRequestQueryService(JdbcInstitutionMembershipRequestQueryStore(jdbc))
        val managedActor = doctorActor().copy(managedInstitutionIds = setOf("legacy-managed"))

        val rootRows = service.listCompatibility(managedActor).filter { it.id.startsWith("legacy-") }
        assertEquals(
            setOf(
                "legacy-doctor-owned-rel",
                "legacy-doctor-managed-rel",
                "legacy-consultant-managed-rel",
                "legacy-doctor-shadow-ledger"
            ),
            rootRows.map { it.id }.toSet()
        )
        assertEquals(rootRows.size, rootRows.map { it.requestType to it.id }.distinct().size)
        assertFalse(rootRows.any { it.id == "legacy-doctor-shadow-rel" || it.id.contains("hidden") })

        assertEquals(
            listOf("legacy-doctor-shadow-ledger"),
            service.listOwned(doctorActor()).filter { it.id.startsWith("legacy-") }.map { it.id }
        )
        val legalActor = ManagementActor(
            "legal-1", false, setOf("INSTITUTION_LEGAL_REPRESENTATIVE"), null,
            setOf("legacy-managed"), emptySet(), emptySet()
        )
        assertTrue(service.listReviewable(legalActor).none { it.id.startsWith("legacy-") })

        val consultantRows = service.listConsultantCompatibility(consultantActor())
            .filter { it.id.startsWith("legacy-") }
        assertEquals(
            setOf("legacy-consultant-revoked-rel", "legacy-consultant-shadow-ledger"),
            consultantRows.map { it.id }.toSet()
        )
        assertFalse(consultantRows.any { it.id == "legacy-consultant-shadow-rel" })
        val revoked = consultantRows.single { it.id == "legacy-consultant-revoked-rel" }
        assertEquals("JOIN", revoked.action)
        assertEquals("REVOKED", revoked.status)
        assertEquals("NONE", revoked.relationshipStatus)
        assertEquals(LocalDateTime.of(2026, 8, 14, 9, 0), revoked.reviewedAt)
        assertEquals(
            listOf("legacy-consultant-shadow-ledger"),
            service.listOwned(consultantActor()).filter { it.id.startsWith("legacy-") }.map { it.id }
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
        listOf(
            DOCTOR_ID to "DOCTOR",
            CONSULTANT_ID to "CONSULTANT",
            OTHER_DOCTOR_ID to "DOCTOR",
            FOREIGN_CONSULTANT_ID to "CONSULTANT"
        ).forEach { (userId, roleCode) ->
            jdbc.update(
                "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, ?, 'ACTIVE')",
                userId,
                roleCode
            )
        }
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

    private fun seedLegacyCompatibilityFixtures() {
        listOf(
            InstitutionSeed("legacy-doctor-owned", "Legacy Doctor Owned", true),
            InstitutionSeed("legacy-managed", "Legacy Managed", true),
            InstitutionSeed("legacy-hidden", "Legacy Hidden", true),
            InstitutionSeed("legacy-doctor-shadow", "Legacy Doctor Shadow", true),
            InstitutionSeed("legacy-consultant-revoked", "Legacy Consultant Revoked", true),
            InstitutionSeed("legacy-consultant-shadow", "Legacy Consultant Shadow", true)
        ).forEach(::seedInstitution)

        seedLegacyDoctorRelationship("legacy-doctor-owned-rel", DOCTOR_ID, "legacy-doctor-owned")
        seedLegacyDoctorRelationship("legacy-doctor-managed-rel", OTHER_DOCTOR_ID, "legacy-managed")
        seedLegacyDoctorRelationship("legacy-doctor-hidden-rel", OTHER_DOCTOR_ID, "legacy-hidden")
        seedLegacyDoctorRelationship("legacy-doctor-shadow-rel", DOCTOR_ID, "legacy-doctor-shadow")
        seedDoctorRequest(
            "legacy-doctor-shadow-ledger",
            "legacy-doctor-shadow",
            "JOIN",
            "APPROVED"
        )

        seedLegacyConsultantRelationship(
            "legacy-consultant-managed-rel",
            FOREIGN_CONSULTANT_ID,
            "legacy-managed"
        )
        seedLegacyConsultantRelationship(
            "legacy-consultant-hidden-rel",
            FOREIGN_CONSULTANT_ID,
            "legacy-hidden"
        )
        seedLegacyConsultantRelationship(
            "legacy-consultant-revoked-rel",
            CONSULTANT_ID,
            "legacy-consultant-revoked",
            status = "REVOKED",
            revokedAt = "2026-08-14 09:00:00"
        )
        seedLegacyConsultantRelationship(
            "legacy-consultant-shadow-rel",
            CONSULTANT_ID,
            "legacy-consultant-shadow"
        )
        seedConsultantRequest(
            "legacy-consultant-shadow-ledger",
            "legacy-consultant-shadow",
            "JOIN",
            "APPROVED"
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

    private fun seedLegacyDoctorRelationship(id: String, doctorId: String, institutionId: String) {
        jdbc.update(
            """
            INSERT INTO doctor_institutions
                (id, doctor_id, institution_id, status, request_note, review_note,
                 confirmed_by, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, 'APPROVED', 'legacy request', 'legacy review', ?, ?, ?, ?)
            """.trimIndent(),
            id,
            doctorId,
            institutionId,
            DOCTOR_ID,
            Timestamp.valueOf("2026-08-14 08:00:00"),
            Timestamp.valueOf("2026-08-14 07:00:00"),
            Timestamp.valueOf("2026-08-14 08:00:00")
        )
    }

    private fun seedLegacyConsultantRelationship(
        id: String,
        consultantId: String,
        institutionId: String,
        status: String = "APPROVED",
        revokedAt: String? = null
    ) {
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, request_note, review_note,
                 confirmed_by, confirmed_at, revoked_at, created_at, updated_at)
            VALUES (?, ?, ?, 'CONSULTANT', ?, 'legacy request', 'legacy review', ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            consultantId,
            institutionId,
            status,
            DOCTOR_ID,
            Timestamp.valueOf("2026-08-14 08:00:00"),
            revokedAt?.let(Timestamp::valueOf),
            Timestamp.valueOf("2026-08-14 07:00:00"),
            Timestamp.valueOf(revokedAt ?: "2026-08-14 08:00:00")
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
        private val DATABASE_NAME = membershipReadDatabaseName(Path.of(""))
        private const val BOOTSTRAP_DATABASE = "myapp_worktree_boot"
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

internal fun membershipReadDatabaseName(workingDirectory: Path): String {
    val normalizedDirectory = workingDirectory.toAbsolutePath().normalize()
    val worktree = if (normalizedDirectory.fileName?.toString() == "joysong-server") {
        normalizedDirectory.parent ?: normalizedDirectory
    } else {
        normalizedDirectory
    }
    val slug = worktree.fileName?.toString().orEmpty()
        .lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
    require(slug.isNotBlank()) { "Cannot derive an isolated MySQL database name from $worktree" }

    val maximumSlugLength = MYSQL_DATABASE_NAME_LIMIT - DATABASE_PREFIX.length - DATABASE_SUFFIX.length
    val safeSlug = if (slug.length <= maximumSlugLength) {
        slug
    } else {
        val hash = UUID.nameUUIDFromBytes(slug.toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
            .take(8)
        "${slug.take(maximumSlugLength - hash.length - 1).trimEnd('_')}_$hash"
    }
    return "$DATABASE_PREFIX$safeSlug$DATABASE_SUFFIX".also {
        require(it.startsWith(DATABASE_PREFIX) && it.length <= MYSQL_DATABASE_NAME_LIMIT)
    }
}

private const val DATABASE_PREFIX = "myapp_worktree_"
private const val DATABASE_SUFFIX = "_query"
private const val MYSQL_DATABASE_NAME_LIMIT = 64

class InstitutionMembershipReadDatabaseNameTest {
    @Test
    fun `database name is derived from the worktree and remains MySQL safe`() {
        assertEquals(
            "myapp_worktree_institution_membership_application_review_query",
            membershipReadDatabaseName(Path.of("institution-membership-application-review", "joysong-server"))
        )

        val longName = membershipReadDatabaseName(
            Path.of("membership-worktree-with-a-very-long-shared-prefix-and-a-unique-tail", "joysong-server")
        )
        assertTrue(longName.startsWith("myapp_worktree_"))
        assertTrue(longName.endsWith("_query"))
        assertTrue(longName.length <= 64)
        assertTrue(longName.matches(Regex("[a-z0-9_]+")))
    }
}
