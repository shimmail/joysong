package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.notification.service.BusinessNotificationService
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.service.AccountLifecycleGuard
import com.joysong.server.wallet.repository.WalletRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.ProxyFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionManager
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.SimpleTransactionStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.full.primaryConstructor

class AdminIdentityServiceTest {

    @Test
    fun `business notification dependency is required and non nullable for identity workflows`() {
        listOf(
            AdminIdentityService::class,
            DoctorInstitutionChangeRequestService::class,
            ConsultantInstitutionChangeRequestService::class
        ).forEach { serviceType ->
            val notificationParameter = requireNotNull(serviceType.primaryConstructor).parameters.single {
                it.type.classifier == BusinessNotificationService::class
            }

            assertEquals(BusinessNotificationService::class, notificationParameter.type.classifier)
            assertFalse(notificationParameter.type.isMarkedNullable)
            assertFalse(notificationParameter.isOptional)
        }
    }

    @Test
    fun `identity approval and rejection notify only the applicant with their target contracts`() {
        val approvedNotifications = mockk<BusinessNotificationService>(relaxed = true)
        identityReviewService(approvedNotifications).reviewApplication("application-1", "admin-1", "APPROVED", "")

        verify(exactly = 1) {
            approvedNotifications.identityApplicationApproved("applicant-1", "application-1")
        }
        verify(exactly = 0) { approvedNotifications.identityApplicationRejected(any(), any(), any()) }

        val rejectedNotifications = mockk<BusinessNotificationService>(relaxed = true)
        identityReviewService(rejectedNotifications).reviewApplication(
            "application-2",
            "admin-1",
            "REJECTED",
            " 材料不完整 "
        )

        verify(exactly = 1) {
            rejectedNotifications.identityApplicationRejected("applicant-1", "application-2", "材料不完整")
        }
        verify(exactly = 0) { rejectedNotifications.identityApplicationApproved(any(), any()) }
    }

    @Test
    fun `failed identity transition emits no notification`() {
        val notifications = mockk<BusinessNotificationService>(relaxed = true)
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg())
        } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(identityReviewResultSet(), 0))
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 0
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            notifications,
            relaxedLifecycleGuard(),
        )

        assertThrows(IllegalStateException::class.java) {
            service.reviewApplication("application-1", "admin-1", "REJECTED", "材料不完整")
        }

        verify(exactly = 0) { notifications.identityApplicationRejected(any(), any(), any()) }
    }

    @Test
    fun `admin doctor practice revoke uses shared relationship cleanup`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        val rs = mockk<ResultSet>()
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns "institution-1"
        every { rs.getString("member_role") } returns "DOCTOR"
        every { rs.getString("status") } returns "APPROVED"
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(rs, 0))
        }
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        service.revokeDoctorPractice("practice-1", "admin-1")

        verify(exactly = 1) {
            relationshipService.forceRevokeLocked("doctor-1", "institution-1", "admin-1")
        }
        verify(exactly = 0) {
            jdbcTemplate.update(match<String> { it.contains("UPDATE doctor_institutions") }, *anyVararg())
        }
    }

    @Test
    fun `admin doctor role revoke cleans every institution through shared service`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        service.revokeRole("doctor-1", "DOCTOR", "admin-1", "认证撤销")

        verify(exactly = 1) { relationshipService.revokeAll("doctor-1", "admin-1") }
    }

    @Test
    fun `doctor role revoke rejects a pending relationship request before any mutation`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("doctor_institution_change_requests") &&
                        it.contains("status = 'PENDING'") && it.contains("FOR UPDATE")
                },
                String::class.java,
                "doctor-1"
            )
        } returns listOf("request-1")
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        every { relationshipService.hasPendingRequestForUpdate("doctor-1", null) } returns true
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        assertThrows(DoctorInstitutionRequestConflictException::class.java) {
            service.revokeRole("doctor-1", "DOCTOR", "admin-1", "认证撤销")
        }

        verify(exactly = 0) { jdbcTemplate.update(any<String>(), *anyVararg()) }
        verify(exactly = 0) { relationshipService.revokeAll(any(), any()) }
        verify(exactly = 1) { relationshipService.lockUser("doctor-1") }
    }

    @Test
    fun `doctor practice revoke rejects only a matching pending relationship request`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        every {
            relationshipService.hasPendingRequestForUpdate("doctor-1", "institution-1")
        } returns true
        val rs = mockk<ResultSet>()
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns "institution-1"
        every { rs.getString("member_role") } returns "DOCTOR"
        every { rs.getString("status") } returns "APPROVED"
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(rs, 0))
        }
        every {
            jdbcTemplate.queryForList(
                match<String> {
                    it.contains("doctor_institution_change_requests") &&
                        it.contains("doctor_id = ?") && it.contains("institution_id = ?") &&
                        it.contains("status = 'PENDING'") && it.contains("FOR UPDATE")
                },
                String::class.java,
                "doctor-1",
                "institution-1"
            )
        } returns listOf("request-1")
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        assertThrows(DoctorInstitutionRequestConflictException::class.java) {
            service.revokeDoctorPractice("practice-1", "admin-1")
        }

        verify(exactly = 0) { relationshipService.forceRevokeLocked(any(), any(), any()) }
    }

    @Test
    fun `doctor practice revoke ignores pending requests for another institution`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        every {
            relationshipService.hasPendingRequestForUpdate("doctor-1", "institution-1")
        } returns false
        every {
            relationshipService.hasPendingRequestForUpdate("doctor-1", null)
        } returns true
        val rs = mockk<ResultSet>()
        every { rs.getString("doctor_id") } returns "doctor-1"
        every { rs.getString("institution_id") } returns "institution-1"
        every { rs.getString("member_role") } returns "DOCTOR"
        every { rs.getString("status") } returns "APPROVED"
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(rs, 0))
        }
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        service.revokeDoctorPractice("practice-1", "admin-1")

        verify(exactly = 1) {
            relationshipService.forceRevokeLocked("doctor-1", "institution-1", "admin-1")
        }
        verify(exactly = 0) { relationshipService.hasPendingRequestForUpdate("doctor-1", null) }
    }

    @Test
    fun `legal representative role revoke joins the shared user lock protocol`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        val relationshipService = mockk<DoctorInstitutionRelationshipService>(relaxed = true)
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            relationshipService,
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        service.revokeRole(
            "legal-1",
            "INSTITUTION_LEGAL_REPRESENTATIVE",
            "admin-1",
            "法人权限撤销"
        )

        verify(exactly = 1) { relationshipService.lockUser("legal-1") }
    }

    @Test
    fun `legacy doctor approve rejects direct projection mutation with a typed conflict`() {
        val jdbcTemplate = mockk<JdbcTemplate>(relaxed = true)
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        val error = assertThrows(DoctorInstitutionRequestConflictException::class.java) {
            service.approveDoctorPractice("request-ledger-id", "admin-1")
        }

        assertTrue(error.message.orEmpty().contains("关系申请"))
        verify(exactly = 0) {
            jdbcTemplate.update(match<String> { it.contains("doctor_institutions") }, *anyVararg())
        }
    }

    @Test
    fun `bindConsultant creates active consultant role and approved membership while preserving other roles`() {
        val fixture = fixture()

        val result = fixture.service.bindConsultant(" user-1 ", " institution-1 ", " admin-1 ")

        assertEquals(bindingView(), result)
        assertEquals(2, fixture.upserts.size)
        assertEquals("CONSULTANT", fixture.upserts[0].args[1])
        assertTrue(fixture.upserts.none { it.sql.contains("INSTITUTION_LEGAL_REPRESENTATIVE") })
        assertEquals("CONSULTANT", fixture.membershipRoleCode)
        verify(exactly = 1) { fixture.walletRepository.createIfAbsent("CONSULTANT", "user-1", "USD") }
    }

    @Test
    fun `generic consultant creation uses the approved platform override without pending projection`() {
        val fixture = fixture()

        val membershipId = fixture.service.createMembership(
            "user-1",
            "institution-1",
            "CONSULTANT",
            "admin-1"
        )

        assertEquals("membership-1", membershipId)
        assertTrue(fixture.upserts.any { it.sql.contains("institution_memberships") && it.sql.contains("'APPROVED'") })
        assertTrue(fixture.upserts.none { it.sql.contains("'PENDING'") })
    }

    @Test
    fun `explicit consultant binding rejects a matching pending ledger request before writes`() {
        val fixture = fixture(pendingRequestCount = 1)

        val error = assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
        }

        assertTrue(error.message.orEmpty().contains("审核"))
        assertTrue(fixture.upserts.isEmpty())
        verify(exactly = 1) {
            fixture.consultantRelationships.lockPair("user-1", "institution-1")
        }
        assertTrue(fixture.pendingQueries.single().contains("FOR UPDATE"))
    }

    @Test
    fun `legacy consultant approve is a typed conflict and never advances the projection`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val rs = resultSet(
            strings = mapOf(
                "user_id" to "user-1",
                "institution_id" to "institution-1",
                "member_role" to "CONSULTANT",
                "status" to "PENDING"
            )
        )
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(rs, 0))
        }
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.approveMembership("membership-1", "admin-1")
        }

        verify(exactly = 0) {
            jdbcTemplate.update(match<String> { it.contains("institution_memberships") }, *anyVararg())
        }
    }

    @Test
    fun `force revoke rejects a matching pending consultant request without fabricating withdrawal`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val consultantRelationships = mockk<ConsultantInstitutionRelationshipOperations>(relaxed = true)
        val pendingSql = slot<String>()
        every { jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(resultSet(
                strings = mapOf(
                    "user_id" to "user-1",
                    "institution_id" to "institution-1",
                    "member_role" to "CONSULTANT",
                    "status" to "APPROVED"
                )
            ), 0))
        }
        every {
            jdbcTemplate.queryForList(
                capture(pendingSql),
                String::class.java,
                "user-1",
                "institution-1"
            )
        } returns listOf("request-1")
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            consultantRelationships,
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.revokeMembership("membership-1", "admin-1")
        }

        verify(exactly = 0) {
            consultantRelationships.forceRevoke(any(), any(), any())
        }
        verify(exactly = 0) {
            jdbcTemplate.update(match<String> { it.contains("WITHDRAWN") }, *anyVararg())
        }
        assertTrue(pendingSql.captured.contains("FOR UPDATE"))
    }

    @Test
    fun `consultant role revoke locks the user and rejects any pending ledger before mutations`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val consultantRelationships = mockk<ConsultantInstitutionRelationshipOperations>(relaxed = true)
        val pendingSql = slot<String>()
        every {
            jdbcTemplate.queryForList(capture(pendingSql), String::class.java, "user-1")
        } returns listOf("request-1")
        val service = AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            consultantRelationships,
            mockk(relaxed = true),
            relaxedLifecycleGuard(),
        )

        assertThrows(ConsultantInstitutionRequestConflictException::class.java) {
            service.revokeRole("user-1", "CONSULTANT", "admin-1", "role revoked")
        }

        verify(exactly = 1) { consultantRelationships.lockUser("user-1") }
        verify(exactly = 0) { jdbcTemplate.update(match<String> { it.contains("user_roles") }, *anyVararg()) }
        assertTrue(pendingSql.captured.contains("status = 'PENDING'"))
        assertTrue(pendingSql.captured.contains("FOR UPDATE"))
    }

    @Test
    fun `bindConsultant restores revoked role and membership clearing revocation fields`() {
        val fixture = fixture()

        fixture.service.bindConsultant("user-1", "institution-1", "admin-1")

        assertEquals(2, fixture.upserts.size)
        assertTrue(fixture.upserts[0].sql.contains("revoked_at = IF(status = 'ACTIVE', revoked_at, NULL)"))
        assertTrue(fixture.upserts[0].sql.contains("revoked_by = IF(status = 'ACTIVE', revoked_by, NULL)"))
        assertTrue(fixture.upserts[0].sql.contains("revoke_reason = IF(status = 'ACTIVE', revoke_reason, '')"))
        assertTrue(fixture.upserts[1].sql.contains("revoked_at = IF(status = 'APPROVED', revoked_at, NULL)"))
        assertTrue(fixture.upserts[0].sql.indexOf("activated_at = IF(status = 'ACTIVE'") < fixture.upserts[0].sql.lastIndexOf("status = 'ACTIVE'"))
        assertTrue(fixture.upserts[1].sql.indexOf("confirmed_by = IF(status = 'APPROVED'") < fixture.upserts[1].sql.lastIndexOf("status = 'APPROVED'"))
    }

    @Test
    fun `bindConsultant repeated approved submission returns final unique membership without a pre-insert membership select`() {
        val fixture = fixture()

        val first = fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
        val second = fixture.service.bindConsultant("user-1", "institution-1", "admin-1")

        assertEquals(first.membershipId, second.membershipId)
        assertEquals(4, fixture.upserts.size)
        assertEquals(2, fixture.finalMembershipQueries)
        assertEquals(0, fixture.preInsertMembershipSelects)
        assertTrue(fixture.upserts[0].sql.contains("activated_at = IF(status = 'ACTIVE', activated_at, NOW())"))
        assertTrue(fixture.upserts[0].sql.contains("updated_at = IF(status = 'ACTIVE', updated_at, NOW())"))
        assertTrue(fixture.upserts[1].sql.contains("confirmed_by = IF(status = 'APPROVED', confirmed_by, ?)"))
        assertTrue(fixture.upserts[1].sql.contains("confirmed_at = IF(status = 'APPROVED', confirmed_at, NOW())"))
        assertTrue(fixture.upserts[1].sql.contains("updated_at = IF(status = 'APPROVED', updated_at, NOW())"))
        assertTrue(fixture.finalMembershipSql.contains("JOIN user_roles ur"))
        assertTrue(fixture.finalMembershipSql.contains("ur.status = 'ACTIVE'"))
        assertTrue(fixture.finalMembershipSql.contains("im.status = 'APPROVED'"))
        assertTrue(fixture.finalMembershipSql.contains("u.account_state = 'ACTIVE'"))
        assertTrue(fixture.finalMembershipSql.contains("i.deleted_at IS NULL"))
    }

    @Test
    fun `bindConsultant uses the shared pair lock before either upsert`() {
        val fixture = fixture()

        fixture.service.bindConsultant("user-1", "institution-1", "admin-1")

        verify(exactly = 1) {
            fixture.consultantRelationships.lockPair("user-1", "institution-1")
        }
        verify(exactly = 1) { fixture.lifecycleGuard.requireActiveForWrite("user-1") }
        assertEquals(1, fixture.validationQueries.size)
        assertTrue(fixture.validationQueries[0].contains("FROM institutions"))
    }

    @Test
    fun `bindConsultant reports medical beauty consultant binding failure when final membership is absent`() {
        val fixture = fixture(finalMembershipFound = false)

        val error = assertThrows(IllegalStateException::class.java) {
            fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
        }

        assertEquals("医美顾问绑定关系写入失败", error.message)
    }

    @Test
    fun `bindConsultant concurrent first requests both return the same final membership`() {
        val fixture = fixture()
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit(Callable { fixture.service.bindConsultant("user-1", "institution-1", "admin-1") })
            }

            val results = futures.map { it.get() }

            assertEquals(listOf("membership-1", "membership-1"), results.map { it.membershipId })
            assertEquals(4, fixture.upserts.size)
            assertEquals(0, fixture.preInsertMembershipSelects)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `bindConsultant rejects non ordinary active user before either upsert`() {
        val fixture = fixture(userRole = "ADMIN")

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
        }

        assertEquals("只能绑定普通用户", error.message)
        assertEquals(emptyList<SqlCall>(), fixture.upserts)
    }

    @Test
    fun `bindConsultant rejects missing and deleted institution before either upsert`() {
        listOf(
            InstitutionCase(exists = false, deleted = false, expectedMessage = "机构不存在"),
            InstitutionCase(exists = true, deleted = true, expectedMessage = "机构已删除")
        ).forEach { case ->
            val fixture = fixture(institutionExists = case.exists, institutionDeleted = case.deleted)

            val error = assertThrows(IllegalArgumentException::class.java) {
                fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
            }

            assertEquals(case.expectedMessage, error.message)
            assertEquals(emptyList<SqlCall>(), fixture.upserts)
        }
    }

    @Test
    fun `bindConsultant rejects blank ids before database access`() {
        val fixture = fixture()

        val error = assertThrows(IllegalArgumentException::class.java) {
            fixture.service.bindConsultant(" ", "institution-1", "admin-1")
        }

        assertEquals("用户 ID 不能为空", error.message)
        verify(exactly = 0) { fixture.jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg()) }
    }

    @Test
    fun `bindConsultant is transactional so a membership write failure rolls back the role upsert`() {
        val fixture = fixture(failMembershipUpsert = true)
        val transactionManager = mockk<PlatformTransactionManager>(relaxed = true)
        val genericTransactionManager: TransactionManager = transactionManager
        val transactionStatus = SimpleTransactionStatus()
        every { transactionManager.getTransaction(any()) } returns transactionStatus
        val proxyFactory = ProxyFactory(fixture.service).apply {
            addAdvice(TransactionInterceptor(genericTransactionManager, AnnotationTransactionAttributeSource()))
        }
        val transactionalService = proxyFactory.proxy as AdminIdentityService

        assertThrows(IllegalStateException::class.java) {
            transactionalService.bindConsultant("user-1", "institution-1", "admin-1")
        }

        assertEquals(2, fixture.upserts.size)
        verify(exactly = 1) { transactionManager.rollback(transactionStatus) }
        assertNotNull(AdminIdentityService::class.java.getMethod("bindConsultant", String::class.java, String::class.java, String::class.java)
            .getAnnotation(Transactional::class.java))
    }

    private fun fixture(
        userRole: String = "USER",
        institutionExists: Boolean = true,
        institutionDeleted: Boolean = false,
        failMembershipUpsert: Boolean = false,
        finalMembershipFound: Boolean = true,
        pendingRequestCount: Long = 0L
    ): Fixture {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val walletRepository = mockk<WalletRepository>(relaxed = true)
        val consultantRelationships = mockk<ConsultantInstitutionRelationshipOperations>(relaxed = true)
        val lifecycleGuard = mockk<AccountLifecycleGuard>()
        every { lifecycleGuard.requireActiveForWrite(any()) } answers {
            UserEntity(id = firstArg(), passwordHash = "test", role = userRole)
        }
        val upserts = java.util.Collections.synchronizedList(mutableListOf<SqlCall>())
        val finalMembershipQueries = AtomicInteger()
        val preInsertMembershipSelects = AtomicInteger()
        val validationQueries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val pendingQueries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finalMembershipSql = java.util.concurrent.atomic.AtomicReference("")
        var membershipRoleCode = ""
        every {
            jdbcTemplate.queryForList(
                match<String> { it.contains("consultant_institution_change_requests") },
                String::class.java,
                "user-1",
                "institution-1"
            )
        } answers {
            pendingQueries += firstArg<String>()
            if (pendingRequestCount == 0L) emptyList() else listOf("request-1")
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val args = secondArg<Array<*>>().toList()
            if (sql.contains("INSERT INTO")) {
                upserts += SqlCall(sql, args)
                if (sql.contains("institution_memberships")) {
                    membershipRoleCode = args[3] as String
                    if (failMembershipUpsert) throw IllegalStateException("membership write failed")
                }
            }
            1
        }
        every {
            jdbcTemplate.query(
                any<String>(),
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        } answers {
            val sql = firstArg<String>()
            val rowMapper = secondArg<RowMapper<Any>>()
            when {
                sql.contains("FROM institutions") -> {
                    validationQueries += sql
                    if (!institutionExists) emptyList() else listOf(rowMapper.mapRow(resultSet(
                        timestamps = mapOf(
                            "deleted_at" to if (institutionDeleted) {
                                Timestamp.valueOf("2026-08-28 00:00:00")
                            } else {
                                null
                            },
                        )
                    ), 0))
                }
                sql.contains("FROM institution_memberships") -> {
                    if (upserts.isEmpty()) preInsertMembershipSelects.incrementAndGet()
                    finalMembershipQueries.incrementAndGet()
                    finalMembershipSql.set(sql)
                    if (!finalMembershipFound) emptyList() else listOf(rowMapper.mapRow(resultSet(
                        strings = mapOf(
                            "id" to "membership-1",
                            "user_id" to "user-1",
                            "user_name" to "用户一",
                            "institution_id" to "institution-1",
                            "institution_name" to "机构一",
                            "member_role" to "CONSULTANT",
                            "status" to "APPROVED"
                        )
                    ), 0))
                }
                else -> error("Unexpected query: $sql")
            }
        }
        return Fixture(
            service = AdminIdentityService(
                jdbcTemplate,
                ObjectMapper(),
                mockk<DoctorInstitutionRelationshipService>(relaxed = true),
                walletRepository,
                consultantRelationships,
                mockk(relaxed = true),
                lifecycleGuard,
            ),
            jdbcTemplate = jdbcTemplate,
            upserts = upserts,
            finalMembershipQueriesProvider = finalMembershipQueries::get,
            preInsertMembershipSelectsProvider = preInsertMembershipSelects::get,
            membershipRoleCodeProvider = { membershipRoleCode },
            validationQueries = validationQueries,
            finalMembershipSqlProvider = finalMembershipSql::get,
            walletRepository = walletRepository,
            pendingQueries = pendingQueries,
            consultantRelationships = consultantRelationships,
            lifecycleGuard = lifecycleGuard,
        )
    }

    private fun identityReviewService(notifications: BusinessNotificationService): AdminIdentityService {
        val jdbcTemplate = mockk<JdbcTemplate>()
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg())
        } answers {
            listOf(secondArg<RowMapper<Any>>().mapRow(identityReviewResultSet(), 0))
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } returns 1
        return AdminIdentityService(
            jdbcTemplate,
            ObjectMapper(),
            mockk(relaxed = true),
            mockk(relaxed = true),
            mockk(relaxed = true),
            notifications,
            relaxedLifecycleGuard(),
        )
    }

    private fun relaxedLifecycleGuard(): AccountLifecycleGuard = mockk(relaxed = true)

    private fun identityReviewResultSet() = resultSet(
        strings = mapOf(
            "user_id" to "applicant-1",
            "role_code" to "CONSULTANT",
            "status" to "PENDING",
            "application_data" to "{}"
        )
    )

    private fun bindingView() = ConsultantBindingAdminView(
        userId = "user-1",
        userName = "用户一",
        institutionId = "institution-1",
        institutionName = "机构一",
        membershipId = "membership-1",
        roleCode = "CONSULTANT",
        status = "APPROVED"
    )

    private fun resultSet(
        strings: Map<String, String> = emptyMap(),
        timestamps: Map<String, Timestamp?> = emptyMap()
    ): ResultSet = mockk<ResultSet>().also { resultSet ->
        strings.forEach { (column, value) -> every { resultSet.getString(column) } returns value }
        timestamps.forEach { (column, value) -> every { resultSet.getTimestamp(column) } returns value }
    }

    private data class InstitutionCase(val exists: Boolean, val deleted: Boolean, val expectedMessage: String)
    private data class SqlCall(val sql: String, val args: List<Any?>)

    private class Fixture(
        val service: AdminIdentityService,
        val jdbcTemplate: JdbcTemplate,
        val upserts: MutableList<SqlCall>,
        private val finalMembershipQueriesProvider: () -> Int,
        private val preInsertMembershipSelectsProvider: () -> Int,
        private val membershipRoleCodeProvider: () -> String,
        val validationQueries: List<String>,
        private val finalMembershipSqlProvider: () -> String,
        val walletRepository: WalletRepository,
        val pendingQueries: List<String>,
        val consultantRelationships: ConsultantInstitutionRelationshipOperations,
        val lifecycleGuard: AccountLifecycleGuard,
    ) {
        val finalMembershipQueries get() = finalMembershipQueriesProvider()
        val preInsertMembershipSelects get() = preInsertMembershipSelectsProvider()
        val membershipRoleCode get() = membershipRoleCodeProvider()
        val finalMembershipSql get() = finalMembershipSqlProvider()
    }
}
