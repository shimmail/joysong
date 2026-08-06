package com.joysong.server.order.entity

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 订单状态变更审计日志实体
 * 只允许插入，不允许更新和删除
 *
 * @author joysong
 * @since 2026-07-30
 */
@Entity
@Table(name = "order_status_logs")
class OrderStatusLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** 关联订单ID */
    @Column(name = "order_id", nullable = false, length = 36)
    val orderId: String = "",

    /** 变更前状态 */
    @Column(name = "from_status", nullable = false, length = 30)
    val fromStatus: String = "",

    /** 变更后状态 */
    @Column(name = "to_status", nullable = false, length = 30)
    val toStatus: String = "",

    /** 操作人ID */
    @Column(name = "operator_id", length = 36)
    val operatorId: String? = null,

    /** 操作人类型：USER/ADMIN/INSTITUTION/SYSTEM */
    @Column(name = "operator_type", nullable = false, length = 20)
    val operatorType: String = "",

    /** 备注说明 */
    @Column(name = "remark", length = 500)
    val remark: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
