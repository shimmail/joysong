package com.joysong.server.order.dto

data class CreateOrderRequest(
    val projectId: String,
    val institutionProjectId: String? = null,
    val doctorId: String = "",
    val quantity: Int = 1,
    val remark: String = "",
    /** 用户优惠券ID（可选），下单时使用优惠券抵扣 */
    val userCouponId: Long? = null,
    /** 预约时间（ISO-8601 格式，如 2026-08-01T10:00:00） */
    val appointmentTime: String? = null
)
