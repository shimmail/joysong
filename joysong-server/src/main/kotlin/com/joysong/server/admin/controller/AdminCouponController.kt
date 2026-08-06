package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.coupon.dto.CouponResponse
import com.joysong.server.coupon.dto.CreateCouponRequest
import com.joysong.server.coupon.dto.IssueCouponRequest
import com.joysong.server.coupon.entity.CouponEntity
import com.joysong.server.coupon.service.CouponService
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*

/**
 * 管理后台优惠券控制器
 * 提供优惠券的创建、编辑、停用、发放、列表查询等管理接口
 *
 * @author joysong
 * @since 2026-07-30
 */
@RestController
@RequestMapping("/api/admin/coupons")
class AdminCouponController(
    private val couponService: CouponService
) {

    companion object {
        private val log = LoggerFactory.getLogger(AdminCouponController::class.java)
    }

    /**
     * 创建优惠券
     *
     * @param request 创建优惠券请求参数
     * @return 创建结果
     */
    @PostMapping
    fun createCoupon(@RequestBody request: CreateCouponRequest): BaseResponse<*> {
        return try {
            val coupon = CouponEntity(
                name = request.name,
                type = request.type,
                discountValue = request.discountValue,
                minAmount = request.minAmount ?: java.math.BigDecimal.ZERO,
                applicableProjectIds = request.applicableProjectIds,
                applicableInstitutionIds = request.applicableInstitutionIds,
                totalCount = request.totalCount ?: 0,
                startTime = request.startTime,
                endTime = request.endTime
            )
            val saved = couponService.saveCoupon(coupon)
            BaseResponse.success(CouponResponse.from(saved))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "创建优惠券失败")
        }
    }

    /**
     * 更新优惠券
     *
     * @param id      优惠券ID
     * @param request 更新请求参数
     * @return 更新结果
     */
    @PutMapping("/{id}")
    fun updateCoupon(
        @PathVariable id: Long,
        @RequestBody request: CreateCouponRequest
    ): BaseResponse<*> {
        return try {
            val coupon = CouponEntity(
                name = request.name,
                type = request.type,
                discountValue = request.discountValue,
                minAmount = request.minAmount ?: java.math.BigDecimal.ZERO,
                applicableProjectIds = request.applicableProjectIds,
                applicableInstitutionIds = request.applicableInstitutionIds,
                totalCount = request.totalCount ?: 0,
                startTime = request.startTime,
                endTime = request.endTime
            )
            val saved = couponService.updateCoupon(id, coupon)
            BaseResponse.success(CouponResponse.from(saved))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "更新优惠券失败")
        }
    }

    /**
     * 获取优惠券列表
     *
     * @param status 状态筛选（可选）
     * @return 优惠券列表
     */
    @GetMapping
    fun listCoupons(@RequestParam(required = false) status: String?): BaseResponse<*> {
        val coupons = couponService.listCoupons(status)
        return BaseResponse.success(coupons.map { CouponResponse.from(it) })
    }

    /**
     * 获取优惠券详情
     *
     * @param id 优惠券ID
     * @return 优惠券详情
     */
    @GetMapping("/{id}")
    fun getCoupon(@PathVariable id: Long): BaseResponse<*> {
        return try {
            val coupon = couponService.getCoupon(id)
            BaseResponse.success(CouponResponse.from(coupon))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "优惠券不存在", 404)
        }
    }

    /**
     * 停用优惠券
     *
     * @param id 优惠券ID
     * @return 更新结果
     */
    @PutMapping("/{id}/deactivate")
    fun deactivateCoupon(@PathVariable id: Long): BaseResponse<*> {
        return try {
            val coupon = couponService.deactivateCoupon(id)
            BaseResponse.success(CouponResponse.from(coupon))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "停用优惠券失败")
        }
    }

    /**
     * 发放优惠券
     * 向指定用户列表批量发放优惠券
     *
     * @param id      优惠券ID
     * @param request 发放请求（包含用户ID列表）
     * @return 成功发放数量
     */
    @PostMapping("/{id}/issue")
    fun issueCoupon(
        @PathVariable id: Long,
        @RequestBody request: IssueCouponRequest
    ): BaseResponse<*> {
        return try {
            val count = couponService.batchIssueCoupon(id, request.userIds)
            BaseResponse.success(mapOf("successCount" to count))
        } catch (e: IllegalArgumentException) {
            BaseResponse.error<Any>(e.message ?: "发放优惠券失败")
        }
    }
}
