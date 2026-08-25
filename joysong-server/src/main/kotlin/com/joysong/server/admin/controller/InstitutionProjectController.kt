package com.joysong.server.admin.controller

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.admin.entity.dto.DoctorProjectBinding
import com.joysong.server.common.BaseResponse
import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.doctor.service.DoctorInstitutionService
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeErrorCode
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.cache.CacheManager
import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.sql.SQLException
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
    private val jdbcTemplate: JdbcTemplate,
    private val travelGroundServicePricing: TravelGroundServicePricing,
    private val objectMapper: ObjectMapper,
    private val cacheManager: CacheManager
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
    @Transactional
    fun create(authentication: Authentication, @RequestBody request: InstitutionProjectRequest): BaseResponse<InstitutionProjectDto> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requirePlatformAdmin(actor)
        validateRequest(request)?.let { return BaseResponse.error(it) }
        if (!institutionRepository.existsById(request.institutionId)) return BaseResponse.error("机构不存在")
        val project = projectRepository.findById(request.projectId).orElse(null)
            ?: return BaseResponse.error("关联项目不存在")
        val doctorBindings = normalizeDoctorBindings(request.doctorBindings)
        validateDoctorBindings(request.institutionId, doctorBindings)?.let { return BaseResponse.error(it, 409) }
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
            currency = request.currency.name,
            coverImage = request.coverImage?.trim()?.takeIf(String::isNotEmpty),
            images = request.images?.trim()?.takeIf(String::isNotEmpty),
            salesCount = if (actor.isAdmin) request.salesCount ?: 0 else 0,
            isActive = request.isActive ?: true
        )
        val saved = institutionProjectRepository.save(entity)
        saveDoctorBindings(saved.id, saved.projectId, doctorBindings)
        evictProjectCachesAfterCommit()
        return BaseResponse.success(toDto(saved, project))
    }

    @PutMapping("/{id}")
    @Transactional
    fun update(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody payload: JsonNode
    ): BaseResponse<InstitutionProjectDto> {
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requirePlatformAdmin(actor)
        val request = parseUpdateRequest(payload)
        validateUpdateRequest(request)?.let { throw projectPayloadInvalid(it) }
        val identity = institutionProjectRepository.findIdentityById(id)
            ?: return BaseResponse.error("机构项目不存在")
        val doctorBindings = normalizeDoctorBindings(request.doctorBindings)
        validateDoctorBindings(identity.institutionId, doctorBindings)?.let { return BaseResponse.error(it, 409) }
        val requestedDoctorIds = doctorBindings.map(DoctorProjectBinding::doctorId).toSet()
        val currentDoctorIds = doctorProjectRepository.findDoctorIdsByInstitutionProjectId(id).distinct().sorted()
        val affectedDoctorIds = (currentDoctorIds + requestedDoctorIds).distinct().sorted()
        val pendingChangeIds = pendingRequestIds("doctor_project_change_requests", id)
        val pendingSplitIds = pendingRequestIds("split_config_proposals", id)
        val relationshipIds = affectedDoctorIds.associateWith { doctorId ->
            activeRelationshipId(doctorId, identity.institutionId, lock = false)
        }
        val configParticipants = compatibilityConfigParticipants(id, lock = false)

        lockPendingRequestsById("doctor_project_change_requests", id, pendingChangeIds)
        lockPendingRequestsById("split_config_proposals", id, pendingSplitIds)
        affectedDoctorIds.forEach { doctorId ->
            val relationshipId = relationshipIds[doctorId]
            val lockedId = lockActiveRelationshipNowait(doctorId, identity.institutionId)
            if (lockedId != relationshipId) {
                throw institutionProjectStale("医生机构关系已变化")
            }
            if (lockedId == null && doctorId in requestedDoctorIds) {
                throw institutionProjectStale("医生机构关系已失效")
            }
        }

        val existing = institutionProjectRepository.findForUpdate(id)
            ?: return BaseResponse.error("机构项目不存在")
        if (existing.institutionId != identity.institutionId || existing.projectId != identity.projectId) {
            throw institutionProjectStale("机构项目关联已变化")
        }
        if (existing.version != request.baseVersion) {
            throw institutionProjectStale("机构项目版本已变化")
        }
        revalidatePendingRequestSet("doctor_project_change_requests", id, pendingChangeIds)
        revalidatePendingRequestSet("split_config_proposals", id, pendingSplitIds)
        lockPlatformProject(existing.projectId)
            ?: throw institutionProjectStale("关联平台项目已失效")
        val lockedCurrentDoctorIds = lockDoctorProjectIds(id)
        if (lockedCurrentDoctorIds != currentDoctorIds) {
            throw institutionProjectStale("医生项目绑定集合已变化")
        }
        val lockedDoctorProjects = affectedDoctorIds.associateWith { doctorId ->
            doctorProjectRepository.findForUpdate(doctorId, id)
        }
        val lockedConfigParticipants = compatibilityConfigParticipants(id, lock = true)
        if (lockedConfigParticipants != configParticipants) {
            throw institutionProjectStale("医生项目兼容配置集合已变化")
        }
        val lockedConfigs = affectedDoctorIds.associateWith { doctorId ->
            configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(doctorId, id)
        }
        revalidateUpdateParticipants(
            currentDoctorIds = currentDoctorIds,
            configParticipants = configParticipants,
            lockedDoctorProjects = lockedDoctorProjects,
            lockedConfigs = lockedConfigs
        )
        val project = projectRepository.findById(existing.projectId).orElse(null)
            ?: return BaseResponse.error("关联项目不存在")
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
            currency = request.currency.name,
            coverImage = request.coverImage?.trim()?.takeIf(String::isNotEmpty),
            images = request.images?.trim()?.takeIf(String::isNotEmpty),
            salesCount = if (actor.isAdmin) request.salesCount ?: 0 else existing.salesCount,
            isActive = request.isActive ?: true,
            updatedAt = LocalDateTime.now()
        )
        val saved = institutionProjectRepository.save(updated)
        institutionProjectRepository.flush()
        syncDoctorBindingsFromLocks(
            saved.id,
            saved.projectId,
            doctorBindings,
            lockedDoctorProjects.values.filterNotNull(),
            lockedConfigs
        )
        evictProjectCachesAfterCommit()
        return BaseResponse.success(toDto(saved, project))
    }

    @DeleteMapping("/{id}")
    @Transactional
    fun delete(authentication: Authentication, @PathVariable id: String): BaseResponse<Void> {
        val existing = institutionProjectRepository.findById(id).orElse(null)
            ?: return BaseResponse.error<Void>("机构项目不存在")
        val actor = managementAccessService.actor(authentication)
        managementAccessService.requirePlatformAdmin(actor)
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
        evictProjectCachesAfterCommit()
        return BaseResponse(code = 200)
    }

    /**
     * 批量保存医生与机构项目的关联关系
     */
    private fun saveDoctorBindings(institutionProjectId: String, projectId: String, doctorBindings: List<DoctorProjectBinding>) {
        val savedBindings = doctorBindings.map { binding ->
            DoctorProjectEntity(
                doctorId = binding.doctorId,
                projectId = projectId,
                institutionProjectId = institutionProjectId,
                price = validatedPrice(binding)
            )
        }
        doctorProjectRepository.saveAll(savedBindings)
        savedBindings.forEach(::syncCompatibilityPrice)
    }

    /** 调整执行医生名单时只使用已经按全局顺序锁定的快照。 */
    private fun syncDoctorBindingsFromLocks(
        institutionProjectId: String,
        projectId: String,
        doctorBindings: List<DoctorProjectBinding>,
        existing: Collection<DoctorProjectEntity>,
        lockedConfigs: Map<String, DoctorInstitutionProjectConfigEntity?>
    ) {
        val requestedDoctorIds = doctorBindings.map { it.doctorId }.toSet()
        val removed = existing.filter { it.doctorId !in requestedDoctorIds }
        removed.forEach { binding ->
            lockedConfigs[binding.doctorId]
                ?.takeIf { it.deletedAt == null }
                ?.let(configRepository::delete)
            jdbcTemplate.update(
                "UPDATE split_config_proposals SET status = 'WITHDRAWN', decided_at = NOW(), decision_note = '机构已移除执行医生' WHERE doctor_id = ? AND institution_project_id = ? AND status = 'PENDING'",
                binding.doctorId,
                institutionProjectId
            )
            jdbcTemplate.update(
                "UPDATE doctor_project_change_requests SET status = 'WITHDRAWN', reviewed_at = NOW(), review_note = '机构已移除执行医生' WHERE doctor_id = ? AND institution_project_id = ? AND request_type IN ('PROFILE_UPDATE', 'LEAVE') AND status = 'PENDING'",
                binding.doctorId,
                institutionProjectId
            )
        }
        doctorProjectRepository.deleteAll(removed)
        val existingByDoctor = existing.associateBy { it.doctorId }
        val savedBindings = doctorBindings.map { binding ->
            val price = validatedPrice(binding)
            existingByDoctor[binding.doctorId]?.copy(price = price, updatedAt = LocalDateTime.now())
                ?: DoctorProjectEntity(
                    doctorId = binding.doctorId,
                    projectId = projectId,
                    institutionProjectId = institutionProjectId,
                    price = price
                )
        }
        doctorProjectRepository.saveAll(savedBindings)
        savedBindings.forEach { binding ->
            saveCompatibilityPrice(binding, lockedConfigs[binding.doctorId])
        }
    }

    private fun parseUpdateRequest(payload: JsonNode): InstitutionProjectUpdateRequest {
        val expected = UPDATE_FIELDS
        if (!payload.isObject || payload.fieldNames().asSequence().toSet() != expected) {
            throw projectPayloadInvalid("机构项目更新字段不完整或包含额外字段")
        }
        fun node(name: String) = payload.get(name)
        val validTypes = node("baseVersion").isIntegralNumber && node("baseVersion").canConvertToLong() &&
            node("baseVersion").longValue() >= 0 &&
            STRING_OR_NULL_FIELDS.all { node(it).isTextual || node(it).isNull } &&
            NUMBER_OR_NULL_FIELDS.all { node(it).isNumber || node(it).isNull } &&
            INTEGER_OR_NULL_FIELDS.all { node(it).isIntegralNumber || node(it).isNull } &&
            node("price").isNumber && node("currency").isTextual &&
            (node("isActive").isBoolean || node("isActive").isNull) && node("doctorBindings").isArray
        if (!validTypes) throw projectPayloadInvalid("机构项目更新字段类型不正确")
        return try {
            objectMapper.treeToValue(payload, InstitutionProjectUpdateRequest::class.java)
        } catch (error: Exception) {
            throw projectPayloadInvalid("机构项目更新内容无法解析", error)
        }
    }

    private fun validateUpdateRequest(request: InstitutionProjectUpdateRequest): String? {
        if (request.price < BigDecimal.ZERO) return "价格不能小于 0"
        if (request.originalPrice != null && request.originalPrice < BigDecimal.ZERO) return "原价不能小于 0"
        if (request.rating != null && (request.rating < BigDecimal.ZERO || request.rating > BigDecimal("5.0"))) {
            return "评分必须在 0 到 5 之间"
        }
        if (request.reviewCount != null && request.reviewCount < 0) return "评价数不能小于 0"
        if (request.salesCount != null && request.salesCount < 0) return "销量不能小于 0"
        return null
    }

    private fun pendingRequestIds(table: String, institutionProjectId: String): List<String> {
        val typeFilter = if (table == "doctor_project_change_requests") {
            " AND request_type IN ('PROFILE_UPDATE', 'LEAVE')"
        } else ""
        return jdbcTemplate.queryForList(
            "SELECT id FROM $table WHERE institution_project_id = ? AND status = 'PENDING'$typeFilter ORDER BY id",
            String::class.java,
            institutionProjectId
        ).sorted()
    }

    private fun lockPendingRequestsById(table: String, institutionProjectId: String, expectedIds: List<String>) {
        val typeFilter = if (table == "doctor_project_change_requests") {
            " AND request_type IN ('PROFILE_UPDATE', 'LEAVE')"
        } else ""
        expectedIds.forEach { requestId ->
            val lockedIds = jdbcTemplate.queryForList(
                "SELECT id FROM $table FORCE INDEX (PRIMARY) WHERE id = ? AND institution_project_id = ? AND status = 'PENDING'$typeFilter FOR UPDATE",
                String::class.java,
                requestId,
                institutionProjectId
            )
            if (lockedIds != listOf(requestId)) {
                throw institutionProjectStale("待处理申请已变化")
            }
        }
    }

    private fun revalidatePendingRequestSet(table: String, institutionProjectId: String, expectedIds: List<String>) {
        val typeFilter = if (table == "doctor_project_change_requests") {
            " AND request_type IN ('PROFILE_UPDATE', 'LEAVE')"
        } else ""
        val currentIds = withNowaitStale("待处理申请集合正在变化，请刷新后重试") {
            jdbcTemplate.queryForList(
                "SELECT id FROM $table WHERE institution_project_id = ? AND status = 'PENDING'$typeFilter ORDER BY id FOR SHARE NOWAIT",
                String::class.java,
                institutionProjectId
            )
        }
        if (currentIds != expectedIds) {
            throw institutionProjectStale("待处理申请集合已变化")
        }
    }

    private fun activeRelationshipId(doctorId: String, institutionId: String, lock: Boolean): String? =
        jdbcTemplate.queryForList(
            """
            SELECT id FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
              AND revoked_at IS NULL AND deleted_at IS NULL
            ${if (lock) "FOR UPDATE NOWAIT" else ""}
            """.trimIndent(),
            String::class.java,
            doctorId,
            institutionId
        ).firstOrNull()

    private fun lockActiveRelationshipNowait(doctorId: String, institutionId: String): String? =
        withNowaitStale("医生机构关系正在变化，请刷新后重试") {
            activeRelationshipId(doctorId, institutionId, lock = true)
        }

    private fun <T> withNowaitStale(message: String, action: () -> T): T = try {
        action()
    } catch (error: DataAccessException) {
        if (error.sqlCauses().any { it.errorCode == MYSQL_NOWAIT_ERROR }) {
            throw institutionProjectStale(message)
        }
        throw error
    }

    private fun Throwable.sqlCauses(): List<SQLException> =
        generateSequence(this) { it.cause }.filterIsInstance<SQLException>().toList()

    private fun compatibilityConfigParticipants(
        institutionProjectId: String,
        lock: Boolean
    ): List<CompatibilityConfigParticipant> = jdbcTemplate.queryForList(
        "SELECT id, doctor_id FROM doctor_institution_project_configs WHERE institution_project_id = ? ORDER BY id ${if (lock) "FOR UPDATE" else ""}",
        institutionProjectId
    ).map { row ->
        CompatibilityConfigParticipant(row.getValue("id").toString(), row.getValue("doctor_id").toString())
    }

    private fun lockPlatformProject(projectId: String): String? = jdbcTemplate.queryForList(
        "SELECT id FROM projects WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
        String::class.java,
        projectId
    ).firstOrNull()

    private fun lockDoctorProjectIds(institutionProjectId: String): List<String> = jdbcTemplate.queryForList(
        "SELECT doctor_id FROM doctor_projects WHERE institution_project_id = ? ORDER BY doctor_id FOR UPDATE",
        String::class.java,
        institutionProjectId
    )

    private fun revalidateUpdateParticipants(
        currentDoctorIds: List<String>,
        configParticipants: List<CompatibilityConfigParticipant>,
        lockedDoctorProjects: Map<String, DoctorProjectEntity?>,
        lockedConfigs: Map<String, DoctorInstitutionProjectConfigEntity?>
    ) {
        if (lockedDoctorProjects.filterValues { it != null }.keys.sorted() != currentDoctorIds) {
            throw institutionProjectStale("医生项目绑定集合已变化")
        }
        lockedConfigs.forEach { (doctorId, config) ->
            val expectedIds = configParticipants.filter { it.doctorId == doctorId }.map { it.id }
            if (expectedIds.size > 1 || listOfNotNull(config?.id) != expectedIds) {
                throw institutionProjectStale("医生项目兼容配置集合已变化")
            }
        }
    }

    private fun evictProjectCachesAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                PROJECT_CACHES.forEach { cacheManager.getCache(it)?.clear() }
            }
        })
    }

    private fun projectPayloadInvalid(message: String, cause: Throwable? = null) =
        ProjectChangeContractException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID,
            message,
            cause
        )

    private fun institutionProjectStale(message: String) = ProjectChangeContractException(
        HttpStatus.CONFLICT,
        ProjectChangeErrorCode.INSTITUTION_PROJECT_VERSION_STALE,
        message
    )

    private fun validateDoctorBindings(institutionId: String, bindings: List<DoctorProjectBinding>): String? {
        val doctorIds = bindings.map { it.doctorId }
        if (doctorIds.size != doctorIds.distinct().size) return "同一医生不能重复绑定到机构项目"
        bindings.forEach { binding ->
            try {
                validatedPrice(binding)
            } catch (error: IllegalArgumentException) {
                return error.message ?: "医生项目价格无效"
            }
        }
        val doctors = doctorRepository.findAllById(doctorIds).associateBy { it.id }
        if (doctors.size != doctorIds.size) return "存在无效的医生"
        val unbound = doctorIds.firstOrNull { doctorId ->
            doctorInstitutionService.findByDoctorId(doctorId).none {
                it.institutionId == institutionId && it.status == "APPROVED"
            }
        }
        return unbound?.let { "医生未绑定当前机构，不能配置该机构项目" }
    }

    private fun normalizeDoctorBindings(bindings: List<DoctorProjectBinding>): List<DoctorProjectBinding> = bindings
        .filter { it.doctorId.isNotBlank() }
        .map { it.copy(doctorId = it.doctorId.trim()) }

    private fun validatedPrice(binding: DoctorProjectBinding): BigDecimal {
        val price = requireNotNull(binding.price) { "医生项目价格不能为空" }
        require(price > BigDecimal.ZERO) { "医生项目价格必须大于 0" }
        travelGroundServicePricing.quote(price)
        return price
    }

    private fun syncCompatibilityPrice(doctorProject: DoctorProjectEntity) {
        val config = configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeleted(
            doctorProject.doctorId,
            doctorProject.institutionProjectId
        )
        saveCompatibilityPrice(doctorProject, config)
    }

    private fun saveCompatibilityPrice(
        doctorProject: DoctorProjectEntity,
        existingConfig: DoctorInstitutionProjectConfigEntity?
    ) {
        val config = existingConfig ?: DoctorInstitutionProjectConfigEntity(
            doctorId = doctorProject.doctorId,
            institutionProjectId = doctorProject.institutionProjectId
        )
        config.medicalListPrice = doctorProject.price
        config.deletedAt = null
        config.updatedAt = LocalDateTime.now()
        configRepository.save(config)
    }

    private fun validateRequest(request: InstitutionProjectRequest): String? {
        if (request.institutionId.isBlank()) return "请选择机构"
        if (request.projectId.isBlank()) return "请选择关联项目"
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
                binding.images,
                binding.price
            )
        } }
        return InstitutionProjectDto(
            id = entity.id,
            version = entity.version,
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
            currency = entity.currency,
            coverImage = entity.coverImage.orEmpty(),
            images = entity.images.orEmpty(),
            effectiveCoverImage = effective.coverImage,
            effectiveImages = effective.images,
            salesCount = entity.salesCount,
            isActive = entity.isActive,
            createdAt = entity.createdAt,
            doctors = doctors
        )
    }

    private companion object {
        val UPDATE_FIELDS = setOf(
            "baseVersion", "name", "category", "description", "rating", "reviewCount", "tags", "slogan",
            "detailContent", "price", "originalPrice", "currency", "coverImage", "images", "salesCount",
            "isActive", "doctorBindings"
        )
        val STRING_OR_NULL_FIELDS = setOf(
            "name", "category", "description", "tags", "slogan", "detailContent", "coverImage", "images"
        )
        val NUMBER_OR_NULL_FIELDS = setOf("rating", "originalPrice")
        val INTEGER_OR_NULL_FIELDS = setOf("reviewCount", "salesCount")
        val PROJECT_CACHES = listOf("discover", "home", "projects")
        const val MYSQL_NOWAIT_ERROR = 3572
    }

    private data class CompatibilityConfigParticipant(val id: String, val doctorId: String)
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
    val currency: com.joysong.server.common.money.CurrencyCode = com.joysong.server.common.money.CurrencyCode.DEFAULT,
    val coverImage: String? = null,
    val images: String? = null,
    val salesCount: Int? = null,
    val isActive: Boolean? = null,
    val doctorBindings: List<DoctorProjectBinding> = emptyList()
)

data class InstitutionProjectUpdateRequest(
    val baseVersion: Long,
    val name: String?,
    val category: String?,
    val description: String?,
    val rating: BigDecimal?,
    val reviewCount: Int?,
    val tags: String?,
    val slogan: String?,
    val detailContent: String?,
    val price: BigDecimal,
    val originalPrice: BigDecimal?,
    val currency: com.joysong.server.common.money.CurrencyCode,
    val coverImage: String?,
    val images: String?,
    val salesCount: Int?,
    val isActive: Boolean?,
    val doctorBindings: List<DoctorProjectBinding>
)

data class InstitutionProjectDto(
    val id: String,
    val version: Long,
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
    val currency: String,
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
    val images: String = "",
    val price: BigDecimal
)
