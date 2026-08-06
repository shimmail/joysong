package com.joysong.server.order.repository

import com.joysong.server.order.entity.OrderStatusLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

/**
 * 订单状态变更审计日志仓储
 *
 * @author joysong
 * @since 2026-07-30
 */
@Repository
interface OrderStatusLogRepository : JpaRepository<OrderStatusLogEntity, Long> {
    fun findByOrderIdOrderByCreatedAtDesc(orderId: String): List<OrderStatusLogEntity>
}
