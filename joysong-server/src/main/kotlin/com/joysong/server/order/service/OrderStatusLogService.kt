package com.joysong.server.order.service

import com.joysong.server.order.entity.OrderStatusLogEntity
import com.joysong.server.order.repository.OrderStatusLogRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * 订单状态变更审计日志服务
 *
 * @author joysong
 * @since 2026-07-30
 */
@Service
class OrderStatusLogService(
    private val orderStatusLogRepository: OrderStatusLogRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderStatusLogService::class.java)
    }

    /**
     * 记录状态变更
     *
     * @param orderId      订单ID
     * @param fromStatus   变更前状态
     * @param toStatus     变更后状态
     * @param operatorId   操作人ID（字符串形式）
     * @param operatorType 操作人类型：USER/ADMIN/INSTITUTION/SYSTEM
     * @param remark       备注说明
     */
    fun logTransition(
        orderId: String,
        fromStatus: String,
        toStatus: String,
        operatorId: String?,
        operatorType: String,
        remark: String? = null
    ) {
        val entity = OrderStatusLogEntity(
            orderId = orderId,
            fromStatus = fromStatus,
            toStatus = toStatus,
            operatorId = operatorId,
            operatorType = operatorType,
            remark = remark
        )
        orderStatusLogRepository.save(entity)
        log.info("订单[{}]状态变更: {} -> {}, 操作人类型: {}", orderId, fromStatus, toStatus, operatorType)
    }

    /**
     * 查询订单状态变更历史
     *
     * @param orderId 订单ID
     * @return 按创建时间倒序排列的状态变更日志列表
     */
    fun listByOrderId(orderId: String): List<OrderStatusLogEntity> {
        return orderStatusLogRepository.findByOrderIdOrderByCreatedAtDesc(orderId)
    }
}
