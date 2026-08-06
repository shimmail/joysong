package com.joysong.server.coupon.dto

import com.joysong.server.coupon.entity.CouponEntity
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 优惠券响应DTO
 *
 * @author joysong
 * @since 2026-07-30
 */
data class CouponResponse(
    val id: Long,
    val name: String,
    val type: String,
    val discountValue: BigDecimal,
    val minAmount: BigDecimal,
    val applicableProjectIds: String?,
    val applicableInstitutionIds: String?,
    val totalCount: Int,
    val issuedCount: Int,
    val usedCount: Int,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val status: String,
    val createdAt: LocalDateTime
) {
    companion object {
        /**
         * 从实体转换为响应DTO
         *
         * @param entity 优惠券实体
         * @return 优惠券响应DTO
         */
        fun from(entity: CouponEntity): CouponResponse = CouponResponse(
            id = entity.id,
            name = entity.name,
            type = entity.type,
            discountValue = entity.discountValue,
            minAmount = entity.minAmount,
            applicableProjectIds = entity.applicableProjectIds,
            applicableInstitutionIds = entity.applicableInstitutionIds,
            totalCount = entity.totalCount,
            issuedCount = entity.issuedCount,
            usedCount = entity.usedCount,
            startTime = entity.startTime,
            endTime = entity.endTime,
            status = entity.status,
            createdAt = entity.createdAt
        )
    }
}
