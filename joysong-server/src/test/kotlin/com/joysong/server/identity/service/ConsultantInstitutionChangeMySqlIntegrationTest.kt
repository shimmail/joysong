package com.joysong.server.identity.service

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier
import javax.sql.DataSource

@Tag("mysql-integration")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConsultantInstitutionChangeMySqlIntegrationTest {
    private lateinit var jdbc: JdbcTemplate

    @BeforeAll
    fun migrateIsolatedDatabase() {
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
    }

    @Test
    fun `approving leave atomically revokes membership resets only target session and closes request`() {
        val fixture = seedActiveRelationship("atomic", includeOtherInstitution = true)
        jdbc.update(
            "INSERT INTO wallets (owner_type, owner_id, currency, available_minor) VALUES ('CONSULTANT', ?, 'USD', 65400)",
            fixture.consultantId
        )
        jdbc.update(
            """
            INSERT INTO orders
                (id, user_id, project_name, consultant_id, consultant_name, status)
            VALUES ('atomic-order', ?, 'Historical Project', ?, 'Consultant', 'COMPLETED')
            """.trimIndent(),
            fixture.customerId,
            fixture.consultantId
        )
        jdbc.update(
            """
            INSERT INTO consultant_institution_change_requests
                (id, consultant_id, institution_id, action, status, request_note, review_note,
                 submitted_by, reviewed_by, reviewed_at)
            VALUES ('atomic-history-request', ?, ?, 'JOIN', 'REJECTED', 'old join', 'historical rejection',
                    ?, ?, NOW())
            """.trimIndent(),
            fixture.consultantId,
            fixture.otherInstitutionId,
            fixture.consultantId,
            fixture.reviewerId
        )
        serviceContext().use { context ->
            val request = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.LEAVE,
                "leave target"
            )

            context.service.review(adminActor(fixture.reviewerId), request.id, MembershipRequestDecision.APPROVED, "approved")

            assertEquals("REVOKED", membershipStatus(fixture.consultantId, fixture.institutionId))
            assertEquals("USER", sessionRole("atomic-target-session"))
            assertNull(sessionInstitution("atomic-target-session"))
            assertEquals("CONSULTANT", sessionRole("atomic-other-session"))
            assertEquals(fixture.otherInstitutionId, sessionInstitution("atomic-other-session"))
            assertEquals("APPROVED", membershipStatus(fixture.consultantId, fixture.otherInstitutionId!!))
            assertEquals(
                "CONSULTANT",
                jdbc.queryForObject("SELECT role FROM users WHERE id = ?", String::class.java, fixture.consultantId)
            )
            assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                    "SELECT status FROM user_roles WHERE user_id = ? AND role_code = 'CONSULTANT'",
                    String::class.java,
                    fixture.consultantId
                )
            )
            assertEquals(
                65400L,
                jdbc.queryForObject(
                    "SELECT available_minor FROM wallets WHERE owner_type = 'CONSULTANT' AND owner_id = ? AND currency = 'USD'",
                    Long::class.java,
                    fixture.consultantId
                )
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE id = 'atomic-order' AND consultant_id = ? AND status = 'COMPLETED'",
                    Int::class.java,
                    fixture.consultantId
                )
            )
            assertEquals("REJECTED", requestStatus("atomic-history-request"))
            assertEquals("APPROVED", requestStatus(request.id))
        }
    }

    @Test
    fun `review rejects institutions that became unverified or deleted and leaves requests pending`() {
        serviceContext().use { context ->
            val unverified = seedUsersAndInstitution("stale-unverified")
            val unverifiedRequest = context.service.submit(
                consultantActor(unverified.consultantId),
                unverified.institutionId,
                ConsultantInstitutionAction.JOIN,
                "join"
            )
            jdbc.update("UPDATE institutions SET is_verified = 0 WHERE id = ?", unverified.institutionId)

            assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
                context.service.review(
                    adminActor(unverified.reviewerId),
                    unverifiedRequest.id,
                    MembershipRequestDecision.APPROVED,
                    "approved"
                )
            }
            assertEquals("PENDING", requestStatus(unverifiedRequest.id))
            assertEquals(0, membershipCount(unverified.consultantId, unverified.institutionId))

            val deleted = seedUsersAndInstitution("stale-deleted")
            val deletedRequest = context.service.submit(
                consultantActor(deleted.consultantId),
                deleted.institutionId,
                ConsultantInstitutionAction.JOIN,
                "join"
            )
            jdbc.update("UPDATE institutions SET deleted_at = NOW() WHERE id = ?", deleted.institutionId)

            assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
                context.service.review(
                    adminActor(deleted.reviewerId),
                    deletedRequest.id,
                    MembershipRequestDecision.APPROVED,
                    "approved"
                )
            }
            assertEquals("PENDING", requestStatus(deletedRequest.id))
            assertEquals(0, membershipCount(deleted.consultantId, deleted.institutionId))
        }
    }

    @Test
    fun `conditional close failure rolls membership session and request back to pending`() {
        val fixture = seedActiveRelationship("rollback")
        serviceContext { delegate -> FailCloseStore(delegate) }.use { context ->
            val request = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.LEAVE,
                "rollback leave"
            )

            assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
                context.service.review(adminActor(fixture.reviewerId), request.id, MembershipRequestDecision.APPROVED, "approved")
            }

            assertEquals("APPROVED", membershipStatus(fixture.consultantId, fixture.institutionId))
            assertEquals("CONSULTANT", sessionRole("rollback-target-session"))
            assertEquals(fixture.institutionId, sessionInstitution("rollback-target-session"))
            assertEquals("PENDING", requestStatus(request.id))
        }
    }

    @Test
    fun `two concurrent submits create one pending row and one typed conflict`() {
        val fixture = seedUsersAndInstitution("submit-race")
        serviceContext().use { context ->
            val results = concurrently(2) {
                context.service.submit(
                    consultantActor(fixture.consultantId),
                    fixture.institutionId,
                    ConsultantInstitutionAction.JOIN,
                    "race"
                )
            }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                1,
                results.count { it.exceptionOrNull() is ConsultantInstitutionRequestConflictException }
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM consultant_institution_change_requests
                    WHERE consultant_id = ? AND institution_id = ? AND status = 'PENDING'
                    """.trimIndent(),
                    Int::class.java,
                    fixture.consultantId,
                    fixture.institutionId
                )
            )
        }
    }

    @Test
    fun `two concurrent reviews apply one relationship effect and return one typed conflict`() {
        val fixture = seedUsersAndInstitution("review-race")
        serviceContext().use { context ->
            val request = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.JOIN,
                "join"
            )
            val results = concurrently(2) {
                context.service.review(
                    adminActor(fixture.reviewerId),
                    request.id,
                    MembershipRequestDecision.APPROVED,
                    "approved"
                )
            }

            assertEquals(1, results.count { it.isSuccess })
            assertEquals(
                1,
                results.count { it.exceptionOrNull() is ConsultantInstitutionRequestConflictException }
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM institution_memberships
                    WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
                      AND status = 'APPROVED' AND revoked_at IS NULL
                    """.trimIndent(),
                    Int::class.java,
                    fixture.consultantId,
                    fixture.institutionId
                )
            )
            assertEquals("APPROVED", requestStatus(request.id))
        }
    }

    @Test
    fun `pending rejected and withdrawn leave preserve both relationships wallet and order history`() {
        val fixture = seedActiveRelationship("non-approved", includeOtherInstitution = true)
        jdbc.update(
            "INSERT INTO wallets (owner_type, owner_id, currency, available_minor) VALUES ('CONSULTANT', ?, 'USD', 32100)",
            fixture.consultantId
        )
        jdbc.update(
            """
            INSERT INTO orders
                (id, user_id, project_name, consultant_id, consultant_name, status)
            VALUES ('non-approved-order', ?, 'Historical Project', ?, 'Consultant', 'COMPLETED')
            """.trimIndent(),
            fixture.customerId,
            fixture.consultantId
        )

        serviceContext().use { context ->
            val withdrawn = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.LEAVE,
                "withdraw me"
            )
            context.service.withdraw(consultantActor(fixture.consultantId), withdrawn.id)

            val rejected = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.LEAVE,
                "reject me"
            )
            context.service.review(adminActor(fixture.reviewerId), rejected.id, MembershipRequestDecision.REJECTED, "not now")

            val pending = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.LEAVE,
                "pending"
            )

            assertEquals("WITHDRAWN", requestStatus(withdrawn.id))
            assertEquals("REJECTED", requestStatus(rejected.id))
            assertEquals("PENDING", requestStatus(pending.id))
            assertEquals("APPROVED", membershipStatus(fixture.consultantId, fixture.institutionId))
            assertEquals("APPROVED", membershipStatus(fixture.consultantId, fixture.otherInstitutionId!!))
            assertEquals(
                32100L,
                jdbc.queryForObject(
                    "SELECT available_minor FROM wallets WHERE owner_type = 'CONSULTANT' AND owner_id = ? AND currency = 'USD'",
                    Long::class.java,
                    fixture.consultantId
                )
            )
            assertEquals(
                1,
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM orders WHERE id = 'non-approved-order' AND consultant_id = ? AND status = 'COMPLETED'",
                    Int::class.java,
                    fixture.consultantId
                )
            )
            assertEquals(
                "ACTIVE",
                jdbc.queryForObject(
                    "SELECT status FROM user_roles WHERE user_id = ? AND role_code = 'CONSULTANT'",
                    String::class.java,
                    fixture.consultantId
                )
            )
        }
    }

    private fun seedUsersAndInstitution(prefix: String): Fixture {
        val fixture = Fixture(
            consultantId = "$prefix-consultant",
            reviewerId = "$prefix-reviewer",
            customerId = "$prefix-customer",
            institutionId = "$prefix-institution"
        )
        jdbc.update(
            """
            INSERT INTO users (id, password_hash, nickname, role) VALUES
                (?, 'hash', 'Consultant', 'CONSULTANT'),
                (?, 'hash', 'Reviewer', 'ADMIN'),
                (?, 'hash', 'Customer', 'USER')
            """.trimIndent(),
            fixture.consultantId,
            fixture.reviewerId,
            fixture.customerId
        )
        jdbc.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, 'CONSULTANT', 'ACTIVE')",
            fixture.consultantId
        )
        jdbc.update(
            "INSERT INTO institutions (id, name, is_verified) VALUES (?, ?, 1)",
            fixture.institutionId,
            "$prefix Institution"
        )
        return fixture
    }

    private fun seedActiveRelationship(prefix: String, includeOtherInstitution: Boolean = false): Fixture {
        var fixture = seedUsersAndInstitution(prefix)
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, 'CONSULTANT', 'APPROVED', ?, NOW())
            """.trimIndent(),
            "$prefix-membership",
            fixture.consultantId,
            fixture.institutionId,
            fixture.reviewerId
        )
        jdbc.update(
            """
            INSERT INTO auth_sessions
                (id, user_id, refresh_token_hash, active_role, active_institution_id, expires_at)
            VALUES (?, ?, ?, 'CONSULTANT', ?, '2030-01-01 00:00:00')
            """.trimIndent(),
            "$prefix-target-session",
            fixture.consultantId,
            "$prefix-target-token",
            fixture.institutionId
        )
        if (includeOtherInstitution) {
            val otherInstitutionId = "$prefix-other-institution"
            fixture = fixture.copy(otherInstitutionId = otherInstitutionId)
            jdbc.update(
                "INSERT INTO institutions (id, name, is_verified) VALUES (?, ?, 1)",
                otherInstitutionId,
                "$prefix Other Institution"
            )
            jdbc.update(
                """
                INSERT INTO institution_memberships
                    (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
                VALUES (?, ?, ?, 'CONSULTANT', 'APPROVED', ?, NOW())
                """.trimIndent(),
                "$prefix-other-membership",
                fixture.consultantId,
                otherInstitutionId,
                fixture.reviewerId
            )
            jdbc.update(
                """
                INSERT INTO auth_sessions
                    (id, user_id, refresh_token_hash, active_role, active_institution_id, expires_at)
                VALUES (?, ?, ?, 'CONSULTANT', ?, '2030-01-01 00:00:00')
                """.trimIndent(),
                "$prefix-other-session",
                fixture.consultantId,
                "$prefix-other-token",
                otherInstitutionId
            )
        }
        return fixture
    }

    private fun serviceContext(
        decorateStore: (ConsultantInstitutionChangeRequestStore) -> ConsultantInstitutionChangeRequestStore = { it }
    ): ServiceContext {
        val context = AnnotationConfigApplicationContext()
        context.register(TransactionConfiguration::class.java)
        context.registerBean(DataSource::class.java, Supplier { dataSource() })
        context.registerBean(JdbcTemplate::class.java, Supplier {
            JdbcTemplate(context.getBean(DataSource::class.java))
        })
        context.registerBean(ConsultantInstitutionRelationshipOperations::class.java, Supplier {
            ConsultantInstitutionRelationshipService(context.getBean(JdbcTemplate::class.java))
        })
        context.registerBean(ConsultantInstitutionChangeRequestStore::class.java, Supplier {
            decorateStore(JdbcConsultantInstitutionChangeRequestStore(context.getBean(JdbcTemplate::class.java)))
        })
        context.registerBean(ConsultantInstitutionChangeRequestService::class.java, Supplier {
            ConsultantInstitutionChangeRequestService(
                context.getBean(ConsultantInstitutionChangeRequestStore::class.java),
                context.getBean(ConsultantInstitutionRelationshipOperations::class.java)
            )
        })
        context.refresh()
        return ServiceContext(context, context.getBean(ConsultantInstitutionChangeRequestService::class.java))
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

    private fun consultantActor(consultantId: String) = ManagementActor(
        userId = consultantId,
        isAdmin = false,
        activeRoles = setOf("CONSULTANT"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun adminActor(reviewerId: String) = ManagementActor(
        userId = reviewerId,
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun membershipStatus(consultantId: String, institutionId: String): String? = jdbc.queryForObject(
        """
        SELECT status FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
        """.trimIndent(),
        String::class.java,
        consultantId,
        institutionId
    )

    private fun requestStatus(id: String): String? = jdbc.queryForObject(
        "SELECT status FROM consultant_institution_change_requests WHERE id = ?",
        String::class.java,
        id
    )

    private fun membershipCount(consultantId: String, institutionId: String): Int = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
        """.trimIndent(),
        Int::class.java,
        consultantId,
        institutionId
    )

    private fun sessionRole(id: String): String? = jdbc.queryForObject(
        "SELECT active_role FROM auth_sessions WHERE id = ?",
        String::class.java,
        id
    )

    private fun sessionInstitution(id: String): String? = jdbc.queryForObject(
        "SELECT active_institution_id FROM auth_sessions WHERE id = ?",
        String::class.java,
        id
    )

    private fun <T> concurrently(count: Int, operation: () -> T): List<Result<T>> {
        val executor = Executors.newFixedThreadPool(count)
        val barrier = CyclicBarrier(count)
        return try {
            val futures = List(count) {
                executor.submit<Result<T>> {
                    barrier.await(10, TimeUnit.SECONDS)
                    runCatching(operation)
                }
            }
            futures.map { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class Fixture(
        val consultantId: String,
        val reviewerId: String,
        val customerId: String,
        val institutionId: String,
        val otherInstitutionId: String? = null
    )

    private data class ServiceContext(
        val context: AnnotationConfigApplicationContext,
        val service: ConsultantInstitutionChangeRequestService
    ) : AutoCloseable {
        override fun close() = context.close()
    }

    private class FailCloseStore(
        private val delegate: ConsultantInstitutionChangeRequestStore
    ) : ConsultantInstitutionChangeRequestStore by delegate {
        override fun changeStatus(
            id: String,
            status: ConsultantInstitutionRequestStatus,
            actorId: String,
            reviewNote: String
        ): Boolean {
            check(delegate.changeStatus(id, status, actorId, reviewNote))
            throw ConsultantInstitutionRequestConflictException("injected failure after conditional close")
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    class TransactionConfiguration {
        @Bean
        fun transactionManager(dataSource: DataSource): PlatformTransactionManager =
            DataSourceTransactionManager(dataSource)
    }

    companion object {
        private const val DATABASE_NAME = "myapp_worktree_institution_membership_application_review_service"
        private const val BOOTSTRAP_DATABASE = "myapp_worktree_consultant_service_bootstrap"

        @Container
        @JvmField
        val mysql = ConsultantServiceMySqlContainer("mysql:8.0.39")
            .withDatabaseName(BOOTSTRAP_DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class ConsultantServiceMySqlContainer(imageName: String) :
    MySQLContainer<ConsultantServiceMySqlContainer>(imageName)
