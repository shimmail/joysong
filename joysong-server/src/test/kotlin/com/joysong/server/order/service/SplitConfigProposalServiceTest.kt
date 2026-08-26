package com.joysong.server.order.service

import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDateTime

class SplitConfigProposalServiceTest {
    @Test
    fun `active config upsert writes a six digit revision timestamp`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val service = SplitConfigProposalService(
            jdbcTemplate,
            mockk(),
            OrderSplitRatePolicy(OrderSplitProperties().apply { platformRate = BigDecimal("40.00") })
        )
        var lockedRead = 0
        var activeConfigSql = ""
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } answers {
            val sql = firstArg<String>()
            if (sql.contains("INSERT INTO doctor_institution_project_configs")) {
                activeConfigSql = sql
                throw IllegalStateException("active config writer reached")
            }
            1
        }
        every {
            jdbcTemplate.query(
                match<String> { it.contains("FROM split_config_proposals") && it.contains("FOR UPDATE") },
                any<RowMapper<Any>>(),
                *anyVararg()
            )
        } answers {
            lockedRead += 1
            val rowMapper = secondArg<RowMapper<Any>>()
            listOf(rowMapper.mapRow(proposalResultSet(fullyConfirmed = lockedRead > 1), 0))
        }

        val stop = assertThrows<IllegalStateException> { service.confirm(adminActor(), "proposal-1", null) }

        assertEquals("active config writer reached", stop.message)
        assertTrue(activeConfigSql.contains("updated_at = CURRENT_TIMESTAMP(6)"))
    }

    @Test
    fun `final confirmation rejects a proposal invalidated by a platform rate change without applying it`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val accessService = mockk<ManagementAccessService>()
        val properties = OrderSplitProperties().apply { platformRate = BigDecimal("40.00") }
        val policy = OrderSplitRatePolicy(properties)
        val service = SplitConfigProposalService(jdbcTemplate, accessService, policy)
        var lockedRead = 0
        every { jdbcTemplate.update(any<String>(), *anyVararg()) } answers {
            if (firstArg<String>().contains("INSERT INTO doctor_institution_project_configs")) {
                error("invalid proposal reached the active split config write")
            }
            1
        }
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<Any>>(), *anyVararg())
        } answers {
            val rowMapper = secondArg<RowMapper<Any>>()
            lockedRead += 1
            listOf(rowMapper.mapRow(proposalResultSet(fullyConfirmed = lockedRead > 1), 0))
        }

        policy.resolve(BigDecimal("40.00"), BigDecimal("20.00"))
        properties.platformRate = BigDecimal("50.00")

        val error = assertThrows<IllegalArgumentException> {
            service.confirm(adminActor(), "proposal-1", null)
        }

        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
        verify(exactly = 0) {
            jdbcTemplate.update(match { it.contains("INSERT INTO doctor_institution_project_configs") }, *anyVararg())
        }
    }

    @Test
    fun `proposal submit rejects rates exceeding the shared split policy before database access`() {
        val jdbcTemplate = mockk<JdbcTemplate>()
        val accessService = mockk<ManagementAccessService>()
        val policy = OrderSplitRatePolicy(OrderSplitProperties().apply {
            platformRate = BigDecimal("40.00")
        })
        val service = SplitConfigProposalService(jdbcTemplate, accessService, policy)

        val error = assertThrows<IllegalArgumentException> {
            service.submit(
                adminActor(),
                SplitConfigProposalRequest(
                    doctorId = "doctor-1",
                    institutionProjectId = "project-1",
                    commissionRate = BigDecimal("20.01"),
                    institutionRate = BigDecimal("40.00")
                )
            )
        }

        assertEquals("平台、合作医疗机构和医美顾问分账比例合计不能超过 100%", error.message)
    }

    private fun adminActor() = ManagementActor(
        userId = "admin-1",
        isAdmin = true,
        activeRoles = setOf("ADMIN"),
        doctorId = null,
        managedInstitutionIds = emptySet(),
        doctorInstitutionIds = emptySet(),
        manageableDoctorIds = emptySet()
    )

    private fun proposalResultSet(fullyConfirmed: Boolean): ResultSet = mockk<ResultSet>().also { resultSet ->
        every { resultSet.getString("config_id") } returns null
        every { resultSet.getString("doctor_id") } returns "doctor-1"
        every { resultSet.getString("institution_project_id") } returns "project-1"
        every { resultSet.getString("institution_id") } returns "institution-1"
        every { resultSet.getBigDecimal("consultation_fee") } returns BigDecimal.ZERO
        every { resultSet.getBigDecimal("commission_rate") } returns BigDecimal("20.00")
        every { resultSet.getBigDecimal("institution_rate") } returns BigDecimal("40.00")
        every { resultSet.getString("proposer_user_id") } returns "doctor-1"
        every { resultSet.getString("status") } returns "PENDING"
        every { resultSet.getTimestamp("doctor_confirmed_at") } returns
            if (fullyConfirmed) Timestamp.valueOf(LocalDateTime.now()) else null
        every { resultSet.getTimestamp("institution_confirmed_at") } returns Timestamp.valueOf(LocalDateTime.now())
    }
}
