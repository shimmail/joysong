package com.joysong.server.dm.service

import com.joysong.server.dm.dto.DmConversationResponse
import com.joysong.server.dm.dto.toResponse
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class OrderServiceConversationService(
    private val orderRepository: OrderRepository,
    private val conversationRepository: DmConversationRepository
) {
    @Transactional
    fun getOrCreate(orderId: String, userId: String): DmConversationResponse {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException(ACCESS_DENIED)
        requireActivatedService(order)
        requireOrderParticipant(order, userId)
        require(order.status in READABLE_STATUSES) { NOT_ACTIVE }

        val existing = conversationRepository.findByConversationTypeAndOrderId(
            DmConversationEntity.ORDER_SERVICE,
            orderId
        )
        if (existing != null) {
            requireConversationMatchesOrder(existing, order)
            return existing.toResponse()
        }

        require(order.status == OrderStatusEnum.SERVICE_ACTIVE.value) { NOT_ACTIVE }
        val conversation = DmConversationEntity(
            id = UUID.randomUUID().toString(),
            conversationType = DmConversationEntity.ORDER_SERVICE,
            orderId = order.id,
            userAId = minOf(order.userId, order.consultantId),
            userBId = maxOf(order.userId, order.consultantId),
            createdAt = LocalDateTime.now(),
            updatedAt = LocalDateTime.now()
        )
        return conversationRepository.saveAndFlush(conversation).toResponse()
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

    fun canRead(conversation: DmConversationEntity, userId: String): Boolean =
        try {
            requireReadAccess(conversation, userId)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    fun canHide(conversation: DmConversationEntity, userId: String): Boolean =
        try {
            authorize(conversation, userId, READABLE_STATUSES).status in HIDEABLE_STATUSES
        } catch (_: IllegalArgumentException) {
            false
        }

    private fun authorize(
        conversation: DmConversationEntity,
        userId: String,
        allowedStatuses: Set<String>,
        lockOrder: Boolean = false
    ): OrderEntity {
        require(conversation.conversationType == DmConversationEntity.ORDER_SERVICE) { ACCESS_DENIED }
        val orderId = conversation.orderId ?: throw IllegalArgumentException(ACCESS_DENIED)
        val order = if (lockOrder) {
            orderRepository.findByIdForUpdate(orderId)
        } else {
            orderRepository.findById(orderId).orElse(null)
        } ?: throw IllegalArgumentException(ACCESS_DENIED)
        requireActivatedService(order)
        require(order.status in allowedStatuses) { NOT_ACTIVE }
        requireOrderParticipant(order, userId)
        requireConversationMatchesOrder(conversation, order)
        return order
    }

    private fun requireActivatedService(order: OrderEntity) {
        require(
            order.paymentFlow == TRAVEL_GROUND_SERVICE_ONLY &&
                order.serviceActivatedAt != null &&
                order.consultantId.isNotBlank() &&
                order.consultantId != order.userId
        ) { NOT_ACTIVE }
    }

    private fun requireOrderParticipant(order: OrderEntity, userId: String) {
        require(userId == order.userId || userId == order.consultantId) { ACCESS_DENIED }
    }

    private fun requireConversationMatchesOrder(
        conversation: DmConversationEntity,
        order: OrderEntity
    ) {
        require(
            conversation.conversationType == DmConversationEntity.ORDER_SERVICE &&
                conversation.orderId == order.id &&
                conversation.userAId == minOf(order.userId, order.consultantId) &&
                conversation.userBId == maxOf(order.userId, order.consultantId)
        ) { ACCESS_DENIED }
    }

    companion object {
        private const val TRAVEL_GROUND_SERVICE_ONLY = "TRAVEL_GROUND_SERVICE_ONLY"
        private const val NOT_ACTIVE = "ORDER_SERVICE_NOT_ACTIVE"
        private const val ACCESS_DENIED = "ORDER_SERVICE_ACCESS_DENIED"
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
