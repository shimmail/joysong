package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.institution.entity.InstitutionProjectEntity
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.TravelGroundServicePricing
import com.joysong.server.payment.domain.Money
import com.joysong.server.project.entity.ProjectEntity
import com.joysong.server.project.service.InstitutionProjectPayload
import com.joysong.server.project.service.InstitutionProjectPayloadPolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.Instant
import java.time.ZoneOffset
import java.math.BigDecimal
import java.util.UUID
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.http.HttpStatus

private val PROJECT_CHANGE_TYPES = setOf("JOIN", "PROFILE_UPDATE", "LEAVE")
private val PROJECT_CHANGE_DECISIONS = setOf("APPROVED", "REJECTED", "CHANGES_REQUESTED")

@Service
class DoctorProjectChangeService(
    private val jdbcTemplate: JdbcTemplate,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val configRepository: DoctorInstitutionProjectConfigRepository,
    private val relationshipService: DoctorInstitutionRelationshipService,
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val travelGroundServicePricing: TravelGroundServicePricing,
    private val payloadPolicy: InstitutionProjectPayloadPolicy,
    private val objectMapper: ObjectMapper
) {
    private val snapshotCodec = DoctorProjectSnapshotCodec(objectMapper)
    private val detailResolver = InstitutionProjectDetailResolver(objectMapper)
    private fun decodeList(raw: String?): List<String> {
        val value = raw.orEmpty()
        if (value.isBlank()) return emptyList()
        return if (value.trimStart().startsWith("[")) {
            objectMapper.readValue(value, object : TypeReference<List<String>>() {})
        } else value.split(',').map(String::trim).filter(String::isNotEmpty)
    }

    private fun encodeList(values: List<String>): String = objectMapper.writeValueAsString(values).also {
        require(it.length <= 20000) { "数组字段序列化后最长 20000 字符" }
    }

    private fun validateProfile(request: DoctorProjectChangeRequest) {
        require(request.serviceDescription.trim().isNotEmpty() && request.serviceDescription.trim().length <= 5000) { "服务说明不能为空且最长 5000" }
        val price = requireNotNull(request.priceSuggestion) { "医生项目价格不能为空" }
        val compatibilityPrice = requireNotNull(request.medicalListPrice) { "医生项目价格不能为空" }
        require(price.compareTo(compatibilityPrice) == 0) { "医生项目价格与兼容价格必须一致" }
        travelGroundServicePricing.quote(price)
        requireNotNull(request.consultationFee) { "面诊费不能为空" }.also(::validateMoney)
        require(request.notes.length <= 2000 && request.scheduleNote.length <= 500 && request.coverImage.length <= 500) { "文本字段长度超限" }
        require(request.serviceTags.size <= 20 && request.serviceTags.all { it.isNotBlank() && it.length <= 100 }) { "服务标签不合法" }
        require(request.images.size <= 20 && request.images.all { it.length <= 500 }) { "项目图片不合法" }
        splitRatePolicy.resolve(requireNotNull(request.institutionRate) { "机构比例不能为空" }, requireNotNull(request.commissionRate) { "顾问比例不能为空" })
    }

    private fun validateMoney(value: BigDecimal) = Money.requireUsdAmount(value)

    fun listProfileUpdateTargetsV2(actor: ManagementActor): List<DoctorProjectProfileUpdateTargetV2> {
        val doctorId = actor.doctorId
            ?: throw AccessDeniedException("只有已认证医生可以读取项目资料修改基线")
        return jdbcTemplate.query(
            """
            SELECT dp.institution_project_id, ip.institution_id, i.name AS institution_name,
                   ip.project_id AS platform_project_id, p.name AS platform_project_name,
                   dp.doctor_id, d.name AS doctor_name,
                   ip.name AS ip_name, ip.category AS ip_category, ip.description AS ip_description,
                   ip.tags AS ip_tags, ip.slogan AS ip_slogan, ip.detail_content AS ip_detail_content,
                   ip.cover_image AS ip_cover_image, ip.images AS ip_images,
                   ip.sales_count AS ip_sales_count, ip.version AS ip_version,
                   p.category AS platform_category, p.description AS platform_description,
                   p.tags AS platform_tags, p.slogan AS platform_slogan,
                   p.detail_content AS platform_detail_content, p.cover_image AS platform_cover_image,
                   p.images AS platform_images,
                   dp.price AS doctor_price, dp.is_active AS doctor_active,
                   dp.updated_at AS doctor_project_updated_at,
                   c.id AS config_id, c.updated_at AS config_updated_at
            FROM doctor_projects dp
            JOIN doctors d ON d.id = dp.doctor_id AND d.deleted_at IS NULL
            JOIN institution_projects ip
              ON ip.id = dp.institution_project_id AND ip.deleted_at IS NULL AND ip.is_active = TRUE
            JOIN institutions i ON i.id = ip.institution_id AND i.deleted_at IS NULL
            JOIN projects p ON p.id = ip.project_id AND p.deleted_at IS NULL
            JOIN doctor_institutions di
              ON di.doctor_id = dp.doctor_id AND di.institution_id = ip.institution_id
             AND di.status = 'APPROVED' AND di.revoked_at IS NULL AND di.deleted_at IS NULL
            LEFT JOIN doctor_institution_project_configs c
              ON c.doctor_id = dp.doctor_id
             AND c.institution_project_id = dp.institution_project_id
             AND c.deleted_at IS NULL
            WHERE dp.doctor_id = ?
            ORDER BY i.name, COALESCE(ip.name, p.name), dp.institution_project_id
            """.trimIndent(),
            { rs, _ -> v2StateFromTargetRow(rs) },
            doctorId
        ).map { state ->
            val quote = travelGroundServicePricing.quoteWithPolicy(state.doctorPrice)
            val snapshot = state.snapshot()
            DoctorProjectProfileUpdateTargetV2(
                institutionProjectId = state.institutionProject.id,
                institutionId = state.institutionProject.institutionId,
                institutionName = state.institutionName,
                platformProjectId = state.platformProject.id,
                platformProjectName = state.platformProject.name,
                doctorId = state.doctorId,
                doctorName = state.doctorName,
                baseRevision = state.revision(quote.pricingPolicyRevision),
                currentProject = snapshot,
                currentDoctorPrice = state.doctorPrice,
                currentDoctorActive = state.doctorActive,
                platformRate = quote.platformRate,
                pricingPolicyRevision = quote.pricingPolicyRevision,
                travelGroundServiceFee = quote.serviceFee
            )
        }
    }

    @Transactional
    fun submitV2(
        actor: ManagementActor,
        request: DoctorProjectChangeV2Request
    ): DoctorProjectChangeViewV2 {
        val doctorId = actor.doctorId
            ?: throw AccessDeniedException("只有已认证医生可以提交项目申请")
        require(request.requestType.trim().uppercase() == "PROFILE_UPDATE") {
            "版本 2 完整编辑只支持 PROFILE_UPDATE"
        }
        val institutionProjectId = request.institutionProjectId.trim()
        require(institutionProjectId.isNotEmpty()) { "机构项目不能为空" }
        require(request.baseRevision.matches(Regex("[0-9a-f]{64}"))) { "baseRevision 不正确" }
        require(request.notes.trim().length <= 2000) { "申请说明最长 2000" }

        val targetIds = projectTargetV2(institutionProjectId, doctorId)
            ?: throw DoctorProjectChangeNotFoundException("机构项目不存在")
        relationshipService.requireActiveRelationshipForUpdate(doctorId, targetIds.institutionId)
        val institutionProject = institutionProjectRepository.findForUpdate(institutionProjectId)
            ?: throw DoctorProjectChangeNotFoundException("机构项目不存在")
        if (!institutionProject.isActive || institutionProject.institutionId != targetIds.institutionId ||
            institutionProject.projectId != targetIds.projectId) {
            throw AccessDeniedException("机构项目关联已失效")
        }
        val platformProject = lockedPlatformProject(targetIds.projectId)
            ?: throw DoctorProjectChangeNotFoundException("平台项目不存在")
        val doctorProject = doctorProjectRepository.findForUpdate(doctorId, institutionProjectId)
            ?: throw AccessDeniedException("医生尚未加入该机构项目")
        if (doctorProject.projectId != targetIds.projectId) {
            throw AccessDeniedException("医生项目绑定与机构项目不一致")
        }
        val lockedConfig = configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(
            doctorId,
            institutionProjectId
        )
        val activeConfig = lockedConfig?.takeIf { it.deletedAt == null }
        val quote = travelGroundServicePricing.quoteWithPolicy(request.price)
        val state = V2ProjectState(
            institutionProject = institutionProject,
            institutionName = targetIds.institutionName,
            platformProject = platformProject,
            doctorId = doctorId,
            doctorName = targetIds.doctorName,
            doctorPrice = doctorProject.price,
            doctorActive = doctorProject.isActive,
            doctorProjectUpdatedAt = doctorProject.updatedAt.toRequiredInstant("医生项目更新时间缺失"),
            configId = activeConfig?.id,
            configUpdatedAt = activeConfig?.updatedAt?.toInstant(ZoneOffset.UTC)
        )
        val lockedRevision = try {
            state.revision(quote.pricingPolicyRevision)
        } catch (e: IllegalArgumentException) {
            throw ProjectChangeContractException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID,
                "机构项目当前有效值不完整",
                e
            )
        }
        if (request.baseRevision != lockedRevision) {
            throw contractConflict(ProjectChangeErrorCode.EDIT_BASE_STALE, "编辑基线已变化，请重新加载")
        }

        val normalized = payloadPolicy.normalize(
            InstitutionProjectPayload(
                name = request.name,
                category = request.category,
                description = request.description,
                tags = request.tags,
                slogan = request.slogan,
                detailContent = request.detailContent,
                coverImage = request.coverImage,
                images = request.images,
                salesCount = request.salesCount
            )
        )
        val proposedEntity = institutionProject.copy(
            name = normalized.name,
            category = normalized.category,
            description = normalized.description,
            tags = encodeOptionalList(normalized.tags),
            slogan = normalized.slogan,
            detailContent = normalized.detailContent,
            coverImage = normalized.coverImage,
            images = encodeOptionalList(normalized.images),
            salesCount = normalized.salesCount
        )
        val currentSnapshot = state.snapshot()
        val proposedDetails = detailResolver.resolveDetails(proposedEntity, platformProject)
        validateEffectivePayload(proposedDetails.effective)
        val proposedSnapshot = InstitutionProjectSnapshotV2(
            schemaVersion = 2,
            association = currentSnapshot.association,
            rawOverrides = proposedDetails.rawOverrides,
            effective = proposedDetails.effective,
            source = currentSnapshot.source
        )
        val sharedChanged = currentSnapshot.rawOverrides != proposedSnapshot.rawOverrides ||
            currentSnapshot.effective.salesCount != proposedSnapshot.effective.salesCount
        if (pendingCount(doctorId, institutionProjectId) != 0L) {
            throw contractConflict(ProjectChangeErrorCode.REQUEST_ALREADY_PENDING, "该项目已有待处理申请")
        }

        val id = UUID.randomUUID().toString()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO doctor_project_change_requests
                    (id, doctor_id, institution_id, institution_project_id, request_type,
                     payload_version, base_institution_project_version, base_platform_inheritance_hash,
                     pricing_policy_revision, proposed_travel_ground_service_fee, shared_changed,
                     current_project_snapshot, proposed_project_snapshot,
                     base_doctor_project_updated_at, base_config_id, base_config_updated_at,
                     current_price, medical_list_price, current_platform_rate,
                     current_doctor_is_active, proposed_doctor_is_active,
                     notes, status, submitted_by)
                VALUES (?, ?, ?, ?, 'PROFILE_UPDATE',
                        2, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
                """.trimIndent(),
                id,
                doctorId,
                institutionProject.institutionId,
                institutionProject.id,
                institutionProject.version,
                currentSnapshot.source.platformInheritanceHash,
                quote.pricingPolicyRevision,
                quote.serviceFee,
                sharedChanged,
                snapshotCodec.encode(currentSnapshot),
                snapshotCodec.encode(proposedSnapshot),
                doctorProject.updatedAt,
                activeConfig?.id,
                activeConfig?.updatedAt,
                doctorProject.price,
                request.price,
                quote.platformRate,
                doctorProject.isActive,
                request.doctorActive,
                request.notes.trim(),
                actor.userId
            )
        } catch (e: DataIntegrityViolationException) {
            throw ProjectChangeContractException(
                HttpStatus.CONFLICT,
                ProjectChangeErrorCode.REQUEST_ALREADY_PENDING,
                "该项目已有待处理申请",
                e
            )
        }
        val now = Instant.now()
        return VersionedDoctorProjectChangeViewV2(
            id = id,
            requestType = "EDIT",
            doctorId = doctorId,
            doctorName = state.doctorName,
            institutionId = institutionProject.institutionId,
            institutionName = state.institutionName,
            institutionProjectId = institutionProject.id,
            institutionProjectName = currentSnapshot.effective.name,
            platformProjectId = platformProject.id,
            platformProjectName = platformProject.name,
            baseRevision = lockedRevision,
            currentProject = currentSnapshot,
            proposedProject = proposedSnapshot,
            latestProject = currentSnapshot,
            latestRevision = lockedRevision,
            sharedChanged = sharedChanged,
            currentDoctorPrice = doctorProject.price,
            proposedDoctorPrice = request.price,
            latestDoctorPrice = doctorProject.price,
            currentDoctorActive = doctorProject.isActive,
            proposedDoctorActive = request.doctorActive,
            latestDoctorActive = doctorProject.isActive,
            platformRate = quote.platformRate,
            pricingPolicyRevision = quote.pricingPolicyRevision,
            travelGroundServiceFee = quote.serviceFee,
            requestStatus = "PENDING",
            notes = request.notes.trim(),
            forceProcessed = false,
            submittedBy = actor.userId,
            submittedAt = now,
            reviewedBy = null,
            reviewerName = null,
            reviewNote = null,
            reviewedAt = null,
            updatedAt = now,
            snapshotState = "VALID",
            snapshotError = null,
            reviewable = false
        )
    }

    fun listProfileUpdateTargets(actor: ManagementActor): List<DoctorProjectProfileUpdateTargetView> {
        val doctorId = actor.doctorId
            ?: throw AccessDeniedException("只有已认证医生可以读取项目资料修改基线")
        return jdbcTemplate.query(
            """
            SELECT dp.institution_project_id,
                   COALESCE(ip.name, p.name) AS project_name,
                   ip.institution_id, i.name AS institution_name,
                   dp.price AS current_price, dp.service_description,
                   dp.service_tags, dp.schedule_note, dp.cover_image, dp.images,
                   c.consultation_fee, c.commission_rate, c.institution_rate, c.medical_list_price
            FROM doctor_projects dp
            JOIN institution_projects ip
              ON ip.id = dp.institution_project_id AND ip.deleted_at IS NULL AND ip.is_active = TRUE
            JOIN institutions i ON i.id = ip.institution_id AND i.deleted_at IS NULL
            JOIN projects p ON p.id = ip.project_id AND p.deleted_at IS NULL
            JOIN doctor_institutions di
              ON di.doctor_id = dp.doctor_id AND di.institution_id = ip.institution_id
             AND di.status = 'APPROVED' AND di.revoked_at IS NULL AND di.deleted_at IS NULL
            LEFT JOIN doctor_institution_project_configs c
              ON c.doctor_id = dp.doctor_id
             AND c.institution_project_id = dp.institution_project_id
             AND c.deleted_at IS NULL
            WHERE dp.doctor_id = ?
            ORDER BY i.name, project_name, dp.institution_project_id
            """.trimIndent(),
            { rs, _ ->
                val commissionRate = rs.getBigDecimal("commission_rate")
                val institutionRate = rs.getBigDecimal("institution_rate")
                val split = if (commissionRate != null && institutionRate != null) {
                    splitRatePolicy.resolve(institutionRate, commissionRate)
                } else splitRatePolicy.defaults()
                DoctorProjectProfileUpdateTargetView(
                    institutionProjectId = rs.getString("institution_project_id"),
                    projectName = rs.getString("project_name"),
                    institutionId = rs.getString("institution_id"),
                    institutionName = rs.getString("institution_name"),
                    currentPrice = rs.getBigDecimal("current_price"),
                    serviceDescription = rs.getString("service_description").orEmpty(),
                    serviceTags = decodeList(rs.getString("service_tags")),
                    scheduleNote = rs.getString("schedule_note").orEmpty(),
                    coverImage = rs.getString("cover_image").orEmpty(),
                    images = decodeList(rs.getString("images")),
                    consultationFee = rs.getBigDecimal("consultation_fee") ?: BigDecimal.ZERO,
                    commissionRate = commissionRate ?: split.consultantRate,
                    institutionRate = institutionRate ?: split.institutionRate,
                    medicalListPrice = rs.getBigDecimal("medical_list_price") ?: BigDecimal.ZERO,
                    platformRate = split.platformRate,
                    doctorRate = split.doctorRate
                )
            },
            doctorId
        )
    }

    fun list(actor: ManagementActor): List<DoctorProjectChangeView> {
        val rows = jdbcTemplate.query(
            """
            SELECT r.id, r.doctor_id, d.name AS doctor_name, r.institution_id,
                   i.name AS institution_name, r.institution_project_id,
                   COALESCE(ip.name, p.name) AS project_name, r.request_type,
                   r.service_description, r.price_suggestion, r.notes, r.service_tags, r.schedule_note,
                   r.cover_image, r.images, r.status, r.submitted_by,
                   r.consultation_fee, r.commission_rate, r.institution_rate, r.medical_list_price, r.force_processed,
                   r.current_price, r.current_service_description, r.current_service_tags,
                   r.current_schedule_note, r.current_cover_image, r.current_images,
                   r.current_consultation_fee, r.current_commission_rate, r.current_institution_rate, r.current_medical_list_price,
                   r.current_platform_rate, r.current_doctor_rate,
                   r.reviewed_by, reviewer.nickname AS reviewer_name, r.review_note,
                   r.submitted_at, r.reviewed_at, r.updated_at
            FROM doctor_project_change_requests r
            JOIN doctors d ON d.id = r.doctor_id
            JOIN institutions i ON i.id = r.institution_id
            JOIN institution_projects ip ON ip.id = r.institution_project_id
            JOIN projects p ON p.id = ip.project_id
            LEFT JOIN users reviewer ON reviewer.id = r.reviewed_by
            WHERE r.payload_version = 1
            ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.submitted_at DESC
            """.trimIndent()
        ) { rs, _ ->
            val requestType = rs.getString("request_type")
            val currentCommissionRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_commission_rate") else null
            val currentInstitutionRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_institution_rate") else null
            DoctorProjectChangeView(
                id = rs.getString("id"),
                doctorId = rs.getString("doctor_id"),
                doctorName = rs.getString("doctor_name"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                institutionProjectId = rs.getString("institution_project_id"),
                projectName = rs.getString("project_name"),
                requestType = requestType,
                serviceDescription = rs.getString("service_description").orEmpty(),
                priceSuggestion = rs.getBigDecimal("price_suggestion"),
                notes = rs.getString("notes").orEmpty(),
                serviceTags = decodeList(rs.getString("service_tags")),
                scheduleNote = rs.getString("schedule_note").orEmpty(),
                coverImage = rs.getString("cover_image").orEmpty(),
                images = decodeList(rs.getString("images")),
                consultationFee = rs.getBigDecimal("consultation_fee"),
                commissionRate = rs.getBigDecimal("commission_rate"),
                institutionRate = rs.getBigDecimal("institution_rate"),
                medicalListPrice = rs.getBigDecimal("medical_list_price"),
                platformRate = rs.getBigDecimal("commission_rate")?.let { splitRatePolicy.currentPlatformRate() },
                doctorRate = rs.getBigDecimal("commission_rate")?.let { splitRatePolicy.resolve(rs.getBigDecimal("institution_rate"), it).doctorRate },
                currentPrice = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_price") else null,
                currentServiceDescription = if (requestType == "PROFILE_UPDATE") rs.getString("current_service_description").orEmpty() else null,
                currentServiceTags = if (requestType == "PROFILE_UPDATE") decodeList(rs.getString("current_service_tags")) else null,
                currentScheduleNote = if (requestType == "PROFILE_UPDATE") rs.getString("current_schedule_note").orEmpty() else null,
                currentCoverImage = if (requestType == "PROFILE_UPDATE") rs.getString("current_cover_image").orEmpty() else null,
                currentImages = if (requestType == "PROFILE_UPDATE") decodeList(rs.getString("current_images")) else null,
                currentConsultationFee = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_consultation_fee") else null,
                currentMedicalListPrice = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_medical_list_price") else null,
                currentCommissionRate = currentCommissionRate,
                currentInstitutionRate = currentInstitutionRate,
                currentPlatformRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_platform_rate") else null,
                currentDoctorRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_doctor_rate") else null,
                forceProcessed = rs.getBoolean("force_processed"),
                status = rs.getString("status"),
                submittedBy = rs.getString("submitted_by"),
                reviewedBy = rs.getString("reviewed_by"),
                reviewerName = rs.getString("reviewer_name"),
                reviewNote = rs.getString("review_note").orEmpty(),
                submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
            )
        }
        if (actor.isAdmin) return rows
        return rows.filter { row ->
            row.doctorId == actor.doctorId || row.institutionId in actor.managedInstitutionIds
        }
    }

    @Transactional(readOnly = true)
    fun listV2(actor: ManagementActor): List<DoctorProjectChangeViewV2> {
        val legacyRows = list(actor).map(::adaptLegacyView)
        val versionedRows = jdbcTemplate.query(
            """
            SELECT r.id, r.payload_version, r.doctor_id, d.name AS doctor_name,
                   r.institution_id, i.name AS institution_name, r.institution_project_id,
                   COALESCE(ip.name, p.name) AS institution_project_name,
                   ip.project_id AS platform_project_id, p.name AS platform_project_name,
                   r.request_type, r.base_institution_project_version,
                   r.base_platform_inheritance_hash, r.pricing_policy_revision,
                   r.current_project_snapshot, r.proposed_project_snapshot, r.shared_changed,
                   r.current_price, r.medical_list_price,
                   r.current_doctor_is_active, r.proposed_doctor_is_active,
                   r.current_platform_rate, r.proposed_travel_ground_service_fee,
                   r.base_doctor_project_updated_at, r.base_config_id, r.base_config_updated_at,
                   r.status, r.notes, r.force_processed, r.submitted_by, r.submitted_at,
                   r.reviewed_by, reviewer.nickname AS reviewer_name, r.review_note,
                   r.reviewed_at, r.updated_at,
                   ip.name AS latest_ip_name, ip.category AS latest_ip_category,
                   ip.description AS latest_ip_description, ip.tags AS latest_ip_tags,
                   ip.slogan AS latest_ip_slogan, ip.detail_content AS latest_ip_detail_content,
                   ip.cover_image AS latest_ip_cover_image, ip.images AS latest_ip_images,
                   ip.sales_count AS latest_ip_sales_count, ip.version AS latest_ip_version,
                   p.category AS latest_platform_category, p.description AS latest_platform_description,
                   p.tags AS latest_platform_tags, p.slogan AS latest_platform_slogan,
                   p.detail_content AS latest_platform_detail_content,
                   p.cover_image AS latest_platform_cover_image, p.images AS latest_platform_images,
                   dp.price AS latest_doctor_price, dp.is_active AS latest_doctor_active,
                   dp.updated_at AS latest_doctor_project_updated_at,
                   c.id AS latest_config_id, c.updated_at AS latest_config_updated_at
            FROM doctor_project_change_requests r
            JOIN doctors d ON d.id = r.doctor_id
            JOIN institutions i ON i.id = r.institution_id
            JOIN institution_projects ip ON ip.id = r.institution_project_id
            JOIN projects p ON p.id = ip.project_id
            LEFT JOIN doctor_projects dp
              ON dp.doctor_id = r.doctor_id AND dp.institution_project_id = r.institution_project_id
            LEFT JOIN doctor_institution_project_configs c
              ON c.doctor_id = r.doctor_id
             AND c.institution_project_id = r.institution_project_id
             AND c.deleted_at IS NULL
            LEFT JOIN users reviewer ON reviewer.id = r.reviewed_by
            WHERE r.payload_version = 2
            ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.submitted_at DESC
            """.trimIndent()
        ) { rs, _ ->
            val currentResult = runCatching { snapshotCodec.decode(rs.getString("current_project_snapshot")) }
            val proposedResult = runCatching { snapshotCodec.decode(rs.getString("proposed_project_snapshot")) }
            val latestState = runCatching { v2StateFromLatestRow(rs) }.getOrNull()
            val latestSnapshot = runCatching { latestState?.snapshot() }.getOrNull()
            val latestRevision = runCatching {
                val live = requireNotNull(latestState)
                val currentPolicy = travelGroundServicePricing.quoteWithPolicy(rs.getBigDecimal("medical_list_price"))
                live.revision(currentPolicy.pricingPolicyRevision)
            }.getOrNull()
            val baseResult = runCatching {
                snapshotCodec.baseRevision(
                    DoctorProjectRevisionSource(
                        institutionProjectId = rs.getString("institution_project_id"),
                        institutionId = rs.getString("institution_id"),
                        platformProjectId = rs.getString("platform_project_id"),
                        institutionProjectVersion = rs.getLong("base_institution_project_version"),
                        platformInheritanceHash = rs.getString("base_platform_inheritance_hash"),
                        doctorProjectUpdatedAt = rs.getTimestamp("base_doctor_project_updated_at").toInstant(),
                        configId = rs.getString("base_config_id"),
                        configUpdatedAt = rs.getTimestamp("base_config_updated_at")?.toInstant(),
                        pricingPolicyRevision = rs.getString("pricing_policy_revision")
                    )
                )
            }
            val baseRevision = baseResult.getOrElse { "0".repeat(64) }
            val snapshotsValid = currentResult.isSuccess && proposedResult.isSuccess && baseResult.isSuccess
            val status = rs.getString("status")
            VersionedDoctorProjectChangeViewV2(
                id = rs.getString("id"),
                requestType = "EDIT",
                doctorId = rs.getString("doctor_id"),
                doctorName = rs.getString("doctor_name"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                institutionProjectId = rs.getString("institution_project_id"),
                institutionProjectName = rs.getString("institution_project_name"),
                platformProjectId = rs.getString("platform_project_id"),
                platformProjectName = rs.getString("platform_project_name"),
                baseRevision = baseRevision,
                currentProject = currentResult.getOrNull(),
                proposedProject = proposedResult.getOrNull(),
                latestProject = latestSnapshot,
                latestRevision = latestRevision,
                sharedChanged = rs.getBoolean("shared_changed"),
                currentDoctorPrice = rs.getBigDecimal("current_price"),
                proposedDoctorPrice = rs.getBigDecimal("medical_list_price"),
                latestDoctorPrice = rs.getBigDecimal("latest_doctor_price"),
                currentDoctorActive = rs.getBoolean("current_doctor_is_active"),
                proposedDoctorActive = rs.getBoolean("proposed_doctor_is_active"),
                latestDoctorActive = nullableBoolean(rs, "latest_doctor_active"),
                platformRate = rs.getBigDecimal("current_platform_rate"),
                pricingPolicyRevision = rs.getString("pricing_policy_revision"),
                travelGroundServiceFee = rs.getBigDecimal("proposed_travel_ground_service_fee"),
                requestStatus = status,
                notes = rs.getString("notes").orEmpty(),
                forceProcessed = rs.getBoolean("force_processed"),
                submittedBy = rs.getString("submitted_by"),
                submittedAt = rs.getTimestamp("submitted_at").toInstant(),
                reviewedBy = rs.getString("reviewed_by"),
                reviewerName = rs.getString("reviewer_name"),
                reviewNote = rs.getString("review_note"),
                reviewedAt = rs.getTimestamp("reviewed_at")?.toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant(),
                snapshotState = if (snapshotsValid) "VALID" else "INVALID",
                snapshotError = if (snapshotsValid) null else ProjectChangeErrorCode.REQUEST_SNAPSHOT_INVALID.name,
                reviewable = snapshotsValid && latestSnapshot != null && latestRevision != null &&
                    status == "PENDING" && (actor.isAdmin || rs.getString("institution_id") in actor.managedInstitutionIds)
            )
        }.filter { row ->
            actor.isAdmin || row.doctorId == actor.doctorId || row.institutionId in actor.managedInstitutionIds
        }
        return legacyRows + versionedRows
    }

    @Transactional
    fun submit(actor: ManagementActor, request: DoctorProjectChangeRequest): DoctorProjectChangeView {
        val doctorId = actor.doctorId
            ?: throw AccessDeniedException("只有已认证医生可以提交项目申请")
        val requestType = request.requestType.trim().uppercase()
        require(requestType in PROJECT_CHANGE_TYPES) { "不支持的项目申请类型" }
        val institutionProjectId = request.institutionProjectId.trim()
        require(institutionProjectId.isNotEmpty()) { "机构项目不能为空" }
        val project = projectTarget(institutionProjectId)
            ?: throw DoctorProjectChangeNotFoundException("机构项目不存在")
        if (project.institutionId !in actor.doctorInstitutionIds) {
            throw AccessDeniedException("医生尚未取得该机构的有效执业关系")
        }
        val existing = doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)
        when (requestType) {
            "JOIN" -> {
                require(existing == null) { "医生已加入该机构项目" }
                travelGroundServicePricing.quote(
                    requireNotNull(request.priceSuggestion) { "申请加入机构项目时必须填写价格建议" }
                )
                require(request.serviceTags.isEmpty() && request.images.isEmpty() &&
                    request.scheduleNote.isBlank() && request.coverImage.isBlank() &&
                    request.consultationFee == null && request.commissionRate == null && request.institutionRate == null &&
                    request.medicalListPrice == null) {
                    "加入机构项目仅允许提交服务内容、价格建议和说明"
                }
            }
            "PROFILE_UPDATE", "LEAVE" -> require(existing != null) { "医生尚未加入该机构项目" }
        }
        val config = if (requestType == "PROFILE_UPDATE") configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(doctorId, institutionProjectId) else null
        if (requestType == "PROFILE_UPDATE") validateProfile(request)
        if (pendingCount(doctorId, institutionProjectId) != 0L) throw DoctorProjectChangeConflictException("该项目已有待处理申请")

        val id = UUID.randomUUID().toString()
        val currentSplit = if (requestType == "PROFILE_UPDATE") {
            val currentCommission = config?.commissionRate ?: splitRatePolicy.defaults().consultantRate
            val currentInstitution = config?.institutionRate ?: splitRatePolicy.defaults().institutionRate
            splitRatePolicy.resolve(currentInstitution, currentCommission)
        } else null
        try { jdbcTemplate.update(
            """
            INSERT INTO doctor_project_change_requests
                (id, doctor_id, institution_id, institution_project_id, request_type,
                 service_description, price_suggestion, consultation_fee, commission_rate, institution_rate, medical_list_price,
                 base_doctor_project_updated_at, base_config_id, base_config_updated_at,
                 current_price, current_service_description, current_service_tags, current_schedule_note,
                 current_cover_image, current_images, current_consultation_fee, current_commission_rate, current_institution_rate, current_medical_list_price,
                 current_platform_rate, current_doctor_rate,
                 notes, service_tags, schedule_note, cover_image, images,
                 status, submitted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent(),
            id,
            doctorId,
            project.institutionId,
            institutionProjectId,
            requestType,
            request.serviceDescription.trim().take(5000),
            request.priceSuggestion,
            request.consultationFee, request.commissionRate, request.institutionRate, request.medicalListPrice,
            existing?.updatedAt, config?.id, config?.updatedAt,
            if (requestType == "PROFILE_UPDATE") existing?.price else null,
            if (requestType == "PROFILE_UPDATE") existing?.serviceDescription else null,
            if (requestType == "PROFILE_UPDATE") existing?.serviceTags else null,
            if (requestType == "PROFILE_UPDATE") existing?.scheduleNote else null,
            if (requestType == "PROFILE_UPDATE") existing?.coverImage else null,
            if (requestType == "PROFILE_UPDATE") existing?.images else null,
            if (requestType == "PROFILE_UPDATE") config?.consultationFee ?: BigDecimal.ZERO else null,
            if (requestType == "PROFILE_UPDATE") config?.commissionRate ?: splitRatePolicy.defaults().consultantRate else null,
            if (requestType == "PROFILE_UPDATE") config?.institutionRate ?: splitRatePolicy.defaults().institutionRate else null,
            if (requestType == "PROFILE_UPDATE") config?.medicalListPrice ?: BigDecimal.ZERO else null,
            currentSplit?.platformRate, currentSplit?.doctorRate,
            request.notes.trim(),
            encodeList(request.serviceTags),
            request.scheduleNote.trim().take(500),
            request.coverImage.trim().take(500),
            encodeList(request.images),
            actor.userId
        ) } catch (e: DataIntegrityViolationException) {
            throw DoctorProjectChangeConflictException("该项目已有待处理申请")
        }
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "项目申请创建失败" }
    }

    @Transactional
    fun review(actor: ManagementActor, id: String, decision: String, reviewNote: String, force: Boolean?): DoctorProjectChangeView {
        val normalizedDecision = decision.trim().uppercase()
        require(normalizedDecision in PROJECT_CHANGE_DECISIONS) { "审核结果不正确" }
        if (normalizedDecision != "APPROVED") require(reviewNote.isNotBlank()) { "驳回或要求修改时必须填写原因" }
        require(force != null) { "force 字段必须显式提交" }
        require(!force || normalizedDecision == "APPROVED") { "仅 APPROVED 决策允许强制处理" }
        if (force) {
            if (!actor.isAdmin) throw AccessDeniedException("只有平台管理员可以强制处理")
            require(reviewNote.isNotBlank()) { "强制处理必须填写说明" }
        }
        val target = lockedTarget(id) ?: throw DoctorProjectChangeNotFoundException("项目申请不存在")
        if (target.status != "PENDING") throw DoctorProjectChangeConflictException("项目申请已处理")
        if (!actor.isAdmin && target.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("只有所属机构法人可以审核该申请")
        }
        if (normalizedDecision == "APPROVED") {
            if (target.requestType == "JOIN") {
                relationshipService.requireActiveRelationshipForUpdate(target.doctorId, target.institutionId)
            }
            applyApproved(target, force)
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE doctor_project_change_requests
            SET status = ?, reviewed_by = ?, review_note = ?, force_processed = ?, reviewed_at = NOW()
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            normalizedDecision,
            actor.userId,
            reviewNote.trim().take(1000),
            force,
            id
        )
        if (updated != 1) throw DoctorProjectChangeConflictException("项目申请已被其他审核人处理")
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "项目申请审核结果读取失败" }
    }

    @Transactional
    fun withdraw(actor: ManagementActor, id: String): DoctorProjectChangeView {
        val target = lockedTarget(id) ?: throw DoctorProjectChangeNotFoundException("项目申请不存在")
        require(target.status == "PENDING") { "只有待处理申请可以撤回" }
        if (!actor.isAdmin && (actor.doctorId != target.doctorId || actor.userId != target.submittedBy)) {
            throw AccessDeniedException("只能撤回自己提交的申请")
        }
        jdbcTemplate.update(
            "UPDATE doctor_project_change_requests SET status = 'WITHDRAWN', reviewed_by = ?, reviewed_at = NOW() WHERE id = ? AND status = 'PENDING'",
            actor.userId,
            id
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "项目申请撤回结果读取失败" }
    }

    private fun applyApproved(target: ChangeTarget, force: Boolean) {
        val existing = doctorProjectRepository.findForUpdate(
            target.doctorId,
            target.institutionProjectId
        )
        when (target.requestType) {
            "JOIN" -> {
                require(existing == null) { "医生已加入该机构项目，申请无法重复通过" }
                travelGroundServicePricing.quote(
                    requireNotNull(target.priceSuggestion) { "加入申请缺少价格建议" }
                )
                doctorProjectRepository.save(target.toEntity(includeProfileFields = false))
            }
            "PROFILE_UPDATE" -> {
                require(existing != null) { "医生项目关系不存在" }
                if (!force) {
                    relationshipService.requireActiveRelationshipForUpdate(target.doctorId, target.institutionId)
                    if (existing.updatedAt != target.baseDoctorProjectUpdatedAt) throw DoctorProjectChangeConflictException("医生项目基线已变化")
                }
                val config = configRepository.findForUpdate(target.doctorId, target.institutionProjectId)
                    ?: configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeletedForUpdate(target.doctorId, target.institutionProjectId)
                if (!force && (config?.id != target.baseConfigId || config?.updatedAt != target.baseConfigUpdatedAt)) throw DoctorProjectChangeConflictException("分账配置基线已变化")
                validateApprovedProfileAmounts(target)
                splitRatePolicy.resolve(requireNotNull(target.institutionRate), requireNotNull(target.commissionRate))
                doctorProjectRepository.save(target.toEntity(existing.createdAt, requireNotNull(target.priceSuggestion)))
                val effective = config ?: DoctorInstitutionProjectConfigEntity(doctorId=target.doctorId, institutionProjectId=target.institutionProjectId)
                effective.consultationFee=requireNotNull(target.consultationFee); effective.commissionRate=requireNotNull(target.commissionRate)
                effective.institutionRate=requireNotNull(target.institutionRate); effective.medicalListPrice=requireNotNull(target.priceSuggestion)
                effective.deletedAt=null; effective.updatedAt=LocalDateTime.now()
                configRepository.save(effective)
            }
            "LEAVE" -> {
                require(existing != null) { "医生项目关系不存在" }
                configRepository.findByDoctorIdAndInstitutionProjectId(target.doctorId, target.institutionProjectId)
                    ?.let(configRepository::delete)
                jdbcTemplate.update(
                    "UPDATE split_config_proposals SET status = 'WITHDRAWN', decided_at = NOW(), decision_note = '医生已退出项目' WHERE doctor_id = ? AND institution_project_id = ? AND status = 'PENDING'",
                    target.doctorId,
                    target.institutionProjectId
                )
                doctorProjectRepository.delete(existing)
            }
        }
    }

    /** Pending rows can predate the shared USD validation, so approval must not trust submission-time checks. */
    private fun validateApprovedProfileAmounts(target: ChangeTarget) {
        val price = requireNotNull(target.priceSuggestion) { "医生项目价格不能为空" }
        val compatibilityPrice = requireNotNull(target.medicalListPrice) { "医生项目价格不能为空" }
        require(price.compareTo(compatibilityPrice) == 0) { "医生项目价格与兼容价格必须一致" }
        travelGroundServicePricing.quote(price)
        requireNotNull(target.consultationFee) { "面诊费不能为空" }.also(::validateMoney)
    }


    private fun ChangeTarget.toEntity(
        createdAt: LocalDateTime = LocalDateTime.now(),
        effectivePrice: BigDecimal = priceSuggestion ?: throw IllegalArgumentException("医生项目价格不能为空"),
        includeProfileFields: Boolean = true
    ) = DoctorProjectEntity(
        doctorId = doctorId,
        projectId = projectId,
        institutionProjectId = institutionProjectId,
        price = effectivePrice,
        serviceDescription = serviceDescription,
        serviceTags = if (includeProfileFields) serviceTags else "",
        scheduleNote = if (includeProfileFields) scheduleNote else "",
        coverImage = if (includeProfileFields) coverImage else "",
        images = if (includeProfileFields) images else "",
        createdAt = createdAt,
        updatedAt = LocalDateTime.now()
    )

    private fun projectTarget(institutionProjectId: String): ProjectTarget? = jdbcTemplate.query(
        "SELECT institution_id, project_id FROM institution_projects WHERE id = ? AND deleted_at IS NULL AND is_active = TRUE",
        { rs, _ -> ProjectTarget(rs.getString("institution_id"), rs.getString("project_id")) },
        institutionProjectId
    ).firstOrNull()

    private fun projectTargetV2(institutionProjectId: String, doctorId: String): V2TargetIdentity? = jdbcTemplate.query(
        """
        SELECT institution_id, project_id, i.name AS institution_name, d.name AS doctor_name
        FROM institution_projects ip
        JOIN institutions i ON i.id = ip.institution_id AND i.deleted_at IS NULL
        JOIN doctors d ON d.id = ? AND d.deleted_at IS NULL
        WHERE ip.id = ? AND ip.deleted_at IS NULL AND ip.is_active = TRUE
        """.trimIndent(),
        { rs, _ ->
            V2TargetIdentity(
                institutionId = rs.getString("institution_id"),
                projectId = rs.getString("project_id"),
                institutionName = rs.getString("institution_name"),
                doctorName = rs.getString("doctor_name")
            )
        },
        doctorId,
        institutionProjectId
    ).firstOrNull()

    private fun lockedTarget(id: String): ChangeTarget? = jdbcTemplate.query(
        """
        SELECT r.doctor_id, r.institution_id, r.institution_project_id, ip.project_id,
               r.request_type, r.service_description, r.price_suggestion, r.notes,
               r.service_tags, r.schedule_note,
               r.cover_image, r.images, r.status, r.submitted_by
               ,r.consultation_fee, r.commission_rate, r.institution_rate, r.medical_list_price,
               r.base_doctor_project_updated_at, r.base_config_id, r.base_config_updated_at
        FROM doctor_project_change_requests r
        JOIN institution_projects ip ON ip.id = r.institution_project_id
        WHERE r.id = ? FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            ChangeTarget(
                doctorId = rs.getString("doctor_id"),
                institutionId = rs.getString("institution_id"),
                institutionProjectId = rs.getString("institution_project_id"),
                projectId = rs.getString("project_id"),
                requestType = rs.getString("request_type"),
                serviceDescription = rs.getString("service_description").orEmpty(),
                priceSuggestion = rs.getBigDecimal("price_suggestion"),
                notes = rs.getString("notes").orEmpty(),
                serviceTags = rs.getString("service_tags").orEmpty(),
                scheduleNote = rs.getString("schedule_note").orEmpty(),
                coverImage = rs.getString("cover_image").orEmpty(),
                images = rs.getString("images").orEmpty(),
                status = rs.getString("status"),
                submittedBy = rs.getString("submitted_by")
                ,consultationFee=rs.getBigDecimal("consultation_fee"), commissionRate=rs.getBigDecimal("commission_rate"),
                institutionRate=rs.getBigDecimal("institution_rate"), medicalListPrice=rs.getBigDecimal("medical_list_price"), baseDoctorProjectUpdatedAt=rs.getTimestamp("base_doctor_project_updated_at")?.toLocalDateTime(),
                baseConfigId=rs.getString("base_config_id"), baseConfigUpdatedAt=rs.getTimestamp("base_config_updated_at")?.toLocalDateTime()
            )
        },
        id
    ).firstOrNull()

    private fun pendingCount(doctorId: String, institutionProjectId: String): Long = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM doctor_project_change_requests WHERE doctor_id = ? AND institution_project_id = ? AND status = 'PENDING'",
        Long::class.java,
        doctorId,
        institutionProjectId
    )

    private fun lockedPlatformProject(projectId: String): ProjectEntity? = jdbcTemplate.query(
        """
        SELECT id, name, category, description, tags, slogan, detail_content, cover_image, images
        FROM projects WHERE id = ? AND deleted_at IS NULL FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            ProjectEntity(
                id = rs.getString("id"),
                name = rs.getString("name"),
                category = rs.getString("category"),
                description = rs.getString("description").orEmpty(),
                tags = rs.getString("tags").orEmpty(),
                slogan = rs.getString("slogan").orEmpty(),
                detailContent = rs.getString("detail_content"),
                coverImage = rs.getString("cover_image").orEmpty(),
                images = rs.getString("images").orEmpty()
            )
        },
        projectId
    ).firstOrNull()

    private fun v2StateFromTargetRow(rs: java.sql.ResultSet): V2ProjectState = V2ProjectState(
        institutionProject = InstitutionProjectEntity(
            id = rs.getString("institution_project_id"),
            institutionId = rs.getString("institution_id"),
            projectId = rs.getString("platform_project_id"),
            name = rs.getString("ip_name"),
            category = rs.getString("ip_category"),
            description = rs.getString("ip_description"),
            tags = rs.getString("ip_tags"),
            slogan = rs.getString("ip_slogan"),
            detailContent = rs.getString("ip_detail_content"),
            coverImage = rs.getString("ip_cover_image"),
            images = rs.getString("ip_images"),
            salesCount = rs.getInt("ip_sales_count"),
            version = rs.getLong("ip_version")
        ),
        institutionName = rs.getString("institution_name"),
        platformProject = ProjectEntity(
            id = rs.getString("platform_project_id"),
            name = rs.getString("platform_project_name"),
            category = rs.getString("platform_category"),
            description = rs.getString("platform_description").orEmpty(),
            tags = rs.getString("platform_tags").orEmpty(),
            slogan = rs.getString("platform_slogan").orEmpty(),
            detailContent = rs.getString("platform_detail_content"),
            coverImage = rs.getString("platform_cover_image").orEmpty(),
            images = rs.getString("platform_images").orEmpty()
        ),
        doctorId = rs.getString("doctor_id"),
        doctorName = rs.getString("doctor_name"),
        doctorPrice = rs.getBigDecimal("doctor_price"),
        doctorActive = rs.getBoolean("doctor_active"),
        doctorProjectUpdatedAt = rs.getTimestamp("doctor_project_updated_at").toInstant(),
        configId = rs.getString("config_id"),
        configUpdatedAt = rs.getTimestamp("config_updated_at")?.toInstant()
    )

    private fun v2StateFromLatestRow(rs: java.sql.ResultSet): V2ProjectState {
        val doctorPrice = requireNotNull(rs.getBigDecimal("latest_doctor_price")) { "医生项目绑定不存在" }
        return V2ProjectState(
            institutionProject = InstitutionProjectEntity(
                id = rs.getString("institution_project_id"),
                institutionId = rs.getString("institution_id"),
                projectId = rs.getString("platform_project_id"),
                name = rs.getString("latest_ip_name"),
                category = rs.getString("latest_ip_category"),
                description = rs.getString("latest_ip_description"),
                tags = rs.getString("latest_ip_tags"),
                slogan = rs.getString("latest_ip_slogan"),
                detailContent = rs.getString("latest_ip_detail_content"),
                coverImage = rs.getString("latest_ip_cover_image"),
                images = rs.getString("latest_ip_images"),
                salesCount = rs.getInt("latest_ip_sales_count"),
                version = rs.getLong("latest_ip_version")
            ),
            institutionName = rs.getString("institution_name"),
            platformProject = ProjectEntity(
                id = rs.getString("platform_project_id"),
                name = rs.getString("platform_project_name"),
                category = rs.getString("latest_platform_category"),
                description = rs.getString("latest_platform_description").orEmpty(),
                tags = rs.getString("latest_platform_tags").orEmpty(),
                slogan = rs.getString("latest_platform_slogan").orEmpty(),
                detailContent = rs.getString("latest_platform_detail_content"),
                coverImage = rs.getString("latest_platform_cover_image").orEmpty(),
                images = rs.getString("latest_platform_images").orEmpty()
            ),
            doctorId = rs.getString("doctor_id"),
            doctorName = rs.getString("doctor_name"),
            doctorPrice = doctorPrice,
            doctorActive = rs.getBoolean("latest_doctor_active"),
            doctorProjectUpdatedAt = rs.getTimestamp("latest_doctor_project_updated_at").toInstant(),
            configId = rs.getString("latest_config_id"),
            configUpdatedAt = rs.getTimestamp("latest_config_updated_at")?.toInstant()
        )
    }

    private fun V2ProjectState.snapshot(): InstitutionProjectSnapshotV2 {
        val platformHash = snapshotCodec.platformInheritanceHash(
            PlatformInheritanceSource(
                platformProjectId = platformProject.id,
                name = platformProject.name,
                category = platformProject.category,
                description = platformProject.description,
                tags = platformProject.tags,
                slogan = platformProject.slogan,
                detailContent = platformProject.detailContent,
                coverImage = platformProject.coverImage,
                images = platformProject.images
            )
        )
        val details = detailResolver.resolveDetails(institutionProject, platformProject)
        return InstitutionProjectSnapshotV2(
            schemaVersion = 2,
            association = ProjectAssociationSnapshot(
                institutionProject.id,
                institutionProject.institutionId,
                platformProject.id
            ),
            rawOverrides = details.rawOverrides,
            effective = details.effective,
            source = ProjectSnapshotSource(institutionProject.version, platformHash)
        )
    }

    private fun V2ProjectState.revision(pricingPolicyRevision: String): String {
        val snapshot = snapshot()
        return snapshotCodec.baseRevision(
            DoctorProjectRevisionSource(
                institutionProjectId = institutionProject.id,
                institutionId = institutionProject.institutionId,
                platformProjectId = platformProject.id,
                institutionProjectVersion = institutionProject.version,
                platformInheritanceHash = snapshot.source.platformInheritanceHash,
                doctorProjectUpdatedAt = doctorProjectUpdatedAt,
                configId = configId,
                configUpdatedAt = configUpdatedAt,
                pricingPolicyRevision = pricingPolicyRevision
            )
        )
    }

    private fun validateEffectivePayload(effective: ProjectEffectiveSnapshot) {
        if (effective.name.isBlank() || effective.category.isBlank()) {
            throw ProjectChangeContractException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID,
                "项目名称和分类必须有有效值"
            )
        }
    }

    private fun encodeOptionalList(values: List<String>?): String? = values?.let(objectMapper::writeValueAsString)

    private fun nullableBoolean(rs: java.sql.ResultSet, column: String): Boolean? {
        val value = rs.getBoolean(column)
        return if (rs.wasNull()) null else value
    }

    private fun LocalDateTime?.toRequiredInstant(message: String): Instant =
        this?.toInstant(ZoneOffset.UTC) ?: throw IllegalStateException(message)

    private fun contractConflict(code: ProjectChangeErrorCode, message: String) =
        ProjectChangeContractException(HttpStatus.CONFLICT, code, message)

    private fun adaptLegacyView(row: DoctorProjectChangeView) = LegacyDoctorProjectChangeViewV2(
        id = row.id,
        doctorId = row.doctorId,
        doctorName = row.doctorName,
        institutionId = row.institutionId,
        institutionName = row.institutionName,
        institutionProjectId = row.institutionProjectId,
        projectName = row.projectName,
        requestType = if (row.requestType == "PROFILE_UPDATE") "EDIT" else row.requestType,
        serviceDescription = row.serviceDescription,
        priceSuggestion = row.priceSuggestion,
        notes = row.notes,
        serviceTags = row.serviceTags,
        scheduleNote = row.scheduleNote,
        coverImage = row.coverImage,
        images = row.images,
        consultationFee = row.consultationFee,
        commissionRate = row.commissionRate,
        institutionRate = row.institutionRate,
        medicalListPrice = row.medicalListPrice,
        platformRate = row.platformRate,
        doctorRate = row.doctorRate,
        forceProcessed = row.forceProcessed,
        currentPrice = row.currentPrice,
        currentServiceDescription = row.currentServiceDescription,
        currentServiceTags = row.currentServiceTags,
        currentScheduleNote = row.currentScheduleNote,
        currentCoverImage = row.currentCoverImage,
        currentImages = row.currentImages,
        currentConsultationFee = row.currentConsultationFee,
        currentMedicalListPrice = row.currentMedicalListPrice,
        currentCommissionRate = row.currentCommissionRate,
        currentInstitutionRate = row.currentInstitutionRate,
        currentPlatformRate = row.currentPlatformRate,
        currentDoctorRate = row.currentDoctorRate,
        status = row.status,
        submittedBy = row.submittedBy,
        reviewedBy = row.reviewedBy,
        reviewerName = row.reviewerName,
        reviewNote = row.reviewNote,
        submittedAt = row.submittedAt,
        reviewedAt = row.reviewedAt,
        updatedAt = row.updatedAt
    )
}

data class DoctorProjectChangeRequest(
    val institutionProjectId: String = "",
    val requestType: String = "JOIN",
    val serviceDescription: String = "",
    val priceSuggestion: BigDecimal? = null,
    val notes: String = "",
    val serviceTags: List<String> = emptyList(),
    val scheduleNote: String = "",
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val consultationFee: BigDecimal? = null,
    val commissionRate: BigDecimal? = null,
    val institutionRate: BigDecimal? = null,
    val medicalListPrice: BigDecimal? = null
)

data class DoctorProjectProfileUpdateTargetView(
    val institutionProjectId: String,
    val projectName: String,
    val institutionId: String,
    val institutionName: String,
    val currentPrice: BigDecimal,
    val serviceDescription: String,
    val serviceTags: List<String>,
    val scheduleNote: String,
    val coverImage: String,
    val images: List<String>,
    val consultationFee: BigDecimal,
    val commissionRate: BigDecimal,
    val institutionRate: BigDecimal,
    val medicalListPrice: BigDecimal,
    val platformRate: BigDecimal,
    val doctorRate: BigDecimal
)

data class DoctorProjectChangeView(
    val id: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String,
    val institutionName: String,
    val institutionProjectId: String,
    val projectName: String,
    val requestType: String,
    val serviceDescription: String,
    val priceSuggestion: BigDecimal?,
    val notes: String,
    val serviceTags: List<String>,
    val scheduleNote: String,
    val coverImage: String,
    val images: List<String>,
    val consultationFee: BigDecimal?, val commissionRate: BigDecimal?, val institutionRate: BigDecimal?, val medicalListPrice: BigDecimal?,
    val platformRate: BigDecimal?, val doctorRate: BigDecimal?, val forceProcessed: Boolean,
    val currentPrice: BigDecimal?,
    val currentServiceDescription: String?,
    val currentServiceTags: List<String>?,
    val currentScheduleNote: String?,
    val currentCoverImage: String?,
    val currentImages: List<String>?,
    val currentConsultationFee: BigDecimal?,
    val currentMedicalListPrice: BigDecimal?,
    val currentCommissionRate: BigDecimal?,
    val currentInstitutionRate: BigDecimal?,
    val currentPlatformRate: BigDecimal?,
    val currentDoctorRate: BigDecimal?,
    val status: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val reviewerName: String?,
    val reviewNote: String,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val updatedAt: LocalDateTime
)

private data class ProjectTarget(val institutionId: String, val projectId: String)

private data class V2TargetIdentity(
    val institutionId: String,
    val projectId: String,
    val institutionName: String,
    val doctorName: String
)

private data class V2ProjectState(
    val institutionProject: InstitutionProjectEntity,
    val institutionName: String,
    val platformProject: ProjectEntity,
    val doctorId: String,
    val doctorName: String,
    val doctorPrice: BigDecimal,
    val doctorActive: Boolean,
    val doctorProjectUpdatedAt: Instant,
    val configId: String?,
    val configUpdatedAt: Instant?
)

private data class ChangeTarget(
    val doctorId: String,
    val institutionId: String,
    val institutionProjectId: String,
    val projectId: String,
    val requestType: String,
    val serviceDescription: String,
    val priceSuggestion: BigDecimal?,
    val notes: String,
    val serviceTags: String,
    val scheduleNote: String,
    val coverImage: String,
    val images: String,
    val status: String,
    val submittedBy: String,
    val consultationFee: BigDecimal?, val commissionRate: BigDecimal?, val institutionRate: BigDecimal?, val medicalListPrice: BigDecimal?,
    val baseDoctorProjectUpdatedAt: LocalDateTime?, val baseConfigId: String?, val baseConfigUpdatedAt: LocalDateTime?
)

class DoctorProjectChangeConflictException(message: String) : RuntimeException(message)
class DoctorProjectChangeNotFoundException(message: String) : RuntimeException(message)
