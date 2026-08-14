package com.joysong.server.identity.service

import org.springframework.stereotype.Service

/**
 * The single mutation dispatcher for institution relationships.
 *
 * State transitions remain owned by the profession-specific ledger services; this facade only
 * parses the explicit protocol type and normalizes their projections.
 */
@Service
class InstitutionMembershipRequestService(
    private val doctorRequests: DoctorInstitutionChangeRequestService,
    private val consultantRequests: ConsultantInstitutionChangeRequestService
) {
    fun submit(
        actor: ManagementActor,
        type: MembershipRequestType,
        institutionId: String,
        action: InstitutionMembershipAction,
        requestNote: String
    ): InstitutionMembershipRequestView = when (type) {
        MembershipRequestType.DOCTOR -> doctorMutation {
            doctorRequests.submit(
                actor,
                institutionId,
                DoctorInstitutionAction.valueOf(action.name),
                requestNote
            ).toUnified()
        }

        MembershipRequestType.CONSULTANT -> consultantMutation {
            consultantRequests.submit(
                actor,
                institutionId,
                ConsultantInstitutionAction.valueOf(action.name),
                requestNote
            ).toUnified()
        }
    }

    fun withdraw(
        actor: ManagementActor,
        type: MembershipRequestType,
        id: String
    ): InstitutionMembershipRequestView = when (type) {
        MembershipRequestType.DOCTOR -> doctorMutation {
            if (!doctorRequests.exists(id)) {
                throw InstitutionMembershipRequestNotFoundException("医生机构关系申请不存在")
            }
            doctorRequests.withdraw(actor, id).toUnified()
        }

        MembershipRequestType.CONSULTANT -> consultantMutation {
            consultantRequests.withdraw(actor, id).toUnified()
        }
    }

    fun review(
        actor: ManagementActor,
        type: MembershipRequestType,
        id: String,
        decision: MembershipRequestDecision,
        reviewNote: String
    ): InstitutionMembershipRequestView = when (type) {
        MembershipRequestType.DOCTOR -> doctorMutation {
            if (!doctorRequests.exists(id)) {
                throw InstitutionMembershipRequestNotFoundException("医生机构关系申请不存在")
            }
            doctorRequests.review(actor, id, decision, reviewNote).toUnified()
        }

        MembershipRequestType.CONSULTANT -> consultantMutation {
            consultantRequests.review(
                actor,
                id,
                decision,
                reviewNote
            ).toUnified()
        }
    }

    private fun <T> doctorMutation(block: () -> T): T = try {
        block()
    } catch (error: InstitutionMembershipRequestNotFoundException) {
        throw error
    } catch (error: DoctorInstitutionRequestConflictException) {
        throw InstitutionMembershipRequestConflictException(
            error.message ?: "机构关系申请已被其他操作处理"
        )
    } catch (error: IllegalStateException) {
        throw InstitutionMembershipRequestConflictException(
            error.message ?: "机构关系申请已被其他操作处理"
        )
    } catch (error: IllegalArgumentException) {
        when (error.message) {
            "机构不存在或已删除", "机构不存在、未认证或已删除", "机构关系申请不存在" ->
                throw InstitutionMembershipRequestNotFoundException(error.message!!)

            "医生已加入该机构",
            "医生尚未加入该机构",
            "医生已不具备该机构的有效执业关系",
            "只有待审核的关系申请可以撤回",
            "只有待审核的关系申请可以审核" ->
                throw InstitutionMembershipRequestConflictException(error.message!!)

            else -> throw error
        }
    }

    private fun <T> consultantMutation(block: () -> T): T = try {
        block()
    } catch (error: ConsultantInstitutionRequestConflictException) {
        if (error.message.orEmpty().startsWith("机构不存在")) {
            throw InstitutionMembershipRequestNotFoundException(error.message!!)
        }
        throw error
    }
}

private fun DoctorInstitutionChangeRequestView.toUnified() = InstitutionMembershipRequestView(
    id = id,
    requestType = MembershipRequestType.DOCTOR,
    applicantId = doctorId,
    applicantName = doctorName,
    institutionId = institutionId,
    institutionName = institutionName,
    action = action.name,
    status = status.name,
    relationshipStatus = relationshipStatus(action.name, status.name),
    requestNote = requestNote,
    reviewNote = reviewNote,
    submittedBy = submittedBy,
    reviewedBy = reviewedBy,
    submittedAt = submittedAt,
    reviewedAt = reviewedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun ConsultantInstitutionChangeRequestView.toUnified() = InstitutionMembershipRequestView(
    id = id,
    requestType = MembershipRequestType.CONSULTANT,
    applicantId = consultantId,
    applicantName = consultantName,
    institutionId = institutionId,
    institutionName = institutionName,
    action = action.name,
    status = status.name,
    relationshipStatus = relationshipStatus(action.name, status.name),
    requestNote = requestNote,
    reviewNote = reviewNote,
    submittedBy = submittedBy,
    reviewedBy = reviewedBy,
    submittedAt = submittedAt,
    reviewedAt = reviewedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

private fun relationshipStatus(action: String, status: String): String = when (action) {
    InstitutionMembershipAction.JOIN.name ->
        if (status == "APPROVED") "APPROVED" else "NONE"

    InstitutionMembershipAction.LEAVE.name ->
        if (status == "APPROVED") "NONE" else "APPROVED"

    else -> "NONE"
}
