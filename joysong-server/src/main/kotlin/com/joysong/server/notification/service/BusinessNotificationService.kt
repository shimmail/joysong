package com.joysong.server.notification.service

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

enum class ProfessionalApplicantRole {
    DOCTOR,
    CONSULTANT
}

@Service
class BusinessNotificationService(
    private val notificationService: NotificationService,
    private val jdbcTemplate: JdbcTemplate
) {

    fun currentLegalRepresentativeIds(institutionId: String): Set<String> =
        jdbcTemplate.queryForList(
            """
            SELECT DISTINCT im.user_id
            FROM institution_memberships im
            JOIN user_roles ur ON ur.user_id = im.user_id
              AND ur.role_code = 'INSTITUTION_LEGAL_REPRESENTATIVE'
              AND ur.status = 'ACTIVE'
            JOIN users u ON u.id = im.user_id AND u.deleted_at IS NULL
            WHERE im.institution_id = ?
              AND im.member_role IN ('INSTITUTION_LEGAL_REPRESENTATIVE', 'LEGAL_REPRESENTATIVE')
              AND im.status = 'APPROVED'
              AND im.revoked_at IS NULL
            """.trimIndent(),
            String::class.java,
            institutionId
        ).map(String::trim).filter(String::isNotEmpty).toSet()

    fun orderCreated(orderId: String, userId: String, consultantId: String) =
        notify(
            recipients = listOf(userId, consultantId),
            type = "ORDER_CREATED",
            title = "订单已创建",
            content = "您的订单已创建，请及时查看订单详情。",
            targetType = "order",
            targetId = orderId
        )

    fun orderServiceActivated(orderId: String, userId: String, consultantId: String, doctorId: String) {
        notifyCandidates(
            candidates = listOf(
                NotificationCandidate(consultantId, "订单服务已开启，请进入服务会话跟进。", "order_service_conversation"),
                NotificationCandidate(userId, "您的订单服务已开启，请查看订单详情。", "order"),
                NotificationCandidate(doctorId, "您的订单服务已开启，请查看订单详情。", "order")
            ),
            type = "ORDER_SERVICE_ACTIVATED",
            title = "行程服务已开启",
            targetId = orderId
        )
    }

    fun orderCompleted(orderId: String, consultantId: String, doctorId: String, institutionId: String) =
        notify(
            recipients = listOf(consultantId, doctorId) + currentLegalRepresentativeIds(institutionId),
            type = "ORDER_COMPLETED",
            title = "订单已完成",
            content = "订单已由用户确认完成。",
            targetType = "order",
            targetId = orderId
        )

    fun orderRefundRequested(orderId: String, userId: String, consultantId: String, doctorId: String) =
        orderRefundNotification("ORDER_REFUND_REQUESTED", "退款申请已提交", "订单退款申请已提交，请查看详情。", orderId, userId, consultantId, doctorId)

    fun orderRefundApproved(orderId: String, userId: String, consultantId: String, doctorId: String) =
        orderRefundNotification("ORDER_REFUND_APPROVED", "退款申请已通过", "订单退款申请已通过，请查看详情。", orderId, userId, consultantId, doctorId)

    fun orderRefundRejected(orderId: String, userId: String, consultantId: String, doctorId: String, reviewNote: String) =
        orderRefundNotification(
            "ORDER_REFUND_REJECTED",
            "退款申请未通过",
            withReviewNote("订单退款申请未通过", reviewNote),
            orderId,
            userId,
            consultantId,
            doctorId
        )

    fun orderRefunded(orderId: String, userId: String, consultantId: String, doctorId: String) =
        notify(
            recipients = listOf(userId, consultantId, doctorId),
            type = "ORDER_REFUNDED",
            title = "退款已完成",
            content = "订单退款已完成，请查看详情。",
            targetType = "order",
            targetId = orderId
        )

    fun orderCancelled(orderId: String, userId: String, consultantId: String) =
        notify(
            recipients = listOf(userId, consultantId),
            type = "ORDER_CANCELLED",
            title = "订单已取消",
            content = "订单已取消，请查看订单详情。",
            targetType = "order",
            targetId = orderId
        )

    fun professionalApplicationSubmitted(institutionId: String, applicantRole: ProfessionalApplicantRole, requestId: String) =
        professionalReviewNotification(
            institutionId,
            applicantRole,
            requestId,
            "PROFESSIONAL_APPLICATION_SUBMITTED",
            "新的${applicantRole.label}机构关系申请",
            "有新的${applicantRole.label}机构关系申请待审核。"
        )

    fun professionalApplicationWithdrawn(institutionId: String, applicantRole: ProfessionalApplicantRole, requestId: String) =
        professionalReviewNotification(
            institutionId,
            applicantRole,
            requestId,
            "PROFESSIONAL_APPLICATION_WITHDRAWN",
            "${applicantRole.label}机构关系申请已撤回",
            "一项${applicantRole.label}机构关系申请已撤回。"
        )

    fun professionalApplicationApproved(applicantId: String, applicantRole: ProfessionalApplicantRole, requestId: String) =
        professionalApplicantNotification(
            applicantId,
            applicantRole,
            requestId,
            "PROFESSIONAL_APPLICATION_APPROVED",
            "${applicantRole.label}机构关系申请已通过",
            "您的${applicantRole.label}机构关系申请已通过。"
        )

    fun professionalApplicationRejected(
        applicantId: String,
        applicantRole: ProfessionalApplicantRole,
        requestId: String,
        reviewNote: String
    ) = professionalApplicantNotification(
        applicantId,
        applicantRole,
        requestId,
        "PROFESSIONAL_APPLICATION_REJECTED",
        "${applicantRole.label}机构关系申请未通过",
        withReviewNote("您的${applicantRole.label}机构关系申请未通过", reviewNote)
    )

    fun identityApplicationApproved(applicantId: String, applicationId: String) =
        notify(
            recipients = listOf(applicantId),
            type = "IDENTITY_APPLICATION_APPROVED",
            title = "身份认证已通过",
            content = "您的身份认证申请已通过。",
            targetType = "identity_management",
            targetId = applicationId
        )

    fun identityApplicationRejected(applicantId: String, applicationId: String, reviewNote: String) =
        notify(
            recipients = listOf(applicantId),
            type = "IDENTITY_APPLICATION_REJECTED",
            title = "身份认证未通过",
            content = withReviewNote("您的身份认证申请未通过", reviewNote),
            targetType = "identity_application",
            targetId = applicationId
        )

    private fun orderRefundNotification(
        type: String,
        title: String,
        content: String,
        orderId: String,
        userId: String,
        consultantId: String,
        doctorId: String
    ) = notify(
        recipients = listOf(userId, consultantId, doctorId),
        type = type,
        title = title,
        content = content,
        targetType = "order_refund",
        targetId = orderId
    )

    private fun professionalReviewNotification(
        institutionId: String,
        applicantRole: ProfessionalApplicantRole,
        requestId: String,
        type: String,
        title: String,
        content: String
    ) = notify(
        recipients = currentLegalRepresentativeIds(institutionId),
        type = type,
        title = title,
        content = content,
        targetType = applicantRole.reviewTargetType,
        targetId = requestId
    )

    private fun professionalApplicantNotification(
        applicantId: String,
        applicantRole: ProfessionalApplicantRole,
        requestId: String,
        type: String,
        title: String,
        content: String
    ) = notify(
        recipients = listOf(applicantId),
        type = type,
        title = title,
        content = content,
        targetType = applicantRole.applicationTargetType,
        targetId = requestId
    )

    private fun notify(
        recipients: Collection<String>,
        type: String,
        title: String,
        content: String,
        targetType: String,
        targetId: String
    ) = notifyCandidates(
        recipients.map { recipientId -> NotificationCandidate(recipientId, content, targetType) },
        type,
        title,
        targetId
    )

    private fun notifyCandidates(
        candidates: Collection<NotificationCandidate>,
        type: String,
        title: String,
        targetId: String
    ) {
        candidates.asSequence()
            .map { candidate -> candidate.copy(userId = candidate.userId.trim()) }
            .filter { candidate -> candidate.userId.isNotEmpty() }
            .distinctBy { candidate -> candidate.userId }
            .forEach { candidate ->
                notifyRecipient(candidate.userId, type, title, candidate.content, candidate.targetType, targetId)
            }
    }

    private fun notifyRecipient(
        recipientId: String,
        type: String,
        title: String,
        content: String,
        targetType: String,
        targetId: String
    ) {
        notificationService.createNotification(recipientId, type, title, content, targetType, targetId)
    }

    private fun withReviewNote(prefix: String, reviewNote: String): String =
        reviewNote.trim().takeIf(String::isNotEmpty)?.let { "$prefix：$it" } ?: "$prefix。"

    private val ProfessionalApplicantRole.label: String
        get() = when (this) {
            ProfessionalApplicantRole.DOCTOR -> "医生"
            ProfessionalApplicantRole.CONSULTANT -> "咨询师"
        }

    private val ProfessionalApplicantRole.reviewTargetType: String
        get() = when (this) {
            ProfessionalApplicantRole.DOCTOR -> "professional_doctor_review"
            ProfessionalApplicantRole.CONSULTANT -> "professional_consultant_review"
        }

    private val ProfessionalApplicantRole.applicationTargetType: String
        get() = when (this) {
            ProfessionalApplicantRole.DOCTOR -> "professional_doctor_application"
            ProfessionalApplicantRole.CONSULTANT -> "professional_consultant_application"
        }

    private data class NotificationCandidate(
        val userId: String,
        val content: String,
        val targetType: String
    )
}
