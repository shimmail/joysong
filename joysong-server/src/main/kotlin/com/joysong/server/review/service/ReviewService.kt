package com.joysong.server.review.service

import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.institution.repository.InstitutionProjectRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.review.entity.ReviewEntity
import com.joysong.server.review.repository.ReviewRepository
import com.joysong.server.settlement.service.SettlementService
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.UUID

@Service
class ReviewService(
    private val reviewRepository: ReviewRepository,
    private val orderRepository: OrderRepository,
    @Lazy private val settlementService: SettlementService,
    private val orderStatusLogService: OrderStatusLogService,
    private val doctorRepository: DoctorRepository,
    private val institutionRepository: InstitutionRepository,
    private val institutionProjectRepository: InstitutionProjectRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(ReviewService::class.java)
        private const val OPERATOR_TYPE_USER = "USER"
        private const val OPERATOR_TYPE_SYSTEM = "SYSTEM"
        private const val TRAVEL_GROUND_SERVICE_PAYMENT_FLOW = "TRAVEL_GROUND_SERVICE_ONLY"
        private const val MAX_REVIEW_IMAGES = 6
        private const val MAX_REVIEW_IMAGES_LENGTH = 2000
    }

    @Transactional(rollbackFor = [Exception::class])
    fun submitReview(
        orderId: String,
        userId: String,
        rating: Int,
        content: String,
        tags: String,
        images: String
    ): ReviewEntity {
        require(rating in 1..5) { "评分必须在1-5之间" }
        require(content.isNotBlank()) { "评价内容不能为空" }
        validateImages(images)

        val order = orderRepository.findByIdForUpdate(orderId)
            ?: throw IllegalArgumentException("订单不存在")

        if (order.userId != userId) {
            throw IllegalArgumentException("无权操作该订单")
        }

        if (order.status !in setOf("COMPLETED", "PENDING_SETTLEMENT", "SETTLED")) {
            throw IllegalArgumentException("订单状态不允许评价，当前状态: ${order.status}")
        }

        if (order.hasReview) {
            throw IllegalArgumentException("该订单已评价，不可重复评价")
        }

        val now = LocalDateTime.now()
        val review = ReviewEntity(
            id = UUID.randomUUID().toString(),
            orderId = orderId,
            userId = userId,
            doctorId = order.doctorId,
            rating = rating,
            content = content,
            tags = tags,
            images = images,
            targetType = "INSTITUTION",
            targetId = order.institutionId,
            createdAt = now
        )

        reviewRepository.save(review)

        // 重算机构/医生/机构项目评分统计
        recalculateStats(order.institutionId, order.doctorId, order.institutionProjectId)

        // 医疗订单的 COMPLETED 评价后进入待结算；旅游地接完成态评价不进入医疗结算链路。
        val previousStatus = order.status
        val shouldEnterSettlement = previousStatus == OrderStatusEnum.COMPLETED.value &&
            order.paymentFlow != TRAVEL_GROUND_SERVICE_PAYMENT_FLOW
        val settlementAt = if (shouldEnterSettlement) {
            order.settlementAt ?: LocalDateTime.now().plusDays(30)
        } else {
            order.settlementAt
        }
        orderRepository.save(
            order.copy(
                status = if (shouldEnterSettlement) {
                    OrderStatusEnum.PENDING_SETTLEMENT.value
                } else {
                    previousStatus
                },
                hasReview = true,
                settlementAt = settlementAt,
                updatedAt = LocalDateTime.now()
            )
        )

        if (shouldEnterSettlement) {
            orderStatusLogService.logTransition(
                orderId = orderId,
                fromStatus = previousStatus,
                toStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
                operatorId = userId,
                operatorType = OPERATOR_TYPE_USER,
                remark = "用户主动评价，进入待结算"
            )
            log.info("订单[{}]用户主动评价完成，状态从{}转为PENDING_SETTLEMENT", orderId, previousStatus)
            settlementService.saveSettlement(orderId, settlementAt)
        }

        return review
    }

    /**
     * 为超时未评价订单创建真实的订单主评价。
     *
     * 自动好评必须落到 reviews 表，否则 order.has_review=true 时客户端无法查询和修改评价，
     * 机构、医生及机构项目的评分统计也无法包含这笔评价。
     */
    @Transactional(rollbackFor = [Exception::class])
    fun submitAutomaticReview(orderId: String): ReviewEntity? {
        val order = orderRepository.findById(orderId).orElse(null) ?: return null
        val currentStatus = OrderStatusEnum.fromValue(order.status) ?: return null
        if (!currentStatus.canTransitionTo(OrderStatusEnum.PENDING_SETTLEMENT)) {
            log.warn("订单[{}]当前状态[{}]不允许自动好评，跳过", orderId, currentStatus.value)
            return null
        }

        val existing = reviewRepository.findByOrderIdAndTargetType(orderId, "INSTITUTION").orElse(null)
        if (existing != null) {
            log.warn("订单[{}]已存在订单主评价，跳过重复自动好评", orderId)
            return existing
        }

        val now = LocalDateTime.now()
        val review = reviewRepository.save(
            ReviewEntity(
                id = UUID.randomUUID().toString(),
                orderId = orderId,
                userId = order.userId,
                doctorId = order.doctorId,
                rating = 5,
                content = "系统默认五星好评 / System default five-star review",
                targetType = "INSTITUTION",
                targetId = order.institutionId,
                createdAt = now
            )
        )

        recalculateStats(order.institutionId, order.doctorId, order.institutionProjectId)

        val settlementAt = order.settlementAt ?: now.plusDays(30)
        orderRepository.save(
            order.copy(
                status = OrderStatusEnum.PENDING_SETTLEMENT.value,
                hasReview = true,
                settlementAt = settlementAt,
                completedAt = order.completedAt ?: now,
                updatedAt = now
            )
        )
        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = currentStatus.value,
            toStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
            operatorId = null,
            operatorType = OPERATOR_TYPE_SYSTEM,
            remark = "超时自动好评"
        )
        settlementService.saveSettlement(orderId, settlementAt)
        log.info("订单[{}]超时自动好评完成", orderId)
        return review
    }

    fun adminListAll(): List<ReviewEntity> = reviewRepository.findAll()

    @Transactional(rollbackFor = [Exception::class])
    fun adminDeleteById(id: String) {
        val review = reviewRepository.findById(id).orElseThrow { IllegalArgumentException("评价不存在") }
        val orderId = review.orderId
        val order = orderRepository.findById(orderId).orElse(null)
        val institutionId = order?.institutionId
            ?: review.targetId.takeIf { review.targetType == "INSTITUTION" }.orEmpty()
        val doctorId = order?.doctorId ?: review.doctorId
        val institutionProjectId = order?.institutionProjectId.orEmpty()

        reviewRepository.deleteById(id)
        reviewRepository.flush()

        // 旧数据可能还保留 PROJECT/DOCTOR 派生评价；只有 canonical 机构评价代表订单已评价状态。
        if (review.targetType == "INSTITUTION") {
            orderRepository.findById(orderId).ifPresent { order ->
                orderRepository.save(order.copy(hasReview = false))
            }
        }

        // 重算评分
        recalculateStats(institutionId, doctorId, institutionProjectId)
    }

    fun getReviewByOrderId(orderId: String, userId: String): ReviewEntity? {
        val order = orderRepository.findById(orderId).orElse(null) ?: return null
        if (order.userId != userId) throw IllegalArgumentException("无权查看该订单评价")
        return reviewRepository.findByOrderIdAndTargetType(orderId, "INSTITUTION").orElse(null)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun updateReview(reviewId: String, userId: String, rating: Int, content: String, tags: String, images: String): ReviewEntity {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { IllegalArgumentException("评价不存在") }
        if (review.userId != userId) {
            throw IllegalArgumentException("无权修改此评价")
        }
        require(review.targetType == "INSTITUTION") { "仅支持修改订单主评价" }
        require(rating in 1..5) { "评分必须在1-5之间" }
        require(content.isNotBlank()) { "评价内容不能为空" }
        validateImages(images)

        val updated = review.copy(
            rating = rating,
            content = content,
            tags = tags,
            images = images,
            updatedAt = LocalDateTime.now()
        )
        val saved = reviewRepository.save(updated)

        // 重算评分统计
        val order = orderRepository.findById(review.orderId).orElse(null)
        recalculateStats(
            order?.institutionId ?: review.targetId,
            order?.doctorId ?: review.doctorId,
            order?.institutionProjectId.orEmpty()
        )

        return saved
    }

    @Transactional(rollbackFor = [Exception::class])
    fun deleteUserReview(reviewId: String, userId: String) {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { IllegalArgumentException("评价不存在") }
        if (review.userId != userId) {
            throw IllegalArgumentException("无权删除此评价")
        }

        val orderId = review.orderId
        val order = orderRepository.findById(orderId).orElse(null)
        val institutionId = order?.institutionId
            ?: review.targetId.takeIf { review.targetType == "INSTITUTION" }.orEmpty()
        val doctorId = order?.doctorId ?: review.doctorId
        val institutionProjectId = order?.institutionProjectId.orEmpty()

        // 软删除评价
        reviewRepository.deleteById(reviewId)
        reviewRepository.flush()

        // 只有订单主评价控制 hasReview；兼容旧 PROJECT/DOCTOR 派生评价数据。
        if (review.targetType == "INSTITUTION") {
            orderRepository.findById(orderId).ifPresent { order ->
                orderRepository.save(order.copy(hasReview = false))
            }
        }

        // 重算评分统计
        recalculateStats(institutionId, doctorId, institutionProjectId)
    }

    private fun recalculateStats(institutionId: String, doctorId: String, institutionProjectId: String) {
        // 机构评分重算
        if (institutionId.isNotBlank()) {
            val reviews = reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", institutionId)
            val count = reviews.size
            val avg = averageRating(reviews)
            institutionRepository.findById(institutionId).ifPresent { institutionRepository.save(it.copy(reviewCount = count, rating = avg)) }
        }
        // 医生评分重算
        if (doctorId.isNotBlank()) {
            val reviews = reviewRepository.findByDoctorIdAndTargetType(doctorId, "INSTITUTION")
            val count = reviews.size
            val avg = averageRating(reviews)
            doctorRepository.findById(doctorId).ifPresent { doctorRepository.save(it.copy(reviewCount = count, rating = avg)) }
        }
        // 机构项目评分仅聚合关联到同一 institution_project_id 的订单评价
        if (institutionProjectId.isNotBlank()) {
            val reviews = reviewRepository.findByInstitutionProjectId(institutionProjectId)
            val count = reviews.size
            val avg = averageRating(reviews)
            institutionProjectRepository.findById(institutionProjectId).ifPresent {
                institutionProjectRepository.save(it.copy(reviewCount = count, rating = avg))
            }
        }
    }

    private fun averageRating(reviews: List<ReviewEntity>): BigDecimal =
        if (reviews.isEmpty()) {
            BigDecimal.ZERO
        } else {
            BigDecimal.valueOf(reviews.map { it.rating }.average()).setScale(1, RoundingMode.HALF_UP)
        }

    private fun validateImages(images: String) {
        require(images.length <= MAX_REVIEW_IMAGES_LENGTH) { "评价图片地址总长度不能超过2000个字符" }
        val count = images.split(',').count { it.isNotBlank() }
        require(count <= MAX_REVIEW_IMAGES) { "评价图片不能超过6张" }
    }
}
