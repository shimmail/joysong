package com.joysong.server.identity.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.DoctorInstitutionAction
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestService
import com.joysong.server.identity.service.DoctorInstitutionChangeRequestView
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
    private val managementAccessService: ManagementAccessService,
    private val requestService: InstitutionMembershipRequestService,
    private val doctorRequestService: DoctorInstitutionChangeRequestService
) {
    @GetMapping
    fun list(authentication: Authentication): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val consultantRequests = requestService.list(actor)
            .asSequence()
            .filter { it.requestType == MembershipRequestType.CONSULTANT }
            .map(InstitutionMembershipRequestView::toResponse)
        val doctorRequests = doctorRequestService.list(actor).asSequence()
            .map(DoctorInstitutionChangeRequestView::toResponse)
        return BaseResponse.success(
            (consultantRequests + doctorRequests).sortedWith(
                compareByDescending<InstitutionMembershipRequestResponse> { it.createdAt }.thenByDescending { it.id }
            ).toList()
        )
    }

    @PostMapping
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitInstitutionMembershipRequest
    ): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        return when (val type = MembershipRequestType.parse(request.requestType)) {
            MembershipRequestType.DOCTOR -> BaseResponse.success(
                doctorRequestService.submit(
                    actor,
                    request.institutionId,
                    DoctorInstitutionAction.parse(request.action),
                    request.requestNote
                ).toResponse()
            )

            MembershipRequestType.CONSULTANT -> {
                require(request.action.isBlank() || request.action.trim().equals("JOIN", ignoreCase = true)) {
                    "顾问机构申请仅支持加入"
                }
                BaseResponse.success(
                    requestService.submit(actor, type, request.institutionId, request.requestNote).toResponse()
                )
            }
        }
    }

    @PostMapping("/{requestType}/{id}/review")
    fun review(
        authentication: Authentication,
        @PathVariable requestType: String,
        @PathVariable id: String,
        @RequestBody request: ReviewInstitutionMembershipRequest
    ): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val type = MembershipRequestType.parse(requestType)
        val decision = MembershipRequestDecision.parse(request.decision)
        return when (type) {
            MembershipRequestType.DOCTOR -> BaseResponse.success(
                doctorRequestService.review(actor, id, decision, request.reviewNote).toResponse()
            )

            MembershipRequestType.CONSULTANT -> BaseResponse.success(
                requestService.review(actor, type, id, decision, request.reviewNote).toResponse()
            )
        }
    }

    @PostMapping("/{requestType}/{id}/withdraw")
    fun withdraw(
        authentication: Authentication,
        @PathVariable requestType: String,
        @PathVariable id: String
    ): BaseResponse<*> {
        val type = MembershipRequestType.parse(requestType)
        require(type == MembershipRequestType.DOCTOR) { "顾问加入申请暂不支持撤回" }
        return BaseResponse.success(
            doctorRequestService.withdraw(managementAccessService.actor(authentication), id).toResponse()
        )
    }
}

data class SubmitInstitutionMembershipRequest(
    val requestType: String,
    val institutionId: String,
    val requestNote: String = "",
    val action: String = "JOIN"
)

data class ReviewInstitutionMembershipRequest(
    val decision: String,
    val reviewNote: String = ""
)

data class InstitutionMembershipRequestResponse(
    val id: String,
    val requestType: String,
    val userId: String,
    val institutionId: String,
    val status: String,
    val requestNote: String,
    val reviewNote: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val deleted: Boolean = false,
    val action: String = "JOIN"
)

private fun InstitutionMembershipRequestView.toResponse() = InstitutionMembershipRequestResponse(
    id = id,
    requestType = requestType.name,
    userId = userId,
    institutionId = institutionId,
    status = status,
    requestNote = requestNote,
    reviewNote = reviewNote,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deleted = deleted
)

private fun DoctorInstitutionChangeRequestView.toResponse() = InstitutionMembershipRequestResponse(
    id = id,
    requestType = MembershipRequestType.DOCTOR.name,
    userId = doctorId,
    institutionId = institutionId,
    status = status.name,
    requestNote = requestNote,
    reviewNote = reviewNote,
    createdAt = createdAt,
    updatedAt = updatedAt,
    action = action.name
)
