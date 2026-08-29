package com.joysong.server.order.consultant

import com.joysong.server.common.OffsetPageRequest
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderContractException
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ConsultantOrderQueryService(
    private val orders: OrderRepository,
    private val conversations: DmConversationRepository,
    private val users: UserRepository,
    private val accessPolicy: ConsultantOrderAccessPolicy
) {
    @Transactional(readOnly = true)
    fun list(
        consultantId: String,
        query: ConsultantOrderListQuery
    ): ConsultantOrderPageResponse {
        accessPolicy.requireActiveConsultant(consultantId)
        val pageable = OffsetPageRequest(query.offset.toLong(), query.limit + 1)
        val fetched = when (query.stage) {
            ConsultantOrderStage.ACTIVE -> orders.findActiveConsultantOrders(
                consultantId,
                TRAVEL_FLOW,
                query.institutionId,
                pageable
            )

            ConsultantOrderStage.PAUSED,
            ConsultantOrderStage.HISTORY -> orders.findConsultantHistoryOrders(
                consultantId,
                TRAVEL_FLOW,
                query.stage.statuses,
                query.institutionId,
                pageable
            )
        }
        val visible = fetched.take(query.limit)
        val visibleOrderIds = visible.map(OrderEntity::id).toSet()
        val conversationOrderIds = visibleOrderIds
            .takeIf { it.isNotEmpty() }
            ?.let(conversations::findOrderServiceOrderIds)
            ?.toSet()
            ?: emptySet()
        val customers = users.findAllById(visible.map(OrderEntity::userId).distinct())
            .associateBy(UserEntity::id)

        return ConsultantOrderPageResponse(
            items = visible.map { order ->
                order.toSummary(
                    query.stage,
                    customers[order.userId],
                    order.id in conversationOrderIds
                )
            },
            offset = query.offset,
            limit = query.limit,
            hasMore = fetched.size > query.limit
        )
    }

    @Transactional(readOnly = true)
    fun detail(consultantId: String, orderId: String): ConsultantOrderDetailResponse {
        val order = orders.findById(orderId).orElse(null)
            ?: throw OrderContractException.consultantOrderNotFound()
        val stage = accessPolicy.requireWorkbenchOrder(order, consultantId)
        val hasConversation = conversations.findByConversationTypeAndOrderId(
            DmConversationEntity.ORDER_SERVICE,
            order.id
        ) != null
        return order.toDetail(stage, users.findByIdAnyState(order.userId), hasConversation)
    }

    private fun OrderEntity.toSummary(
        stage: ConsultantOrderStage,
        user: UserEntity?,
        hasConversation: Boolean
    ): ConsultantOrderSummaryResponse {
        val conversation = conversationAccess(stage, hasConversation)
        return ConsultantOrderSummaryResponse(
            id = id,
            orderNo = orderNo ?: "",
            stage = stage.name,
            status = status,
            refundStatus = refundStatus,
            project = ConsultantOrderProjectResponse(projectId, projectName, coverImage),
            institution = ConsultantOrderInstitutionResponse(institutionId, institutionName),
            customer = publicCustomer(user),
            appointmentTime = appointmentTime,
            updatedAt = updatedAt ?: createdAt,
            conversationReadable = conversation.readable,
            messageSendable = conversation.sendable,
            readOnly = !conversation.sendable
        )
    }

    private fun OrderEntity.toDetail(
        stage: ConsultantOrderStage,
        user: UserEntity?,
        hasConversation: Boolean
    ): ConsultantOrderDetailResponse {
        val conversation = conversationAccess(stage, hasConversation)
        return ConsultantOrderDetailResponse(
            id = id,
            orderNo = orderNo ?: "",
            stage = stage.name,
            status = status,
            refundStatus = refundStatus,
            project = ConsultantOrderProjectResponse(projectId, projectName, coverImage),
            institution = ConsultantOrderInstitutionResponse(institutionId, institutionName),
            customer = publicCustomer(user),
            doctor = ConsultantOrderDoctorResponse(doctorId.trim().takeIf(String::isNotEmpty), doctorName),
            appointmentTime = appointmentTime,
            remark = remark,
            createdAt = createdAt,
            updatedAt = updatedAt ?: createdAt,
            serviceActivatedAt = requireNotNull(serviceActivatedAt),
            completedAt = completedAt,
            conversationReadable = conversation.readable,
            messageSendable = conversation.sendable,
            readOnly = !conversation.sendable,
            conversation = conversation
        )
    }

    private fun conversationAccess(
        stage: ConsultantOrderStage,
        hasConversation: Boolean
    ): ConsultantOrderConversationResponse = when (stage) {
        ConsultantOrderStage.ACTIVE -> ConsultantOrderConversationResponse(readable = true, sendable = true)
        ConsultantOrderStage.PAUSED,
        ConsultantOrderStage.HISTORY -> ConsultantOrderConversationResponse(
            readable = hasConversation,
            sendable = false
        )
    }

    private fun publicCustomer(user: UserEntity?): ConsultantOrderCustomerResponse {
        if (
            user == null ||
            user.deletedAt != null ||
            user.accountState != AccountState.ACTIVE
        ) {
            return ConsultantOrderCustomerResponse("匿名用户", null)
        }
        return ConsultantOrderCustomerResponse(
            displayName = user.nickname.trim().ifEmpty { "用户" },
            avatar = user.avatar.trim().takeIf(String::isNotEmpty)
        )
    }

    private companion object {
        const val TRAVEL_FLOW = "TRAVEL_GROUND_SERVICE_ONLY"
    }
}
