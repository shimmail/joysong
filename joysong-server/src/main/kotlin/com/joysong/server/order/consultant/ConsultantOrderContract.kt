package com.joysong.server.order.consultant

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.service.OrderContractException

enum class ConsultantOrderStage(val statuses: Set<String>) {
    ACTIVE(setOf(OrderStatusEnum.SERVICE_ACTIVE.value)),
    PAUSED(
        setOf(
            OrderStatusEnum.REFUND_REVIEW.value,
            OrderStatusEnum.REFUND_PROCESSING.value
        )
    ),
    HISTORY(
        setOf(
            OrderStatusEnum.COMPLETED.value,
            OrderStatusEnum.REFUNDED.value
        )
    );

    companion object {
        fun parse(raw: String?): ConsultantOrderStage {
            val wire = raw ?: ACTIVE.name
            return entries.firstOrNull { it.name == wire }
                ?: throw OrderContractException.invalidQuery()
        }

        fun fromStatus(status: String): ConsultantOrderStage? =
            entries.firstOrNull { status in it.statuses }
    }
}

data class ConsultantOrderListQuery(
    val stage: ConsultantOrderStage,
    val institutionId: String?,
    val offset: Int,
    val limit: Int
) {
    companion object {
        fun parse(
            stage: String?,
            institutionId: String?,
            offset: String?,
            limit: String?
        ): ConsultantOrderListQuery {
            val parsedOffset = (offset ?: "0").toIntOrNull()
                ?: throw OrderContractException.invalidQuery()
            val parsedLimit = (limit ?: "20").toIntOrNull()
                ?: throw OrderContractException.invalidQuery()
            if (parsedOffset < 0 || parsedLimit !in 1..100) {
                throw OrderContractException.invalidQuery()
            }
            return ConsultantOrderListQuery(
                stage = ConsultantOrderStage.parse(stage),
                institutionId = institutionId?.trim()?.takeIf(String::isNotEmpty),
                offset = parsedOffset,
                limit = parsedLimit
            )
        }
    }
}
