package com.joysong.server.dm.service

import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.dto.toResponse
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.order.consultant.ConsultantOrderAccessPolicy
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderContractException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class OrderServiceConversationService(
    private val orderRepository: OrderRepository,
    private val conversationRepository: DmConversationRepository,
    private val consultantAccessPolicy: ConsultantOrderAccessPolicy
) {
    @Transactional
    fun getOrCreate(orderId: String, userId: String): DmConversationResponse {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw OrderContractException.serviceAccessDenied()
        requireActivatedService(order)
        consultantAccessPolicy.requireConversationParticipant(order, userId)
        if (order.status !in READABLE_STATUSES) {
            throw OrderContractException.serviceNotActive()
        }

        val existing = conversationRepository.findByConversationTypeAndOrderId(
            DmConversationEntity.ORDER_SERVICE,
            orderId
        )
        if (existing != null) {
            requireConversationMatchesOrder(existing, order)
            return existing.toResponseFor(order)
        }

        if (order.status != OrderStatusEnum.SERVICE_ACTIVE.value) {
            throw OrderContractException.serviceReadOnly()
        }
        return conversationRepository.saveAndFlush(newConversation(order)).toResponseFor(order)
    }

    @Transactional(readOnly = true)
    fun requireReadAccess(conversation: DmConversationEntity, userId: String) {
        authorize(conversation, userId, READABLE_STATUSES)
    }

    @Transactional
    fun requireSendAccess(conversation: DmConversationEntity, userId: String) {
        authorize(
            conversation,
            userId,
            setOf(OrderStatusEnum.SERVICE_ACTIVE.value),
            lockOrder = true
        )
    }

    @Transactional(readOnly = true)
    fun responseIfReadable(
        conversation: DmConversationEntity,
        userId: String
    ): DmConversationResponse? =
        try {
            val order = authorize(conversation, userId, READABLE_STATUSES)
            conversation.toResponseFor(order)
        } catch (_: OrderContractException) {
            null
        }

    private fun authorize(
        conversation: DmConversationEntity,
        userId: String,
        allowedStatuses: Set<String>,
        lockOrder: Boolean = false
    ): OrderEntity {
        if (conversation.conversationType != DmConversationEntity.ORDER_SERVICE) {
            throw OrderContractException.serviceAccessDenied()
        }
        val orderId = conversation.orderId ?: throw OrderContractException.serviceAccessDenied()
        val order = if (lockOrder) {
            orderRepository.findByIdForUpdate(orderId)
        } else {
            orderRepository.findById(orderId).orElse(null)
        } ?: throw OrderContractException.serviceAccessDenied()
        requireActivatedService(order)
        if (order.status !in READABLE_STATUSES) {
            throw OrderContractException.serviceNotActive()
        }
        consultantAccessPolicy.requireConversationParticipant(order, userId)
        requireConversationMatchesOrder(conversation, order)
        if (order.status !in allowedStatuses) {
            throw OrderContractException.serviceReadOnly()
        }
        return order
    }

    private fun requireActivatedService(order: OrderEntity) {
        val activated = order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY &&
            order.serviceActivatedAt != null &&
            order.consultantId.isNotBlank() &&
            order.consultantId != order.userId
        if (!activated) throw OrderContractException.serviceNotActive()
    }

    private fun OrderEntity.isServiceMessagingEnabled(): Boolean =
        status == OrderStatusEnum.SERVICE_ACTIVE.value

    private fun DmConversationEntity.toResponseFor(order: OrderEntity): DmConversationResponse =
        toResponse(
            canHide = order.status in HIDEABLE_STATUSES,
            serviceMessagingEnabled = order.isServiceMessagingEnabled()
        )

    private fun requireConversationMatchesOrder(
        conversation: DmConversationEntity,
        order: OrderEntity
    ) {
        val matches = conversation.conversationType == DmConversationEntity.ORDER_SERVICE &&
            conversation.orderId == order.id &&
            conversation.userAId == minOf(order.userId, order.consultantId) &&
            conversation.userBId == maxOf(order.userId, order.consultantId)
        if (!matches) throw OrderContractException.serviceAccessDenied()
    }

    private fun newConversation(order: OrderEntity): DmConversationEntity = DmConversationEntity(
        id = UUID.randomUUID().toString(),
        conversationType = DmConversationEntity.ORDER_SERVICE,
        orderId = order.id,
        userAId = minOf(order.userId, order.consultantId),
        userBId = maxOf(order.userId, order.consultantId),
        createdAt = LocalDateTime.now(),
        updatedAt = LocalDateTime.now()
    )

    companion object {
        private const val TRAVEL_GROUND_SERVICE_ONLY = "TRAVEL_GROUND_SERVICE_ONLY"
        private val READABLE_STATUSES = setOf(
            OrderStatusEnum.SERVICE_ACTIVE.value,
            OrderStatusEnum.COMPLETED.value,
            OrderStatusEnum.REFUND_REVIEW.value,
            OrderStatusEnum.REFUND_PROCESSING.value,
            OrderStatusEnum.REFUNDED.value
        )
        private val HIDEABLE_STATUSES = setOf(
            OrderStatusEnum.COMPLETED.value,
            OrderStatusEnum.REFUNDED.value
        )
    }
}
