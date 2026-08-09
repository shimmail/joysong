package com.joysong.server.order.service

import com.joysong.server.common.apiSlice
import com.joysong.server.coupon.service.CouponService
import com.joysong.server.refund.entity.RefundEntity
import com.joysong.server.refund.repository.RefundRepository
import com.joysong.server.review.service.ReviewService
import com.joysong.server.discover.repository.DoctorProjectRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.institution.service.InstitutionProjectDetailResolver
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.doctor.repository.DoctorInstitutionRepository
import com.joysong.server.order.dto.CreateOrderRequest
import com.joysong.server.order.dto.OrderResponse
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.project.repository.ProjectRepository
import com.joysong.server.settlement.service.SettlementService
import jakarta.persistence.EntityManager
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.security.access.AccessDeniedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import com.joysong.server.payment.domain.Money
import com.joysong.server.refund.service.RefundExecutionService

/**
 * 订单核心业务服务
 * 负责订单的创建、状态流转（核验/完成/结算触发）及管理员操作
 *
 * @author joysong
 * @since 2026-07-30
 */
@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val projectRepository: ProjectRepository,
    private val institutionProjectRepository: InstitutionProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val doctorInstitutionProjectConfigRepository: DoctorInstitutionProjectConfigRepository,
    private val doctorProjectRepository: DoctorProjectRepository,
    private val doctorRepository: DoctorRepository,
    private val orderStatusLogService: OrderStatusLogService,
    private val couponService: CouponService,
    @Lazy private val settlementService: SettlementService,
    private val entityManager: EntityManager,
    @Lazy private val refundRepository: RefundRepository,
    private val institutionProjectDetailResolver: InstitutionProjectDetailResolver,
    @Lazy private val reviewService: ReviewService,
    private val institutionConsultantService: InstitutionConsultantService,
    private val doctorInstitutionRepository: DoctorInstitutionRepository,
    private val refundExecutionService: RefundExecutionService? = null
) {
    private val secureRandom = SecureRandom()

    companion object {
        private val log = LoggerFactory.getLogger(OrderService::class.java)

        /** 默认面诊金（无医生配置时使用） */
        private val DEFAULT_CONSULTATION_FEE = BigDecimal.ZERO

        /** 核销码长度（6位数字） */
        private const val VERIFY_CODE_LENGTH = 6

        /** 操作人类型：用户 */
        private const val OPERATOR_TYPE_USER = "USER"

        /** 操作人类型：机构 */
        private const val OPERATOR_TYPE_INSTITUTION = "INSTITUTION"

        /** 操作人类型：系统 */
        private const val OPERATOR_TYPE_SYSTEM = "SYSTEM"

        /** 退款状态：已批准 */
        private const val REFUND_STATUS_APPROVED = "APPROVED"
    }

    /**
     * 创建订单
     * 根据项目和机构项目信息创建订单，从医生-机构配置读取面诊金，无配置时使用默认值
     *
     * @param userId  当前用户ID
     * @param request 创建订单请求参数
     * @return 订单响应对象
     */
    @Transactional(rollbackFor = [Exception::class])
    fun createOrder(userId: String, request: CreateOrderRequest): OrderResponse {
        require(request.quantity in 1..99) { "项目数量必须在 1-99 之间" }
        require(request.remark.length <= 500) { "订单备注不能超过 500 字" }
        require(!request.institutionProjectId.isNullOrBlank()) { "订单必须关联机构项目" }
        require(request.doctorId.isNotBlank()) { "订单必须关联医生" }
        require(request.consultantId.isNotBlank()) { "订单必须关联医美顾问" }
        val project = projectRepository.findById(request.projectId)
            .orElseThrow { IllegalArgumentException("项目不存在: ${request.projectId}") }

        val orderNo = generateOrderNo()
        val now = LocalDateTime.now()

        val institutionProjectId = request.institutionProjectId.trim()
        val institutionProject = institutionProjectRepository.findById(institutionProjectId)
            .orElseThrow { IllegalArgumentException("机构项目不存在: $institutionProjectId") }
        require(institutionProject.isActive) { "机构项目已停用，暂不可预约" }
        require(institutionProject.projectId == project.id) { "机构项目与所选项目不一致" }
        val effectiveProject = institutionProjectDetailResolver.resolve(institutionProject, project)

        val institution = institutionRepository.findById(institutionProject.institutionId)
            .orElseThrow { IllegalArgumentException("机构不存在: ${institutionProject.institutionId}") }
        require(institution.name.isNotBlank()) { "机构名称不能为空" }

        require(
            doctorProjectRepository.existsByDoctorIdAndInstitutionProjectId(
                request.doctorId,
                institutionProject.id
            )
        ) { "所选医生未加入该机构项目" }
        require(
            doctorInstitutionRepository.findByDoctorIdOrderByCreatedAtAsc(request.doctorId)
                .any {
                    it.institutionId == institution.id &&
                        it.status == "APPROVED" &&
                        it.revokedAt == null
                }
        ) { "所选医生未取得该机构有效执业关系" }
        val doctor = doctorRepository.findById(request.doctorId)
            .orElseThrow { IllegalArgumentException("医生不存在") }
        require(doctor.name.isNotBlank()) { "医生名称不能为空" }

        val consultant = institutionConsultantService.requireApprovedConsultant(
            institution.id,
            request.consultantId
        )
        require(consultant.name.isNotBlank()) { "医美顾问名称不能为空" }

        val unitPrice = institutionProject.price
        val coverImage = effectiveProject.coverImage
        val configuredConsultationFee = doctorInstitutionProjectConfigRepository
            .findByDoctorIdAndInstitutionProjectId(request.doctorId, institutionProject.id)
            ?.consultationFee
            ?: DEFAULT_CONSULTATION_FEE

        val originalTotal = unitPrice.multiply(BigDecimal.valueOf(request.quantity.toLong()))

        // 优惠券必须属于当前用户且仍为 UNUSED、未过期；无效券不能静默降级为无券下单。
        val discountAmount: BigDecimal
        val couponId: Long?
        val userCouponId: Long?
        if (request.userCouponId != null) {
            val userCoupon = couponService.listUserAvailableCoupons(userId)
                .firstOrNull { it.id == request.userCouponId }
                ?: throw IllegalArgumentException("优惠券不存在、不属于当前用户或已失效")
            couponId = userCoupon.couponId
            userCouponId = userCoupon.id
            discountAmount = couponService.calculateDiscount(couponId, originalTotal)
        } else {
            couponId = null
            userCouponId = null
            discountAmount = BigDecimal.ZERO
        }

        // 应用优惠后的价格，不低于 0
        val discountedPrice = (originalTotal - discountAmount).max(BigDecimal.ZERO)
        val consultationFee = configuredConsultationFee.min(discountedPrice)
        // 剩余金额 = 优惠后价格 - 面诊金，不低于 0
        val remainingAmount = (discountedPrice - consultationFee).max(BigDecimal.ZERO)

        // 解析预约时间（支持 ISO-8601，兼容旧 Android 的空格分隔格式）；非法值不再静默丢弃。
        val appointmentTime: LocalDateTime? = request.appointmentTime?.takeIf { it.isNotBlank() }?.let {
            try {
                LocalDateTime.parse(it)
            } catch (e1: Exception) {
                try {
                    // 兼容 "yyyy-MM-dd HH:mm" 格式
                    LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                } catch (_: Exception) {
                    throw IllegalArgumentException("预约时间格式不正确，请使用 ISO-8601，例如 2026-08-01T10:00:00")
                }
            }
        }
        require(appointmentTime == null || appointmentTime.isAfter(now)) { "预约时间必须晚于当前时间" }

        val orderId = UUID.randomUUID().toString()

        val order = OrderEntity(
            id = orderId,
            userId = userId,
            projectName = effectiveProject.name,
            institutionName = institution.name,
            coverImage = coverImage,
            currency = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,
            price = discountedPrice,
            totalAmountMinor = Money.toMinor(discountedPrice, com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE),
            paidAmount = BigDecimal.ZERO,
            paidAmountMinor = 0,
            couponId = couponId,
            userCouponId = userCouponId,
            discountAmount = discountAmount,
            discountAmountMinor = Money.toMinor(discountAmount, com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE),
            status = OrderStatusEnum.PENDING_PAYMENT.value,
            createdAt = now,
            projectId = project.id,
            institutionId = institution.id,
            consultantId = consultant.id,
            consultantName = consultant.name,
            doctorId = request.doctorId,
            doctorName = doctor.name,
            orderNo = orderNo,
            consultationFee = consultationFee,
            consultationFeeMinor = Money.toMinor(consultationFee, com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE),
            remainingAmount = remainingAmount,
            remainingAmountMinor = Money.toMinor(remainingAmount, com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE),
            pricingCountry = "CN",
            quantity = request.quantity,
            remark = request.remark,
            institutionProjectId = institutionProject.id,
            appointmentTime = appointmentTime
        )

        val saved = orderRepository.save(order)
        userCouponId?.let { couponService.redeemCoupon(it, orderId) }
        orderStatusLogService.logTransition(
            orderId = saved.id,
            fromStatus = "",
            toStatus = OrderStatusEnum.PENDING_PAYMENT.value,
            operatorId = userId,
            operatorType = OPERATOR_TYPE_USER,
            remark = "创建订单"
        )
        log.info("用户[{}]创建订单[{}]成功, 订单号: {}", userId, saved.id, orderNo)
        return OrderResponse.from(saved)
    }

    /**
     * 获取用户的订单列表
     *
     * @param userId 用户ID
     * @return 订单列表
     */
    fun getOrdersByUser(userId: String): List<OrderEntity> {
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId)
    }

    fun getOrdersForManagement(actor: ManagementActor, status: String?, offset: Int, limit: Int): List<OrderResponse> {
        val normalizedStatus = status?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
        if (normalizedStatus != null) {
            require(OrderStatusEnum.fromValue(normalizedStatus) != null) { "订单状态无效" }
        }
        val visible = orderRepository.findAll()
            .asSequence()
            .filter { canManageOrder(actor, it) }
            .filter { normalizedStatus == null || it.status == normalizedStatus }
            .sortedByDescending { it.createdAt }
            .map(OrderResponse::forManagement)
            .toList()
        return visible.apiSlice(offset, limit)
    }

    fun requireOrderForManagement(actor: ManagementActor, orderId: String): OrderEntity {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在") }
        if (!canManageOrder(actor, order)) throw AccessDeniedException("无权管理该订单")
        return order
    }

    private fun canManageOrder(actor: ManagementActor, order: OrderEntity): Boolean =
        actor.isAdmin ||
            order.institutionId in actor.managedInstitutionIds ||
            (actor.doctorId != null && order.doctorId == actor.doctorId)

    /**
     * 按状态获取用户的订单列表
     *
     * @param userId 用户ID
     * @param status 订单状态
     * @return 符合条件的订单列表
     */
    fun getOrdersByUserAndStatus(userId: String, status: String): List<OrderEntity> {
        return orderRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, status)
    }

    /**
     * 获取单个订单详情（带用户鉴权）
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     * @return 订单实体，不存在或无权限返回 null
     */
    fun getOrderById(orderId: String, userId: String): OrderEntity? {
        val order = orderRepository.findById(orderId).orElse(null) ?: return null
        return if (order.userId == userId) order else null
    }

    /**
     * 生成核销码
     * 为订单生成6位随机核销码，供用户到店时核验使用
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     * @return 更新后的订单响应对象
     */
    @Transactional(rollbackFor = [Exception::class])
    fun requestVerification(orderId: String, userId: String): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在: $orderId") }
        require(order.userId == userId) { "无权操作该订单" }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        require(currentStatus == OrderStatusEnum.CONSULTATION_PAID) {
            "当前状态[${currentStatus.value}]不允许生成核销码"
        }

        val verifyCode = generateVerificationCode()
        val updated = orderRepository.save(
            order.copy(verifyCode = verifyCode, updatedAt = LocalDateTime.now())
        )
        log.info("订单[{}]生成核销码成功", orderId)
        return OrderResponse.from(updated)
    }

    private fun generateVerificationCode(): String =
        (secureRandom.nextInt(900000) + 100000).toString()

    /**
     * 确认到店核验
     * 机构端扫码确认用户到店，订单状态变为 VERIFIED
     *
     * @param orderId    订单ID
     * @param operatorId 操作人（机构）ID
     * @return 更新后的订单响应对象
     */
    @Transactional(rollbackFor = [Exception::class])
    fun confirmVerification(orderId: String, operatorId: String, verificationCode: String): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在: $orderId") }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        require(currentStatus == OrderStatusEnum.CONSULTATION_PAID) {
            "当前状态[${currentStatus.value}]不允许确认到店核验，需先支付面诊金"
        }
        require(order.verifyCode != null && order.verifyCode == verificationCode.trim()) { "核销码不正确" }

        val updated = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.VERIFIED.value,
                verifiedAt = LocalDateTime.now(),
                verifyCode = null,
                updatedAt = LocalDateTime.now()
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.VERIFIED.value,
            operatorId = operatorId,
            operatorType = OPERATOR_TYPE_INSTITUTION,
            remark = "确认到店核验"
        )
        log.info("订单[{}]确认到店核验成功, 操作人: {}", orderId, operatorId)
        return OrderResponse.from(updated)
    }

    /**
     * 机构申请项目完成
     * 机构执行完项目后申请完成，等待用户确认
     * 权限由管理端控制器按订单所属机构/医生校验。
     *
     * @param orderId 订单ID
     * @param operatorId  调用者用户ID
     * @return 更新后的订单响应对象
     */
    @Transactional(rollbackFor = [Exception::class])
    fun requestCompletion(orderId: String, operatorId: String, verificationCode: String): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在: $orderId") }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        require(currentStatus == OrderStatusEnum.BALANCE_PAID) {
            "当前状态[${currentStatus.value}]不允许申请完成，需先支付尾款"
        }
        require(order.verifyCode != null && order.verifyCode == verificationCode.trim()) { "核销码不正确" }

        val updated = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.PENDING_COMPLETION.value,
                completionRequestedAt = LocalDateTime.now(),
                verifyCode = null,
                updatedAt = LocalDateTime.now()
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.PENDING_COMPLETION.value,
            operatorId = operatorId,
            operatorType = OPERATOR_TYPE_INSTITUTION,
            remark = "机构申请项目完成"
        )
        log.info("订单[{}]机构申请完成, 操作人: {}", orderId, operatorId)
        return OrderResponse.from(updated)
    }

    /**
     * 用户确认项目完成
     * 用户确认项目已完成，触发创建结算记录，结算到期时间为30天后
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     * @return 更新后的订单响应对象
     */
    @Transactional(rollbackFor = [Exception::class])
    fun confirmCompletion(orderId: String, userId: String): OrderResponse {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在: $orderId") }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        require(currentStatus.canTransitionTo(OrderStatusEnum.COMPLETED)) {
            "当前状态[${currentStatus.value}]不允许确认完成"
        }
        require(order.userId == userId) { "无权操作该订单" }

        val settlementAt = LocalDateTime.now().plusDays(30)
        val now = LocalDateTime.now()
        val updated = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.COMPLETED.value,
                settlementAt = settlementAt,
                completedAt = now,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.COMPLETED.value,
            operatorId = userId,
            operatorType = OPERATOR_TYPE_USER,
            remark = "用户确认项目完成"
        )
        log.info("订单[{}]用户确认完成, 结算到期时间: {}", orderId, settlementAt)

        settlementService.saveSettlement(orderId)
        return OrderResponse.from(updated)
    }

    /**
     * 超时自动好评
     * 系统定时调用，对超时未评价的订单自动好评并进入待结算状态，同时创建结算记录
     *
     * @param orderId 订单ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun autoCompleteReview(orderId: String) {
        reviewService.submitAutomaticReview(orderId)
    }

    /**
     * 取消超时未支付的订单
     * 定时任务调用，取消 PENDING_PAYMENT 状态超过30分钟的订单
     */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelExpiredPendingOrder(orderId: String) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val currentStatus = OrderStatusEnum.fromValue(order.status) ?: return
        if (currentStatus != OrderStatusEnum.PENDING_PAYMENT) {
            return
        }
        orderRepository.save(
            order.copy(
                status = OrderStatusEnum.CANCELLED.value,
                updatedAt = LocalDateTime.now()
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.CANCELLED.value,
            operatorId = null,
            operatorType = OPERATOR_TYPE_SYSTEM,
            remark = "支付超时自动取消"
        )
        log.info("订单[{}]支付超时自动取消", orderId)
    }

    /**
     * 取消尾款超时未支付的订单
     * 定时任务调用，取消 VERIFIED 状态超过2小时的订单
     *
     * 处理逻辑：
     * 1. 如果用户已申请退款（refundStatus=PENDING），先将退款记录置为CANCELLED，重置订单退款状态
     * 2. 如果用户已支付面诊金（consultationFee > 0 且 paidAmount > 0），创建 APPROVED 退款记录
     * 3. 将订单状态设为 CANCELLED，同步退款状态和金额
     */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelBalanceTimeoutOrder(orderId: String) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val currentStatus = OrderStatusEnum.fromValue(order.status) ?: return
        if (currentStatus != OrderStatusEnum.VERIFIED) {
            return
        }
        val now = LocalDateTime.now()
        // 跟踪退款状态，Step 1 可能重置，供 Step 3 使用
        var effectiveRefundStatus = order.refundStatus
        var effectiveRefundAmount = order.refundAmount

        // Step 1: 如果用户已申请退款（PENDING），先取消退款申请
        if (order.refundStatus == "PENDING") {
            val refund = refundRepository.findFirstByOrderIdOrderByCreatedAtDesc(orderId)
            if (refund != null) {
                // 将 PENDING 退款记录置为 CANCELLED，保留审计记录
                refundRepository.save(
                    refund.copy(status = "CANCELLED", processedAt = now, updatedAt = now)
                )
                log.info("订单[{}]尾款超时取消前，将待审核退款记录[{}]置为CANCELLED", orderId, refund.id)
            }
            // 重置订单退款状态，避免残留
            effectiveRefundStatus = "NONE"
            effectiveRefundAmount = BigDecimal.ZERO
            orderRepository.save(
                order.copy(
                    refundStatus = effectiveRefundStatus,
                    refundAmount = effectiveRefundAmount,
                    updatedAt = now
                )
            )
            orderStatusLogService.logTransition(
                orderId = orderId,
                fromStatus = currentStatus.value,
                toStatus = currentStatus.value,
                operatorId = null,
                operatorType = OPERATOR_TYPE_SYSTEM,
                remark = "尾款超时取消前，取消待审核退款申请"
            )
        }

        // Step 2: 如果用户已支付面诊金，创建退款记录（APPROVED）
        if (order.consultationFee > BigDecimal.ZERO && order.paidAmount > BigDecimal.ZERO) {
            val refundAmount = order.paidAmount
            effectiveRefundStatus = REFUND_STATUS_APPROVED
            effectiveRefundAmount = refundAmount
            val refund = RefundEntity(
                id = UUID.randomUUID().toString(),
                refundNo = generateRefundNo(),
                orderId = orderId,
                userId = order.userId,
                currency = order.currency,
                amount = refundAmount,
                requestedAmountMinor = order.paidAmountMinor ?: Money.toMinor(refundAmount, order.currency),
                reason = "尾款支付超时自动取消",
                reasonCode = "BALANCE_PAYMENT_TIMEOUT",
                description = "VERIFIED状态超过2小时未支付尾款，系统自动取消并退还面诊金",
                status = REFUND_STATUS_APPROVED,
                createdAt = now,
                processedAt = now,
                originalStatus = order.status,
                orderNo = order.orderNo ?: "",
                projectName = order.projectName,
                paymentAmount = order.price,
                paymentTime = order.paymentTime,
                userPhone = order.userPhone,
                refundAmount = refundAmount,
                updatedAt = now,
                requestedAt = now
            )
            var savedRefund = refundRepository.save(refund)
            refundExecutionService?.execute(savedRefund)?.let { outcome ->
                savedRefund = refundRepository.save(savedRefund.copy(
                    refundedAmountMinor = outcome.refundedAmountMinor,
                    completedAt = now,
                    updatedAt = now
                ))
            }
            log.info("订单[{}]尾款超时取消，创建面诊金退款记录，退款金额: {}", orderId, refundAmount)
        }

        // Step 3: 取消订单，使用跟踪变量确保退款状态一致
        orderRepository.save(
            order.copy(
                status = OrderStatusEnum.CANCELLED.value,
                refundStatus = effectiveRefundStatus,
                refundAmount = effectiveRefundAmount,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.CANCELLED.value,
            operatorId = null,
            operatorType = OPERATOR_TYPE_SYSTEM,
            remark = "尾款支付超时自动取消"
        )

        // 退还优惠券
        if (order.userCouponId != null) {
            try {
                couponService.returnCoupon(order.userCouponId)
                log.info("退还优惠券成功[orderId={}, userCouponId={}]", orderId, order.userCouponId)
            } catch (e: Exception) {
                log.warn("退还优惠券失败[orderId={}, userCouponId={}]: {}", orderId, order.userCouponId, e.message)
            }
        }

        log.info("订单[{}]尾款支付超时自动取消", orderId)
    }

    /**
     * 取消超时未到店的 CONSULTATION_PAID 订单
     * 定时任务调用，取消 CONSULTATION_PAID 状态超过30天未到店核验的订单，自动取消并退款
     * CONSULTATION_PAID 阶段退款无需审核，直接批准，订单状态变为 REFUNDED
     */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelExpiredConsultationPaidOrder(orderId: String) {
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        val currentStatus = OrderStatusEnum.fromValue(order.status) ?: return
        if (currentStatus != OrderStatusEnum.CONSULTATION_PAID) {
            return
        }
        val now = LocalDateTime.now()
        val refundAmount = order.paidAmount

        // 创建退款记录（CONSULTATION_PAID 阶段无责退款，自动批准）
        val refund = RefundEntity(
            id = UUID.randomUUID().toString(),
            refundNo = generateRefundNo(),
            orderId = orderId,
            userId = order.userId,
            currency = order.currency,
            amount = refundAmount,
            requestedAmountMinor = order.paidAmountMinor ?: Money.toMinor(refundAmount, order.currency),
            reason = "超时未到店自动退款",
            reasonCode = "CONSULTATION_NO_SHOW_TIMEOUT",
            description = "面诊金支付后30天内未到店核验，系统自动退款",
            status = REFUND_STATUS_APPROVED,
            createdAt = now,
            processedAt = now,
            originalStatus = order.status,
            orderNo = order.orderNo ?: "",
            projectName = order.projectName,
            paymentAmount = order.price,
            paymentTime = order.paymentTime,
            userPhone = order.userPhone,
            refundAmount = refundAmount,
            updatedAt = now,
            requestedAt = now
        )
        var savedRefund = refundRepository.save(refund)
        refundExecutionService?.execute(savedRefund)?.let { outcome ->
            savedRefund = refundRepository.save(savedRefund.copy(
                refundedAmountMinor = outcome.refundedAmountMinor,
                completedAt = now,
                updatedAt = now
            ))
        }

        // 更新订单状态为 REFUNDED（与用户主动退款一致），写入退款信息
        orderRepository.save(
            order.copy(
                status = OrderStatusEnum.REFUNDED.value,
                refundStatus = REFUND_STATUS_APPROVED,
                refundAmount = refundAmount,
                updatedAt = now
            )
        )

        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.REFUNDED.value,
            operatorId = null,
            operatorType = OPERATOR_TYPE_SYSTEM,
            remark = "超时未到店自动退款"
        )

        // 退还优惠券
        if (order.userCouponId != null) {
            try {
                couponService.returnCoupon(order.userCouponId)
                log.info("退还优惠券成功[orderId={}, userCouponId={}]", orderId, order.userCouponId)
            } catch (e: Exception) {
                log.warn("退还优惠券失败[orderId={}, userCouponId={}]: {}", orderId, order.userCouponId, e.message)
            }
        }

        log.info("订单[{}]超时未到店自动退款，退款金额: {}", orderId, refundAmount)
    }

    // ---- 用户操作 ----

    /**
     * 取消待支付订单（物理删除）
     * 仅允许 PENDING_PAYMENT 状态的订单取消，使用原生 SQL 绕过 @SQLDelete 注解执行真正的 DELETE
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelOrder(orderId: String, userId: String) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { RuntimeException("订单不存在") }

        // 鉴权：确保当前用户拥有该订单
        if (order.userId != userId) {
            throw RuntimeException("无权操作此订单")
        }

        // 仅 PENDING_PAYMENT 状态可取消
        if (order.status != OrderStatusEnum.PENDING_PAYMENT.value) {
            throw RuntimeException("当前状态不允许取消")
        }

        order.userCouponId?.let { couponService.returnCoupon(it) }

        val now = LocalDateTime.now()
        orderRepository.save(
            order.copy(status = OrderStatusEnum.CANCELLED.value, updatedAt = now)
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = OrderStatusEnum.PENDING_PAYMENT.value,
            toStatus = OrderStatusEnum.CANCELLED.value,
            operatorId = userId,
            operatorType = OPERATOR_TYPE_USER,
            remark = "用户取消待支付订单"
        )

        log.info("用户[{}]取消订单[{}]", userId, orderId)
    }

    /**
     * 管理员物理删除订单（硬删除）
     * 支持所有状态的订单，使用原生 SQL 绕过 @SQLDelete 执行真正的 DELETE
     *
     * @param orderId 订单ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun adminHardDeleteOrder(orderId: String) {
        val order = orderRepository.findByIdIncludeDeleted(orderId)
            ?: throw RuntimeException("订单不存在")

        entityManager.createNativeQuery("DELETE FROM orders WHERE id = :id")
            .setParameter("id", orderId)
            .executeUpdate()

        log.info("管理员物理删除订单[{}]", orderId)
    }

    /**
     * 用户删除订单（软删除）
     * 仅允许删除已结束状态的订单：已取消、已完成、已退款、已结算
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     * @return 是否删除成功
     */
    @Transactional(rollbackFor = [Exception::class])
    fun deleteOrder(orderId: String, userId: String): Boolean {
        val order = orderRepository.findById(orderId).orElse(null) ?: return false
        require(order.userId == userId) { "无权操作该订单" }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        val deletableStatuses = setOf(
            OrderStatusEnum.CANCELLED,
            OrderStatusEnum.COMPLETED,
            OrderStatusEnum.REFUNDED,
            OrderStatusEnum.SETTLED,
            OrderStatusEnum.PENDING_SETTLEMENT
        )
        require(currentStatus in deletableStatuses) {
            "当前状态[${currentStatus.value}]不允许删除，仅已取消/已完成/已退款/已结算的订单可删除"
        }

        orderRepository.deleteById(orderId)
        log.info("用户[{}]删除订单[{}]", userId, orderId)
        return true
    }

    // ---- Admin 方法 ----

    /** 管理员查询所有订单（包含软删除） */
    fun adminListAll(): List<OrderEntity> = orderRepository.findAllIncludeDeleted()

    /** 管理员根据ID查询订单（包含软删除） */
    fun adminFindById(id: String): OrderEntity? = orderRepository.findByIdIncludeDeleted(id)

    /**
     * 管理员更新订单状态
     * 使用状态机校验状态转换合法性
     *
     * @param id     订单ID
     * @param status 目标状态字符串
     * @return 更新后的订单，不存在返回 null
     */
    @Transactional(rollbackFor = [Exception::class])
    fun adminUpdateStatus(id: String, status: String): OrderEntity? {
        val order = orderRepository.findById(id).orElse(null) ?: return null
        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单当前状态无效: ${order.status}")
        val targetStatus = OrderStatusEnum.fromValue(status)
            ?: throw IllegalArgumentException("目标状态无效: $status")
        require(currentStatus.canTransitionTo(targetStatus)) {
            "状态转换不合法：${currentStatus.value} -> ${targetStatus.value}"
        }
        val updated = orderRepository.save(
            order.copy(status = status, updatedAt = LocalDateTime.now())
        )
        orderStatusLogService.logTransition(
            orderId = id,
            fromStatus = currentStatus.value,
            toStatus = targetStatus.value,
            operatorId = null,
            operatorType = "ADMIN",
            remark = "管理员修改状态"
        )
        log.info("管理员将订单[{}]状态从[{}]更新为[{}]", id, currentStatus.value, targetStatus.value)
        return updated
    }

    /**
     * 管理员手动完成首次到店核销，仅用于后台测试。
     * 与机构核销保持相同的业务字段，但无需用户提供核销码。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun adminManualVerify(id: String, operatorId: String): OrderEntity {
        val order = orderRepository.findById(id)
            .orElseThrow { IllegalArgumentException("订单不存在: $id") }
        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单当前状态无效: ${order.status}")
        require(currentStatus == OrderStatusEnum.CONSULTATION_PAID) {
            "仅面诊金已支付的订单可以手动核销"
        }

        val now = LocalDateTime.now()
        val updated = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.VERIFIED.value,
                verifiedAt = now,
                verifyCode = null,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            orderId = id,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.VERIFIED.value,
            operatorId = operatorId,
            operatorType = "ADMIN",
            remark = "管理员手动完成机构核销（测试）"
        )
        log.info("管理员[{}]手动完成订单[{}]机构核销", operatorId, id)
        return updated
    }

    /** 管理员删除订单（软删除） */
    fun adminDeleteById(id: String) = orderRepository.deleteById(id)

    /** 管理员查询指定用户的所有订单（包含软删除） */
    fun adminFindByUserId(userId: String): List<OrderEntity> = orderRepository.findByUserIdIncludeDeleted(userId)

    /** 订单总数 */
    fun count(): Long = orderRepository.count()

    /**
     * 生成订单编号
     * 格式：JOY + yyyyMMddHHmmss + 4位随机数
     */
    private fun generateOrderNo(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val random = secureRandom.nextInt(9000) + 1000
        return "JOY$timestamp$random"
    }

    private fun generateRefundNo(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"))
        return "RFD$timestamp${UUID.randomUUID().toString().take(6).uppercase()}"
    }
}
