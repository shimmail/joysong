package com.joysong.server.identity.controller

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.InstitutionMembershipAction
import com.joysong.server.identity.service.InstitutionMembershipRequestQueryService
import com.joysong.server.identity.service.InstitutionMembershipRequestService
import com.joysong.server.identity.service.InstitutionMembershipRequestView
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
import java.time.LocalDateTime

@RestController
@RequestMapping("/api/management/institution-membership-requests")
class InstitutionMembershipRequestController(
    private val accessService: ManagementAccessService,
    private val requestService: InstitutionMembershipRequestService,
    private val queryService: InstitutionMembershipRequestQueryService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<List<LegacyInstitutionMembershipRequestResponse>> =
        BaseResponse.success(
            queryService.listCompatibility(accessService.actor(authentication)).map { it.toLegacyResponse() }
        )

    @GetMapping("/owned")
    fun owned(authentication: Authentication): BaseResponse<List<InstitutionMembershipRequestResponse>> =
        BaseResponse.success(
            queryService.listOwned(accessService.actor(authentication)).map { it.toResponse() }
        )

    @GetMapping("/reviewable")
    fun reviewable(authentication: Authentication): BaseResponse<List<InstitutionMembershipRequestResponse>> =
        BaseResponse.success(
            queryService.listReviewable(accessService.actor(authentication)).map { it.toResponse() }
        )

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitInstitutionMembershipRequest
    ): BaseResponse<InstitutionMembershipRequestResponse> {
        require(request.unknownFields.isEmpty()) { "请求包含未知字段" }
        val type = MembershipRequestType.parse(request.requestType)
        val action = InstitutionMembershipAction.parse(request.action)
        return BaseResponse.success(
            requestService.submit(
                accessService.actor(authentication),
                type,
                request.institutionId,
                action,
                request.requestNote
            ).toResponse()
        )
    }

    @PostMapping("/{requestType}/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable requestType: String,
        @PathVariable id: String,
        @RequestBody request: ReviewInstitutionMembershipRequest
    ): BaseResponse<InstitutionMembershipRequestResponse> {
        require(request.unknownFields.isEmpty()) { "请求包含未知字段" }
        return BaseResponse.success(
            requestService.review(
                accessService.actor(authentication),
                MembershipRequestType.parse(requestType),
                id,
                MembershipRequestDecision.parse(request.decision),
                request.reviewNote
            ).toResponse()
        )
    }

    @PostMapping("/{requestType}/{id}/withdraw")
    fun withdraw(
        authentication: Authentication,
        @PathVariable requestType: String,
        @PathVariable id: String
    ): BaseResponse<InstitutionMembershipRequestResponse> = BaseResponse.success(
        requestService.withdraw(
            accessService.actor(authentication),
            MembershipRequestType.parse(requestType),
            id
        ).toResponse()
    )
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class SubmitInstitutionMembershipRequest(
    val requestType: String,
    val institutionId: String,
    val action: String,
    val requestNote: String = ""
) {
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }
}

@JsonIgnoreProperties(ignoreUnknown = false)
data class ReviewInstitutionMembershipRequest(
    val decision: String,
    val reviewNote: String = ""
) {
    val unknownFields: MutableMap<String, Any?> = linkedMapOf()

    @JsonAnySetter
    fun unknown(name: String, value: Any?) {
        unknownFields[name] = value
    }
}

data class InstitutionMembershipRequestResponse(
    val id: String,
    val requestType: String,
    val applicantId: String,
    val applicantName: String,
    val institutionId: String,
    val institutionName: String,
    val action: String,
    val status: String,
    val relationshipStatus: String,
    val requestNote: String,
    val reviewNote: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

data class LegacyInstitutionMembershipRequestResponse(
    val id: String,
    val requestType: String,
    val applicantId: String,
    val userId: String,
    val applicantName: String,
    val institutionId: String,
    val institutionName: String,
    val action: String,
    val status: String,
    val relationshipStatus: String,
    val requestNote: String,
    val reviewNote: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

private fun InstitutionMembershipRequestView.toResponse() = InstitutionMembershipRequestResponse(
    id,
    requestType.name,
    applicantId,
    applicantName,
    institutionId,
    institutionName,
    action,
    status,
    relationshipStatus,
    requestNote,
    reviewNote,
    submittedBy,
    reviewedBy,
    submittedAt,
    reviewedAt,
    createdAt,
    updatedAt
)

private fun InstitutionMembershipRequestView.toLegacyResponse() = LegacyInstitutionMembershipRequestResponse(
    id,
    requestType.name,
    applicantId,
    applicantId,
    applicantName,
    institutionId,
    institutionName,
    action,
    status,
    relationshipStatus,
    requestNote,
    reviewNote,
    submittedBy,
    reviewedBy,
    submittedAt,
    reviewedAt,
    createdAt,
    updatedAt
)
