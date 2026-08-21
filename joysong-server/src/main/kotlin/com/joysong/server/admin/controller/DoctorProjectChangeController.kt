package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.institution.service.DoctorProjectChangeRequest
import com.joysong.server.institution.service.DoctorProjectChangeService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

@RestController
@RequestMapping("/api/admin/institution-project-requests")
class DoctorProjectChangeController(
    private val service: DoctorProjectChangeService,
    private val managementAccessService: ManagementAccessService,
    private val objectMapper: ObjectMapper
) {
    @GetMapping("/profile-update-targets")
    fun listProfileUpdateTargets(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.listProfileUpdateTargets(managementAccessService.actor(authentication)))

    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.actor(authentication)))

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody body: JsonNode
    ): BaseResponse<*> = BaseResponse.success(
        service.submit(managementAccessService.actor(authentication), parseRequest(body))
    )

    private fun parseRequest(body: JsonNode): DoctorProjectChangeRequest {
        require(body.isObject) { "请求内容格式不正确" }
        val type = body.path("requestType").takeIf { it.isTextual }?.asText()?.uppercase()
            ?: throw IllegalArgumentException("requestType 必须提交")
        if (type == "PROFILE_UPDATE") {
            val expected = setOf("institutionProjectId", "requestType", "serviceDescription", "priceSuggestion", "notes",
                "serviceTags", "scheduleNote", "coverImage", "images", "consultationFee", "commissionRate", "institutionRate", "medicalListPrice")
            val actual = body.fieldNames().asSequence().toSet()
            require(actual == expected) { "PROFILE_UPDATE 必须且仅能提交 13 个约定字段" }
        }
        return objectMapper.treeToValue(body, DoctorProjectChangeRequest::class.java)
    }

    @PostMapping("/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: ProjectChangeReviewRequest
    ): BaseResponse<*> = BaseResponse.success(
        service.review(
            managementAccessService.actor(authentication),
            id,
            request.decision,
            request.reviewNote,
            request.force
        )
    )

    @PostMapping("/{id}/withdraw")
    fun withdraw(authentication: Authentication, @PathVariable id: String): BaseResponse<*> =
        BaseResponse.success(service.withdraw(managementAccessService.actor(authentication), id))
}

data class ProjectChangeReviewRequest(
    val decision: String = "",
    val reviewNote: String = "",
    val force: Boolean? = null
)
