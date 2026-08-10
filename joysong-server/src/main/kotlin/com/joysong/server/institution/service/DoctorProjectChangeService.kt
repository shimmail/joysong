package com.joysong.server.institution.service

import com.joysong.server.discover.entity.DoctorProjectEntity
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
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
    private val configRepository: DoctorInstitutionProjectConfigRepository
) {
    fun list(actor: ManagementActor): List<DoctorProjectChangeView> {
        val rows = jdbcTemplate.query(
            """
            SELECT r.id, r.doctor_id, d.name AS doctor_name, r.institution_id,
                   i.name AS institution_name, r.institution_project_id,
                   COALESCE(ip.name, p.name) AS project_name, r.request_type,
                   r.service_description, r.price_suggestion, r.notes, r.service_tags, r.schedule_note,
                   r.cover_image, r.images, r.status, r.submitted_by,
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
            DoctorProjectChangeView(
                id = rs.getString("id"),
                doctorId = rs.getString("doctor_id"),
                doctorName = rs.getString("doctor_name"),
                institutionId = rs.getString("institution_id"),
                institutionName = rs.getString("institution_name"),
                institutionProjectId = rs.getString("institution_project_id"),
                projectName = rs.getString("project_name"),
                requestType = rs.getString("request_type"),
                serviceDescription = rs.getString("service_description").orEmpty(),
                priceSuggestion = rs.getBigDecimal("price_suggestion"),
                notes = rs.getString("notes").orEmpty(),
                serviceTags = rs.getString("service_tags").orEmpty(),
                scheduleNote = rs.getString("schedule_note").orEmpty(),
                coverImage = rs.getString("cover_image").orEmpty(),
                images = rs.getString("images").orEmpty(),
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
                require(listOf(
                    request.serviceTags,
                    request.scheduleNote,
                    request.coverImage,
                    request.images
                ).all { it.isBlank() }) {
                    "加入机构项目仅允许提交服务内容、价格建议和说明"
                }
            }
            "PROFILE_UPDATE", "LEAVE" -> require(existing != null) { "医生尚未加入该机构项目" }
        }
        require(pendingCount(doctorId, institutionProjectId) == 0L) { "该项目已有待处理申请" }

        val id = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO doctor_project_change_requests
                (id, doctor_id, institution_id, institution_project_id, request_type,
                 service_description, price_suggestion, notes, service_tags, schedule_note, cover_image, images,
                 status, submitted_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            """.trimIndent(),
            id,
            doctorId,
            project.institutionId,
            institutionProjectId,
            requestType,
            request.serviceDescription.trim().take(5000),
            request.priceSuggestion,
            request.notes.trim().take(2000),
            request.serviceTags.trim().take(500),
            request.scheduleNote.trim().take(500),
            request.coverImage.trim().take(500),
            request.images.trim().take(2000),
            actor.userId
        )
        return requireNotNull(list(actor).firstOrNull { it.id == id }) { "项目申请创建失败" }
    }

    @Transactional
    fun review(actor: ManagementActor, id: String, decision: String, reviewNote: String): DoctorProjectChangeView {
        val normalizedDecision = decision.trim().uppercase()
        require(normalizedDecision in PROJECT_CHANGE_DECISIONS) { "审核结果不正确" }
        if (normalizedDecision != "APPROVED") require(reviewNote.isNotBlank()) { "驳回或要求修改时必须填写原因" }
        val target = lockedTarget(id) ?: throw IllegalArgumentException("项目申请不存在")
        require(target.status == "PENDING") { "项目申请已处理" }
        if (!actor.isAdmin && target.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("只有所属机构法人可以审核该申请")
        }
        if (normalizedDecision == "APPROVED") {
            if (target.requestType == "JOIN") requireActiveInstitutionRelationship(target)
            applyApproved(target)
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE doctor_project_change_requests
            SET status = ?, reviewed_by = ?, review_note = ?, reviewed_at = NOW()
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            normalizedDecision,
            actor.userId,
            reviewNote.trim().take(1000),
            id
        )
        check(updated == 1) { "项目申请已被其他审核人处理" }
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

    private fun applyApproved(target: ChangeTarget) {
        val existing = doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(
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
                doctorProjectRepository.save(target.toEntity(existing.createdAt, existing.price))
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

    private fun requireActiveInstitutionRelationship(target: ChangeTarget) {
        val activeCount = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*) FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ?
              AND status = 'APPROVED' AND deleted_at IS NULL
            """.trimIndent(),
            Long::class.java,
            target.doctorId,
            target.institutionId
        ) ?: 0L
        if (activeCount == 0L) {
            throw AccessDeniedException("医生与机构的有效执业关系已失效")
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
    val serviceTags: String = "",
    val scheduleNote: String = "",
    val coverImage: String = "",
    val images: String = ""
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
    val serviceTags: String,
    val scheduleNote: String,
    val coverImage: String,
    val images: String,
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
    val submittedBy: String
)
