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

class ManagementProjectControllerTest {
    @Test
    fun `active consultant reads deterministic project summaries while admin is rejected`() {
        val repository = mockk<ProjectRepository>()
        every { repository.findAll() } returns listOf(ProjectEntity("2", "B"), ProjectEntity("1", "A"))
        val catalog = ManagementProjectCatalogService(repository)
        val access = mockk<ManagementAccessService>()
        val auth = mockk<Authentication>()
        every { access.actor(auth) } returns actor(setOf("CONSULTANT"))
        val controller = ManagementProjectController(access, catalog)

        assertEquals(listOf("1", "2"), controller.list(auth).data!!.map { it.id })

        every { access.actor(auth) } returns actor(setOf("ADMIN"), admin = true)
        assertThrows(AccessDeniedException::class.java) { controller.list(auth) }
    }

    private fun actor(roles: Set<String>, admin: Boolean = false) =
        ManagementActor("user-1", admin, roles, null, emptySet(), emptySet(), emptySet())
}
