package com.joysong.server.notification.service

import com.joysong.server.notification.dto.NotificationResponse
import java.util.Locale

internal object NotificationTextProjection {

    fun localize(notification: NotificationResponse, locale: Locale): NotificationResponse {
        if (!locale.language.equals("en", ignoreCase = true)) return notification
        val text = englishText(notification) ?: return notification
        return notification.copy(title = text.title, content = text.content)
    }

    private fun englishText(notification: NotificationResponse): NotificationText? =
        when (notification.type.trim().uppercase()) {
            "DM_NEW" -> directMessageText(notification)
            "ORDER_CREATED" -> NotificationText(
                "Order created",
                "Your order has been created. View the order details."
            )

            "ORDER_SERVICE_ACTIVATED" -> serviceActivatedText(notification.targetType)
            "ORDER_COMPLETED" -> NotificationText(
                "Order completed",
                "The user has confirmed the order as completed."
            )

            "ORDER_REFUND_REQUESTED" -> NotificationText(
                "Refund request submitted",
                "The order refund request has been submitted. View details."
            )

            "ORDER_REFUND_APPROVED" -> NotificationText(
                "Refund request approved",
                "The order refund request has been approved. View details."
            )

            "ORDER_REFUND_REJECTED" -> NotificationText(
                "Refund request rejected",
                rejectionContent(
                    notification.content,
                    chinesePrefix = "订单退款申请未通过",
                    englishPrefix = "The order refund request was rejected"
                )
            )

            "ORDER_REFUNDED" -> NotificationText(
                "Refund completed",
                "The order refund has been completed. View details."
            )

            "ORDER_CANCELLED" -> NotificationText(
                "Order cancelled",
                "The order has been cancelled. View the order details."
            )

            "PROFESSIONAL_APPLICATION_SUBMITTED" -> professionalText(
                notification.targetType,
                titleSuffix = "institution relationship application",
                content = { role -> "A new $role institution relationship application is awaiting review." },
                newTitle = true
            )

            "PROFESSIONAL_APPLICATION_WITHDRAWN" -> professionalText(
                notification.targetType,
                titleSuffix = "institution relationship application withdrawn",
                content = { role -> "A $role institution relationship application has been withdrawn." }
            )

            "PROFESSIONAL_APPLICATION_APPROVED" -> professionalText(
                notification.targetType,
                titleSuffix = "institution relationship application approved",
                content = { role -> "Your $role institution relationship application has been approved." }
            )

            "PROFESSIONAL_APPLICATION_REJECTED" -> professionalRejectionText(notification)
            "INSTITUTION_PROJECT_APPLICATION_SUBMITTED" -> NotificationText(
                "New institution project application",
                "A new institution project application is awaiting review."
            )

            "INSTITUTION_PROJECT_APPLICATION_APPROVED" -> NotificationText(
                "Institution project application approved",
                "Your institution project application has been approved."
            )

            "INSTITUTION_PROJECT_APPLICATION_REJECTED" -> NotificationText(
                "Institution project application rejected",
                rejectionContent(
                    notification.content,
                    chinesePrefix = "您的机构项目申请未通过",
                    englishPrefix = "Your institution project application was rejected"
                )
            )

            "IDENTITY_APPLICATION_APPROVED" -> NotificationText(
                "Identity verification approved",
                "Your identity verification application has been approved."
            )

            "IDENTITY_APPLICATION_REJECTED" -> NotificationText(
                "Identity verification rejected",
                rejectionContent(
                    notification.content,
                    chinesePrefix = "您的身份认证申请未通过",
                    englishPrefix = "Your identity verification application was rejected"
                )
            )

            "PROFESSIONAL_IDENTITY_REVOKED" -> professionalIdentityRevokedText(notification)
            "INSTITUTION_MEMBERSHIP_REVOKED" -> NotificationText(
                "Institution membership revoked",
                "Your institution membership was revoked by an administrator."
            )

            else -> null
        }

    private fun directMessageText(notification: NotificationResponse): NotificationText {
        val title = when (notification.title.trim()) {
            "新私信" -> "New direct message"
            "客服回复" -> "Customer service reply"
            "用户咨询" -> "User inquiry"
            else -> "New message"
        }
        val content = if (notification.content.trim() == "[图片]") "[Image]" else notification.content
        return NotificationText(title, content)
    }

    private fun serviceActivatedText(targetType: String): NotificationText? = when (targetType) {
        "order" -> NotificationText(
            "Travel service started",
            "Your order service has started. View the order details."
        )

        "order_service_conversation" -> NotificationText(
            "Travel service started",
            "The order service has started. Open the service conversation to follow up."
        )

        else -> null
    }

    private fun professionalText(
        targetType: String,
        titleSuffix: String,
        content: (String) -> String,
        newTitle: Boolean = false
    ): NotificationText? {
        val role = professionalRole(targetType) ?: return null
        val title = if (newTitle) "New $role $titleSuffix" else "${role.titlecase()} $titleSuffix"
        return NotificationText(title, content(role))
    }

    private fun professionalRejectionText(notification: NotificationResponse): NotificationText? {
        val role = professionalRole(notification.targetType) ?: return null
        val chineseRole = if (role == "doctor") "医生" else "咨询师"
        return NotificationText(
            "${role.titlecase()} institution relationship application rejected",
            rejectionContent(
                notification.content,
                chinesePrefix = "您的${chineseRole}机构关系申请未通过",
                englishPrefix = "Your $role institution relationship application was rejected"
            )
        )
    }

    private fun professionalIdentityRevokedText(notification: NotificationResponse): NotificationText? {
        val role = revokedIdentityRole(notification.targetId) ?: return null
        return NotificationText(
            "Professional identity revoked",
            rejectionContent(
                notification.content,
                chinesePrefix = "您的${role.chineseLabel}专业身份已被管理员撤销",
                englishPrefix = "Your professional identity as ${role.englishDescription} was revoked by an administrator"
            )
        )
    }

    private fun professionalRole(targetType: String): String? = when (targetType) {
        "professional_doctor_review",
        "professional_doctor_application",
        "professional_doctor_relationships" -> "doctor"

        "professional_consultant_review",
        "professional_consultant_application",
        "professional_consultant_relationships" -> "consultant"

        else -> null
    }

    private fun revokedIdentityRole(roleCode: String): RevokedIdentityRole? = when (roleCode.trim().uppercase()) {
        "DOCTOR" -> RevokedIdentityRole("医生", "a doctor")
        "CONSULTANT" -> RevokedIdentityRole("医美顾问", "a medical aesthetics consultant")
        "INSTITUTION_LEGAL_REPRESENTATIVE" -> RevokedIdentityRole("机构法人", "an institution legal representative")
        "INSTITUTION_CUSTOMER_SERVICE" -> RevokedIdentityRole("机构客服", "institution customer service")
        else -> null
    }

    private fun rejectionContent(source: String, chinesePrefix: String, englishPrefix: String): String {
        val normalized = source.trim()
        val reason = when {
            normalized.startsWith("$chinesePrefix：") -> normalized.removePrefix("$chinesePrefix：").trim()
            normalized.startsWith("$chinesePrefix:") -> normalized.removePrefix("$chinesePrefix:").trim()
            normalized == chinesePrefix || normalized == "$chinesePrefix。" || normalized == "$chinesePrefix." -> ""
            else -> return source
        }
        return if (reason.isEmpty()) "$englishPrefix." else "$englishPrefix. Reason: $reason"
    }

    private fun String.titlecase(): String = replaceFirstChar { first -> first.titlecase(Locale.ENGLISH) }

    private data class NotificationText(val title: String, val content: String)
    private data class RevokedIdentityRole(val chineseLabel: String, val englishDescription: String)
}
