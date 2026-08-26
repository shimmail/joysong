package com.joysong.server.admin.controller

import com.fasterxml.jackson.core.JacksonException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.institution.service.DoctorProjectChangeConflictException
import com.joysong.server.institution.service.DoctorProjectChangeRequest
import com.joysong.server.institution.service.DoctorProjectChangeService
import com.joysong.server.institution.service.DoctorProjectChangeV2Request
import com.joysong.server.institution.service.DoctorProjectReviewV2Command
import com.joysong.server.institution.service.ProjectChangeContractException
import com.joysong.server.institution.service.ProjectChangeDecision
import com.joysong.server.institution.service.ProjectChangeErrorCode
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v2/admin/institution-project-requests")
class DoctorProjectChangeV2Controller(
    private val service: DoctorProjectChangeService,
    private val managementAccessService: ManagementAccessService,
    private val objectMapper: ObjectMapper
) {
    @GetMapping("/profile-update-targets")
    fun listProfileUpdateTargets(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.listProfileUpdateTargetsV2(managementAccessService.actor(authentication)))

    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.listV2(managementAccessService.actor(authentication)))

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody body: JsonNode
    ): BaseResponse<*> {
        requireObject(body)
        val requestType = requiredText(body, "requestType").trim().uppercase()
        val actor = managementAccessService.actor(authentication)
        val result = when (requestType) {
            "JOIN" -> {
                requireExactFields(body, joinFields)
                requireTextFields(body, "institutionProjectId", "serviceDescription", "notes")
                requireNumber(body, "priceSuggestion")
                submitLegacy(actor, body)
            }
            "LEAVE" -> {
                requireExactFields(body, leaveFields)
                requireTextFields(body, "institutionProjectId")
                submitLegacy(actor, body)
            }
            "PROFILE_UPDATE" -> {
                requireExactFields(body, profileFields)
                validateProfileFieldTypes(body)
                adaptSemanticPayloadError {
                    service.submitV2(actor, convert(body, DoctorProjectChangeV2Request::class.java))
                }
            }
            else -> throw invalidPayload("requestType 不受支持")
        }
        return BaseResponse.success(result)
    }

    @PostMapping("/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody body: JsonNode
    ): BaseResponse<*> {
        requireObject(body)
        requireExactFields(body, reviewFields)
        requireTextFields(body, "decision", "reviewNote")
        requireBoolean(body, "force")
        requireNullableText(body, "forceBaseRevision")
        val request = convert(body, ProjectChangeReviewV2Request::class.java)
        val decision = try {
            ProjectChangeDecision.valueOf(request.decision.trim().uppercase())
        } catch (e: IllegalArgumentException) {
            throw invalidPayload("审核结果不正确", e)
        }
        if (request.force && decision != ProjectChangeDecision.APPROVED) {
            throw ProjectChangeContractException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ProjectChangeErrorCode.FORCE_NOT_APPLICABLE,
                "仅 APPROVED 决策允许强制处理"
            )
        }
        if (!request.force && request.forceBaseRevision != null) {
            throw invalidPayload("普通审核必须提交 forceBaseRevision=null")
        }
        if (request.force && (request.reviewNote.isBlank() || request.forceBaseRevision.isNullOrBlank())) {
            throw invalidPayload("强制批准必须提交非空说明和 forceBaseRevision")
        }
        if (decision != ProjectChangeDecision.APPROVED && request.reviewNote.isBlank()) {
            throw invalidPayload("驳回或要求修改时必须填写原因")
        }
        val command = DoctorProjectReviewV2Command(
            decision = decision,
            reviewNote = request.reviewNote,
            force = request.force,
            forceBaseRevision = request.forceBaseRevision
        )
        return BaseResponse.success(
            service.reviewV2(managementAccessService.actor(authentication), id, command)
        )
    }

    @PostMapping("/{id}/withdraw")
    fun withdraw(authentication: Authentication, @PathVariable id: String): BaseResponse<*> =
        BaseResponse.success(service.withdrawV2(managementAccessService.actor(authentication), id))

    private fun submitLegacy(actor: ManagementActor, body: JsonNode) = try {
        adaptSemanticPayloadError {
            service.submit(actor, convert(body, DoctorProjectChangeRequest::class.java))
        }
    } catch (e: DoctorProjectChangeConflictException) {
        throw ProjectChangeContractException(
            HttpStatus.CONFLICT,
            ProjectChangeErrorCode.REQUEST_ALREADY_PENDING,
            e.message ?: "该项目已有待处理申请",
            e
        )
    }

    private inline fun <T> adaptSemanticPayloadError(delegate: () -> T): T = try {
        delegate()
    } catch (e: IllegalArgumentException) {
        throw invalidPayload("请求字段值不正确", e)
    }

    private fun validateProfileFieldTypes(body: JsonNode) {
        requireTextFields(body, "institutionProjectId", "baseRevision", "notes")
        nullableTextFields.forEach { field -> requireNullableText(body, field) }
        nullableTextArrayFields.forEach { field -> requireNullableTextArray(body, field) }
        requireNumber(body, "price")
        requireIntegralNumber(body, "salesCount")
        requireBoolean(body, "doctorActive")
    }

    private fun requireObject(body: JsonNode) {
        if (!body.isObject) throw invalidPayload("请求内容必须是 JSON 对象")
    }

    private fun requireExactFields(body: JsonNode, expected: Set<String>) {
        val actual = body.fieldNames().asSequence().toSet()
        if (actual != expected) throw invalidPayload("请求字段必须且只能是约定字段")
    }

    private fun requireTextFields(body: JsonNode, vararg fields: String) {
        fields.forEach { requiredText(body, it) }
    }

    private fun requiredText(body: JsonNode, field: String): String {
        val value = body.get(field)
        if (value == null || !value.isTextual) throw invalidPayload("$field 必须是字符串")
        return value.textValue()
    }

    private fun requireNullableText(body: JsonNode, field: String) {
        val value = body.get(field) ?: throw invalidPayload("$field 必须提交")
        if (!value.isNull && !value.isTextual) throw invalidPayload("$field 必须是字符串或 null")
    }

    private fun requireNullableTextArray(body: JsonNode, field: String) {
        val value = body.get(field) ?: throw invalidPayload("$field 必须提交")
        if (value.isNull) return
        if (!value.isArray || value.any { !it.isTextual }) {
            throw invalidPayload("$field 必须是字符串数组或 null")
        }
    }

    private fun requireNumber(body: JsonNode, field: String) {
        if (body.get(field)?.isNumber != true) throw invalidPayload("$field 必须是数字")
    }

    private fun requireIntegralNumber(body: JsonNode, field: String) {
        if (body.get(field)?.isIntegralNumber != true) throw invalidPayload("$field 必须是整数")
    }

    private fun requireBoolean(body: JsonNode, field: String) {
        if (body.get(field)?.isBoolean != true) throw invalidPayload("$field 必须是布尔值")
    }

    private fun <T> convert(body: JsonNode, type: Class<T>): T = try {
        objectMapper.treeToValue(body, type)
    } catch (e: JacksonException) {
        throw invalidPayload("请求字段值不正确", e)
    } catch (e: IllegalArgumentException) {
        throw invalidPayload("请求字段值不正确", e)
    }

    private fun invalidPayload(message: String, cause: Throwable? = null) =
        ProjectChangeContractException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            ProjectChangeErrorCode.PROJECT_PAYLOAD_INVALID,
            message,
            cause
        )

    private data class ProjectChangeReviewV2Request(
        val decision: String,
        val reviewNote: String,
        val force: Boolean,
        val forceBaseRevision: String?
    )

    private companion object {
        val joinFields = setOf(
            "requestType", "institutionProjectId", "serviceDescription", "priceSuggestion", "notes"
        )
        val leaveFields = setOf("requestType", "institutionProjectId")
        val profileFields = setOf(
            "requestType", "institutionProjectId", "baseRevision", "name", "category", "description", "tags",
            "slogan", "detailContent", "price", "salesCount", "doctorActive", "coverImage", "images", "notes"
        )
        val reviewFields = setOf("decision", "reviewNote", "force", "forceBaseRevision")
        val nullableTextFields = setOf(
            "name", "category", "description", "slogan", "detailContent", "coverImage"
        )
        val nullableTextArrayFields = setOf("tags", "images")
    }
}
