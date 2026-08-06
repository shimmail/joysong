package com.joysong.server.coupon.entity

import java.time.LocalDateTime
import jakarta.persistence.*

/**
 * 用户优惠券实体
 *
 * @author joysong
 * @since 2026-07-30
 */
@Entity
@Table(name = "user_coupons")
class UserCouponEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** 用户ID */
    @Column(name = "user_id", nullable = false)
    var userId: String = "",

    /** 优惠券ID */
    @Column(name = "coupon_id", nullable = false)
    var couponId: Long = 0,

    /** 状态：UNUSED/USED/EXPIRED */
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "UNUSED",

    /** 使用时间 */
    @Column(name = "used_at")
    var usedAt: LocalDateTime? = null,

    /** 关联订单ID（使用后关联） */
    @Column(name = "order_id")
    var orderId: String? = null,

    /** 过期时间（从优惠券 end_time 继承） */
    @Column(name = "expire_at", nullable = false)
    var expireAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)
