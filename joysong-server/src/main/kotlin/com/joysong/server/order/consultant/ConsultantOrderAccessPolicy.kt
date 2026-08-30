package com.joysong.server.order.consultant

import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.service.OrderContractException
import org.springframework.stereotype.Service

@Service
class ConsultantOrderAccessPolicy(
    private val identities: IdentityAuthorizationService
) {
    fun requireActiveConsultant(userId: String) {
        if (!identities.hasActiveRole(userId, "CONSULTANT")) {
            throw OrderContractException.roleRequired()
        }
    }

    fun requireWorkbenchOrder(
        order: OrderEntity,
        consultantId: String
    ): ConsultantOrderStage {
        requireActiveConsultant(consultantId)
        return requireWorkbenchOrderForActiveConsultant(order, consultantId)
    }

    internal fun requireWorkbenchOrderForActiveConsultant(
        order: OrderEntity,
        consultantId: String
    ): ConsultantOrderStage {
        val stage = ConsultantOrderStage.fromStatus(order.status)
        if (
            order.consultantId != consultantId ||
            order.consultantId == order.userId ||
            order.paymentFlow != "TRAVEL_GROUND_SERVICE_ONLY" ||
            order.serviceActivatedAt == null ||
            stage == null
        ) {
            throw OrderContractException.consultantOrderNotFound()
        }
        return stage
    }

    fun requireConversationParticipant(order: OrderEntity, requesterId: String) {
        when (requesterId) {
            order.consultantId -> requireActiveConsultant(requesterId)
            order.userId -> Unit
            else -> throw OrderContractException.serviceAccessDenied()
        }
    }
}
