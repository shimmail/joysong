package com.joysong.server.project.service

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.joysong.server.common.money.CurrencyCode
import com.joysong.server.identity.service.ManagementActor
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

private val PROJECT_REQUEST_DECISIONS = setOf("APPROVED", "REJECTED", "CHANGES_REQUESTED")

@Service
class ProfessionalProjectRequestService(
    private val jdbcTemplate: JdbcTemplate
) {
    @Transactional
    fun submitPlatform(
        actor: ManagementActor,
        request: DoctorPlatformProjectRequest
    ): ProjectRequestSubmissionResult {
        val doctorId = requireDoctor(actor)
        val name = required(request.name, "项目名称不能为空")
        val category = required(request.category, "项目分类不能为空")
        val description = required(request.description, "项目说明不能为空")
        require(count(
            """
            SELECT COUNT(*) FROM professional_project_requests
            WHERE request_type = 'PLATFORM' AND doctor_id = ? AND name = ? AND category = ?
              AND status = 'PENDING'
            """.trimIndent(),
            doctorId, name, category
        ) == 0L) { "同一项目已有待处理申请" }

        val id = UUID.randomUUID().toString()
        insertRequest(
            id, "PLATFORM", doctorId, null, null, name, category, description,
            null, null, request.notes.trim().takeIf(String::isNotEmpty)
        )
        return ProjectRequestSubmissionResult(id, "PLATFORM", "PENDING")
    }

    @Transactional
    fun submitInstitution(
        actor: ManagementActor,
        institutionId: String,
        request: DoctorInstitutionProjectRequest
    ): ProjectRequestSubmissionResult {
        val doctorId = requireDoctor(actor)
        val targetInstitutionId = required(institutionId, "机构不能为空")
        if (targetInstitutionId !in actor.doctorInstitutionIds) {
            throw AccessDeniedException("只能向已通过执业关系的机构提交项目申请")
        }
        val projectId = required(request.projectId, "平台项目不能为空")
        require(count("SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL", projectId) == 1L) {
            "平台项目不存在"
        }
        require(count(
            "SELECT COUNT(*) FROM institution_projects WHERE institution_id = ? AND project_id = ?",
            targetInstitutionId,
            projectId
        ) == 0L) { "该机构已存在此平台项目，请申请加入机构项目" }
        val serviceContent = required(request.description.orEmpty(), "服务内容不能为空")
        require(request.price >= BigDecimal.ZERO) { "建议价格不能为负数" }
        require(count(
            """
            SELECT COUNT(*) FROM professional_project_requests
            WHERE request_type = 'INSTITUTION' AND doctor_id = ? AND institution_id = ? AND project_id = ?
              AND status = 'PENDING'
            """.trimIndent(),
            doctorId, targetInstitutionId, projectId
        ) == 0L) { "同一项目已有待处理申请" }

        val id = UUID.randomUUID().toString()
        insertRequest(
            id, "INSTITUTION", doctorId, targetInstitutionId, projectId, null, null, null,
            serviceContent, request.price, request.notes.trim().takeIf(String::isNotEmpty)
        )
        return ProjectRequestSubmissionResult(id, "INSTITUTION", "PENDING")
    }

    fun list(actor: ManagementActor): List<ProfessionalProjectRequestView> {
        val conditions = mutableListOf<String>()
        val args = mutableListOf<Any>()
        if (!actor.isAdmin) {
            actor.doctorId?.let {
                conditions += "r.doctor_id = ?"
                args += it
            }
            if (actor.managedInstitutionIds.isNotEmpty()) {
                conditions += "r.institution_id IN (${actor.managedInstitutionIds.joinToString(",") { "?" }})"
                args.addAll(actor.managedInstitutionIds)
            }
            if (conditions.isEmpty()) return emptyList()
        }
        val where = if (conditions.isEmpty()) "" else "WHERE (${conditions.joinToString(" OR ")})"
        return jdbcTemplate.query(
            """
            SELECT r.id, r.request_type, r.doctor_id, d.name AS doctor_name,
                   r.institution_id, i.name AS institution_name, r.project_id,
                   p.name AS project_name, r.name, r.category, r.description,
                   r.service_content, r.price_suggestion, r.notes, r.status,
                   r.review_note, r.reviewed_by, r.reviewed_at,
                   r.resulting_project_id, r.resulting_institution_project_id,
                   r.submitted_at, r.updated_at
            FROM professional_project_requests r
            JOIN doctors d ON d.id = r.doctor_id
            LEFT JOIN institutions i ON i.id = r.institution_id
            LEFT JOIN projects p ON p.id = r.project_id
            $where
            ORDER BY CASE r.status WHEN 'PENDING' THEN 0 ELSE 1 END, r.submitted_at DESC
            """.trimIndent(),
            { rs, _ ->
                ProfessionalProjectRequestView(
                    id = rs.getString("id"),
                    requestType = rs.getString("request_type"),
                    doctorId = rs.getString("doctor_id"),
                    doctorName = rs.getString("doctor_name"),
                    institutionId = rs.getString("institution_id"),
                    institutionName = rs.getString("institution_name"),
                    projectId = rs.getString("project_id"),
                    projectName = rs.getString("project_name"),
                    name = rs.getString("name"),
                    category = rs.getString("category"),
                    description = rs.getString("description"),
                    serviceContent = rs.getString("service_content"),
                    priceSuggestion = rs.getBigDecimal("price_suggestion"),
                    notes = rs.getString("notes"),
                    status = rs.getString("status"),
                    reviewNote = rs.getString("review_note"),
                    reviewedBy = rs.getString("reviewed_by"),
                    reviewedAt = rs.getTimestamp("reviewed_at")?.toLocalDateTime(),
                    resultingProjectId = rs.getString("resulting_project_id"),
                    resultingInstitutionProjectId = rs.getString("resulting_institution_project_id"),
                    submittedAt = rs.getTimestamp("submitted_at").toLocalDateTime(),
                    updatedAt = rs.getTimestamp("updated_at").toLocalDateTime()
                )
            },
            *args.toTypedArray()
        )
    }

    @Transactional
    fun reviewPlatform(
        actor: ManagementActor,
        id: String,
        review: ProjectRequestReview
    ): ProjectRequestReviewResult {
        if (!actor.isAdmin) throw AccessDeniedException("该操作仅限平台管理员")
        return review(actor, id, review, "PLATFORM")
    }

    @Transactional
    fun reviewInstitution(
        actor: ManagementActor,
        id: String,
        review: ProjectRequestReview
    ): ProjectRequestReviewResult = review(actor, id, review, "INSTITUTION")

    private fun review(
        actor: ManagementActor,
        id: String,
        review: ProjectRequestReview,
        expectedType: String
    ): ProjectRequestReviewResult {
        val decision = review.decision.trim().uppercase()
        require(decision in PROJECT_REQUEST_DECISIONS) { "审核决定不正确" }
        val reviewNote = review.reviewNote.trim().takeIf(String::isNotEmpty)
        require(decision == "APPROVED" || reviewNote != null) { "拒绝或要求修改时必须填写审核意见" }

        val target = lockedTarget(required(id, "申请不能为空"))
            ?: throw IllegalArgumentException("项目申请不存在")
        require(target.requestType == expectedType) { "项目申请类型不正确" }
        require(target.status == "PENDING") { "项目申请已处理" }
        if (expectedType == "INSTITUTION" && !actor.isAdmin && target.institutionId !in actor.managedInstitutionIds) {
            throw AccessDeniedException("只能审核本机构的项目申请")
        }

        var resultingProjectId: String? = null
        var resultingInstitutionProjectId: String? = null
        if (decision == "APPROVED") {
            if (expectedType == "PLATFORM") {
                resultingProjectId = createProject(target)
            } else {
                lockInstitution(requireNotNull(target.institutionId))
                validateInstitutionApproval(target)
                resultingInstitutionProjectId = createInstitutionProject(target)
            }
        }
        val updated = jdbcTemplate.update(
            """
            UPDATE professional_project_requests
            SET status = ?, review_note = ?, reviewed_by = ?, reviewed_at = NOW(),
                resulting_project_id = ?, resulting_institution_project_id = ?
            WHERE id = ? AND status = 'PENDING'
            """.trimIndent(),
            decision, reviewNote, actor.userId, resultingProjectId, resultingInstitutionProjectId, target.id
        )
        check(updated == 1) { "项目申请已被其他审核人处理" }
        return ProjectRequestReviewResult(target.id, decision, resultingProjectId, resultingInstitutionProjectId)
    }

    private fun createProject(target: ProjectRequestTarget): String {
        val projectId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            "INSERT INTO projects (id, name, category, description) VALUES (?, ?, ?, ?)",
            projectId, target.name, target.category, target.description
        )
        return projectId
    }

    private fun createInstitutionProject(target: ProjectRequestTarget): String {
        val institutionProjectId = UUID.randomUUID().toString()
        jdbcTemplate.update(
            """
            INSERT INTO institution_projects
                (id, institution_id, project_id, description, detail_content, price)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            institutionProjectId, target.institutionId, target.projectId,
            target.serviceContent, target.serviceContent, target.priceSuggestion
        )
        jdbcTemplate.update(
            """
            INSERT INTO doctor_projects
                (doctor_id, project_id, institution_project_id, service_description, schedule_note, price)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            target.doctorId, target.projectId, institutionProjectId,
            target.serviceContent, "", target.priceSuggestion
        )
        return institutionProjectId
    }

    private fun lockInstitution(institutionId: String) {
        requireNotNull(jdbcTemplate.queryForObject(
            "SELECT id FROM institutions WHERE id = ? FOR UPDATE",
            String::class.java,
            institutionId
        )) { "机构不存在" }
    }

    private fun validateInstitutionApproval(target: ProjectRequestTarget) {
        require(count(
            """
            SELECT COUNT(*) FROM doctor_institutions
            WHERE doctor_id = ? AND institution_id = ? AND status = 'APPROVED'
              AND revoked_at IS NULL AND deleted_at IS NULL
            """.trimIndent(),
            target.doctorId,
            requireNotNull(target.institutionId)
        ) == 1L) { "医生已不具备该机构的有效执业关系" }
        require(count(
            "SELECT COUNT(*) FROM projects WHERE id = ? AND deleted_at IS NULL",
            requireNotNull(target.projectId)
        ) == 1L) { "平台项目已失效" }
        require(count(
            "SELECT COUNT(*) FROM institution_projects WHERE institution_id = ? AND project_id = ?",
            target.institutionId,
            target.projectId
        ) == 0L) { "该机构已存在此平台项目，请改为申请加入机构项目" }
    }

    private fun lockedTarget(id: String): ProjectRequestTarget? = jdbcTemplate.query(
        """
        SELECT id, request_type, doctor_id, institution_id, project_id, name, category,
               description, service_content, price_suggestion, notes, status
        FROM professional_project_requests WHERE id = ? FOR UPDATE
        """.trimIndent(),
        { rs, _ ->
            ProjectRequestTarget(
                id = rs.getString("id"),
                requestType = rs.getString("request_type"),
                doctorId = rs.getString("doctor_id"),
                institutionId = rs.getString("institution_id"),
                projectId = rs.getString("project_id"),
                name = rs.getString("name"),
                category = rs.getString("category"),
                description = rs.getString("description"),
                serviceContent = rs.getString("service_content"),
                priceSuggestion = rs.getBigDecimal("price_suggestion"),
                notes = rs.getString("notes"),
                status = rs.getString("status")
            )
        },
        id
    ).firstOrNull()

    private fun insertRequest(
        id: String,
        type: String,
        doctorId: String,
        institutionId: String?,
        projectId: String?,
        name: String?,
        category: String?,
        description: String?,
        serviceContent: String?,
        priceSuggestion: BigDecimal?,
        notes: String?
    ) {
        try {
            jdbcTemplate.update(
                """
                INSERT INTO professional_project_requests
                    (id, request_type, doctor_id, institution_id, project_id, name, category,
                     description, service_content, price_suggestion, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, type, doctorId, institutionId, projectId, name, category,
                description, serviceContent, priceSuggestion, notes
            )
        } catch (_: DuplicateKeyException) {
            throw IllegalArgumentException("同一项目已有待处理申请")
        }
    }

    private fun requireDoctor(actor: ManagementActor): String = actor.doctorId
        ?: throw AccessDeniedException("只有医生可以提交项目申请")

    private fun required(value: String, message: String): String =
        value.trim().also { require(it.isNotEmpty()) { message } }

    private fun count(sql: String, vararg args: Any): Long =
        jdbcTemplate.queryForObject(sql, Long::class.java, *args)
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class DoctorPlatformProjectRequest(
    val name: String = "",
    val category: String = "",
    val description: String = "",
    val referencePrice: BigDecimal = BigDecimal.ZERO,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val slogan: String = "",
    val salesCount: Int = 0,
    val coverImage: String = "",
    val images: List<String> = emptyList(),
    val detailContent: String? = null,
    val tags: List<String> = emptyList(),
    val categoryTags: List<String> = emptyList(),
    val notes: String = ""
) {
    @JsonIgnore
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }

}

@JsonIgnoreProperties(ignoreUnknown = false)
data class DoctorInstitutionProjectRequest(
    val projectId: String = "",
    val name: String? = null,
    val category: String? = null,
    val description: String? = null,
    val tags: List<String>? = null,
    val slogan: String? = null,
    val detailContent: String? = null,
    val price: BigDecimal = BigDecimal.ZERO,
    val originalPrice: BigDecimal? = null,
    val currency: CurrencyCode = CurrencyCode.DEFAULT,
    val coverImage: String? = null,
    val images: List<String>? = null,
    val salesCount: Int = 0,
    val isActive: Boolean = true,
    val consultationFee: BigDecimal = BigDecimal.ZERO,
    val commissionRate: BigDecimal = BigDecimal.ZERO,
    val institutionRate: BigDecimal = BigDecimal.ZERO,
    val notes: String = ""
) {
    @JsonIgnore
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }

}

data class InstitutionProjectApplicationFormConfig(
    val platformRate: BigDecimal
)

data class ProjectRequestReview(
    val decision: String = "",
    val reviewNote: String = ""
)

data class ProjectRequestSubmissionResult(
    val id: String,
    val requestType: String,
    val status: String
)

data class ProjectRequestReviewResult(
    val id: String,
    val status: String,
    val resultingProjectId: String?,
    val resultingInstitutionProjectId: String?
)

data class ProfessionalProjectRequestView(
    val id: String,
    val requestType: String,
    val doctorId: String,
    val doctorName: String,
    val institutionId: String?,
    val institutionName: String?,
    val projectId: String?,
    val projectName: String?,
    val name: String?,
    val category: String?,
    val description: String?,
    val serviceContent: String?,
    val priceSuggestion: BigDecimal?,
    val notes: String?,
    val status: String,
    val reviewNote: String?,
    val reviewedBy: String?,
    val reviewedAt: LocalDateTime?,
    val resultingProjectId: String?,
    val resultingInstitutionProjectId: String?,
    val submittedAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

private data class ProjectRequestTarget(
    val id: String,
    val requestType: String,
    val doctorId: String,
    val institutionId: String?,
    val projectId: String?,
    val name: String?,
    val category: String?,
    val description: String?,
    val serviceContent: String?,
    val priceSuggestion: BigDecimal?,
    val notes: String?,
    val status: String
)

class ProfessionalProjectRequestNotFoundException(message: String) : RuntimeException(message)

class ProfessionalProjectRequestConflictException(message: String) : RuntimeException(message)
