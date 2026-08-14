package com.joysong.server.identity.controller

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
import com.joysong.server.identity.service.ManagementAccessService
import com.joysong.server.identity.service.MembershipRequestType
import org.springframework.security.core.Authentication
import org.springframework.security.access.AccessDeniedException
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
    private val requestService: InstitutionMembershipRequestService,
    private val queryService: InstitutionMembershipRequestQueryService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<List<ConsultantMembershipResponse>> {
        val actor = accessService.actor(authentication)
        if ("CONSULTANT" !in actor.activeRoles) {
            throw AccessDeniedException("只有本人已激活的顾问可以访问机构关系")
        }
        return BaseResponse.success(
            queryService.listConsultantCompatibility(actor)
                .map { it.toConsultantCompatibilityResponse() }
        )
    }

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitConsultantMembershipRequest
    ): BaseResponse<ConsultantMembershipResponse> {
        require(request.unknownFields.isEmpty()) { "请求包含未知字段" }
        return BaseResponse.success(
            requestService.submit(
                accessService.actor(authentication),
                MembershipRequestType.CONSULTANT,
                request.institutionId,
                InstitutionMembershipAction.JOIN,
                request.requestNote
            ).toConsultantCompatibilityResponse()
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class SubmitConsultantMembershipRequest(
    val institutionId: String,
    val requestNote: String
) {
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }
}

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

private fun InstitutionMembershipRequestView.toConsultantCompatibilityResponse() =
    ConsultantMembershipResponse(
        id = id,
        institutionId = institutionId,
        institutionName = institutionName,
        status = status,
        requestNote = requestNote,
        reviewNote = reviewNote,
        createdAt = createdAt,
        updatedAt = updatedAt,
        confirmedBy = reviewedBy,
        confirmedAt = reviewedAt.takeIf { action == "JOIN" && status == "APPROVED" },
        revokedAt = reviewedAt.takeIf {
            status == "REVOKED" || (action == "LEAVE" && status == "APPROVED")
        }
    )
