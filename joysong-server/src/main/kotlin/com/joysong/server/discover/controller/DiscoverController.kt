package com.joysong.server.discover.controller

import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.common.BaseResponse
import com.joysong.server.common.apiPage
import com.joysong.server.common.toCompatibilityResponse
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.discover.dto.InstitutionProjectDetailDto
import com.joysong.server.discover.dto.InstitutionProjectItemResponse
import com.joysong.server.discover.dto.FilterOptionsResponse
import com.joysong.server.discover.dto.ProjectWithInstitutionsResponse
import com.joysong.server.discover.dto.ArticleResponse
import com.joysong.server.discover.dto.DiaryResponse
import com.joysong.server.discover.dto.DoctorResponse
import com.joysong.server.discover.dto.InstitutionResponse
import com.joysong.server.discover.dto.toResponse
import com.joysong.server.discover.service.DiscoverDetailService
import com.joysong.server.discover.service.DiscoverService
import com.joysong.server.discover.service.DiscoverSearchService
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.order.service.TravelGroundServiceQuote
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/discover")
class DiscoverController(
    private val projectRepository: ProjectRepository,
    private val diaryRepository: DiaryRepository,
    private val doctorRepository: DoctorRepository,
    private val institutionRepository: InstitutionRepository,
    private val articleRepository: ArticleRepository,
    private val discoverDetailService: DiscoverDetailService,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val discoverService: DiscoverService,
    private val discoverSearchService: DiscoverSearchService,
    private val configRepository: DoctorInstitutionProjectConfigRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    private val institutionConsultantService: InstitutionConsultantService,
    private val travelGroundServicePricing: TravelGroundServicePricing
) {
    @GetMapping("/filter-options")
    fun getFilterOptions(): BaseResponse<*> {
        val allProjects = projectRepository.findAll()
        val projectMap = allProjects.associateBy { it.id }
        val effectiveInstitutionProjects = institutionProjectRepository.findAll()
            .filter { it.isActive }
            .mapNotNull { ip -> projectMap[ip.projectId]?.let { institutionProjectDetailResolver.resolve(ip, it) } }

        // categories: projects表category字段所有DISTINCT非空值
        val categories = (allProjects.map { it.category } + effectiveInstitutionProjects.map { it.category })
            .filter { it.isNotBlank() }
            .distinct()

        // tags: projects表tags字段拆分后去重非空值
        val tags = (allProjects + effectiveInstitutionProjects).flatMap { project ->
            project.tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }.distinct()

        // cities: institutions表city字段所有DISTINCT非空值
        val cities = institutionRepository.findAll().map { it.city }
            .filter { it.isNotBlank() }
            .distinct()

        return BaseResponse.success(FilterOptionsResponse(categories, tags, cities))
    }

    @GetMapping("/projects")
    fun getProjects(
        @RequestParam(defaultValue = "") categories: String,
        @RequestParam(defaultValue = "") cities: String,
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "") tags: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<BaseResponse<List<ProjectWithInstitutionsResponse>>> {
        val categoryList = if (categories.isBlank()) emptyList() else categories.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val cityList = if (cities.isBlank()) emptyList() else cities.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val tagList = if (tags.isBlank()) emptyList() else tags.split(",").map { it.trim() }.filter { it.isNotBlank() }
        val filtered = discoverSearchService.searchProjects(categoryList, cityList, query, tagList)
        return filtered.apiPage(offset, limit).toCompatibilityResponse()
    }

    @GetMapping("/diaries")
    fun getDiaries(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<BaseResponse<List<DiaryResponse>>> {
        val result = if (query.isBlank()) diaryRepository.findPublishedOrderByPublishDateDesc()
        else {
            val escaped = query.replace("%", "\\%").replace("_", "\\_")
            diaryRepository.findPublishedByTitleOrTags(escaped)
        }
        return result.map { it.toResponse() }.apiPage(offset, limit).toCompatibilityResponse()
    }

    @GetMapping("/doctors")
    fun getDoctors(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<BaseResponse<List<DoctorResponse>>> {
        val result = if (query.isBlank()) doctorRepository.findAll()
        else {
            val escaped = query.replace("%", "\\%").replace("_", "\\_")
            doctorRepository.findByNameContainingOrSpecialtiesContaining(escaped, escaped)
        }
        return result.map { it.toResponse() }.apiPage(offset, limit).toCompatibilityResponse()
    }

    @GetMapping("/institutions")
    fun getInstitutions(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<BaseResponse<List<InstitutionResponse>>> {
        val result = if (query.isBlank()) institutionRepository.findAll()
        else {
            val escaped = query.replace("%", "\\%").replace("_", "\\_")
            institutionRepository.findByNameContainingOrCityContaining(escaped, escaped)
        }
        return result.map { it.toResponse() }.apiPage(offset, limit).toCompatibilityResponse()
    }

    @GetMapping("/articles")
    fun getArticles(
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): ResponseEntity<BaseResponse<List<ArticleResponse>>> {
        val result = if (query.isBlank()) articleRepository.findAll()
        else {
            val escaped = query.replace("%", "\\%").replace("_", "\\_")
            articleRepository.findByTitleContainingOrAuthorNameContaining(escaped, escaped)
        }
        return result.map { it.toResponse() }.apiPage(offset, limit).toCompatibilityResponse()
    }

    // Detail endpoints
    @GetMapping("/projects/{id}")
    fun getProjectById(@PathVariable id: String): BaseResponse<*> {
        val detail = discoverDetailService.getProjectDetail(id)
        if (detail == null) {
            return BaseResponse<Any>(
                code = 404,
                message = "RESOURCE_NOT_FOUND: Project not found / 未找到项目"
            )
        }
        return BaseResponse.success(detail)
    }

    @GetMapping("/diaries/{id}")
    fun getDiaryById(@PathVariable id: String): BaseResponse<*> {
        return diaryRepository.findPublishedById(id)
            .map { BaseResponse.success(it.toResponse()) }
            .orElse(BaseResponse.error("RESOURCE_NOT_FOUND: Diary not found / 未找到日记", 404))
    }

    @GetMapping("/doctors/{id}")
    fun getDoctorById(@PathVariable id: String): BaseResponse<*> {
        val detail = discoverDetailService.getDoctorDetail(id)
        if (detail == null) {
            return BaseResponse<Any>(
                code = 404,
                message = "RESOURCE_NOT_FOUND: Doctor not found / 未找到医生"
            )
        }
        return BaseResponse.success(detail)
    }

    @GetMapping("/institutions/{id}")
    fun getInstitutionById(@PathVariable id: String): BaseResponse<*> {
        val detail = discoverDetailService.getInstitutionDetail(id)
        if (detail == null) {
            return BaseResponse<Any>(
                code = 404,
                message = "RESOURCE_NOT_FOUND: Institution not found / 未找到机构"
            )
        }
        return BaseResponse.success(detail)
    }

    @GetMapping("/articles/{id}")
    fun getArticleById(@PathVariable id: String): BaseResponse<*> {
        return articleRepository.findById(id)
            .map { BaseResponse.success(it.toResponse()) }
            .orElse(BaseResponse.error("RESOURCE_NOT_FOUND: Article not found / 未找到文章", 404))
    }

    @GetMapping("/institutions/{id}/projects")
    fun getInstitutionProjects(@PathVariable id: String): BaseResponse<*> {
        // N+1 修复：通过 DiscoverService 批量查询 Project，避免循环内逐个 findById
        return BaseResponse.success(discoverService.getInstitutionProjectsWithProject(id))
    }

    @GetMapping("/institutions/{institutionId}/projects/{projectId}")
    fun getInstitutionProjectDetail(
        @PathVariable institutionId: String,
        @PathVariable projectId: String
    ): BaseResponse<*> {
        val detail = discoverDetailService.getInstitutionProjectDetail(institutionId, projectId)
        return if (detail != null) BaseResponse.success(detail)
        else BaseResponse<Any>(
            code = 404,
            message = "RESOURCE_NOT_FOUND: Institution project not found / 未找到机构项目"
        )
    }

    @GetMapping("/institutions/{id}/diaries")
    fun getInstitutionDiaries(@PathVariable id: String): BaseResponse<*> {
        return BaseResponse.success(discoverDetailService.getInstitutionDiaries(id))
    }

    @GetMapping("/institutions/{id}/doctors")
    fun getInstitutionDoctors(@PathVariable id: String): BaseResponse<*> {
        return BaseResponse.success(discoverDetailService.getInstitutionDoctors(id))
    }

    @GetMapping("/institutions/{id}/consultants")
    fun getInstitutionConsultants(@PathVariable id: String): BaseResponse<*> =
        BaseResponse.success(institutionConsultantService.listApprovedConsultants(id))

    /**
     * 查询某机构项目关联的医生列表
     * N+1 修复：通过 DiscoverService 批量查询 Doctor，避免循环内逐个 findById
     */
    @GetMapping("/institution-projects/{institutionProjectId}/doctors")
    fun getDoctorsByInstitutionProject(@PathVariable institutionProjectId: String): BaseResponse<*> {
        return BaseResponse.success(discoverService.getDoctorsByInstitutionProject(institutionProjectId))
    }

    @GetMapping("/travel-ground-service-quote")
    fun getTravelGroundServiceQuote(
        @RequestParam doctorId: String,
        @RequestParam institutionProjectId: String
    ): BaseResponse<TravelGroundServiceQuote> {
        val config = configRepository
            .findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)
            ?: throw IllegalArgumentException("MEDICAL_LIST_PRICE_NOT_CONFIGURED")
        return BaseResponse.success(travelGroundServicePricing.quote(config.medicalListPrice))
    }
}
