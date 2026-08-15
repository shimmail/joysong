package com.joysong.server.project.controller

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

@RestController
@RequestMapping("/api/management/project-requests")
class ProfessionalProjectRequestController(
    private val service: ProfessionalProjectRequestService,
    private val managementAccessService: ManagementAccessService,
    private val splitRatePolicy: OrderSplitRatePolicy
) {
    @GetMapping
    fun list(authentication: Authentication?): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.authenticatedActor(authentication)))

    @PostMapping("/platform")
    fun submitPlatform(
        authentication: Authentication?,
        @RequestBody request: DoctorPlatformProjectRequest
    ): BaseResponse<*> {
        require(!request.containsUnsupportedFields()) { "请求包含不支持的字段" }
        return BaseResponse.success(service.submitPlatform(managementAccessService.authenticatedActor(authentication), request))
    }

    @PostMapping("/institutions/{institutionId}")
    fun submitInstitution(
        authentication: Authentication?,
        @PathVariable institutionId: String,
        @RequestBody request: DoctorInstitutionProjectRequest
    ): BaseResponse<*> {
        require(!request.containsUnsupportedFields()) { "请求包含不支持的字段" }
        return BaseResponse.success(service.submitInstitution(managementAccessService.authenticatedActor(authentication), institutionId, request))
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
