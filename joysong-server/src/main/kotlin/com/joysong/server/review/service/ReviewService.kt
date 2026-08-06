package com.joysong.server.review.service

import com.joysong.server.doctor.repository.DoctorRepository
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
    private val institutionRepository: InstitutionRepository
) {

    companion object {
        private val log = LoggerFactory.getLogger(ReviewService::class.java)
        private const val OPERATOR_TYPE_USER = "USER"
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

        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在") }

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

        // 重算机构/医生评分统计
        recalculateStats(order.institutionId, order.doctorId)

        // 评价提交后，将订单从 COMPLETED/PENDING_SETTLEMENT 转入 PENDING_SETTLEMENT
        val previousStatus = order.status
        val settlementAt = order.settlementAt ?: LocalDateTime.now().plusDays(30)
        val updatedOrder = orderRepository.save(
            order.copy(
                status = OrderStatusEnum.PENDING_SETTLEMENT.value,
                hasReview = true,
                settlementAt = settlementAt,
                updatedAt = LocalDateTime.now()
            )
        )

        orderStatusLogService.logTransition(
            orderId = orderId,
            fromStatus = previousStatus,
            toStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
            operatorId = userId,
            operatorType = OPERATOR_TYPE_USER,
            remark = "用户主动评价，进入待结算"
        )
        log.info("订单[{}]用户主动评价完成，状态从{}转为PENDING_SETTLEMENT", orderId, previousStatus)

        settlementService.saveSettlement(orderId)

        return review
    }

    fun adminListAll(): List<ReviewEntity> = reviewRepository.findAll()

    @Transactional(rollbackFor = [Exception::class])
    fun adminDeleteById(id: String) {
        val review = reviewRepository.findById(id).orElseThrow { IllegalArgumentException("评价不存在") }
        val institutionId = review.targetId
        val doctorId = review.doctorId
        val orderId = review.orderId

        reviewRepository.deleteById(id)

        // 重置订单 hasReview
        orderRepository.findById(orderId).ifPresent { order ->
            orderRepository.save(order.copy(hasReview = false))
        }

        // 重算评分
        recalculateStats(institutionId, doctorId)
    }

    fun getReviewByOrderId(orderId: String, userId: String): ReviewEntity? {
        val order = orderRepository.findById(orderId).orElse(null) ?: return null
        if (order.userId != userId) throw IllegalArgumentException("无权查看该订单评价")
        return reviewRepository.findByOrderId(orderId).orElse(null)
    }

    @Transactional(rollbackFor = [Exception::class])
    fun updateReview(reviewId: String, userId: String, rating: Int, content: String, tags: String, images: String): ReviewEntity {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { IllegalArgumentException("评价不存在") }
        if (review.userId != userId) {
            throw IllegalArgumentException("无权修改此评价")
        }
        require(rating in 1..5) { "评分必须在1-5之间" }
        require(content.isNotBlank()) { "评价内容不能为空" }

        val updated = review.copy(
            rating = rating,
            content = content,
            tags = tags,
            images = images,
            updatedAt = LocalDateTime.now()
        )
        val saved = reviewRepository.save(updated)

        // 重算评分统计
        recalculateStats(review.targetId, review.doctorId)

        return saved
    }

    @Transactional(rollbackFor = [Exception::class])
    fun deleteUserReview(reviewId: String, userId: String) {
        val review = reviewRepository.findById(reviewId)
            .orElseThrow { IllegalArgumentException("评价不存在") }
        if (review.userId != userId) {
            throw IllegalArgumentException("无权删除此评价")
        }

        val institutionId = review.targetId
        val doctorId = review.doctorId
        val orderId = review.orderId

        // 软删除评价
        reviewRepository.deleteById(reviewId)

        // 重置订单 hasReview 标记
        orderRepository.findById(orderId).ifPresent { order ->
            orderRepository.save(order.copy(hasReview = false))
        }

        // 重算评分统计
        recalculateStats(institutionId, doctorId)
    }

    private fun recalculateStats(institutionId: String, doctorId: String) {
        // 机构评分重算
        if (institutionId.isNotBlank()) {
            val reviews = reviewRepository.findByTargetTypeAndTargetId("INSTITUTION", institutionId)
            val count = reviews.size
            val avg = if (count > 0) BigDecimal.valueOf(reviews.map { it.rating }.average()).setScale(1, RoundingMode.HALF_UP) else BigDecimal.ZERO
            institutionRepository.findById(institutionId).ifPresent { institutionRepository.save(it.copy(reviewCount = count, rating = avg)) }
        }
        // 医生评分重算
        if (doctorId.isNotBlank()) {
            val reviews = reviewRepository.findByDoctorId(doctorId)
            val count = reviews.size
            val avg = if (count > 0) BigDecimal.valueOf(reviews.map { it.rating }.average()).setScale(1, RoundingMode.HALF_UP) else BigDecimal.ZERO
            doctorRepository.findById(doctorId).ifPresent { doctorRepository.save(it.copy(reviewCount = count, rating = avg)) }
        }
    }
}
