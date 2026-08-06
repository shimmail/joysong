package com.joysong.server.coupon.repository

import com.joysong.server.coupon.entity.UserCouponEntity
import org.springframework.data.jpa.repository.JpaRepository

/**
 * 用户优惠券仓库
 *
 * @author joysong
 * @since 2026-07-30
 */
interface UserCouponRepository : JpaRepository<UserCouponEntity, Long> {

    /**
     * 按用户ID和状态查询优惠券列表
     *
     * @param userId 用户ID
     * @param status 状态
     * @return 用户优惠券列表
     */
    fun findByUserIdAndStatus(userId: String, status: String): List<UserCouponEntity>

    /**
     * 按用户ID查询所有优惠券
     *
     * @param userId 用户ID
     * @return 用户优惠券列表
     */
    fun findByUserId(userId: String): List<UserCouponEntity>

    /**
     * 按订单ID查询关联的用户优惠券
     *
     * @param orderId 订单ID
     * @return 用户优惠券
     */
    fun findByOrderId(orderId: String): UserCouponEntity?

    /**
     * 按优惠券ID查询已发放的用户优惠券列表
     *
     * @param couponId 优惠券ID
     * @return 用户优惠券列表
     */
    fun findByCouponId(couponId: Long): List<UserCouponEntity>

    /**
     * 统计指定优惠券的发放数量
     *
     * @param couponId 优惠券ID
     * @return 发放数量
     */
    fun countByCouponId(couponId: Long): Long
}
