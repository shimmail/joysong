package com.joysong.server.project.controller

import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import java.math.BigDecimal

class ManagementProjectControllerTest {
    @Test
    fun `catalog maps exactly the approved inheritance summary values`() {
        val repository = mockk<ProjectRepository>()
        every { repository.findAll() } returns listOf(
            ProjectEntity(
                id = "project-1",
                name = "Hydrating facial",
                category = "Skin care",
                description = "Restores moisture",
                tags = "hydration,facial",
                categoryTags = "skin,care",
                coverImage = "https://example.com/cover.png",
                images = "https://example.com/one.png,https://example.com/two.png",
                referencePrice = BigDecimal("188.00"),
                currency = "USD",
                slogan = "Refresh your skin",
                detailContent = "Long-form treatment details",
                salesCount = 37,
                rating = BigDecimal("4.9"),
                reviewCount = 18
            )
        )

        val summary = ManagementProjectCatalogService(repository).list().single()
        val fields = summary.javaClass.declaredFields.associateBy { it.name }

        assertEquals(
            setOf(
                "id", "name", "category", "description", "tags", "categoryTags",
                "coverImage", "referencePrice", "currency", "slogan", "detailContent",
                "images", "salesCount"
            ),
            fields.keys
        )
        fields.values.forEach { it.trySetAccessible() }
        assertEquals("Refresh your skin", fields.getValue("slogan").get(summary))
        assertEquals("Long-form treatment details", fields.getValue("detailContent").get(summary))
        assertEquals(
            "https://example.com/one.png,https://example.com/two.png",
            fields.getValue("images").get(summary)
        )
        assertEquals(37, fields.getValue("salesCount").get(summary))
    }

    @Test
    fun `active consultant including admin consultant reads deterministic project summaries while admin only is rejected`() {
        val repository = mockk<ProjectRepository>()
        every { repository.findAll() } returns listOf(ProjectEntity("2", "B"), ProjectEntity("1", "A"))
        val catalog = ManagementProjectCatalogService(repository)
        val access = mockk<ManagementAccessService>()
        val auth = mockk<Authentication>()
        every { access.actor(auth) } returns actor(setOf("CONSULTANT"))
        val controller = ManagementProjectController(access, catalog)

        assertEquals(listOf("1", "2"), controller.list(auth).data!!.map { it.id })

        every { access.actor(auth) } returns actor(setOf("ADMIN", "CONSULTANT"), admin = true)
        assertEquals(listOf("1", "2"), controller.list(auth).data!!.map { it.id })

        every { access.actor(auth) } returns actor(setOf("ADMIN"), admin = true)
        assertThrows(AccessDeniedException::class.java) { controller.list(auth) }
    }

    private fun actor(roles: Set<String>, admin: Boolean = false) =
        ManagementActor("user-1", admin, roles, null, emptySet(), emptySet(), emptySet())
}
