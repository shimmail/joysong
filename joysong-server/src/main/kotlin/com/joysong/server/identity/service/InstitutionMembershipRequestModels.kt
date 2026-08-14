package com.joysong.server.identity.service

import java.time.LocalDateTime

enum class MembershipRequestType {
    DOCTOR,
    CONSULTANT;

    companion object {
        fun parse(value: String): MembershipRequestType = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: throw IllegalArgumentException("不支持的机构关系申请类型")
    }
}

enum class InstitutionMembershipAction {
    JOIN,
    LEAVE;

    companion object {
        fun parse(value: String): InstitutionMembershipAction = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: throw IllegalArgumentException("不支持的机构关系申请动作")
    }
}

enum class MembershipRequestDecision {
    APPROVED,
    REJECTED;

    companion object {
        fun parse(value: String): MembershipRequestDecision = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: throw IllegalArgumentException("不支持的审核决定")
    }
}

data class InstitutionMembershipRequestView(
    val id: String,
    val requestType: MembershipRequestType,
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

class InstitutionMembershipRequestNotFoundException(message: String) : RuntimeException(message)

class InstitutionMembershipRequestConflictException(message: String) : RuntimeException(message)

enum class ConsultantInstitutionAction {
    JOIN,
    LEAVE;

    companion object {
        fun parse(value: String): ConsultantInstitutionAction = entries.firstOrNull {
            it.name == value.trim().uppercase()
        } ?: throw IllegalArgumentException("不支持的顾问机构关系申请类型")
    }
}

enum class ConsultantInstitutionRequestStatus {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN
}

data class ConsultantInstitutionChangeRequestView(
    val id: String,
    val consultantId: String,
    val consultantName: String,
    val institutionId: String,
    val institutionName: String,
    val action: ConsultantInstitutionAction,
    val status: ConsultantInstitutionRequestStatus,
    val requestNote: String,
    val reviewNote: String,
    val submittedBy: String,
    val reviewedBy: String?,
    val submittedAt: LocalDateTime,
    val reviewedAt: LocalDateTime?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
)

class ConsultantInstitutionRequestNotFoundException(message: String) : RuntimeException(message)

class ConsultantInstitutionRequestConflictException(message: String) : RuntimeException(message)
