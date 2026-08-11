package com.joysong.server.project.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.project.service.InstitutionProjectRequestSubmission
import com.joysong.server.project.service.PlatformProjectRequestSubmission
import com.joysong.server.project.service.ProfessionalProjectRequestService
import com.joysong.server.project.service.ProjectRequestReview
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/management/project-requests")
class ProfessionalProjectRequestController(
    private val service: ProfessionalProjectRequestService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.actor(authentication)))

    @PostMapping("/platform")
    fun submitPlatform(
        authentication: Authentication,
        @RequestBody request: PlatformProjectRequestSubmission
    ): BaseResponse<*> = BaseResponse.success(
        service.submitPlatform(managementAccessService.actor(authentication), request)
    )

    @PostMapping("/institutions/{institutionId}")
    fun submitInstitution(
        authentication: Authentication,
        @PathVariable institutionId: String,
        @RequestBody request: InstitutionProjectRequestSubmission
    ): BaseResponse<*> = BaseResponse.success(
        service.submitInstitution(managementAccessService.actor(authentication), institutionId, request)
    )

    @PostMapping("/{id}/review")
    fun reviewInstitution(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: ProjectRequestReview
    ): BaseResponse<*> = BaseResponse.success(
        service.reviewInstitution(managementAccessService.actor(authentication), id, request)
    )
}

@RestController
@RequestMapping("/api/admin/project-requests")
class AdminProfessionalProjectRequestController(
    private val service: ProfessionalProjectRequestService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(service.list(managementAccessService.actor(authentication)))

    @PostMapping("/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable id: String,
        @RequestBody request: ProjectRequestReview
    ): BaseResponse<*> = BaseResponse.success(
        service.reviewPlatform(managementAccessService.actor(authentication), id, request)
    )
}
