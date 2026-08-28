package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.sql.Timestamp
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
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
    fun `stale legal representative actor cannot review after current authority was revoked`() {
        val fixture = seedUsersAndInstitution("stale-legal-authority")
        jdbc.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'ACTIVE')",
            fixture.reviewerId
        )
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'APPROVED', ?, NOW())
            """.trimIndent(),
            "stale-legal-authority-membership",
            fixture.reviewerId,
            fixture.institutionId,
            fixture.reviewerId
        )
        serviceContext().use { context ->
            val request = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.JOIN,
                "join"
            )
            val staleActor = legalActor(fixture.reviewerId, fixture.institutionId)
            jdbc.update(
                "UPDATE user_roles SET status = 'REVOKED' WHERE user_id = ? AND role_code = 'INSTITUTION_LEGAL_REPRESENTATIVE'",
                fixture.reviewerId
            )
            jdbc.update(
                "UPDATE institution_memberships SET status = 'REVOKED', revoked_at = NOW() WHERE id = ?",
                "stale-legal-authority-membership"
            )

            assertThrows(org.springframework.security.access.AccessDeniedException::class.java) {
                context.service.review(
                    staleActor,
                    request.id,
                    MembershipRequestDecision.APPROVED,
                    "approved"
                )
            }

            assertEquals("PENDING", requestStatus(request.id))
            assertEquals(0, activeMembershipCount(fixture.consultantId, fixture.institutionId))
        }
    }

    @Test
    fun `conditional close failure rolls membership session and request back to pending`() {
        val fixture = seedActiveRelationship("rollback")
        serviceContext(decorateStore = { delegate -> FailCloseStore(delegate) }).use { context ->
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
    fun `submit join racing admin bind leaves exactly one legal outcome`() {
        val fixture = seedUsersAndInstitution("submit-bind-race", userAccountRole = "USER")
        lateinit var pausingStore: PauseBeforeCreateStore
        serviceContext(decorateStore = { delegate ->
            PauseBeforeCreateStore(delegate).also { pausingStore = it }
        }).use { context ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val submit = executor.submit<Result<Any>> {
                    runCatching {
                        context.service.submit(
                            consultantActor(fixture.consultantId),
                            fixture.institutionId,
                            ConsultantInstitutionAction.JOIN,
                            "join"
                        )
                    }
                }
                assertTrue(pausingStore.beforeCreate.await(10, TimeUnit.SECONDS))
                val bind = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.bindConsultant(
                            fixture.consultantId,
                            fixture.institutionId,
                            fixture.reviewerId
                        )
                    }
                }
                assertAdminBlocked(bind)
                pausingStore.continueCreate.countDown()

                val results = listOf(submit.get(30, TimeUnit.SECONDS), bind.get(30, TimeUnit.SECONDS))
                val pending = pendingRequestCount(fixture.consultantId, fixture.institutionId)
                val approved = activeMembershipCount(fixture.consultantId, fixture.institutionId)

                assertEquals(1, results.count { it.isSuccess })
                assertEquals(1, pending + approved)
                assertTrue((pending == 1 && approved == 0) || (pending == 0 && approved == 1))
                assertEquals(
                    1,
                    results.count { it.exceptionOrNull() is ConsultantInstitutionRequestConflictException }
                )
            } finally {
                pausingStore.continueCreate.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `submit leave racing admin force revoke leaves exactly one legal outcome`() {
        val fixture = seedActiveRelationship("submit-revoke-race")
        lateinit var pausingStore: PauseBeforeCreateStore
        serviceContext(decorateStore = { delegate ->
            PauseBeforeCreateStore(delegate).also { pausingStore = it }
        }).use { context ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val submit = executor.submit<Result<Any>> {
                    runCatching {
                        context.service.submit(
                            consultantActor(fixture.consultantId),
                            fixture.institutionId,
                            ConsultantInstitutionAction.LEAVE,
                            "leave"
                        )
                    }
                }
                assertTrue(pausingStore.beforeCreate.await(10, TimeUnit.SECONDS))
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeMembership(
                            "submit-revoke-race-membership",
                            fixture.reviewerId
                        )
                    }
                }
                assertAdminBlocked(revoke)
                pausingStore.continueCreate.countDown()

                val results = listOf(submit.get(30, TimeUnit.SECONDS), revoke.get(30, TimeUnit.SECONDS))
                val pending = pendingRequestCount(fixture.consultantId, fixture.institutionId)
                val status = membershipStatus(fixture.consultantId, fixture.institutionId)

                assertEquals(1, results.count { it.isSuccess })
                assertTrue((pending == 1 && status == "APPROVED") || (pending == 0 && status == "REVOKED"))
                assertEquals(
                    1,
                    results.count { it.exceptionOrNull() is ConsultantInstitutionRequestConflictException }
                )
                assertEquals(
                    0,
                    jdbc.queryForObject(
                        "SELECT COUNT(*) FROM consultant_institution_change_requests WHERE consultant_id = ? AND institution_id = ? AND status = 'WITHDRAWN'",
                        Int::class.java,
                        fixture.consultantId,
                        fixture.institutionId
                    )
                )
            } finally {
                pausingStore.continueCreate.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `doctor submit first serializes role revoke behind the pending ledger`() {
        val fixture = seedDoctor("doctor-submit-first")
        lateinit var pausingStore: PauseBeforeDoctorCreateStore
        lateinit var pausingRelationships: PausingDoctorRelationships
        serviceContext(
            decorateDoctorStore = { delegate ->
                PauseBeforeDoctorCreateStore(delegate).also { pausingStore = it }
            },
            decorateDoctorRelationships = { delegate ->
                PausingDoctorRelationships(delegate).also { pausingRelationships = it }
            }
        ).use { context ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val submit = executor.submit<Result<Any>> {
                    runCatching {
                        context.doctorService.submit(
                            doctorActor(fixture.doctorId),
                            fixture.institutionId,
                            DoctorInstitutionAction.JOIN,
                            "join"
                        )
                    }
                }
                assertTrue(pausingStore.beforeCreate.await(10, TimeUnit.SECONDS))
                pausingRelationships.observeNextUserLockAttempt.set(true)
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeRole(
                            fixture.doctorId,
                            "DOCTOR",
                            fixture.reviewerId,
                            "revoked"
                        )
                    }
                }
                assertTrue(pausingRelationships.userLockAttempted.await(10, TimeUnit.SECONDS))
                assertAdminBlocked(revoke)
                pausingStore.continueCreate.countDown()

                val submitResult = submit.get(30, TimeUnit.SECONDS)
                val revokeResult = revoke.get(30, TimeUnit.SECONDS)
                assertTrue(submitResult.isSuccess)
                assertTrue(revokeResult.exceptionOrNull() is DoctorInstitutionRequestConflictException)
                assertEquals("ACTIVE", doctorRoleStatus(fixture.doctorId))
                assertEquals(1, doctorVerified(fixture.doctorId))
                assertEquals(1, pendingDoctorRequestCount(fixture.doctorId, fixture.institutionId))
            } finally {
                pausingStore.continueCreate.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `doctor role revoke first makes a concurrent submit observe inactive current identity`() {
        val fixture = seedDoctor("doctor-role-first")
        lateinit var pausingRelationships: PausingDoctorRelationships
        serviceContext(
            decorateDoctorRelationships = { delegate ->
                PausingDoctorRelationships(delegate).also { pausingRelationships = it }
            }
        ).use { context ->
            pausingRelationships.pauseNextUserLock.set(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeRole(
                            fixture.doctorId,
                            "DOCTOR",
                            fixture.reviewerId,
                            "revoked"
                        )
                    }
                }
                assertTrue(pausingRelationships.userLockAcquired.await(10, TimeUnit.SECONDS))
                val submitStarted = CountDownLatch(1)
                val submit = executor.submit<Result<Any>> {
                    submitStarted.countDown()
                    runCatching {
                        context.doctorService.submit(
                            doctorActor(fixture.doctorId),
                            fixture.institutionId,
                            DoctorInstitutionAction.JOIN,
                            "join"
                        )
                    }
                }
                assertTrue(submitStarted.await(10, TimeUnit.SECONDS))
                pausingRelationships.continueAfterUserLock.countDown()

                assertTrue(revoke.get(30, TimeUnit.SECONDS).isSuccess)
                assertTrue(submit.get(30, TimeUnit.SECONDS).exceptionOrNull() is AccessDeniedException)
                assertEquals("REVOKED", doctorRoleStatus(fixture.doctorId))
                assertEquals(0, doctorVerified(fixture.doctorId))
                assertEquals(0, pendingDoctorRequestCount(fixture.doctorId, fixture.institutionId))
            } finally {
                pausingRelationships.continueAfterUserLock.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `doctor leave submit first preserves relationship and session when practice revoke conflicts`() {
        val fixture = seedDoctor("doctor-leave-first", activeRelationship = true)
        lateinit var pausingStore: PauseBeforeDoctorCreateStore
        lateinit var pausingRelationships: PausingDoctorRelationships
        serviceContext(
            decorateDoctorStore = { delegate ->
                PauseBeforeDoctorCreateStore(delegate).also { pausingStore = it }
            },
            decorateDoctorRelationships = { delegate ->
                PausingDoctorRelationships(delegate).also { pausingRelationships = it }
            }
        ).use { context ->
            val executor = Executors.newFixedThreadPool(2)
            try {
                val submit = executor.submit<Result<Any>> {
                    runCatching {
                        context.doctorService.submit(
                            doctorActor(fixture.doctorId),
                            fixture.institutionId,
                            DoctorInstitutionAction.LEAVE,
                            "leave"
                        )
                    }
                }
                assertTrue(pausingStore.beforeCreate.await(10, TimeUnit.SECONDS))
                pausingRelationships.observeNextPairLockAttempt.set(true)
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeDoctorPractice(fixture.practiceId, fixture.reviewerId)
                    }
                }
                assertTrue(pausingRelationships.pairLockAttempted.await(10, TimeUnit.SECONDS))
                assertAdminBlocked(revoke)
                pausingStore.continueCreate.countDown()

                assertTrue(submit.get(30, TimeUnit.SECONDS).isSuccess)
                assertTrue(revoke.get(30, TimeUnit.SECONDS).exceptionOrNull() is DoctorInstitutionRequestConflictException)
                assertEquals("APPROVED", doctorRelationshipStatus(fixture.doctorId, fixture.institutionId))
                assertEquals("DOCTOR", sessionRole(fixture.sessionId))
                assertEquals(fixture.institutionId, sessionInstitution(fixture.sessionId))
                assertEquals(1, pendingDoctorRequestCount(fixture.doctorId, fixture.institutionId))
            } finally {
                pausingStore.continueCreate.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `doctor practice revoke first cleans the target before concurrent leave eligibility`() {
        val fixture = seedDoctor("doctor-practice-first", activeRelationship = true)
        lateinit var pausingRelationships: PausingDoctorRelationships
        serviceContext(
            decorateDoctorRelationships = { delegate ->
                PausingDoctorRelationships(delegate).also { pausingRelationships = it }
            }
        ).use { context ->
            pausingRelationships.pauseNextPairLock.set(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeDoctorPractice(fixture.practiceId, fixture.reviewerId)
                    }
                }
                assertTrue(pausingRelationships.pairLockAcquired.await(10, TimeUnit.SECONDS))
                val submitStarted = CountDownLatch(1)
                val submit = executor.submit<Result<Any>> {
                    submitStarted.countDown()
                    runCatching {
                        context.doctorService.submit(
                            doctorActor(fixture.doctorId),
                            fixture.institutionId,
                            DoctorInstitutionAction.LEAVE,
                            "leave"
                        )
                    }
                }
                assertTrue(submitStarted.await(10, TimeUnit.SECONDS))
                pausingRelationships.continueAfterPairLock.countDown()

                assertTrue(revoke.get(30, TimeUnit.SECONDS).isSuccess)
                assertTrue(submit.get(30, TimeUnit.SECONDS).exceptionOrNull() is DoctorInstitutionRequestConflictException)
                assertEquals("REVOKED", doctorRelationshipStatus(fixture.doctorId, fixture.institutionId))
                assertEquals("USER", sessionRole(fixture.sessionId))
                assertNull(sessionInstitution(fixture.sessionId))
                assertEquals(0, pendingDoctorRequestCount(fixture.doctorId, fixture.institutionId))
            } finally {
                pausingRelationships.continueAfterPairLock.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `legal revoke first makes stale actor review fail under the shared user lock`() {
        val fixture = seedDoctor("doctor-legal-first")
        seedLegalAuthority(fixture)
        lateinit var pausingRelationships: PausingDoctorRelationships
        serviceContext(
            decorateDoctorRelationships = { delegate ->
                PausingDoctorRelationships(delegate).also { pausingRelationships = it }
            }
        ).use { context ->
            val request = context.doctorService.submit(
                doctorActor(fixture.doctorId),
                fixture.institutionId,
                DoctorInstitutionAction.JOIN,
                "join"
            )
            val staleActor = legalActor(fixture.reviewerId, fixture.institutionId)
            pausingRelationships.pauseNextUserLock.set(true)
            val executor = Executors.newFixedThreadPool(2)
            try {
                val revoke = executor.submit<Result<Any>> {
                    runCatching {
                        context.adminService.revokeRole(
                            fixture.reviewerId,
                            "INSTITUTION_LEGAL_REPRESENTATIVE",
                            fixture.customerId,
                            "revoked"
                        )
                    }
                }
                assertTrue(pausingRelationships.userLockAcquired.await(10, TimeUnit.SECONDS))
                val reviewStarted = CountDownLatch(1)
                val review = executor.submit<Result<Any>> {
                    reviewStarted.countDown()
                    runCatching {
                        context.doctorService.review(
                            staleActor,
                            request.id,
                            MembershipRequestDecision.APPROVED,
                            "approved"
                        )
                    }
                }
                assertTrue(reviewStarted.await(10, TimeUnit.SECONDS))
                pausingRelationships.continueAfterUserLock.countDown()

                assertTrue(revoke.get(30, TimeUnit.SECONDS).isSuccess)
                assertTrue(review.get(30, TimeUnit.SECONDS).exceptionOrNull() is org.springframework.security.access.AccessDeniedException)
                assertEquals("PENDING", doctorRequestStatus(request.id))
                assertEquals(0, activeDoctorRelationshipCount(fixture.doctorId, fixture.institutionId))
            } finally {
                pausingRelationships.continueAfterUserLock.countDown()
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `admin force revoke cleans an inactive target institution and preserves unrelated identity history`() {
        val fixture = seedActiveRelationship("force-inactive", includeOtherInstitution = true)
        jdbc.update(
            "INSERT INTO wallets (owner_type, owner_id, currency, available_minor) VALUES ('CONSULTANT', ?, 'USD', 77700)",
            fixture.consultantId
        )
        jdbc.update(
            """
            INSERT INTO orders (id, user_id, project_name, consultant_id, consultant_name, status)
            VALUES ('force-inactive-order', ?, 'Historical Project', ?, 'Consultant', 'COMPLETED')
            """.trimIndent(),
            fixture.customerId,
            fixture.consultantId
        )
        jdbc.update(
            """
            INSERT INTO consultant_institution_change_requests
                (id, consultant_id, institution_id, action, status, request_note, review_note,
                 submitted_by, reviewed_by, reviewed_at)
            VALUES ('force-inactive-history', ?, ?, 'JOIN', 'REJECTED', 'old', 'rejected', ?, ?, NOW())
            """.trimIndent(),
            fixture.consultantId,
            fixture.institutionId,
            fixture.consultantId,
            fixture.reviewerId
        )
        jdbc.update(
            "UPDATE institutions SET is_verified = 0, deleted_at = NOW() WHERE id = ?",
            fixture.institutionId
        )

        serviceContext().use { context ->
            context.adminService.revokeMembership("force-inactive-membership", fixture.reviewerId)
        }

        assertEquals("REVOKED", membershipStatus(fixture.consultantId, fixture.institutionId))
        assertEquals("USER", sessionRole("force-inactive-target-session"))
        assertEquals("APPROVED", membershipStatus(fixture.consultantId, fixture.otherInstitutionId!!))
        assertEquals("CONSULTANT", sessionRole("force-inactive-other-session"))
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject(
                "SELECT status FROM user_roles WHERE user_id = ? AND role_code = 'CONSULTANT'",
                String::class.java,
                fixture.consultantId
            )
        )
        assertEquals(
            77700L,
            jdbc.queryForObject(
                "SELECT available_minor FROM wallets WHERE owner_type = 'CONSULTANT' AND owner_id = ? AND currency = 'USD'",
                Long::class.java,
                fixture.consultantId
            )
        )
        assertEquals(
            1,
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE id = 'force-inactive-order' AND status = 'COMPLETED'",
                Int::class.java
            )
        )
        assertEquals("REJECTED", requestStatus("force-inactive-history"))
    }

    @Test
    fun `approving join restores the same revoked membership with reviewer metadata`() {
        val fixture = seedUsersAndInstitution("restore-join")
        val membershipId = "restore-join-membership"
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status,
                 confirmed_by, confirmed_at, revoked_at)
            VALUES (?, ?, ?, 'CONSULTANT', 'REVOKED', ?,
                    '2025-01-02 03:04:05', '2025-02-03 04:05:06')
            """.trimIndent(),
            membershipId,
            fixture.consultantId,
            fixture.institutionId,
            fixture.customerId
        )

        serviceContext().use { context ->
            val request = context.service.submit(
                consultantActor(fixture.consultantId),
                fixture.institutionId,
                ConsultantInstitutionAction.JOIN,
                "restore relationship"
            )
            context.service.review(
                adminActor(fixture.reviewerId),
                request.id,
                MembershipRequestDecision.APPROVED,
                "approved"
            )

            val memberships = jdbc.queryForList(
                """
                SELECT id, status, confirmed_by, confirmed_at, revoked_at
                FROM institution_memberships
                WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
                """.trimIndent(),
                fixture.consultantId,
                fixture.institutionId
            )
            assertEquals(1, memberships.size)
            assertEquals(membershipId, memberships.single()["id"])
            assertEquals("APPROVED", memberships.single()["status"])
            assertEquals(fixture.reviewerId, memberships.single()["confirmed_by"])
            assertTrue(memberships.single()["confirmed_at"] != null)
            assertTrue(memberships.single()["confirmed_at"] != Timestamp.valueOf("2025-01-02 03:04:05"))
            assertNull(memberships.single()["revoked_at"])
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

    private fun seedUsersAndInstitution(prefix: String, userAccountRole: String = "CONSULTANT"): Fixture {
        val fixture = Fixture(
            consultantId = "$prefix-consultant",
            reviewerId = "$prefix-reviewer",
            customerId = "$prefix-customer",
            institutionId = "$prefix-institution"
        )
        jdbc.update(
            """
            INSERT INTO users (id, password_hash, nickname, role) VALUES
                (?, 'hash', 'Consultant', ?),
                (?, 'hash', 'Reviewer', 'ADMIN'),
                (?, 'hash', 'Customer', 'USER')
            """.trimIndent(),
            fixture.consultantId,
            userAccountRole,
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

    private fun seedDoctor(prefix: String, activeRelationship: Boolean = false): DoctorFixture {
        val fixture = DoctorFixture(
            doctorId = "$prefix-doctor",
            reviewerId = "$prefix-reviewer",
            customerId = "$prefix-customer",
            institutionId = "$prefix-institution",
            practiceId = "$prefix-practice",
            sessionId = "$prefix-doctor-session"
        )
        jdbc.update(
            """
            INSERT INTO users (id, password_hash, nickname, role) VALUES
                (?, 'hash', 'Doctor', 'DOCTOR'),
                (?, 'hash', 'Reviewer', 'ADMIN'),
                (?, 'hash', 'Customer', 'USER')
            """.trimIndent(),
            fixture.doctorId,
            fixture.reviewerId,
            fixture.customerId
        )
        jdbc.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, 'DOCTOR', 'ACTIVE')",
            fixture.doctorId
        )
        jdbc.update(
            "INSERT INTO doctors (id, name, is_verified) VALUES (?, 'Doctor', 1)",
            fixture.doctorId
        )
        jdbc.update(
            "INSERT INTO institutions (id, name, is_verified) VALUES (?, ?, 1)",
            fixture.institutionId,
            "$prefix Institution"
        )
        if (activeRelationship) {
            jdbc.update(
                """
                INSERT INTO doctor_institutions
                    (id, doctor_id, institution_id, is_primary, status, confirmed_by, confirmed_at)
                VALUES (?, ?, ?, 1, 'APPROVED', ?, NOW())
                """.trimIndent(),
                fixture.practiceId,
                fixture.doctorId,
                fixture.institutionId,
                fixture.reviewerId
            )
            jdbc.update(
                """
                INSERT INTO auth_sessions
                    (id, user_id, refresh_token_hash, active_role, active_institution_id, expires_at)
                VALUES (?, ?, ?, 'DOCTOR', ?, '2030-01-01 00:00:00')
                """.trimIndent(),
                fixture.sessionId,
                fixture.doctorId,
                "$prefix-doctor-token",
                fixture.institutionId
            )
        }
        return fixture
    }

    private fun seedLegalAuthority(fixture: DoctorFixture) {
        jdbc.update(
            "INSERT INTO user_roles (user_id, role_code, status) VALUES (?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'ACTIVE')",
            fixture.reviewerId
        )
        jdbc.update(
            """
            INSERT INTO institution_memberships
                (id, user_id, institution_id, member_role, status, confirmed_by, confirmed_at)
            VALUES (?, ?, ?, 'INSTITUTION_LEGAL_REPRESENTATIVE', 'APPROVED', ?, NOW())
            """.trimIndent(),
            "${fixture.practiceId}-legal",
            fixture.reviewerId,
            fixture.institutionId,
            fixture.customerId
        )
    }

    private fun serviceContext(
        decorateStore: (ConsultantInstitutionChangeRequestStore) -> ConsultantInstitutionChangeRequestStore = { it },
        decorateDoctorStore: (DoctorInstitutionChangeRequestStore) -> DoctorInstitutionChangeRequestStore = { it },
        decorateDoctorRelationships: (DoctorInstitutionRelationshipOperations) -> DoctorInstitutionRelationshipOperations = { it }
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
        context.registerBean(DoctorInstitutionRelationshipOperations::class.java, Supplier {
            decorateDoctorRelationships(DoctorInstitutionRelationshipService(context.getBean(JdbcTemplate::class.java)))
        })
        context.registerBean(ConsultantInstitutionChangeRequestStore::class.java, Supplier {
            decorateStore(JdbcConsultantInstitutionChangeRequestStore(context.getBean(JdbcTemplate::class.java)))
        })
        context.registerBean(InstitutionRelationshipReviewAuthorityOperations::class.java, Supplier {
            InstitutionRelationshipReviewAuthorityService(context.getBean(JdbcTemplate::class.java))
        })
        context.registerBean(DoctorInstitutionChangeRequestStore::class.java, Supplier {
            decorateDoctorStore(JdbcDoctorInstitutionChangeRequestStore(context.getBean(JdbcTemplate::class.java)))
        })
        context.registerBean(DoctorInstitutionChangeRequestService::class.java, Supplier {
            DoctorInstitutionChangeRequestService(
                context.getBean(DoctorInstitutionChangeRequestStore::class.java),
                context.getBean(DoctorInstitutionRelationshipOperations::class.java),
                context.getBean(InstitutionRelationshipReviewAuthorityOperations::class.java),
                mockk<BusinessNotificationService>(relaxed = true)
            )
        })
        context.registerBean(ConsultantInstitutionChangeRequestService::class.java, Supplier {
            ConsultantInstitutionChangeRequestService(
                context.getBean(ConsultantInstitutionChangeRequestStore::class.java),
                context.getBean(ConsultantInstitutionRelationshipOperations::class.java),
                context.getBean(InstitutionRelationshipReviewAuthorityOperations::class.java),
                mockk<BusinessNotificationService>(relaxed = true)
            )
        })
        context.registerBean(AdminIdentityService::class.java, Supplier {
            AdminIdentityService(
                context.getBean(JdbcTemplate::class.java),
                ObjectMapper(),
                context.getBean(DoctorInstitutionRelationshipOperations::class.java),
                mockk<WalletRepository>(relaxed = true),
                context.getBean(ConsultantInstitutionRelationshipOperations::class.java),
                mockk<BusinessNotificationService>(relaxed = true),
                mockk<AccountLifecycleGuard>().also { guard ->
                    every { guard.requireActiveForWrite(any()) } answers {
                        UserEntity(id = firstArg(), passwordHash = "test", role = "USER")
                    }
                },
            )
        })
        context.refresh()
        return ServiceContext(
            context,
            context.getBean(ConsultantInstitutionChangeRequestService::class.java),
            context.getBean(DoctorInstitutionChangeRequestService::class.java),
            context.getBean(AdminIdentityService::class.java)
        )
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

    private fun doctorActor(doctorId: String) = ManagementActor(
        userId = doctorId,
        isAdmin = false,
        activeRoles = setOf("DOCTOR"),
        doctorId = doctorId,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = setOf(doctorId)
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

    private fun legalActor(reviewerId: String, institutionId: String) = ManagementActor(
        userId = reviewerId,
        isAdmin = false,
        activeRoles = setOf("INSTITUTION_LEGAL_REPRESENTATIVE"),
        doctorId = null,
        managedInstitutionIds = setOf(institutionId),
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

    private fun activeMembershipCount(consultantId: String, institutionId: String): Int = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM institution_memberships
        WHERE user_id = ? AND institution_id = ? AND member_role = 'CONSULTANT'
          AND status = 'APPROVED' AND revoked_at IS NULL
        """.trimIndent(),
        Int::class.java,
        consultantId,
        institutionId
    )

    private fun pendingRequestCount(consultantId: String, institutionId: String): Int = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM consultant_institution_change_requests
        WHERE consultant_id = ? AND institution_id = ? AND status = 'PENDING'
        """.trimIndent(),
        Int::class.java,
        consultantId,
        institutionId
    )

    private fun pendingDoctorRequestCount(doctorId: String, institutionId: String): Int = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM doctor_institution_change_requests
        WHERE doctor_id = ? AND institution_id = ? AND status = 'PENDING'
        """.trimIndent(),
        Int::class.java,
        doctorId,
        institutionId
    )

    private fun doctorRequestStatus(id: String): String? = jdbc.queryForObject(
        "SELECT status FROM doctor_institution_change_requests WHERE id = ?",
        String::class.java,
        id
    )

    private fun doctorRoleStatus(doctorId: String): String? = jdbc.queryForObject(
        "SELECT status FROM user_roles WHERE user_id = ? AND role_code = 'DOCTOR'",
        String::class.java,
        doctorId
    )

    private fun doctorVerified(doctorId: String): Int = jdbc.queryForObject(
        "SELECT is_verified FROM doctors WHERE id = ?",
        Int::class.java,
        doctorId
    )

    private fun doctorRelationshipStatus(doctorId: String, institutionId: String): String? = jdbc.queryForObject(
        "SELECT status FROM doctor_institutions WHERE doctor_id = ? AND institution_id = ?",
        String::class.java,
        doctorId,
        institutionId
    )

    private fun activeDoctorRelationshipCount(doctorId: String, institutionId: String): Int = jdbc.queryForObject(
        """
        SELECT COUNT(*) FROM doctor_institutions
        WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
          AND revoked_at IS NULL AND deleted_at IS NULL
        """.trimIndent(),
        Int::class.java,
        doctorId,
        institutionId
    )

    private fun assertAdminBlocked(future: java.util.concurrent.Future<Result<Any>>) {
        assertThrows(TimeoutException::class.java) {
            future.get(750, TimeUnit.MILLISECONDS)
        }
    }

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

    private data class DoctorFixture(
        val doctorId: String,
        val reviewerId: String,
        val customerId: String,
        val institutionId: String,
        val practiceId: String,
        val sessionId: String
    )

    private data class ServiceContext(
        val context: AnnotationConfigApplicationContext,
        val service: ConsultantInstitutionChangeRequestService,
        val doctorService: DoctorInstitutionChangeRequestService,
        val adminService: AdminIdentityService
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

    private class PauseBeforeCreateStore(
        private val delegate: ConsultantInstitutionChangeRequestStore
    ) : ConsultantInstitutionChangeRequestStore by delegate {
        val beforeCreate = CountDownLatch(1)
        val continueCreate = CountDownLatch(1)

        override fun create(
            consultantId: String,
            institutionId: String,
            action: ConsultantInstitutionAction,
            requestNote: String
        ): ConsultantInstitutionChangeRequestView {
            beforeCreate.countDown()
            check(continueCreate.await(10, TimeUnit.SECONDS)) { "Timed out waiting to continue request insert" }
            return delegate.create(consultantId, institutionId, action, requestNote)
        }
    }

    private class PauseBeforeDoctorCreateStore(
        private val delegate: DoctorInstitutionChangeRequestStore
    ) : DoctorInstitutionChangeRequestStore by delegate {
        val beforeCreate = CountDownLatch(1)
        val continueCreate = CountDownLatch(1)

        override fun create(
            doctorId: String,
            institutionId: String,
            action: DoctorInstitutionAction,
            requestNote: String
        ): DoctorInstitutionChangeRequestView {
            beforeCreate.countDown()
            check(continueCreate.await(10, TimeUnit.SECONDS)) { "Timed out waiting to continue doctor request insert" }
            return delegate.create(doctorId, institutionId, action, requestNote)
        }
    }

    private class PausingDoctorRelationships(
        private val delegate: DoctorInstitutionRelationshipOperations
    ) : DoctorInstitutionRelationshipOperations by delegate {
        val pauseNextUserLock = AtomicBoolean(false)
        val userLockAcquired = CountDownLatch(1)
        val continueAfterUserLock = CountDownLatch(1)
        val pauseNextPairLock = AtomicBoolean(false)
        val pairLockAcquired = CountDownLatch(1)
        val continueAfterPairLock = CountDownLatch(1)
        val observeNextUserLockAttempt = AtomicBoolean(false)
        val userLockAttempted = CountDownLatch(1)
        val observeNextPairLockAttempt = AtomicBoolean(false)
        val pairLockAttempted = CountDownLatch(1)

        override fun lockUser(userId: String) {
            if (observeNextUserLockAttempt.compareAndSet(true, false)) {
                userLockAttempted.countDown()
            }
            delegate.lockUser(userId)
            if (pauseNextUserLock.compareAndSet(true, false)) {
                userLockAcquired.countDown()
                check(continueAfterUserLock.await(10, TimeUnit.SECONDS)) {
                    "Timed out waiting to continue after doctor user lock"
                }
            }
        }

        override fun lockPair(userIds: Collection<String>, institutionId: String) {
            if (observeNextPairLockAttempt.compareAndSet(true, false)) {
                pairLockAttempted.countDown()
            }
            delegate.lockPair(userIds, institutionId)
            if (pauseNextPairLock.compareAndSet(true, false)) {
                pairLockAcquired.countDown()
                check(continueAfterPairLock.await(10, TimeUnit.SECONDS)) {
                    "Timed out waiting to continue after doctor pair lock"
                }
            }
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
        private val DATABASE_NAME = membershipReadDatabaseName(Path.of(""))
            .removeSuffix("_query") + "_svc"
        private const val BOOTSTRAP_DATABASE = "task4_boot"

        @Container
        @JvmField
        val mysql = ConsultantServiceMySqlContainer("mysql:8.0.39")
            .withDatabaseName(BOOTSTRAP_DATABASE)
            .withTmpFs(mapOf("/var/lib/mysql" to "rw"))
    }
}

class ConsultantServiceMySqlContainer(imageName: String) :
    MySQLContainer<ConsultantServiceMySqlContainer>(imageName)
