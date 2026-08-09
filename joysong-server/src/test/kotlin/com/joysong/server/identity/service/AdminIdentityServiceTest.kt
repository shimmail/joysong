package com.joysong.server.identity.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class AdminIdentityServiceTest {

    @Test
    fun `bindConsultant creates active consultant role and approved membership while preserving other roles`() {
        val fixture = fixture()

        val result = fixture.service.bindConsultant(" user-1 ", " institution-1 ", " admin-1 ")

        assertEquals(bindingView(), result)
        assertEquals(2, fixture.upserts.size)
        assertEquals("CONSULTANT", fixture.upserts[0].args[1])
        assertTrue(fixture.upserts.none { it.sql.contains("INSTITUTION_LEGAL_REPRESENTATIVE") })
        assertEquals("CONSULTANT", fixture.membershipRoleCode)
    }

    @Test
    fun `bindConsultant restores revoked role and membership clearing revocation fields`() {
        val fixture = fixture()

        fixture.service.bindConsultant("user-1", "institution-1", "admin-1")

        assertEquals(2, fixture.upserts.size)
        assertEquals(true, fixture.upserts[0].sql.contains("revoked_at = NULL"))
        assertEquals(true, fixture.upserts[0].sql.contains("revoked_by = NULL"))
        assertEquals(true, fixture.upserts[0].sql.contains("revoke_reason = ''"))
        assertEquals(true, fixture.upserts[1].sql.contains("revoked_at = NULL"))
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
    fun `bindConsultant rejects invalid targets before either upsert`() {
        val cases = listOf(
            TargetCase(userCount = 0, institutionCount = 1, expectedMessage = "用户不存在、已注销或不是普通用户"),
            TargetCase(userCount = 1, institutionCount = 0, expectedMessage = "机构不存在或已删除")
        )

        cases.forEach { case ->
            val fixture = fixture(userCount = case.userCount, institutionCount = case.institutionCount)

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
        verify(exactly = 0) { fixture.jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg()) }
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
        userCount: Long = 1,
        institutionCount: Long = 1,
        failMembershipUpsert: Boolean = false
    ): Fixture {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val upserts = java.util.Collections.synchronizedList(mutableListOf<SqlCall>())
        val finalMembershipQueries = AtomicInteger()
        val preInsertMembershipSelects = AtomicInteger()
        var membershipRoleCode = ""
        every { jdbcTemplate.queryForObject(any<String>(), Long::class.java, *anyVararg()) } answers {
            when {
                firstArg<String>().contains("FROM users") -> userCount
                firstArg<String>().contains("FROM institutions") -> institutionCount
                else -> error("Unexpected count query: ${firstArg<String>()}")
            }
        }
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            val args = secondArg<Array<*>>().toList()
            if (sql.contains("FROM institution_memberships") && !sql.contains("INSERT")) {
                preInsertMembershipSelects.incrementAndGet()
            }
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
                any<RowMapper<ConsultantBindingAdminView>>(),
                *anyVararg()
            )
        } answers {
            finalMembershipQueries.incrementAndGet()
            listOf(bindingView())
        }
        return Fixture(
            service = AdminIdentityService(jdbcTemplate, ObjectMapper()),
            jdbcTemplate = jdbcTemplate,
            upserts = upserts,
            finalMembershipQueriesProvider = finalMembershipQueries::get,
            preInsertMembershipSelectsProvider = preInsertMembershipSelects::get,
            membershipRoleCodeProvider = { membershipRoleCode }
        )
    }

    private fun bindingView() = ConsultantBindingAdminView(
        userId = "user-1",
        userName = "用户一",
        institutionId = "institution-1",
        institutionName = "机构一",
        membershipId = "membership-1",
        roleCode = "CONSULTANT",
        status = "APPROVED"
    )

    private data class TargetCase(val userCount: Long, val institutionCount: Long, val expectedMessage: String)
    private data class SqlCall(val sql: String, val args: List<Any?>)

    private class Fixture(
        val service: AdminIdentityService,
        val jdbcTemplate: JdbcTemplate,
        val upserts: MutableList<SqlCall>,
        private val finalMembershipQueriesProvider: () -> Int,
        private val preInsertMembershipSelectsProvider: () -> Int,
        private val membershipRoleCodeProvider: () -> String
    ) {
        val finalMembershipQueries get() = finalMembershipQueriesProvider()
        val preInsertMembershipSelects get() = preInsertMembershipSelectsProvider()
        val membershipRoleCode get() = membershipRoleCodeProvider()
    }
}
