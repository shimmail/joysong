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
import com.joysong.server.common.OffsetPageRequest
import com.joysong.server.identity.service.InstitutionConsultantService
import com.joysong.server.identity.service.DoctorInstitutionRelationshipService
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
import org.springframework.data.domain.PageRequest
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
    private val doctorInstitutionRelationshipService: DoctorInstitutionRelationshipService,
    private val travelGroundServicePricing: TravelGroundServicePricing,
    private val refundExecutionService: RefundExecutionService? = null
) {
    private val secureRandom = SecureRandom()

    companion object {
        private val log = LoggerFactory.getLogger(OrderService::class.java)

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

        private const val TRAVEL_GROUND_SERVICE_PAYMENT_FLOW = "TRAVEL_GROUND_SERVICE_ONLY"
        private const val MEDICAL_PAYMENT_NOT_SUPPORTED = "MEDICAL_PAYMENT_NOT_SUPPORTED"

        private val LEGACY_MEDICAL_STATUSES = setOf(
            OrderStatusEnum.PENDING_PAYMENT,
            OrderStatusEnum.CONSULTATION_PAID,
            OrderStatusEnum.VERIFIED,
            OrderStatusEnum.BALANCE_PAID,
            OrderStatusEnum.PENDING_COMPLETION,
            OrderStatusEnum.COMPLETED,
            OrderStatusEnum.PENDING_SETTLEMENT,
            OrderStatusEnum.SETTLED
        )
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

        doctorProjectRepository.findByDoctorIdAndInstitutionProjectId(
            request.doctorId,
            institutionProject.id
        ) ?: throw IllegalArgumentException("所选医生未加入该机构项目")
        doctorInstitutionRelationshipService.requireActiveRelationshipForUpdate(request.doctorId, institution.id)
        val doctor = doctorRepository.findById(request.doctorId)
            .orElseThrow { IllegalArgumentException("医生不存在") }
        require(doctor.name.isNotBlank()) { "医生名称不能为空" }

        val consultant = institutionConsultantService.requireApprovedConsultant(
            institution.id,
            request.consultantId
        )
        require(consultant.name.isNotBlank()) { "医美顾问名称不能为空" }

        val coverImage = effectiveProject.coverImage
        val config = doctorInstitutionProjectConfigRepository
            .findByDoctorIdAndInstitutionProjectId(request.doctorId, institutionProject.id)
            ?: throw IllegalArgumentException("MEDICAL_LIST_PRICE_NOT_CONFIGURED")
        val quote = travelGroundServicePricing.quote(config.medicalListPrice)
        val serviceFee = Money.fromMinor(quote.travelGroundServiceFeeMinor, quote.currency)

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
            currency = quote.currency,
            price = serviceFee,
            totalAmountMinor = quote.travelGroundServiceFeeMinor,
            paidAmount = BigDecimal.ZERO,
            paidAmountMinor = 0,
            couponId = null,
            userCouponId = null,
            discountAmount = BigDecimal.ZERO,
            discountAmountMinor = 0,
            status = OrderStatusEnum.PENDING_SERVICE_FEE.value,
            paymentFlow = TRAVEL_GROUND_SERVICE_PAYMENT_FLOW,
            medicalListPriceMinor = quote.medicalListPriceMinor,
            platformServiceRateBps = quote.platformServiceRateBps,
            travelGroundServiceFeeMinor = quote.travelGroundServiceFeeMinor,
            createdAt = now,
            projectId = project.id,
            institutionId = institution.id,
            consultantId = consultant.id,
            consultantName = consultant.name,
            consultantAvatar = consultant.avatar,
            doctorId = request.doctorId,
            doctorName = doctor.name,
            orderNo = orderNo,
            consultationFee = BigDecimal.ZERO,
            consultationFeeMinor = 0,
            remainingAmount = BigDecimal.ZERO,
            remainingAmountMinor = 0,
            pricingCountry = "CN",
            quantity = 1,
            remark = request.remark,
            institutionProjectId = institutionProject.id,
            appointmentTime = appointmentTime
        )

        val saved = orderRepository.save(order)
        orderStatusLogService.logTransition(
            orderId = saved.id,
            fromStatus = "",
            toStatus = OrderStatusEnum.PENDING_SERVICE_FEE.value,
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
        require(offset >= 0) { "offset 不能小于 0" }
        require(limit in 1..100) { "limit 必须在 1-100 之间" }
        val doctorId = requireProfessionalDoctor(actor)
        return orderRepository.findManagementOrders(
            doctorId, normalizedStatus, OffsetPageRequest(offset.toLong(), limit)
        ).content.map(OrderResponse::forManagement)
    }

    fun requireOrderForManagement(actor: ManagementActor, orderId: String): OrderEntity {
        requireProfessionalDoctor(actor)
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在") }
        if (!canManageOrder(actor, order)) throw AccessDeniedException("无权管理该订单")
        return order
    }

    private fun canManageOrder(actor: ManagementActor, order: OrderEntity): Boolean =
        actor.isAdmin || ("DOCTOR" in actor.activeRoles && actor.doctorId != null && order.doctorId == actor.doctorId)

    private fun requireProfessionalDoctor(actor: ManagementActor): String? {
        if (actor.isAdmin) return null
        if ("DOCTOR" !in actor.activeRoles) throw AccessDeniedException("仅限在职医生")
        return actor.doctorId ?: throw AccessDeniedException("仅限在职医生")
    }

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
        requireMedicalPaymentSupported(order)

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
    fun confirmVerificationForManagement(actor: ManagementActor, orderId: String, verificationCode: String): OrderResponse {
        requireProfessionalDoctor(actor)
        val order = orderRepository.findByIdForUpdate(orderId) ?: throw OrderManagementNotFoundException()
        if (!canManageOrder(actor, order)) throw AccessDeniedException("无权管理该订单")
        return confirmVerificationLocked(order, actor.userId, verificationCode)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun confirmVerification(orderId: String, operatorId: String, verificationCode: String): OrderResponse {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        return confirmVerificationLocked(order, operatorId, verificationCode)
    }

    private fun confirmVerificationLocked(order: OrderEntity, operatorId: String, verificationCode: String): OrderResponse {
        val orderId = order.id
        requireMedicalPaymentSupported(order)
        if (order.status == OrderStatusEnum.VERIFIED.value && order.verifiedAt != null) {
            return OrderResponse.forManagement(order)
        }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        if (currentStatus != OrderStatusEnum.CONSULTATION_PAID) throw OrderManagementConflictException("当前订单状态不允许确认到店核验")
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
        return OrderResponse.forManagement(updated)
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
    fun requestCompletionForManagement(actor: ManagementActor, orderId: String, verificationCode: String): OrderResponse {
        requireProfessionalDoctor(actor)
        val order = orderRepository.findByIdForUpdate(orderId) ?: throw OrderManagementNotFoundException()
        if (!canManageOrder(actor, order)) throw AccessDeniedException("无权管理该订单")
        return requestCompletionLocked(order, actor.userId, verificationCode)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun requestCompletion(orderId: String, operatorId: String, verificationCode: String): OrderResponse {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        return requestCompletionLocked(order, operatorId, verificationCode)
    }

    private fun requestCompletionLocked(order: OrderEntity, operatorId: String, verificationCode: String): OrderResponse {
        val orderId = order.id
        requireMedicalPaymentSupported(order)
        if (order.status == OrderStatusEnum.PENDING_COMPLETION.value && order.completionRequestedAt != null) {
            return OrderResponse.forManagement(order)
        }

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        if (currentStatus != OrderStatusEnum.BALANCE_PAID) throw OrderManagementConflictException("当前订单状态不允许申请完成")
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
        return OrderResponse.forManagement(updated)
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
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在: $orderId")
        require(order.userId == userId) { "无权操作该订单" }
        if (order.paymentFlow == TRAVEL_GROUND_SERVICE_PAYMENT_FLOW) {
            if (order.status == OrderStatusEnum.COMPLETED.value) return OrderResponse.from(order)
            require(order.status == OrderStatusEnum.SERVICE_ACTIVE.value) {
                "当前状态[${order.status}]不允许确认完成"
            }
            val now = LocalDateTime.now()
            val completed = orderRepository.save(
                order.copy(
                    status = OrderStatusEnum.COMPLETED.value,
                    completedAt = now,
                    updatedAt = now
                )
            )
            orderStatusLogService.logTransition(
                orderId = orderId,
                fromStatus = OrderStatusEnum.SERVICE_ACTIVE.value,
                toStatus = OrderStatusEnum.COMPLETED.value,
                operatorId = userId,
                operatorType = OPERATOR_TYPE_USER,
                remark = "用户确认旅游地接服务完成"
            )
            log.info("订单[{}]确认旅游地接服务完成", orderId)
            return OrderResponse.from(completed)
        }
        requireMedicalPaymentSupported(order)

        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单状态无效: ${order.status}")
        require(currentStatus.canTransitionTo(OrderStatusEnum.COMPLETED)) {
            "当前状态[${currentStatus.value}]不允许确认完成"
        }
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

        settlementService.saveSettlement(orderId, settlementAt)
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
        val order = orderRepository.findById(orderId).orElse(null) ?: return
        requireMedicalPaymentSupported(order)
        reviewService.submitAutomaticReview(orderId)
    }

    /** 定时任务调用，取消任一支付流程中超过 30 分钟仍未支付的订单。 */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelExpiredPendingOrder(orderId: String) {
        val order = orderRepository.findByIdForUpdate(orderId) ?: return
        val currentStatus = OrderStatusEnum.fromValue(order.status) ?: return
        if (currentStatus !in setOf(
                OrderStatusEnum.PENDING_PAYMENT,
                OrderStatusEnum.PENDING_SERVICE_FEE
            )
        ) {
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
     * 取消待支付订单并保留状态审计。
     * 仅允许旧流程待支付或旅游地接服务费待支付状态取消。
     *
     * @param orderId 订单ID
     * @param userId  当前用户ID
     */
    @Transactional(rollbackFor = [Exception::class])
    fun cancelOrder(orderId: String, userId: String) {
        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw RuntimeException("订单不存在")

        // 鉴权：确保当前用户拥有该订单
        if (order.userId != userId) {
            throw RuntimeException("无权操作此订单")
        }

        val cancellableStatuses = setOf(
            OrderStatusEnum.PENDING_PAYMENT.value,
            OrderStatusEnum.PENDING_SERVICE_FEE.value
        )
        if (order.status !in cancellableStatuses) {
            throw RuntimeException("当前状态不允许取消")
        }

        order.userCouponId?.let { couponService.returnCoupon(it) }

        val now = LocalDateTime.now()
        orderRepository.save(
            order.copy(status = OrderStatusEnum.CANCELLED.value, updatedAt = now)
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = order.status,
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
        orderRepository.findByIdIncludeDeleted(orderId)
            ?: throw RuntimeException("订单不存在")

        require(!orderRepository.hasMoneyReferences(orderId)) {
            "订单已关联支付、退款或结算账本记录，禁止物理删除"
        }

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
        val order = orderRepository.findByIdForUpdate(orderId) ?: return false
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
        val order = orderRepository.findByIdForUpdate(id) ?: return null
        val currentStatus = OrderStatusEnum.fromValue(order.status)
            ?: throw IllegalStateException("订单当前状态无效: ${order.status}")
        val targetStatus = OrderStatusEnum.fromValue(status)
            ?: throw IllegalArgumentException("目标状态无效: $status")
        if (order.paymentFlow == TRAVEL_GROUND_SERVICE_PAYMENT_FLOW) {
            if (targetStatus in LEGACY_MEDICAL_STATUSES) {
                throw IllegalArgumentException(MEDICAL_PAYMENT_NOT_SUPPORTED)
            }
            require(
                currentStatus == OrderStatusEnum.PENDING_SERVICE_FEE &&
                    targetStatus == OrderStatusEnum.CANCELLED
            ) {
                "旅游地接服务订单状态只能由专用支付或退款流程推进"
            }
        }
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
        requireMedicalPaymentSupported(order)
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

    private fun requireMedicalPaymentSupported(order: OrderEntity) {
        require(order.paymentFlow != TRAVEL_GROUND_SERVICE_PAYMENT_FLOW) {
            MEDICAL_PAYMENT_NOT_SUPPORTED
        }
    }

    /** 管理员删除订单（软删除）。锁定订单以与支付成功回调顺序化。 */
    @Transactional(rollbackFor = [Exception::class])
    fun adminDeleteById(id: String) {
        orderRepository.findByIdForUpdate(id) ?: return
        orderRepository.deleteById(id)
    }

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

class OrderManagementNotFoundException : RuntimeException("订单不存在")
class OrderManagementConflictException(message: String) : RuntimeException(message)
