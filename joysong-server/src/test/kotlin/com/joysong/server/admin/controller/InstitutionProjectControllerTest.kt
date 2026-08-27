package com.joysong.server.admin.controller

import com.joysong.server.admin.entity.dto.DoctorProjectBinding
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.common.GlobalExceptionHandler
import com.joysong.server.config.OrderSplitProperties
import com.joysong.server.config.TravelGroundServicePricingProperties
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.entity.DoctorInstitutionEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionProjectIdentity
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeErrorCode
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.order.service.TravelGroundServiceFeeRatePolicy
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.cache.concurrent.ConcurrentMapCacheManager
import org.springframework.dao.CannotAcquireLockException
import org.springframework.jdbc.UncategorizedSQLException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.core.Authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.math.BigDecimal
import java.sql.SQLException
import java.time.LocalDateTime
import java.util.Optional

class InstitutionProjectControllerTest {
    @Test
    fun `put accepts exactly the versioned update wire and returns the new version`() {
        val fixture = Fixture()

        fixture.mockMvc.perform(
            put("/api/admin/institution-projects/ip-1")
                .principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(fixture.validUpdateJson())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.version").value(1))

        verify(exactly = 1) { fixture.institutionProjects.save(any()) }
        verify(exactly = 1) { fixture.institutionProjects.flush() }
    }

    @Test
    fun `list and create responses expose version while post keeps the legacy create wire`() {
        val fixture = Fixture()
        fixture.stubListProject(version = 7)

        fixture.mockMvc.perform(
            get("/api/admin/institution-projects").principal(fixture.authentication)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].version").value(7))

        val createPayload = fixture.objectMapper.createObjectNode().apply {
            put("institutionId", "institution-1")
            put("projectId", "project-1")
            put("price", BigDecimal("3500.00"))
            put("currency", "USD")
            putArray("doctorBindings")
        }
        fixture.mockMvc.perform(
            post("/api/admin/institution-projects")
                .principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createPayload.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.code").value(200))
            .andExpect(jsonPath("$.data.version").value(0))
    }

    @Test
    fun `put rejects a missing baseVersion before writes`() {
        val fixture = Fixture()
        val payload = fixture.validUpdatePayload().apply { remove("baseVersion") }

        fixture.mockMvc.perform(
            put("/api/admin/institution-projects/ip-1")
                .principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_PAYLOAD_INVALID"))

        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `put rejects a missing nullable key before writes`() {
        val fixture = Fixture()
        val payload = fixture.validUpdatePayload().apply { remove("description") }

        fixture.mockMvc.perform(
            put("/api/admin/institution-projects/ip-1")
                .principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_PAYLOAD_INVALID"))

        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `put rejects extra association and version keys before writes`() {
        val fixture = Fixture()
        val payload = fixture.validUpdatePayload().apply {
            put("institutionId", "institution-1")
            put("version", 0)
        }

        fixture.mockMvc.perform(
            put("/api/admin/institution-projects/ip-1")
                .principal(fixture.authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload.toString())
        )
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.errorCode").value("PROJECT_PAYLOAD_INVALID"))

        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `put rejects invalid scalar values and types before writes`() {
        val invalidMutations: List<ObjectNode.() -> Unit> = listOf(
            { put("baseVersion", "0") },
            { put("baseVersion", -1) },
            { put("currency", "INVALID") },
            { put("price", "3500.00") },
            { put("isActive", 1) }
        )
        invalidMutations.forEach { mutate ->
            val fixture = Fixture()
            val payload = fixture.validUpdatePayload().apply(mutate)

            fixture.mockMvc.perform(
                put("/api/admin/institution-projects/ip-1")
                    .principal(fixture.authentication)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload.toString())
            )
                .andExpect(status().isUnprocessableEntity)
                .andExpect(jsonPath("$.errorCode").value("PROJECT_PAYLOAD_INVALID"))

            fixture.verifyNoUpdateWrites()
        }
    }

    @Test
    fun `stale locked version returns typed conflict with zero downstream writes or cache callbacks`() {
        val fixture = Fixture()
        fixture.stubLockedInstitutionProject(version = 1)
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixture.mockMvc.perform(
                put("/api/admin/institution-projects/ip-1")
                    .principal(fixture.authentication)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(fixture.validUpdateJson())
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.errorCode").value("INSTITUTION_PROJECT_VERSION_STALE"))

            fixture.verifyNoUpdateWrites()
            verify(exactly = 0) { fixture.doctorProjects.findForUpdate(any(), any()) }
            verify(exactly = 0) {
                fixture.jdbc.queryForList(
                    match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                    String::class.java,
                    any<String>()
                )
            }
            verify(exactly = 0) {
                fixture.jdbc.queryForList(
                    match<String> { it.contains("doctor_institution_project_configs") && it.contains("FOR UPDATE") },
                    any<String>()
                )
            }
            verify(exactly = 0) {
                fixture.jdbc.update(
                    match<String> { it.contains("doctor_project_change_requests") },
                    any<String>(),
                    any<String>()
                )
                fixture.jdbc.update(
                    match<String> { it.contains("split_config_proposals") },
                    any<String>(),
                    any<String>()
                )
            }
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `locked institution project disappearing after identity peek returns typed stale with zero downstream effects`() {
        val fixture = Fixture()
        every { fixture.institutionProjects.findForUpdate("ip-1") } returns null
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixture.mockMvc.perform(
                put("/api/admin/institution-projects/ip-1")
                    .principal(fixture.authentication)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(fixture.validUpdateJson())
            )
                .andExpect(status().isConflict)
                .andExpect(jsonPath("$.errorCode").value("INSTITUTION_PROJECT_VERSION_STALE"))

            fixture.verifyNoUpdateWrites()
            verify(exactly = 0) { fixture.doctorProjects.findForUpdate(any(), any()) }
            verify(exactly = 0) {
                fixture.jdbc.queryForList(
                    match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                    String::class.java,
                    any<String>()
                )
                fixture.jdbc.queryForList(
                    match<String> { it.contains("FROM doctor_projects") && it.contains("FOR UPDATE") },
                    String::class.java,
                    any<String>()
                )
                fixture.jdbc.queryForList(
                    match<String> { it.contains("doctor_institution_project_configs") && it.contains("FOR UPDATE") },
                    any<String>()
                )
                fixture.jdbc.update(
                    match<String> { it.contains("doctor_project_change_requests") },
                    any<String>(),
                    any<String>()
                )
                fixture.jdbc.update(
                    match<String> { it.contains("split_config_proposals") },
                    any<String>(),
                    any<String>()
                )
            }
            assertEquals(0, TransactionSynchronizationManager.getSynchronizations().size)
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }

    @Test
    fun `update follows request relationship institution platform doctor config lock order`() {
        val fixture = Fixture()
        val current = doctorProject("doctor-a").single()
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns current
        fixture.stubLockedDoctorIds(listOf("doctor-a"))
        every {
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("doctor_project_change_requests") &&
                        !it.contains("FOR UPDATE") && !it.contains("FOR SHARE")
                },
                String::class.java,
                "ip-1"
            )
        } returns listOf("request-b", "request-a")
        every {
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("split_config_proposals") &&
                        !it.contains("FOR UPDATE") && !it.contains("FOR SHARE")
                },
                String::class.java,
                "ip-1"
            )
        } returns listOf("split-b", "split-a")
        listOf("request-a", "request-b").forEach { requestId ->
            every {
                fixture.jdbc.queryForList(
                    match<String> {
                        it.contains("doctor_project_change_requests") && it.contains("FORCE INDEX (PRIMARY)") &&
                            it.contains("WHERE id = ?") && it.contains("FOR UPDATE")
                    },
                    String::class.java,
                    requestId,
                    "ip-1"
                )
            } returns listOf(requestId)
        }
        listOf("split-a", "split-b").forEach { requestId ->
            every {
                fixture.jdbc.queryForList(
                    match<String> {
                        it.contains("split_config_proposals") && it.contains("FORCE INDEX (PRIMARY)") &&
                            it.contains("WHERE id = ?") && it.contains("FOR UPDATE")
                    },
                    String::class.java,
                    requestId,
                    "ip-1"
                )
            } returns listOf(requestId)
        }
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
        } returns listOf("request-a", "request-b")
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("split_config_proposals") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
        } returns listOf("split-a", "split-b")

        fixture.update(
            institutionProjectRequest(
                doctorBindings = listOf(
                    DoctorProjectBinding("doctor-b", price = BigDecimal("4299.00")),
                    DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00"))
                )
            )
        )

        verifyOrder {
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("doctor_project_change_requests") && it.contains("FORCE INDEX (PRIMARY)") &&
                        it.contains("FOR UPDATE")
                },
                String::class.java,
                "request-a",
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("doctor_project_change_requests") && it.contains("FORCE INDEX (PRIMARY)") &&
                        it.contains("FOR UPDATE")
                },
                String::class.java,
                "request-b",
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("split_config_proposals") && it.contains("FORCE INDEX (PRIMARY)") &&
                        it.contains("FOR UPDATE")
                },
                String::class.java,
                "split-a",
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("split_config_proposals") && it.contains("FORCE INDEX (PRIMARY)") &&
                        it.contains("FOR UPDATE")
                },
                String::class.java,
                "split-b",
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_institutions") && it.contains("FOR UPDATE NOWAIT") },
                String::class.java,
                "doctor-a",
                "institution-1"
            )
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_institutions") && it.contains("FOR UPDATE NOWAIT") },
                String::class.java,
                "doctor-b",
                "institution-1"
            )
            fixture.institutionProjects.findForUpdate("ip-1")
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> { it.contains("split_config_proposals") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
            fixture.jdbc.queryForList(
                match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                String::class.java,
                "project-1"
            )
            fixture.jdbc.queryForList(
                match<String> { it.contains("FROM doctor_projects") && it.contains("FOR UPDATE") },
                String::class.java,
                "ip-1"
            )
            fixture.doctorProjects.findForUpdate("doctor-a", "ip-1")
            fixture.doctorProjects.findForUpdate("doctor-b", "ip-1")
            fixture.jdbc.queryForList(
                match<String> {
                    it.contains("doctor_institution_project_configs") && it.contains("ORDER BY id") &&
                        it.contains("FOR UPDATE")
                },
                "ip-1"
            )
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-a", "ip-1")
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-b", "ip-1")
        }
    }

    @Test
    fun `request set changed after primary locks returns typed stale before writes`() {
        val fixture = Fixture()
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
        } returns listOf("new-request")

        val stale = assertThrows(ProjectChangeContractException::class.java) {
            fixture.update(institutionProjectRequest(doctorBindings = emptyList()))
        }

        assertEquals(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE, stale.errorCode)
        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `request set NOWAIT 3572 returns typed stale before writes`() {
        val fixture = Fixture()
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("FOR SHARE NOWAIT") },
                String::class.java,
                "ip-1"
            )
        } throws UncategorizedSQLException("request set lock", "SELECT", SQLException("nowait", "HY000", 3572))

        val stale = assertThrows(ProjectChangeContractException::class.java) {
            fixture.update(institutionProjectRequest(doctorBindings = emptyList()))
        }

        assertEquals(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE, stale.errorCode)
        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `removed doctor with an already invalid relationship is still cleaned up`() {
        val fixture = Fixture()
        val current = doctorProject("doctor-a").single()
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns current
        fixture.stubLockedDoctorIds(listOf("doctor-a"))
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("FROM doctor_institutions") },
                String::class.java,
                "doctor-a",
                "institution-1"
            )
        } returns emptyList()

        fixture.update(institutionProjectRequest(doctorBindings = emptyList()))

        verify { fixture.doctorProjects.deleteAll(match<Iterable<DoctorProjectEntity>> { it.single().doctorId == "doctor-a" }) }
        verify {
            fixture.jdbc.update(
                match<String> { it.contains("doctor_project_change_requests") && it.contains("WITHDRAWN") },
                "doctor-a",
                "ip-1"
            )
            fixture.jdbc.update(
                match<String> { it.contains("split_config_proposals") && it.contains("WITHDRAWN") },
                "doctor-a",
                "ip-1"
            )
        }
    }

    @Test
    fun `relationship NOWAIT conflicts become typed stale with zero writes`() {
        val fixture = Fixture()
        every {
            fixture.jdbc.queryForList(
                match<String> { it.contains("doctor_institutions") && it.contains("FOR UPDATE NOWAIT") },
                String::class.java,
                "doctor-a",
                "institution-1"
            )
        } throws UncategorizedSQLException("relationship lock", "SELECT", SQLException("nowait", "HY000", 3572))

        val stale = assertThrows(ProjectChangeContractException::class.java) {
            fixture.update(
                institutionProjectRequest(
                    listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
                )
            )
        }

        assertEquals(ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE, stale.errorCode)
        fixture.verifyNoUpdateWrites()
    }

    @Test
    fun `relationship pure spring timeout and deadlock errors are never converted to stale`() {
        listOf(
            CannotAcquireLockException("no SQL cause", IllegalStateException("unknown lock failure")),
            CannotAcquireLockException("lock timeout", SQLException("lock timeout", "HY000", 1205)),
            CannotAcquireLockException("deadlock", SQLException("deadlock", "40001", 1213))
        ).forEach { databaseError ->
            val fixture = Fixture()
            every {
                fixture.jdbc.queryForList(
                    match<String> { it.contains("doctor_institutions") && it.contains("FOR UPDATE NOWAIT") },
                    String::class.java,
                    "doctor-a",
                    "institution-1"
                )
            } throws databaseError

            val actual = assertThrows(CannotAcquireLockException::class.java) {
                fixture.update(
                    institutionProjectRequest(
                        listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
                    )
                )
            }

            assertSame(databaseError, actual)
            fixture.verifyNoUpdateWrites()
        }
    }

    @Test
    fun `all admin mutators clear all project caches after commit only`() {
        val operations: List<(Fixture) -> Unit> = listOf(
            { fixture -> fixture.controller.create(fixture.authentication, institutionProjectRequest(emptyList())) },
            { fixture -> fixture.update(institutionProjectRequest(emptyList())) },
            { fixture -> fixture.controller.delete(fixture.authentication, "ip-1") }
        )
        operations.forEachIndexed { index, operation ->
            val fixture = Fixture()
            listOf("discover", "home", "projects").forEach { cacheName ->
                fixture.cacheManager.getCache(cacheName)!!.put("sentinel-$index", "present")
            }
            TransactionSynchronizationManager.initSynchronization()
            try {
                operation(fixture)
                listOf("discover", "home", "projects").forEach { cacheName ->
                    assertEquals("present", fixture.cacheManager.getCache(cacheName)!!.get("sentinel-$index", String::class.java))
                }
                val callbacks = TransactionSynchronizationManager.getSynchronizations()
                assertEquals(1, callbacks.size)
                callbacks.forEach(TransactionSynchronization::afterCommit)
                listOf("discover", "home", "projects").forEach { cacheName ->
                    assertNull(fixture.cacheManager.getCache(cacheName)!!.get("sentinel-$index"))
                }
            } finally {
                TransactionSynchronizationManager.clearSynchronization()
            }
        }
    }

    @Test
    fun `rollback and missing synchronization never clear caches early`() {
        val fixture = Fixture()
        listOf("discover", "home", "projects").forEach { cacheName ->
            fixture.cacheManager.getCache(cacheName)!!.put("rollback", "present")
        }
        TransactionSynchronizationManager.initSynchronization()
        try {
            fixture.update(institutionProjectRequest(emptyList()))
            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
        fixture.update(institutionProjectRequest(emptyList()))
        listOf("discover", "home", "projects").forEach { cacheName ->
            assertEquals("present", fixture.cacheManager.getCache(cacheName)!!.get("rollback", String::class.java))
        }
    }

    @Test
    fun `create saves two doctors on one institution project with independent prices`() {
        val fixture = Fixture()
        val request = institutionProjectRequest(
            doctorBindings = listOf(
                DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")),
                DoctorProjectBinding("doctor-b", price = BigDecimal("4299.00"))
            )
        )

        fixture.controller.create(fixture.authentication, request)

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.associate { it.doctorId to it.price } == mapOf(
                    "doctor-a" to BigDecimal("3999.00"),
                    "doctor-b" to BigDecimal("4299.00")
                )
            })
        }
        verify(exactly = 2) {
            fixture.configs.save(match {
                it.medicalListPrice in setOf(BigDecimal("3999.00"), BigDecimal("4299.00"))
            })
        }
    }

    @Test
    fun `update changes a retained inactive doctor price without clearing profile fields`() {
        val fixture = Fixture()
        val existing = DoctorProjectEntity(
            doctorId = "doctor-a",
            projectId = "project-1",
            institutionProjectId = "ip-1",
            price = BigDecimal("3000.00"),
            serviceDescription = "keep-description",
            serviceTags = "keep-tags",
            scheduleNote = "keep-schedule",
            coverImage = "keep-cover",
            images = "keep-images",
            isActive = false
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns listOf(existing)
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns existing
        fixture.stubLockedDoctorIds(listOf("doctor-a"))

        fixture.update(
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.single().price.compareTo(BigDecimal("3999.00")) == 0 &&
                    !rows.single().isActive &&
                    rows.single().serviceDescription == "keep-description" &&
                    rows.single().serviceTags == "keep-tags" &&
                    rows.single().scheduleNote == "keep-schedule" &&
                    rows.single().coverImage == "keep-cover" &&
                    rows.single().images == "keep-images"
            })
        }
    }

    @Test
    fun `update uses locked doctor snapshot so concurrently approved profile fields survive`() {
        val fixture = Fixture()
        val stale = doctorProject(
            doctorId = "doctor-a",
            description = "stale-description",
            tags = "stale-tags",
            images = "stale-images"
        )
        val approved = doctorProject(
            doctorId = "doctor-a",
            description = "approved-description",
            tags = "approved-tags",
            images = "approved-images"
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns stale
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns approved.single()
        fixture.stubLockedDoctorIds(listOf("doctor-a"))

        fixture.update(
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.doctorProjects.saveAll(match<Iterable<DoctorProjectEntity>> { rows ->
                rows.single().serviceDescription == "approved-description" &&
                    rows.single().serviceTags == "approved-tags" &&
                    rows.single().images == "approved-images"
            })
        }
        verifyOrder {
            fixture.doctorProjects.findForUpdate("doctor-a", "ip-1")
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-a", "ip-1")
        }
    }

    @Test
    fun `update restores soft-deleted compatibility row without clearing legacy fields`() {
        val fixture = Fixture()
        val legacyConfig = DoctorInstitutionProjectConfigEntity(
            id = "config-a",
            doctorId = "doctor-a",
            institutionProjectId = "ip-1",
            consultationFee = BigDecimal("88.00"),
            commissionRate = BigDecimal("12.00"),
            institutionRate = BigDecimal("35.00"),
            medicalListPrice = BigDecimal("3000.00"),
            deletedAt = LocalDateTime.of(2026, 8, 20, 9, 0)
        )
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns emptyList()
        every {
            fixture.configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate("doctor-a", "ip-1")
        } returns legacyConfig
        fixture.stubConfig(legacyConfig)

        fixture.update(
            institutionProjectRequest(
                doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")))
            )
        )

        verify {
            fixture.configs.save(match {
                it.id == "config-a" && it.medicalListPrice.compareTo(BigDecimal("3999.00")) == 0 &&
                    it.consultationFee.compareTo(BigDecimal("88.00")) == 0 &&
                    it.commissionRate.compareTo(BigDecimal("12.00")) == 0 &&
                    it.institutionRate.compareTo(BigDecimal("35.00")) == 0 && it.deletedAt == null
            })
        }
    }

    @Test
    fun `removing doctor withdraws pending profile update and leave requests`() {
        val fixture = Fixture()
        every { fixture.doctorProjects.findByInstitutionProjectId("ip-1") } returns doctorProject("doctor-a")
        every { fixture.doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns listOf("doctor-a")
        every { fixture.doctorProjects.findForUpdate("doctor-a", "ip-1") } returns doctorProject("doctor-a").single()
        fixture.stubLockedDoctorIds(listOf("doctor-a"))

        fixture.update(institutionProjectRequest(doctorBindings = emptyList()))

        verify {
            fixture.jdbc.update(
                match<String> {
                    it.contains("UPDATE doctor_project_change_requests") &&
                        it.contains("status = 'WITHDRAWN'") &&
                        it.contains("request_type IN ('PROFILE_UPDATE', 'LEAVE')") &&
                        it.contains("status = 'PENDING'")
                },
                "doctor-a",
                "ip-1"
            )
        }
    }

    @Test
    fun `missing doctor price returns an error without saving bindings`() = assertRejectedPrice(null)

    @Test
    fun `zero doctor price returns an error without saving bindings`() = assertRejectedPrice(BigDecimal.ZERO)

    @Test
    fun `fractional cent doctor price returns an error without saving bindings`() = assertRejectedPrice(BigDecimal("3999.001"))

    @Test
    fun `duplicate doctor binding returns an error without saving bindings`() {
        val fixture = Fixture()

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(
                doctorBindings = listOf(
                    DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00")),
                    DoctorProjectBinding("doctor-a", price = BigDecimal("4299.00"))
                )
            )
        )

        assertEquals(409, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    @Test
    fun `unrelated institution doctor price returns an error without saving bindings`() {
        val fixture = Fixture(doctorInstitutionId = "institution-other")

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = BigDecimal("3999.00"))))
        )

        assertEquals(409, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    private fun assertRejectedPrice(price: BigDecimal?) {
        val fixture = Fixture()

        val response = fixture.controller.create(
            fixture.authentication,
            institutionProjectRequest(doctorBindings = listOf(DoctorProjectBinding("doctor-a", price = price)))
        )

        assertNotEquals(200, response.code)
        verify(exactly = 0) { fixture.doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
    }

    private class Fixture(doctorInstitutionId: String = "institution-1") {
        val objectMapper: ObjectMapper = jacksonObjectMapper()
        val authentication = mockk<Authentication>()
        val institutionProjects = mockk<InstitutionProjectRepository>(relaxed = true)
        val doctorProjects = mockk<DoctorProjectRepository>(relaxed = true)
        private val doctors = mockk<DoctorRepository>()
        private val projects = mockk<ProjectRepository>()
        private val institutions = mockk<InstitutionRepository>()
        val configs = mockk<DoctorInstitutionProjectConfigRepository>(relaxed = true)
        private val orders = mockk<OrderRepository>(relaxed = true)
        private val doctorInstitutions = mockk<DoctorInstitutionService>()
        private val access = mockk<ManagementAccessService>()
        val jdbc = mockk<JdbcTemplate>(relaxed = true)
        private val travelGroundServicePricing = TravelGroundServicePricing(
            TravelGroundServiceFeeRatePolicy(TravelGroundServicePricingProperties())
        )
        val cacheManager = ConcurrentMapCacheManager("discover", "home", "projects")
        val controller = InstitutionProjectController(
            institutionProjects,
            doctorProjects,
            doctors,
            projects,
            institutions,
            configs,
            orders,
            InstitutionProjectDetailResolver(),
            doctorInstitutions,
            access,
            jdbc,
            travelGroundServicePricing,
            objectMapper,
            cacheManager
        )
        val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(GlobalExceptionHandler())
            .build()

        init {
            val actor = ManagementActor("admin-1", true, setOf("ADMIN"), null, emptySet(), emptySet(), emptySet())
            every { access.actor(authentication) } returns actor
            every { authentication.name } returns "admin-1"
            every { access.requirePlatformAdmin(actor) } returns Unit
            every { institutions.existsById("institution-1") } returns true
            every { projects.findById("project-1") } returns Optional.of(ProjectEntity("project-1", "Project"))
            every { institutionProjects.findByInstitutionIdAndProjectId("institution-1", "project-1") } returns null
            val identity = mockk<InstitutionProjectIdentity>()
            every { identity.institutionId } returns "institution-1"
            every { identity.projectId } returns "project-1"
            every { institutionProjects.findIdentityById("ip-1") } returns identity
            every { institutionProjects.findForUpdate("ip-1") } returns
                InstitutionProjectEntity("ip-1", "institution-1", "project-1", price = BigDecimal("3500.00"))
            every { institutionProjects.findById("ip-1") } returns Optional.of(
                InstitutionProjectEntity("ip-1", "institution-1", "project-1", price = BigDecimal("3500.00"))
            )
            every { institutionProjects.save(any()) } answers {
                firstArg<InstitutionProjectEntity>().let { entity ->
                    if (entity.id == "ip-1") entity.copy(version = 1) else entity
                }
            }
            every { doctors.findAllById(any<Iterable<String>>()) } answers {
                firstArg<Iterable<String>>().map { DoctorEntity(it, it) }
            }
            every { doctorInstitutions.findByDoctorId(any()) } answers {
                listOf(DoctorInstitutionEntity("di-1", firstArg(), doctorInstitutionId))
            }
            every { configs.findByDoctorIdAndInstitutionProjectIdIncludeDeleted(any(), any()) } returns null
            every { configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(any(), any()) } returns null
            every { configs.save(any<com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity>()) } answers { firstArg() }
            every { doctorProjects.findByInstitutionProjectId("ip-1") } returns emptyList()
            every { doctorProjects.findDoctorIdsByInstitutionProjectId("ip-1") } returns emptyList()
            every { doctorProjects.findForUpdate(any(), any()) } returns null
            every {
                jdbc.queryForList(
                    match<String> { it.contains("FROM projects") && it.contains("FOR UPDATE") },
                    String::class.java,
                    "project-1"
                )
            } returns listOf("project-1")
            listOf("doctor-a", "doctor-b").forEach { doctorId ->
                every {
                    jdbc.queryForList(
                        match<String> { it.contains("FROM doctor_institutions") && it.contains("doctor_id = ?") },
                        String::class.java,
                        doctorId,
                        "institution-1"
                    )
                } returns listOf("di-$doctorId")
            }
        }

        fun validUpdatePayload() = objectMapper.createObjectNode().apply {
            put("baseVersion", 0)
            putNull("name")
            putNull("category")
            putNull("description")
            putNull("rating")
            putNull("reviewCount")
            putNull("tags")
            putNull("slogan")
            putNull("detailContent")
            put("price", BigDecimal("3500.00"))
            putNull("originalPrice")
            put("currency", "USD")
            putNull("coverImage")
            putNull("images")
            putNull("salesCount")
            putNull("isActive")
            putArray("doctorBindings")
        }

        fun validUpdateJson(): String = validUpdatePayload().toString()

        fun stubLockedInstitutionProject(version: Long) {
            every { institutionProjects.findForUpdate("ip-1") } returns InstitutionProjectEntity(
                "ip-1",
                "institution-1",
                "project-1",
                price = BigDecimal("3500.00"),
                version = version
            )
        }

        fun stubLockedDoctorIds(ids: List<String>) {
            every {
                jdbc.queryForList(
                    match<String> { it.contains("FROM doctor_projects") && it.contains("FOR UPDATE") },
                    String::class.java,
                    "ip-1"
                )
            } returns ids
        }

        fun stubConfig(config: DoctorInstitutionProjectConfigEntity) {
            every {
                jdbc.queryForList(
                    match<String> { it.contains("doctor_institution_project_configs") && !it.contains("FOR UPDATE") },
                    "ip-1"
                )
            } returns listOf(mapOf("id" to config.id, "doctor_id" to config.doctorId))
            every {
                jdbc.queryForList(
                    match<String> { it.contains("doctor_institution_project_configs") && it.contains("FOR UPDATE") },
                    "ip-1"
                )
            } returns listOf(mapOf("id" to config.id, "doctor_id" to config.doctorId))
            every {
                configs.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(config.doctorId, "ip-1")
            } returns config
        }

        fun stubListProject(version: Long) {
            val entity = InstitutionProjectEntity(
                "ip-list",
                "institution-1",
                "project-1",
                price = BigDecimal("3500.00"),
                version = version
            )
            every { institutionProjects.findAll() } returns listOf(entity)
            every { projects.findAllById(any<Iterable<String>>()) } returns listOf(ProjectEntity("project-1", "Project"))
            every { doctorProjects.findByInstitutionProjectId("ip-list") } returns emptyList()
        }

        fun update(request: InstitutionProjectRequest) = controller.update(
            authentication,
            "ip-1",
            validUpdatePayload().apply {
                put("name", request.name)
                put("category", request.category)
                put("description", request.description)
                put("rating", request.rating)
                put("reviewCount", request.reviewCount)
                put("tags", request.tags)
                put("slogan", request.slogan)
                put("detailContent", request.detailContent)
                put("price", request.price)
                put("originalPrice", request.originalPrice)
                put("currency", request.currency.name)
                put("coverImage", request.coverImage)
                put("images", request.images)
                put("salesCount", request.salesCount)
                put("isActive", request.isActive)
                set<JsonNode>("doctorBindings", objectMapper.valueToTree(request.doctorBindings))
            }
        )

        fun verifyNoUpdateWrites() {
            verify(exactly = 0) { institutionProjects.save(any()) }
            verify(exactly = 0) { institutionProjects.flush() }
            verify(exactly = 0) { doctorProjects.saveAll(any<Iterable<DoctorProjectEntity>>()) }
            verify(exactly = 0) { doctorProjects.deleteAll(any<Iterable<DoctorProjectEntity>>()) }
            verify(exactly = 0) { configs.save(any<DoctorInstitutionProjectConfigEntity>()) }
            verify(exactly = 0) { configs.delete(any()) }
        }
    }

    private fun institutionProjectRequest(doctorBindings: List<DoctorProjectBinding>) = InstitutionProjectRequest(
        institutionId = "institution-1",
        projectId = "project-1",
        price = BigDecimal("3500.00"),
        doctorBindings = doctorBindings
    )

    private fun doctorProject(
        doctorId: String,
        description: String = "description",
        tags: String = "tags",
        images: String = "images"
    ) = listOf(
        DoctorProjectEntity(
            doctorId = doctorId,
            projectId = "project-1",
            institutionProjectId = "ip-1",
            price = BigDecimal("3000.00"),
            serviceDescription = description,
            serviceTags = tags,
            images = images
        )
    )
}
