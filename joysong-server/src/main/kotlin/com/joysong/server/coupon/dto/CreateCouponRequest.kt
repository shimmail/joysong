package com.joysong.server.coupon.dto

import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 创建优惠券请求DTO
 *
 * @author joysong
 * @since 2026-07-30
 */
data class CreateCouponRequest(
    /** 优惠券名称 */
    val name: String,

    /** 类型：FIXED(满减) / PERCENTAGE(折扣) */
    val type: String,

    /** 折扣值：满减为金额，折扣为百分比（如 15.00 表示 85 折） */
    val discountValue: BigDecimal,

    /** 最低消费金额（满减门槛），默认 0 */
    val minAmount: BigDecimal? = null,

    /** 适用项目ID列表（逗号分隔，null 表示全部适用） */
    val applicableProjectIds: String? = null,

    /** 适用机构ID列表（逗号分隔，null 表示全部适用） */
    val applicableInstitutionIds: String? = null,

    /** 发放总量（0 表示不限量） */
    val totalCount: Int? = null,

    /** 有效期开始 */
    val startTime: LocalDateTime,

    /** 有效期结束 */
    val endTime: LocalDateTime
)

/**
 * 批量发放优惠券请求DTO
 *
 * @author joysong
 * @since 2026-07-30
 */
data class IssueCouponRequest(
    /** 目标用户ID列表 */
    val userIds: List<String>
)
