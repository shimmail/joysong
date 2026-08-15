package com.joysong.server.project.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.project.service.DoctorInstitutionProjectRequest
import com.joysong.server.project.service.DoctorPlatformProjectRequest
import com.joysong.server.project.service.InstitutionProjectApplicationFormConfig
import com.joysong.server.project.service.ProfessionalProjectRequestService
import com.joysong.server.project.service.ProjectRequestReview
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

private fun ManagementAccessService.authenticatedActor(authentication: Authentication?): com.joysong.server.identity.service.ManagementActor =
    authentication?.let(::actor) ?: throw AccessDeniedException("请先登录")

private fun validateProjectRequestReview(request: ProjectRequestReview) {
    val decision = request.decision.trim().uppercase()
    require(decision in setOf("APPROVED", "REJECTED")) { "审核决定不正确" }
    require(decision != "REJECTED" || request.reviewNote.isNotBlank()) { "拒绝时必须填写审核意见" }
}

private val PLATFORM_REQUEST_FIELDS = setOf(
    "name", "category", "description", "referencePrice", "currency", "slogan", "salesCount",
    "coverImage", "images", "detailContent", "tags", "categoryTags", "notes"
)

private val INSTITUTION_REQUEST_FIELDS = setOf(
    "projectId", "name", "category", "description", "tags", "slogan", "detailContent", "price",
    "originalPrice", "currency", "coverImage", "images", "salesCount", "isActive", "consultationFee",
    "commissionRate", "institutionRate", "notes"
)

private fun <T> ObjectMapper.readExactRequest(
    request: ObjectNode,
    expectedFields: Set<String>,
    type: Class<T>
): T {
    require(request.fieldNames().asSequence().toSet() == expectedFields) {
        "请求字段不完整或包含不支持的字段"
    }
    return treeToValue(request, type)
}

@RestController
@RequestMapping("/api/management/project-requests")
class ProfessionalProjectRequestController(
    private val service: ProfessionalProjectRequestService,
    private val managementAccessService: ManagementAccessService,
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val objectMapper: ObjectMapper
) {
    @GetMapping
    fun list(authentication: Authentication?): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.authenticatedActor(authentication)))

    @PostMapping("/platform")
    fun submitPlatform(
        authentication: Authentication?,
        @RequestBody request: ObjectNode
    ): BaseResponse<*> {
        val typedRequest = objectMapper.readExactRequest(request, PLATFORM_REQUEST_FIELDS, DoctorPlatformProjectRequest::class.java)
        return BaseResponse.success(service.submitPlatform(managementAccessService.authenticatedActor(authentication), typedRequest))
    }

    @PostMapping("/institutions/{institutionId}")
    fun submitInstitution(
        authentication: Authentication?,
        @PathVariable institutionId: String,
        @RequestBody request: ObjectNode
    ): BaseResponse<*> {
        val typedRequest = objectMapper.readExactRequest(
            request,
            INSTITUTION_REQUEST_FIELDS,
            DoctorInstitutionProjectRequest::class.java
        )
        return BaseResponse.success(
            service.submitInstitution(managementAccessService.authenticatedActor(authentication), institutionId, typedRequest)
        )
    }

    @GetMapping("/institution-form-config")
    fun institutionFormConfig(authentication: Authentication?): BaseResponse<InstitutionProjectApplicationFormConfig> {
        val actor = managementAccessService.authenticatedActor(authentication)
        if (actor.doctorId == null || "DOCTOR" !in actor.activeRoles) {
            throw AccessDeniedException("只有已认证的在职医生可以查看项目申请配置")
        }
        return BaseResponse.success(InstitutionProjectApplicationFormConfig(splitRatePolicy.currentPlatformRate()))
    }

    @PostMapping("/{id}/review")
    fun reviewInstitution(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestBody request: ProjectRequestReview
    ): BaseResponse<*> {
        validateProjectRequestReview(request)
        return BaseResponse.success(service.reviewInstitution(managementAccessService.authenticatedActor(authentication), id, request))
    }
}

@RestController
@RequestMapping("/api/admin/project-requests")
class AdminProfessionalProjectRequestController(
    private val service: ProfessionalProjectRequestService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(authentication: Authentication?): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.authenticatedActor(authentication)))

    @PostMapping("/{id}/review")
    fun review(
        authentication: Authentication?,
        @PathVariable id: String,
        @RequestBody request: ProjectRequestReview
    ): BaseResponse<*> {
        validateProjectRequestReview(request)
        return BaseResponse.success(service.reviewPlatform(managementAccessService.authenticatedActor(authentication), id, request))
    }
}
