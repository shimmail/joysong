package com.joysong.server.discover.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.banner.entity.BannerEntity
import com.joysong.server.discover.service.DiscoverDetailService
import com.joysong.server.discover.service.DiscoverService
import com.joysong.server.home.service.HomeService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.project.entity.ProjectEntity
import org.junit.jupiter.api.Test
import org.springframework.cache.annotation.Cacheable
import java.math.BigDecimal
import java.time.LocalDateTime

class PublicContentDtosTest {

    @Test
    fun `public response does not serialize persistence audit fields`() {
        val entity = BannerEntity(
            id = "banner-1",
            title = "Title",
            createdAt = LocalDateTime.of(2026, 1, 1, 0, 0),
            deletedAt = LocalDateTime.of(2026, 1, 2, 0, 0)
        )

        val json = jacksonObjectMapper().writeValueAsString(entity.toResponse())

        assertTrue(json.contains("\"id\":\"banner-1\""))
        assertTrue(json.contains("\"title\":\"Title\""))
        assertFalse(json.contains("createdAt"))
        assertFalse(json.contains("updatedAt"))
        assertFalse(json.contains("deletedAt"))
    }

    @Test
    fun `home and discover read services have no unbounded cache annotations`() {
        val serviceTypes = listOf(
            HomeService::class.java,
            DiscoverService::class.java,
            DiscoverDetailService::class.java
        )

        val cachedMethods = serviceTypes.flatMap { type ->
            type.declaredMethods.filter { method ->
                method.getAnnotationsByType(Cacheable::class.java).isNotEmpty()
            }
        }

        assertTrue(cachedMethods.isEmpty(), "Public read methods must not use an unbounded cache")
    }

    @Test
    fun `public DTO fields never expose persistence entities`() {
        val dtoTypes = listOf(
            BannerResponse::class.java,
            ProjectResponse::class.java,
            ArticleResponse::class.java,
            DiaryResponse::class.java,
            DoctorResponse::class.java,
            InstitutionResponse::class.java,
            InstitutionProjectResponse::class.java
        )

        val entityFields = dtoTypes.flatMap { it.declaredFields.toList() }
            .filter { it.type.packageName.contains(".entity") }

        assertTrue(entityFields.isEmpty(), "Public DTOs must not contain JPA entity fields")
    }

    @Test
    fun `inherited null institution media keeps the legacy public empty strings`() {
        val response = InstitutionProjectEntity(
            id = "institution-project-1",
            institutionId = "institution-1",
            projectId = "project-1",
            price = java.math.BigDecimal("100.00"),
            coverImage = null,
            images = null
        ).toResponse()

        assertEquals("", response.coverImage)
        assertEquals("", response.images)
    }

    @Test
    fun `project responses expose platform and institution project case counts`() {
        val project = ProjectEntity(
            id = "project-1",
            name = "项目",
            caseCount = 12
        ).toResponse()
        val institutionProject = InstitutionProjectEntity(
            id = "institution-project-1",
            institutionId = "institution-1",
            projectId = "project-1",
            price = BigDecimal("100.00"),
            caseCount = 7
        ).toResponse()

        assertEquals(12, project.caseCount)
        assertEquals(7, institutionProject.caseCount)
    }
}
