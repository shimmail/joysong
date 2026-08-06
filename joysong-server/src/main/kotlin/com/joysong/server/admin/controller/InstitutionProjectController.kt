package com.joysong.server.admin.controller

import com.joysong.server.admin.entity.dto.DoctorProjectBinding
import com.joysong.server.common.BaseResponse
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.UUID
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/admin/institution-projects")
class InstitutionProjectController(
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val doctorRepository: DoctorRepository,
    private val projectRepository: ProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val configRepository: DoctorInstitutionProjectConfigRepository,
    private val orderRepository: OrderRepository,
    private val detailResolver: InstitutionProjectDetailResolver,
    private val doctorInstitutionService: DoctorInstitutionService,
    private val managementAccessService: ManagementAccessService,
    private val jdbcTemplate: JdbcTemplate
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) institutionId: String?
    ): BaseResponse<List<InstitutionProjectDto>> {
        val entities = when {
            projectId != null -> institutionProjectRepository.findByProjectId(projectId)
            institutionId != null -> institutionProjectRepository.findByInstitutionId(institutionId)
            else -> institutionProjectRepository.findAll()
        }
        val actor = managementAccessService.actor(authentication)
        val visibleEntities = if (actor.isAdmin) entities else entities.filter {
            managementAccessService.canViewInstitutionProject(actor, it.id)
        }
        val projectIds = visibleEntities.map { it.projectId }.distinct()
        val projectMap = projectRepository.findAllById(projectIds).associateBy { it.id }
        val dtos = visibleEntities.mapNotNull { entity -> projectMap[entity.projectId]?.let { toDto(entity, it) } }
        return BaseResponse.success(dtos)
    }

    @PostMapping
    @Caching(evict = [
        CacheEvict(cacheNames = ["discover"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    @Transactional
    fun create(authentication: Authentication, @RequestBody request: InstitutionProjectRequest): BaseResponse<InstitutionProjectDto> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireInstitutionManaged(actor, request.institutionId)
        validateRequest(request)?.let { return BaseResponse.error(it) }
        if (!institutionRepository.existsById(request.institutionId)) return BaseResponse.error("机构不存在")
        val project = projectRepository.findById(request.projectId).orElse(null)
            ?: return BaseResponse.error("关联项目不存在")
        validateDoctorBindings(request.institutionId, request.doctorBindings)?.let { return BaseResponse.error(it, 409) }
        if (institutionProjectRepository.findByInstitutionIdAndProjectId(request.institutionId, request.projectId) != null) {
            return BaseResponse.error("该机构已关联此项目")
        }
        val entity = InstitutionProjectEntity(
            id = UUID.randomUUID().toString(),
            institutionId = request.institutionId,
            projectId = request.projectId,
            name = detailResolver.normalize(request.name),
            category = detailResolver.normalize(request.category),
            description = detailResolver.normalize(request.description),
            rating = if (actor.isAdmin) request.rating else null,
            reviewCount = if (actor.isAdmin) request.reviewCount else null,
            tags = detailResolver.normalize(request.tags),
            slogan = detailResolver.normalize(request.slogan),
            detailContent = detailResolver.normalizeRichText(request.detailContent),
            price = request.price,
            originalPrice = request.originalPrice,
            coverImage = request.coverImage ?: "",
            images = request.images ?: "",
            salesCount = if (actor.isAdmin) request.salesCount ?: 0 else 0,
            isActive = request.isActive ?: true
        )
        val saved = institutionProjectRepository.save(entity)
        saveDoctorBindings(saved.id, saved.projectId, request.doctorBindings)
        return BaseResponse.success(toDto(saved, project))
    }

    @PutMapping("/{id}")
    @Caching(evict = [
        CacheEvict(cacheNames = ["discover"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    @Transactional
    fun update(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: InstitutionProjectRequest
    ): BaseResponse<InstitutionProjectDto> {
        val existing = institutionProjectRepository.findById(id).orElse(null)
            ?: return BaseResponse.error("机构项目不存在")
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireInstitutionManaged(actor, existing.institutionId)
        validateRequest(request, validateAssociationIds = false)?.let { return BaseResponse.error(it) }
        val project = projectRepository.findById(existing.projectId).orElse(null)
            ?: return BaseResponse.error("关联项目不存在")
        validateDoctorBindings(existing.institutionId, request.doctorBindings)?.let { return BaseResponse.error(it, 409) }
        val updated = existing.copy(
            name = detailResolver.normalize(request.name),
            category = detailResolver.normalize(request.category),
            description = detailResolver.normalize(request.description),
            rating = if (actor.isAdmin) request.rating else existing.rating,
            reviewCount = if (actor.isAdmin) request.reviewCount else existing.reviewCount,
            tags = detailResolver.normalize(request.tags),
            slogan = detailResolver.normalize(request.slogan),
            detailContent = detailResolver.normalizeRichText(request.detailContent),
            price = request.price,
            originalPrice = request.originalPrice,
            coverImage = request.coverImage?.trim().orEmpty(),
            images = request.images?.trim().orEmpty(),
            salesCount = if (actor.isAdmin) request.salesCount ?: 0 else existing.salesCount,
            isActive = request.isActive ?: true,
            updatedAt = LocalDateTime.now()
        )
        val saved = institutionProjectRepository.save(updated)
        syncDoctorBindings(saved.id, saved.projectId, request.doctorBindings)
        return BaseResponse.success(toDto(saved, project))
    }

    @DeleteMapping("/{id}")
    @Caching(evict = [
        CacheEvict(cacheNames = ["discover"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    @Transactional
    fun delete(authentication: Authentication, @PathVariable id: String): BaseResponse<Void> {
        val existing = institutionProjectRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Void>("机构项目不存在")
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requireInstitutionManaged(actor, existing.institutionId)
        val finalStatuses = listOf("CANCELLED", "REFUNDED", "SETTLED")
        if (orderRepository.existsByInstitutionProjectIdAndStatusNotIn(id, finalStatuses)) {
            return BaseResponse.error("该机构项目存在进行中的订单，请先停用并处理订单后再删除", 409)
        }
        // 同时清理医生-机构项目关联
        doctorProjectRepository.deleteByInstitutionProjectId(id)
        jdbcTemplate.update(
            "UPDATE doctor_institution_project_configs SET deleted_at = NOW() WHERE institution_project_id = ? AND deleted_at IS NULL",
            id
        )
        jdbcTemplate.update(
            "UPDATE split_config_proposals SET status = 'WITHDRAWN', decided_at = NOW(), decision_note = '机构项目已删除' WHERE institution_project_id = ? AND status = 'PENDING'",
            id
        )
        jdbcTemplate.update(
            "UPDATE doctor_project_change_requests SET status = 'REJECTED', reviewed_at = NOW(), review_note = '机构项目已删除' WHERE institution_project_id = ? AND status = 'PENDING'",
            id
        )
        institutionProjectRepository.deleteById(id)
        return BaseResponse(code = 200)
    }

    /**
     * 批量保存医生与机构项目的关联关系
     */
    private fun saveDoctorBindings(institutionProjectId: String, projectId: String, doctorBindings: List<DoctorProjectBinding>) {
        val entities = doctorBindings
            .filter { it.doctorId.isNotBlank() }
            .map { binding ->
                DoctorProjectEntity(
                    doctorId = binding.doctorId,
                    projectId = projectId,
                    institutionProjectId = institutionProjectId
                )
            }
        doctorProjectRepository.saveAll(entities)
    }

    /** 调整执行医生名单时保留未移除医生已经审核通过的个人项目资料。 */
    private fun syncDoctorBindings(institutionProjectId: String, projectId: String, doctorBindings: List<DoctorProjectBinding>) {
        val requestedDoctorIds = doctorBindings.map { it.doctorId.trim() }.filter(String::isNotEmpty).toSet()
        val existing = doctorProjectRepository.findByInstitutionProjectId(institutionProjectId)
        val removed = existing.filter { it.doctorId !in requestedDoctorIds }
        removed.forEach { binding ->
            configRepository.findByDoctorIdAndInstitutionProjectId(binding.doctorId, institutionProjectId)
                ?.let(configRepository::delete)
            jdbcTemplate.update(
                "UPDATE split_config_proposals SET status = 'WITHDRAWN', decided_at = NOW(), decision_note = '机构已移除执行医生' WHERE doctor_id = ? AND institution_project_id = ? AND status = 'PENDING'",
                binding.doctorId,
                institutionProjectId
            )
        }
        doctorProjectRepository.deleteAll(removed)
        val existingDoctorIds = existing.map { it.doctorId }.toSet()
        doctorProjectRepository.saveAll(
            requestedDoctorIds.filter { it !in existingDoctorIds }.map { doctorId ->
                DoctorProjectEntity(
                    doctorId = doctorId,
                    projectId = projectId,
                    institutionProjectId = institutionProjectId
                )
            }
        )
    }

    private fun validateDoctorBindings(institutionId: String, bindings: List<DoctorProjectBinding>): String? {
        val doctorIds = bindings.map { it.doctorId.trim() }.filter { it.isNotBlank() }
        if (doctorIds.size != doctorIds.distinct().size) return "同一医生不能重复绑定到机构项目"
        val doctors = doctorRepository.findAllById(doctorIds).associateBy { it.id }
        if (doctors.size != doctorIds.size) return "存在无效的医生"
        val unbound = doctorIds.firstOrNull { doctorId ->
            doctorInstitutionService.findByDoctorId(doctorId).none {
                it.institutionId == institutionId && it.status == "APPROVED"
            }
        }
        return unbound?.let { "医生未绑定当前机构，不能配置该机构项目" }
    }

    private fun validateRequest(request: InstitutionProjectRequest, validateAssociationIds: Boolean = true): String? {
        if (validateAssociationIds && request.institutionId.isBlank()) return "请选择机构"
        if (validateAssociationIds && request.projectId.isBlank()) return "请选择关联项目"
        if (request.price < BigDecimal.ZERO) return "价格不能小于 0"
        if (request.originalPrice != null && request.originalPrice < BigDecimal.ZERO) return "原价不能小于 0"
        if (request.rating != null && (request.rating < BigDecimal.ZERO || request.rating > BigDecimal("5.0"))) {
            return "评分必须在 0 到 5 之间"
        }
        if (request.reviewCount != null && request.reviewCount < 0) return "评价数不能小于 0"
        if (request.salesCount != null && request.salesCount < 0) return "销量不能小于 0"
        return null
    }

    private fun toDto(entity: InstitutionProjectEntity, project: com.joysong.server.project.entity.ProjectEntity): InstitutionProjectDto {
        val effective = detailResolver.resolve(entity, project)
        val doctorProjects = doctorProjectRepository.findByInstitutionProjectId(entity.id)
        val doctorMap = doctorRepository.findAllById(doctorProjects.map { it.doctorId }).associateBy { it.id }
        val doctors = doctorProjects.mapNotNull { binding -> doctorMap[binding.doctorId]?.let { doctor ->
            DoctorSummary(
                doctor.id,
                doctor.name,
                doctor.title,
                doctor.avatar,
                binding.serviceDescription,
                binding.serviceTags,
                binding.scheduleNote,
                binding.coverImage,
                binding.images
            )
        } }
        return InstitutionProjectDto(
            id = entity.id,
            institutionId = entity.institutionId,
            projectId = entity.projectId,
            projectName = effective.name,
            baseProjectName = project.name,
            name = entity.name,
            category = entity.category,
            description = entity.description,
            rating = entity.rating,
            reviewCount = entity.reviewCount,
            tags = entity.tags,
            slogan = entity.slogan,
            detailContent = entity.detailContent,
            effectiveName = effective.name,
            effectiveCategory = effective.category,
            effectiveDescription = effective.description,
            effectiveRating = effective.rating,
            effectiveReviewCount = effective.reviewCount,
            effectiveTags = effective.tags,
            effectiveSlogan = effective.slogan,
            effectiveDetailContent = effective.detailContent,
            price = entity.price,
            originalPrice = entity.originalPrice,
            coverImage = entity.coverImage,
            images = entity.images,
            effectiveCoverImage = effective.coverImage,
            effectiveImages = effective.images,
            salesCount = entity.salesCount,
            isActive = entity.isActive,
            createdAt = entity.createdAt,
            doctors = doctors
        )
    }
}

data class InstitutionProjectRequest(
    val institutionId: String = "",
    val projectId: String = "",
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val rating: BigDecimal? = null,
    val reviewCount: Int? = null,
    val tags: String? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
    val originalPrice: BigDecimal? = null,
    val coverImage: String? = null,
    val images: String? = null,
    val salesCount: Int? = null,
    val isActive: Boolean? = null,
    val doctorBindings: List<DoctorProjectBinding> = emptyList()
)

data class InstitutionProjectDto(
    val id: String,
    val institutionId: String,
    val projectId: String,
    val projectName: String,
    val baseProjectName: String,
    val name: String?,
    val category: String?,
    val description: String?,
    val rating: BigDecimal?,
    val reviewCount: Int?,
    val tags: String?,
    val slogan: String?,
    val detailContent: String?,
    val effectiveName: String,
    val effectiveCategory: String,
    val effectiveDescription: String,
    val effectiveRating: BigDecimal,
    val effectiveReviewCount: Int,
    val effectiveTags: String,
    val effectiveSlogan: String,
    val effectiveDetailContent: String?,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val coverImage: String,
    val images: String,
    val effectiveCoverImage: String,
    val effectiveImages: String,
    val salesCount: Int,
    val isActive: Boolean,
    val createdAt: java.time.LocalDateTime,
    val doctors: List<DoctorSummary>
)

data class DoctorSummary(
    val id: String,
    val name: String,
    val title: String,
    val avatar: String,
    val serviceDescription: String = "",
    val serviceTags: String = "",
    val scheduleNote: String = "",
    val coverImage: String = "",
    val images: String = ""
)
