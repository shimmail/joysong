package com.joysong.server.coupon.repository

import com.joysong.server.coupon.entity.CouponEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * 优惠券定义仓库
 *
 * @author joysong
 * @since 2026-07-30
 */
interface CouponRepository : JpaRepository<CouponEntity, Long> {

    /**
     * 按状态查询优惠券列表
     *
     * @param status 状态
     * @return 优惠券列表
     */
    fun findByStatus(status: String): List<CouponEntity>

    /**
     * 查询指定状态且已过期的优惠券列表
     *
     * @param status 状态
     * @param endTime 过期时间阈值
     * @return 过期优惠券列表
     */
    fun findByStatusAndEndTimeBefore(status: String, endTime: LocalDateTime): List<CouponEntity>
}
