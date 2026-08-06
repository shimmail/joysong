package com.joysong.server.project.service

import com.joysong.server.admin.entity.dto.ProjectRequest
import com.joysong.server.admin.entity.vo.ProjectAdminVo
import com.joysong.server.catalog.service.CatalogIntegrityService
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class ProjectService(
    private val projectRepository: ProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val catalogIntegrityService: CatalogIntegrityService
) {

    @Cacheable(cacheNames = ["projects"], key = "'all'")
    fun findAll(): List<ProjectEntity> = projectRepository.findAll()

    @Cacheable(cacheNames = ["projects"], key = "#id")
    fun findById(id: String): ProjectEntity? = projectRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["projects"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    fun save(entity: ProjectEntity): ProjectEntity = projectRepository.save(entity)

    @Caching(evict = [
        CacheEvict(cacheNames = ["projects"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun deleteByIdIfSafe(id: String): String? {
        if (!projectRepository.existsById(id)) return "项目不存在"
        catalogIntegrityService.projectDeletionBlocker(id)?.let { return it }
        projectRepository.deleteById(id)
        return null
    }

    fun findByCategoryContainingOrNameContaining(category: String, name: String): List<ProjectEntity> =
        projectRepository.findByCategoryContainingOrNameContaining(category, name)

    @Cacheable(cacheNames = ["projects"], key = "'category:' + #category")
    fun findByCategory(category: String): List<ProjectEntity> = projectRepository.findByCategory(category)

    fun findByNameContainingOrCategoryContaining(name: String, category: String): List<ProjectEntity> =
        projectRepository.findByNameContainingOrCategoryContaining(name, category)

    @Cacheable(cacheNames = ["projects"], key = "'search:' + #keyword")
    fun searchProjects(keyword: String): List<ProjectEntity> = projectRepository.searchProjects(keyword)

    // ---- Admin 方法 ----

    fun listAdminProjects(keyword: String?): List<ProjectAdminVo> {
        val projects = if (!keyword.isNullOrBlank()) {
            projectRepository.searchProjects(keyword.trim())
        } else {
            projectRepository.findAll()
        }
        return projects.map { project ->
            val doctorIds = doctorProjectRepository.findByProjectId(project.id).map { it.doctorId }
            ProjectAdminVo(
                id = project.id,
                name = project.name,
                category = project.category,
                description = project.description,
                tags = project.tags,
                categoryTags = project.categoryTags,
                coverImage = project.coverImage,
                images = project.images,
                referencePrice = project.referencePrice,
                slogan = project.slogan,
                detailContent = project.detailContent,
                rating = project.rating,
                reviewCount = project.reviewCount,
                salesCount = project.salesCount,
                createdAt = project.createdAt,
                doctorIds = doctorIds
            )
        }
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["projects"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun adminCreateProject(request: ProjectRequest): ProjectEntity {
        val projectId = UUID.randomUUID().toString()
        val entity = ProjectEntity(
            id = projectId,
            name = request.name,
            category = request.category,
            description = request.description,
            tags = request.tags,
            categoryTags = request.categoryTags,
            coverImage = request.coverImage,
            images = request.images,
            referencePrice = request.referencePrice,
            slogan = request.slogan,
            detailContent = request.detailContent,
            rating = request.rating,
            reviewCount = request.reviewCount,
            salesCount = request.salesCount
        )
        return projectRepository.save(entity)
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["projects"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true),
        CacheEvict(cacheNames = ["discover"], allEntries = true)
    ])
    @Transactional
    fun adminUpdateProject(id: String, request: ProjectRequest): ProjectEntity? {
        val existing = projectRepository.findById(id).orElse(null) ?: return null
        val entity = ProjectEntity(
            id = id,
            name = request.name,
            category = request.category,
            description = request.description,
            tags = request.tags,
            categoryTags = request.categoryTags,
            coverImage = request.coverImage,
            images = request.images,
            referencePrice = request.referencePrice,
            slogan = request.slogan,
            detailContent = request.detailContent,
            rating = request.rating,
            reviewCount = request.reviewCount,
            salesCount = request.salesCount,
            createdAt = existing.createdAt
        )
        return projectRepository.save(entity)
    }

    fun count(): Long = projectRepository.count()
}
