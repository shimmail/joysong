package com.joysong.server.identity.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipCandidatePage
import com.joysong.server.identity.service.InstitutionMembershipCandidateService
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.MembershipRequestType
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/management/institution-membership-candidates")
class InstitutionMembershipCandidateController(
    private val accessService: ManagementAccessService,
    private val candidateService: InstitutionMembershipCandidateService
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @RequestParam requestType: String,
        @RequestParam action: String,
        @RequestParam(defaultValue = "") query: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ): BaseResponse<InstitutionMembershipCandidatePage> = BaseResponse.success(
        candidateService.list(
            accessService.actor(authentication),
            MembershipRequestType.parse(requestType),
            InstitutionMembershipAction.parse(action),
            query,
            offset,
            limit
        )
    )
}
