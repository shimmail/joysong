package com.joysong.server.identity.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.MembershipRequestDecision
import com.joysong.server.identity.service.MembershipRequestType
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/management/institution-membership-requests")
class InstitutionMembershipRequestController(
    private val managementAccessService: ManagementAccessService,
    private val requestService: InstitutionMembershipRequestService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> = BaseResponse.success(
        requestService.list(managementAccessService.actor(authentication))
    )

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitInstitutionMembershipRequest
    ): BaseResponse<*> = BaseResponse.success(
        requestService.submit(
            managementAccessService.actor(authentication),
            MembershipRequestType.parse(request.requestType),
            request.institutionId,
            request.requestNote
        )
    )

    @PostMapping("/{requestType}/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable requestType: String,
        @PathVariable id: String,
        @RequestBody request: ReviewInstitutionMembershipRequest
    ): BaseResponse<*> = BaseResponse.success(
        requestService.review(
            managementAccessService.actor(authentication),
            MembershipRequestType.parse(requestType),
            id,
            MembershipRequestDecision.parse(request.decision),
            request.reviewNote
        )
    )
}

data class SubmitInstitutionMembershipRequest(
    val requestType: String,
    val institutionId: String,
    val requestNote: String = ""
)

data class ReviewInstitutionMembershipRequest(
    val decision: String,
    val reviewNote: String = ""
)
