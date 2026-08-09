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
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime
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
        assertTrue(fixture.finalMembershipSql.contains("u.deleted_at IS NULL"))
        assertTrue(fixture.finalMembershipSql.contains("i.deleted_at IS NULL"))
    }

    @Test
    fun `bindConsultant locks user then institution before either upsert`() {
        val fixture = fixture()

        fixture.service.bindConsultant("user-1", "institution-1", "admin-1")

        assertEquals(2, fixture.validationQueries.size)
        assertTrue(fixture.validationQueries[0].contains("FROM users"))
        assertTrue(fixture.validationQueries[0].contains("FOR UPDATE"))
        assertTrue(fixture.validationQueries[1].contains("FROM institutions"))
        assertTrue(fixture.validationQueries[1].contains("FOR UPDATE"))
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
    fun `bindConsultant rejects each invalid user state before either upsert`() {
        val cases = listOf(
            TargetCase(userExists = false, expectedMessage = "用户不存在"),
            TargetCase(userDeleted = true, expectedMessage = "用户已注销"),
            TargetCase(userRole = "ADMIN", expectedMessage = "只能绑定普通用户")
        )

        cases.forEach { case ->
            val fixture = fixture(userExists = case.userExists, userDeleted = case.userDeleted, userRole = case.userRole)

            val error = assertThrows(IllegalArgumentException::class.java) {
                fixture.service.bindConsultant("user-1", "institution-1", "admin-1")
            }

            assertEquals(case.expectedMessage, error.message)
            assertEquals(emptyList<SqlCall>(), fixture.upserts)
        }
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
        userExists: Boolean = true,
        userDeleted: Boolean = false,
        userRole: String = "USER",
        institutionExists: Boolean = true,
        institutionDeleted: Boolean = false,
        failMembershipUpsert: Boolean = false
    ): Fixture {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val upserts = java.util.Collections.synchronizedList(mutableListOf<SqlCall>())
        val finalMembershipQueries = AtomicInteger()
        val preInsertMembershipSelects = AtomicInteger()
        val validationQueries = java.util.Collections.synchronizedList(mutableListOf<String>())
        val finalMembershipSql = java.util.concurrent.atomic.AtomicReference("")
        var membershipRoleCode = ""
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
                sql.contains("FROM users") -> {
                    validationQueries += sql
                    if (!userExists) emptyList() else listOf(rowMapper.mapRow(resultSet(
                        strings = mapOf("role" to userRole),
                        timestamps = mapOf("deleted_at" to userDeleted.timestampOrNull())
                    ), 0))
                }
                sql.contains("FROM institutions") -> {
                    validationQueries += sql
                    if (!institutionExists) emptyList() else listOf(rowMapper.mapRow(resultSet(
                        timestamps = mapOf("deleted_at" to institutionDeleted.timestampOrNull())
                    ), 0))
                }
                sql.contains("FROM institution_memberships") -> {
                    if (upserts.isEmpty()) preInsertMembershipSelects.incrementAndGet()
                    finalMembershipQueries.incrementAndGet()
                    finalMembershipSql.set(sql)
                    listOf(rowMapper.mapRow(resultSet(
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
            service = AdminIdentityService(jdbcTemplate, ObjectMapper()),
            jdbcTemplate = jdbcTemplate,
            upserts = upserts,
            finalMembershipQueriesProvider = finalMembershipQueries::get,
            preInsertMembershipSelectsProvider = preInsertMembershipSelects::get,
            membershipRoleCodeProvider = { membershipRoleCode },
            validationQueries = validationQueries,
            finalMembershipSqlProvider = finalMembershipSql::get
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

    private fun resultSet(
        strings: Map<String, String> = emptyMap(),
        timestamps: Map<String, Timestamp?> = emptyMap()
    ): ResultSet = mockk<ResultSet>().also { resultSet ->
        strings.forEach { (column, value) -> every { resultSet.getString(column) } returns value }
        timestamps.forEach { (column, value) -> every { resultSet.getTimestamp(column) } returns value }
    }

    private fun Boolean.timestampOrNull(): Timestamp? = if (this) Timestamp.valueOf(LocalDateTime.now()) else null

    private data class TargetCase(
        val userExists: Boolean = true,
        val userDeleted: Boolean = false,
        val userRole: String = "USER",
        val expectedMessage: String
    )
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
        private val finalMembershipSqlProvider: () -> String
    ) {
        val finalMembershipQueries get() = finalMembershipQueriesProvider()
        val preInsertMembershipSelects get() = preInsertMembershipSelectsProvider()
        val membershipRoleCode get() = membershipRoleCodeProvider()
        val finalMembershipSql get() = finalMembershipSqlProvider()
    }
}
