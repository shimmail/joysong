package com.joysong.server.identity.controller

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/management/consultant-memberships")
class ConsultantMembershipController(
    private val accessService: ManagementAccessService,
    private val membershipService: InstitutionMembershipRequestService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<List<ConsultantMembershipResponse>> =
        BaseResponse.success(membershipService.listOwnedConsultant(accessService.actor(authentication)).map { it.toResponse() })

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitConsultantMembershipRequest
    ): BaseResponse<ConsultantMembershipResponse> = BaseResponse.success(
        membershipService.submitConsultant(
            accessService.actor(authentication), request.institutionId, request.requestNote
        ).toResponse()
    )
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class SubmitConsultantMembershipRequest(
    val institutionId: String,
    val requestNote: String
)

data class ConsultantMembershipResponse(
    val id: String,
    val institutionId: String,
    val institutionName: String,
    val status: String,
    val requestNote: String,
    val reviewNote: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val confirmedBy: String?,
    val confirmedAt: LocalDateTime?,
    val revokedAt: LocalDateTime?
)

private fun InstitutionMembershipRequestView.toResponse() = ConsultantMembershipResponse(
    id, institutionId, institutionName, status, requestNote, reviewNote,
    createdAt, updatedAt, confirmedBy, confirmedAt, revokedAt
)
