package com.joysong.server.home.service

import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.banner.repository.BannerRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.discover.dto.ArticleResponse
import com.joysong.server.discover.dto.BannerResponse
import com.joysong.server.discover.dto.DiaryResponse
import com.joysong.server.discover.dto.ProjectResponse
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.home.entity.dto.RecommendedInstitutionProjectDto
import com.joysong.server.institution.entity.InstitutionEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service

@Service
class HomeService(
    private val bannerRepository: BannerRepository,
    private val projectRepository: ProjectRepository,
    private val articleRepository: ArticleRepository,
    private val diaryRepository: DiaryRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver
) {

    fun getBanners(): List<BannerResponse> =
        bannerRepository.findAllByOrderBySortOrderAsc().map { it.toResponse() }

    fun getHotProjects(): List<ProjectResponse> =
        projectRepository.findAll().map { it.toResponse() }

    fun getExpertArticles(): List<ArticleResponse> =
        articleRepository.findAll().map { it.toResponse() }

    fun getUserDiaries(): List<DiaryResponse> =
        diaryRepository.findPublishedOrderByPublishDateDesc().map { it.toResponse() }

    /**
     * 首页推荐机构项目：按销量倒序返回前 8 个机构项目，合并项目模板信息
     * N+1 修复：先批量查出所有关联的 Project 和 Institution，再在内存中组装结果
     */
    fun getRecommendedInstitutionProjects(): List<RecommendedInstitutionProjectDto> {
        val allInstitutionProjects = institutionProjectRepository.findAll().filter { it.isActive }
        if (allInstitutionProjects.isEmpty()) return emptyList()

        // 批量收集所有 projectId 和 institutionId
        val projectIds = allInstitutionProjects.map { it.projectId }.distinct()
        val institutionIds = allInstitutionProjects.map { it.institutionId }.distinct()

        // 一次性批量查询 Project 和 Institution，构建 ID -> Entity 映射
        val projectMap: Map<String, ProjectEntity> = projectRepository.findAllById(projectIds)
            .associateBy { it.id }
        val institutionMap: Map<String, InstitutionEntity> = institutionRepository.findAllById(institutionIds)
            .associateBy { it.id }

        // 在内存中组装结果
        return allInstitutionProjects.mapNotNull { ip ->
            val project = projectMap[ip.projectId] ?: return@mapNotNull null
            val institution = institutionMap[ip.institutionId] ?: return@mapNotNull null
            val effective = institutionProjectDetailResolver.resolve(ip, project)
            RecommendedInstitutionProjectDto(
                institutionProjectId = ip.id,
                institutionId = ip.institutionId,
                projectId = ip.projectId,
                projectName = effective.name,
                institutionName = institution.name,
                price = ip.price,
                originalPrice = ip.originalPrice,
                coverImage = effective.coverImage,
                category = effective.category,
                salesCount = ip.salesCount,
                description = effective.description,
                rating = effective.rating,
                reviewCount = effective.reviewCount,
                tags = effective.tags,
                slogan = effective.slogan,
                detailContent = effective.detailContent
            )
        }.sortedByDescending { it.salesCount }.take(8)
    }
}
