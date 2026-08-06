package com.joysong.server.coupon.service

import com.joysong.server.coupon.entity.CouponEntity
import com.joysong.server.coupon.entity.UserCouponEntity
import com.joysong.server.coupon.repository.CouponRepository
import com.joysong.server.coupon.repository.UserCouponRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime

/**
 * 优惠券服务
 * 处理优惠券的创建、发放、核销、过期等核心业务
 *
 * @author joysong
 * @since 2026-07-30
 */
@Service
class CouponService(
    private val couponRepository: CouponRepository,
    private val userCouponRepository: UserCouponRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(CouponService::class.java)

        /** 优惠券状态：有效 */
        private const val STATUS_ACTIVE = "ACTIVE"

        /** 优惠券状态：已停用 */
        private const val STATUS_INACTIVE = "INACTIVE"

        /** 优惠券状态：已过期 */
        private const val STATUS_EXPIRED = "EXPIRED"

        /** 用户优惠券状态：未使用 */
        private const val USER_COUPON_UNUSED = "UNUSED"

        /** 用户优惠券状态：已使用 */
        private const val USER_COUPON_USED = "USED"

        /** 用户优惠券状态：已过期 */
        private const val USER_COUPON_EXPIRED = "EXPIRED"

        /** 优惠券类型：满减 */
        private const val TYPE_FIXED = "FIXED"

        /** 优惠券类型：折扣 */
        private const val TYPE_PERCENTAGE = "PERCENTAGE"

        /** 百分比除数 */
        private val PERCENTAGE_DIVISOR = BigDecimal("100")

        /** 不限量标识 */
        private const val UNLIMITED_TOTAL_COUNT = 0
    }

    /**
     * 创建优惠券
     * 管理员创建优惠券定义，校验参数合法性
     *
     * @param coupon 优惠券实体
     * @return 保存后的优惠券实体
     */
    fun saveCoupon(coupon: CouponEntity): CouponEntity {
        require(coupon.name.isNotBlank()) { "优惠券名称不能为空" }
        require(coupon.type in listOf(TYPE_FIXED, TYPE_PERCENTAGE)) { "优惠券类型无效：${coupon.type}" }
        require(coupon.discountValue > BigDecimal.ZERO) { "折扣值必须大于0" }
        require(coupon.endTime.isAfter(coupon.startTime)) { "结束时间必须晚于开始时间" }
        if (coupon.type == TYPE_PERCENTAGE) {
            require(coupon.discountValue <= PERCENTAGE_DIVISOR) { "百分比折扣值不能超过100" }
        }
        val saved = couponRepository.save(coupon)
        log.info("创建优惠券[id={}, name={}, type={}]", saved.id, saved.name, saved.type)
        return saved
    }

    /**
     * 更新优惠券
     * 仅允许修改未过期的优惠券
     *
     * @param id     优惠券ID
     * @param coupon 更新内容
     * @return 更新后的优惠券实体
     */
    fun updateCoupon(id: Long, coupon: CouponEntity): CouponEntity {
        val existing = getCoupon(id)
        require(existing.status != STATUS_EXPIRED) { "已过期优惠券不允许修改" }
        require(coupon.endTime.isAfter(LocalDateTime.now())) { "结束时间不能早于当前时间" }

        existing.name = coupon.name
        existing.type = coupon.type
        existing.discountValue = coupon.discountValue
        existing.minAmount = coupon.minAmount
        existing.applicableProjectIds = coupon.applicableProjectIds
        existing.applicableInstitutionIds = coupon.applicableInstitutionIds
        existing.totalCount = coupon.totalCount
        existing.startTime = coupon.startTime
        existing.endTime = coupon.endTime
        existing.updatedAt = LocalDateTime.now()

        val saved = couponRepository.save(existing)
        log.info("更新优惠券[id={}]", id)
        return saved
    }

    /**
     * 获取优惠券详情
     *
     * @param id 优惠券ID
     * @return 优惠券实体
     * @throws IllegalArgumentException 优惠券不存在时抛出
     */
    fun getCoupon(id: Long): CouponEntity {
        return couponRepository.findById(id)
            .orElseThrow { IllegalArgumentException("优惠券不存在: $id") }
    }

    /**
     * 获取优惠券列表
     *
     * @param status 状态筛选，null 表示查询全部
     * @return 优惠券列表
     */
    fun listCoupons(status: String?): List<CouponEntity> {
        return if (status.isNullOrBlank()) {
            couponRepository.findAll()
        } else {
            couponRepository.findByStatus(status)
        }
    }

    /**
     * 发放优惠券给用户
     * 检查库存（totalCount > 0 时检查 issuedCount < totalCount）、有效期、状态
     *
     * @param couponId 优惠券ID
     * @param userId   用户ID（字符串形式，内部转换为 Long）
     * @return 用户优惠券记录
     */
    @Transactional(rollbackFor = [Exception::class])
    fun issueCouponToUser(couponId: Long, userId: String): UserCouponEntity {
        val coupon = getCoupon(couponId)
        val now = LocalDateTime.now()

        require(coupon.status == STATUS_ACTIVE) { "优惠券当前不可用，状态: ${coupon.status}" }
        require(!now.isBefore(coupon.startTime) && !now.isAfter(coupon.endTime)) {
            "当前时间不在优惠券有效期内"
        }
        if (coupon.totalCount != UNLIMITED_TOTAL_COUNT) {
            require(coupon.issuedCount < coupon.totalCount) { "优惠券库存不足" }
        }

        val userCoupon = UserCouponEntity(
            userId = userId,
            couponId = couponId,
            status = USER_COUPON_UNUSED,
            expireAt = coupon.endTime
        )
        val saved = userCouponRepository.save(userCoupon)

        coupon.issuedCount++
        coupon.updatedAt = now
        couponRepository.save(coupon)

        log.info("发放优惠券[couponId={}, userId={}, userCouponId={}]", couponId, userId, saved.id)
        return saved
    }

    /**
     * 批量发放优惠券
     * 管理员操作，向多个用户发放指定优惠券
     *
     * @param couponId 优惠券ID
     * @param userIds  目标用户ID列表
     * @return 成功发放数量
     */
    @Transactional(rollbackFor = [Exception::class])
    fun batchIssueCoupon(couponId: Long, userIds: List<String>): Int {
        require(userIds.isNotEmpty()) { "用户ID列表不能为空" }
        var successCount = 0
        for (userId in userIds) {
            try {
                issueCouponToUser(couponId, userId)
                successCount++
            } catch (e: Exception) {
                log.warn("发放优惠券失败[couponId={}, userId={}]: {}", couponId, userId, e.message)
            }
        }
        log.info("批量发放优惠券[couponId={}, 总数={}, 成功={}]", couponId, userIds.size, successCount)
        return successCount
    }

    /**
     * 核销优惠券
     * 订单支付面诊金时调用，将用户优惠券标记为 USED
     *
     * @param userCouponId 用户优惠券记录ID
     * @param orderId      关联订单ID
     * @return 更新后的用户优惠券记录
     */
    @Transactional(rollbackFor = [Exception::class])
    fun redeemCoupon(userCouponId: Long, orderId: String): UserCouponEntity {
        val userCoupon = userCouponRepository.findById(userCouponId)
            .orElseThrow { IllegalArgumentException("用户优惠券不存在: $userCouponId") }
        require(userCoupon.status == USER_COUPON_UNUSED) { "优惠券状态不允许核销，当前状态: ${userCoupon.status}" }
        require(!LocalDateTime.now().isAfter(userCoupon.expireAt)) { "优惠券已过期" }

        val now = LocalDateTime.now()
        userCoupon.status = USER_COUPON_USED
        userCoupon.usedAt = now
        userCoupon.orderId = orderId
        val saved = userCouponRepository.save(userCoupon)

        val coupon = getCoupon(userCoupon.couponId)
        coupon.usedCount++
        coupon.updatedAt = now
        couponRepository.save(coupon)

        log.info("核销优惠券[userCouponId={}, orderId={}]", userCouponId, orderId)
        return saved
    }

    /**
     * 退还优惠券
     * 订单取消/退款时调用，将用户优惠券恢复为 UNUSED
     *
     * @param userCouponId 用户优惠券记录ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun returnCoupon(userCouponId: Long) {
        val userCoupon = userCouponRepository.findById(userCouponId)
            .orElseThrow { IllegalArgumentException("用户优惠券不存在: $userCouponId") }
        require(userCoupon.status == USER_COUPON_USED) { "仅已使用的优惠券可退还，当前状态: ${userCoupon.status}" }

        userCoupon.status = USER_COUPON_UNUSED
        userCoupon.usedAt = null
        userCoupon.orderId = null
        userCouponRepository.save(userCoupon)

        val coupon = getCoupon(userCoupon.couponId)
        if (coupon.usedCount > 0) {
            coupon.usedCount--
            coupon.updatedAt = LocalDateTime.now()
            couponRepository.save(coupon)
        }
        log.info("退还优惠券[userCouponId={}]", userCouponId)
    }

    /**
     * 查询用户可用优惠券
     * 返回用户持有的 UNUSED 且未过期的优惠券
     *
     * @param userId 用户ID
     * @return 可用优惠券列表
     */
    fun listUserAvailableCoupons(userId: String): List<UserCouponEntity> {
        val now = LocalDateTime.now()
        return userCouponRepository.findByUserIdAndStatus(userId, USER_COUPON_UNUSED)
            .filter { !now.isAfter(it.expireAt) }
    }

    /**
     * 查询用户所有优惠券
     *
     * @param userId 用户ID
     * @param status 状态筛选，null 表示查询全部
     * @return 用户优惠券列表
     */
    fun listUserCoupons(userId: String, status: String?): List<UserCouponEntity> {
        return if (status.isNullOrBlank()) {
            userCouponRepository.findByUserId(userId)
        } else {
            userCouponRepository.findByUserIdAndStatus(userId, status)
        }
    }

    /**
     * 计算优惠金额
     * 根据优惠券类型和订单金额计算实际优惠
     * - FIXED: discountValue（需检查 minAmount 门槛）
     * - PERCENTAGE: originalPrice * discountValue / 100（HALF_UP 舍入）
     *
     * @param couponId      优惠券ID
     * @param originalPrice 订单原始金额
     * @return 优惠金额，不满足条件时返回 BigDecimal.ZERO
     */
    fun calculateDiscount(couponId: Long, originalPrice: BigDecimal): BigDecimal {
        val coupon = getCoupon(couponId)
        val now = LocalDateTime.now()

        if (coupon.status != STATUS_ACTIVE) {
            log.warn("优惠券[id={}]状态非ACTIVE，当前状态: {}", couponId, coupon.status)
            return BigDecimal.ZERO
        }
        if (now.isBefore(coupon.startTime) || now.isAfter(coupon.endTime)) {
            log.warn("优惠券[id={}]不在有效期内", couponId)
            return BigDecimal.ZERO
        }

        return when (coupon.type) {
            TYPE_FIXED -> {
                if (originalPrice < coupon.minAmount) {
                    log.info("订单金额{}未达到满减门槛{}", originalPrice, coupon.minAmount)
                    BigDecimal.ZERO
                } else {
                    coupon.discountValue.min(originalPrice)
                }
            }
            TYPE_PERCENTAGE -> {
                originalPrice.multiply(coupon.discountValue)
                    .divide(PERCENTAGE_DIVISOR, 2, RoundingMode.HALF_UP)
            }
            else -> {
                log.warn("未知的优惠券类型: {}", coupon.type)
                BigDecimal.ZERO
            }
        }
    }

    /**
     * 处理过期优惠券
     * 定时任务调用，将已过期的 UNUSED 优惠券标记为 EXPIRED
     */
    @Transactional(rollbackFor = [Exception::class])
    fun expireOverdueCoupons() {
        val now = LocalDateTime.now()
        val expiredCoupons = couponRepository.findByStatusAndEndTimeBefore(STATUS_ACTIVE, now)
        for (coupon in expiredCoupons) {
            coupon.status = STATUS_EXPIRED
            coupon.updatedAt = now
            couponRepository.save(coupon)
        }

        val unusedExpired = userCouponRepository.findAll()
            .filter { it.status == USER_COUPON_UNUSED && now.isAfter(it.expireAt) }
        for (userCoupon in unusedExpired) {
            userCoupon.status = USER_COUPON_EXPIRED
            userCouponRepository.save(userCoupon)
        }

        log.info("过期处理完成：优惠券过期{}张，用户优惠券过期{}张", expiredCoupons.size, unusedExpired.size)
    }

    /**
     * 停用优惠券
     *
     * @param id 优惠券ID
     * @return 更新后的优惠券实体
     */
    fun deactivateCoupon(id: Long): CouponEntity {
        val coupon = getCoupon(id)
        require(coupon.status == STATUS_ACTIVE) { "仅有效优惠券可停用，当前状态: ${coupon.status}" }
        coupon.status = STATUS_INACTIVE
        coupon.updatedAt = LocalDateTime.now()
        val saved = couponRepository.save(coupon)
        log.info("停用优惠券[id={}]", id)
        return saved
    }
}
