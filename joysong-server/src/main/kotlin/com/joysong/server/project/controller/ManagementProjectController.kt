package com.joysong.server.project.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/api/management/projects")
class ManagementProjectController(
    private val accessService: ManagementAccessService,
    private val catalogService: ManagementProjectCatalogService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<List<ManagementProjectSummary>> {
        val actor = accessService.actor(authentication)
        if (actor.isAdmin || actor.activeRoles.intersect(PROFESSIONAL_ROLES).isEmpty()) {
            throw AccessDeniedException("只有已认证的专业身份可以查看项目目录")
        }
        return BaseResponse.success(catalogService.list())
    }

    private companion object {
        val PROFESSIONAL_ROLES = setOf("DOCTOR", "CONSULTANT", "INSTITUTION_LEGAL_REPRESENTATIVE")
    }
}

@Service
class ManagementProjectCatalogService(private val projectRepository: ProjectRepository) {
    fun list(): List<ManagementProjectSummary> = projectRepository.findAll()
        .sortedWith(compareBy<ProjectEntity> { it.name }.thenBy { it.id })
        .map { it.toSummary() }
}

data class ManagementProjectSummary(
    val id: String,
    val name: String,
    val category: String,
    val description: String,
    val tags: String,
    val categoryTags: String,
    val coverImage: String,
    val referencePrice: BigDecimal,
    val currency: String
)

private fun ProjectEntity.toSummary() = ManagementProjectSummary(
    id, name, category, description, tags, categoryTags, coverImage, referencePrice, currency
)
