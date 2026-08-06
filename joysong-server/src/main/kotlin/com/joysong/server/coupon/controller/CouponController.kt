package com.joysong.server.coupon.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.coupon.dto.CouponResponse
import com.joysong.server.coupon.dto.UserCouponResponse
import com.joysong.server.coupon.service.CouponService
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal

/**
 * 用户端优惠券控制器
 * 提供用户可用优惠券查询、所有优惠券查询、优惠金额计算等接口
 *
 * @author joysong
 * @since 2026-07-30
 */
@RestController
@RequestMapping("/api/coupons")
class CouponController(
    private val couponService: CouponService
) {

    companion object {
        private val log = LoggerFactory.getLogger(CouponController::class.java)
    }

    /**
     * 查询用户可用优惠券
     * 返回当前用户持有的 UNUSED 且未过期的优惠券
     *
     * @param authentication JWT认证信息
     * @return 可用优惠券列表
     */
    @GetMapping("/available")
    fun listAvailableCoupons(authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        val userCoupons = couponService.listUserAvailableCoupons(userId)
        val responses = userCoupons.mapNotNull { uc ->
            try {
                val coupon = couponService.getCoupon(uc.couponId)
                UserCouponResponse.from(uc, coupon)
            } catch (e: IllegalArgumentException) {
                log.warn("用户优惠券[couponId={}]对应的优惠券不存在", uc.couponId)
                null
            }
        }
        return BaseResponse.success(responses)
    }

    /**
     * 查询用户所有优惠券
     *
     * @param authentication JWT认证信息
     * @param status         状态筛选（可选）：UNUSED/USED/EXPIRED
     * @return 用户优惠券列表
     */
    @GetMapping("/my")
    fun listMyCoupons(
        authentication: Authentication,
        @RequestParam(required = false) status: String?
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val userCoupons = couponService.listUserCoupons(userId, status)
        val responses = userCoupons.mapNotNull { uc ->
            try {
                val coupon = couponService.getCoupon(uc.couponId)
                UserCouponResponse.from(uc, coupon)
            } catch (e: IllegalArgumentException) {
                log.warn("用户优惠券[couponId={}]对应的优惠券不存在", uc.couponId)
                null
            }
        }
        return BaseResponse.success(responses)
    }

    /**
     * 计算优惠金额
     * 根据优惠券ID和订单原始金额计算实际优惠金额
     *
     * @param id            优惠券ID
     * @param originalPrice 订单原始金额
     * @return 优惠金额计算结果
     */
    @GetMapping("/{id}/discount")
    fun calculateDiscount(
        @PathVariable id: Long,
        @RequestParam originalPrice: BigDecimal
    ): BaseResponse<*> {
        require(originalPrice >= BigDecimal.ZERO) { "订单金额不能为负数" }
        val discount = couponService.calculateDiscount(id, originalPrice)
        return BaseResponse.success(
            mapOf(
                "couponId" to id,
                "originalPrice" to originalPrice.toPlainString(),
                "discountAmount" to discount.toPlainString()
            )
        )
    }
}
