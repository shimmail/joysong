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

@RestController
@RequestMapping("/api/admin/institution-project-requests")
class DoctorProjectChangeController(
    private val service: DoctorProjectChangeService,
    private val managementAccessService: ManagementAccessService
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
        @RequestBody request: DoctorProjectChangeRequest
    ): BaseResponse<*> = BaseResponse.success(
        service.submit(managementAccessService.actor(authentication), request)
    )

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
