package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.entity.DoctorInstitutionProjectConfigEntity
import com.joysong.server.order.service.OrderSplitRatePolicy
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.math.BigDecimal
import java.util.UUID

private val PROJECT_CHANGE_TYPES = setOf("JOIN", "PROFILE_UPDATE", "LEAVE")
private val PROJECT_CHANGE_DECISIONS = setOf("APPROVED", "REJECTED", "CHANGES_REQUESTED")

@Service
class DoctorProjectChangeService(
    private val jdbcTemplate: JdbcTemplate,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val configRepository: DoctorInstitutionProjectConfigRepository,
    private val relationshipService: DoctorInstitutionRelationshipService,
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    private fun decodeList(raw: String?): List<String> = raw.orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)

    private fun validateProfile(request: DoctorProjectChangeRequest) {
        require(request.serviceDescription.trim().isNotEmpty() && request.serviceDescription.trim().length <= 5000) { "服务说明不能为空且最长 5000" }
        requireNotNull(request.priceSuggestion) { "项目价格不能为空" }.also(::validateMoney)
        requireNotNull(request.consultationFee) { "面诊费不能为空" }.also(::validateMoney)
        require(request.notes.length <= 2000 && request.scheduleNote.length <= 500 && request.coverImage.length <= 500) { "文本字段长度超限" }
        require(request.serviceTags.size <= 20 && request.serviceTags.all { it.isNotBlank() && it.length <= 100 }) { "服务标签不合法" }
        require(request.images.size <= 20 && request.images.all { it.length <= 500 }) { "项目图片不合法" }
        splitRatePolicy.resolve(requireNotNull(request.institutionRate) { "机构比例不能为空" }, requireNotNull(request.commissionRate) { "顾问比例不能为空" })
    }

    private fun validateMoney(value: BigDecimal) {
        require(value >= BigDecimal.ZERO && value <= BigDecimal("99999999.99") && value.stripTrailingZeros().scale() <= 2) { "金额须在范围内且最多两位小数" }
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
                   c.consultation_fee, c.commission_rate, c.institution_rate
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
                   r.consultation_fee, r.commission_rate, r.institution_rate, r.force_processed,
                   r.current_price, r.current_service_description, r.current_service_tags,
                   r.current_schedule_note, r.current_cover_image, r.current_images,
                   r.current_consultation_fee, r.current_commission_rate, r.current_institution_rate,
                   r.reviewed_by, reviewer.nickname AS reviewer_name, r.review_note,
                   r.submitted_at, r.reviewed_at, r.updated_at
            FROM doctor_project_change_requests r
            JOIN doctors d ON d.id = r.doctor_id
            JOIN institutions i ON i.id = r.institution_id
            JOIN institution_projects ip ON ip.id = r.institution_project_id
            JOIN projects p ON p.id = ip.project_id
            LEFT JOIN users reviewer ON reviewer.id = r.reviewed_by
            ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.submitted_at DESC
            """.trimIndent()
        ) { rs, _ ->
            val requestType = rs.getString("request_type")
            val currentCommissionRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_commission_rate") else null
            val currentInstitutionRate = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_institution_rate") else null
            val currentSplit = if (currentCommissionRate != null && currentInstitutionRate != null) {
                splitRatePolicy.resolve(currentInstitutionRate, currentCommissionRate)
            } else null
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
                platformRate = rs.getBigDecimal("commission_rate")?.let { splitRatePolicy.currentPlatformRate() },
                doctorRate = rs.getBigDecimal("commission_rate")?.let { splitRatePolicy.resolve(rs.getBigDecimal("institution_rate"), it).doctorRate },
                currentPrice = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_price") else null,
                currentServiceDescription = if (requestType == "PROFILE_UPDATE") rs.getString("current_service_description").orEmpty() else null,
                currentServiceTags = if (requestType == "PROFILE_UPDATE") decodeList(rs.getString("current_service_tags")) else null,
                currentScheduleNote = if (requestType == "PROFILE_UPDATE") rs.getString("current_schedule_note").orEmpty() else null,
                currentCoverImage = if (requestType == "PROFILE_UPDATE") rs.getString("current_cover_image").orEmpty() else null,
                currentImages = if (requestType == "PROFILE_UPDATE") decodeList(rs.getString("current_images")) else null,
                currentConsultationFee = if (requestType == "PROFILE_UPDATE") rs.getBigDecimal("current_consultation_fee") else null,
                currentCommissionRate = currentCommissionRate,
                currentInstitutionRate = currentInstitutionRate,
                currentPlatformRate = currentSplit?.platformRate,
                currentDoctorRate = currentSplit?.doctorRate,
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

    @Transactional
    fun submit(actor: ManagementActor, request: DoctorProjectChangeRequest): DoctorProjectChangeView {
        val doctorId = actor.doctorId
            ?: throw AccessDeniedException("只有已认证医生可以提交项目申请")
        val requestType = request.requestType.trim().uppercase()
        require(requestType in PROJECT_CHANGE_TYPES) { "不支持的项目申请类型" }
        val institutionProjectId = request.institutionProjectId.trim()
        require(institutionProjectId.isNotEmpty()) { "机构项目不能为空" }
        val project = projectTarget(institutionProjectId)
            ?: throw IllegalArgumentException("机构项目不存在")
        if (project.institutionId !in actor.doctorInstitutionIds) {
            throw AccessDeniedException("医生尚未取得该机构的有效执业关系")
        }
        val existing = doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(doctorId, institutionProjectId)
        when (requestType) {
            "JOIN" -> {
                require(existing == null) { "医生已加入该机构项目" }
                require(request.priceSuggestion != null && request.priceSuggestion >= BigDecimal.ZERO) {
                    "申请加入机构项目时必须填写非负价格建议"
                }
                require(request.serviceTags.isEmpty() && request.images.isEmpty() &&
                    request.scheduleNote.isBlank() && request.coverImage.isBlank() &&
                    request.consultationFee == null && request.commissionRate == null && request.institutionRate == null) {
                    "加入机构项目仅允许提交服务内容、价格建议和说明"
                }
            }
            "PROFILE_UPDATE", "LEAVE" -> require(existing != null) { "医生尚未加入该机构项目" }
        }
        val config = if (requestType == "PROFILE_UPDATE") configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeleted(doctorId, institutionProjectId) else null
        if (requestType == "PROFILE_UPDATE") validateProfile(request)
        if (pendingCount(doctorId, institutionProjectId) != 0L) throw DoctorProjectChangeConflictException("该项目已有待处理申请")

        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO doctor_project_change_requests
                (id, doctor_id, institution_id, institution_project_id, request_type,
                 service_description, price_suggestion, consultation_fee, commission_rate, institution_rate,
                 base_doctor_project_updated_at, base_config_id, base_config_updated_at,
                 current_price, current_service_description, current_service_tags, current_schedule_note,
                 current_cover_image, current_images, current_consultation_fee, current_commission_rate, current_institution_rate,
                 notes, service_tags, schedule_note, cover_image, images,
                 status, submitted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent(),
            id,
            doctorId,
            project.institutionId,
            institutionProjectId,
            requestType,
            request.serviceDescription.trim().take(5000),
            request.priceSuggestion,
            request.consultationFee, request.commissionRate, request.institutionRate,
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
            request.notes.trim().take(2000),
            request.serviceTags.joinToString(",").take(500),
            request.scheduleNote.trim().take(500),
            request.coverImage.trim().take(500),
            request.images.joinToString(",").take(2000),
            actor.userId
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "项目申请创建失败" }
    }

    @Transactional
    fun review(actor: ManagementActor, id: String, decision: String, reviewNote: String, force: Boolean? = false): DoctorProjectChangeView {
        val normalizedDecision = decision.trim().uppercase()
        require(normalizedDecision in PROJECT_CHANGE_DECISIONS) { "审核结果不正确" }
        if (normalizedDecision != "APPROVED") require(reviewNote.isNotBlank()) { "驳回或要求修改时必须填写原因" }
        require(force != null) { "force 字段必须显式提交" }
        if (force) {
            if (!actor.isAdmin) throw AccessDeniedException("只有平台管理员可以强制处理")
            require(reviewNote.isNotBlank()) { "强制处理必须填写说明" }
        }
        val target = lockedTarget(id) ?: throw IllegalArgumentException("项目申请不存在")
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
        val target = lockedTarget(id) ?: throw IllegalArgumentException("项目申请不存在")
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
                require(target.priceSuggestion != null) { "加入申请缺少价格建议" }
                doctorProjectRepository.save(target.toEntity(includeProfileFields = false))
            }
            "PROFILE_UPDATE" -> {
                require(existing != null) { "医生项目关系不存在" }
                if (!force) {
                    relationshipService.requireActiveRelationshipForUpdate(target.doctorId, target.institutionId)
                    if (existing.updatedAt != target.baseDoctorProjectUpdatedAt) throw DoctorProjectChangeConflictException("医生项目基线已变化")
                }
                val config = configRepository.findForUpdate(target.doctorId, target.institutionProjectId)
                    ?: configRepository.findByDoctorIdAndInstitutionProjectIdIncludeDeleted(target.doctorId, target.institutionProjectId)
                if (!force && (config?.id != target.baseConfigId || config?.updatedAt != target.baseConfigUpdatedAt)) throw DoctorProjectChangeConflictException("分账配置基线已变化")
                splitRatePolicy.resolve(requireNotNull(target.institutionRate), requireNotNull(target.commissionRate))
                doctorProjectRepository.save(target.toEntity(existing.createdAt, requireNotNull(target.priceSuggestion)))
                val effective = config ?: DoctorInstitutionProjectConfigEntity(doctorId=target.doctorId, institutionProjectId=target.institutionProjectId)
                effective.consultationFee=requireNotNull(target.consultationFee); effective.commissionRate=requireNotNull(target.commissionRate)
                effective.institutionRate=requireNotNull(target.institutionRate); effective.deletedAt=null; effective.updatedAt=LocalDateTime.now()
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
        "SELECT institution_id, project_id FROM institution_projects WHERE id = ? AND deleted_at IS NULL",
        { rs, _ -> ProjectTarget(rs.getString("institution_id"), rs.getString("project_id")) },
        institutionProjectId
    ).firstOrNull()

    private fun lockedTarget(id: String): ChangeTarget? = jdbcTemplate.query(
        """
        SELECT r.doctor_id, r.institution_id, r.institution_project_id, ip.project_id,
               r.request_type, r.service_description, r.price_suggestion, r.notes,
               r.service_tags, r.schedule_note,
               r.cover_image, r.images, r.status, r.submitted_by
               ,r.consultation_fee, r.commission_rate, r.institution_rate,
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
                institutionRate=rs.getBigDecimal("institution_rate"), baseDoctorProjectUpdatedAt=rs.getTimestamp("base_doctor_project_updated_at")?.toLocalDateTime(),
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
    val institutionRate: BigDecimal? = null
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
    val consultationFee: BigDecimal?, val commissionRate: BigDecimal?, val institutionRate: BigDecimal?,
    val platformRate: BigDecimal?, val doctorRate: BigDecimal?, val forceProcessed: Boolean,
    val currentPrice: BigDecimal?,
    val currentServiceDescription: String?,
    val currentServiceTags: List<String>?,
    val currentScheduleNote: String?,
    val currentCoverImage: String?,
    val currentImages: List<String>?,
    val currentConsultationFee: BigDecimal?,
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
    val consultationFee: BigDecimal?, val commissionRate: BigDecimal?, val institutionRate: BigDecimal?,
    val baseDoctorProjectUpdatedAt: LocalDateTime?, val baseConfigId: String?, val baseConfigUpdatedAt: LocalDateTime?
)

class DoctorProjectChangeConflictException(message: String) : RuntimeException(message)
