package com.joysong.server.order.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.settlement.repository.SettlementRepository
import com.joysong.server.settlement.service.SettlementReleaseService
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/**
 * 订单定时任务
 * 处理支付超时取消、尾款超时、自动好评、结算到期等周期性任务
 *
 * @author joysong
 * @since 2026-07-30
 */
@Component
class OrderScheduledTasks(
    private val orderRepository: OrderRepository,
    private val orderService: OrderService,
    private val settlementRepository: SettlementRepository,
    private val settlementReleaseService: SettlementReleaseService
) {

    companion object {
        private val log = LoggerFactory.getLogger(OrderScheduledTasks::class.java)

        /** 面诊金支付超时时间（分钟） */
        private const val PENDING_PAYMENT_TIMEOUT_MINUTES = 30L

        /** 尾款支付超时时间（小时） */
        private const val BALANCE_TIMEOUT_HOURS = 2L

        /** CONSULTATION_PAID 超时未到店时间（天） */
        private const val CONSULTATION_PAID_TIMEOUT_DAYS = 30L

        /** 自动好评等待天数（天） */
        private const val AUTO_REVIEW_DELAY_DAYS = 7L

        private const val DUE_SETTLEMENT_BATCH_SIZE = 100
    }

    /**
     * 超时取消：每5分钟检查 PENDING_PAYMENT 状态超过30分钟未支付的订单，自动取消
     */
    @Scheduled(fixedRate = 300000)
    fun cancelExpiredPendingOrders() {
        val cutoff = LocalDateTime.now().minusMinutes(PENDING_PAYMENT_TIMEOUT_MINUTES)
        val expiredOrders = orderRepository.findByStatusAndCreatedAtBefore(
            OrderStatusEnum.PENDING_PAYMENT.value, cutoff
        )
        expiredOrders.forEach { order ->
            try {
                orderService.cancelExpiredPendingOrder(order.id)
            } catch (e: Exception) {
                log.error("取消超时订单[{}]失败: {}", order.id, e.message, e)
            }
        }
        if (expiredOrders.isNotEmpty()) {
            log.info("本次共处理{}条超时未支付订单", expiredOrders.size)
        }
    }

    /**
     * CONSULTATION_PAID 超时未到店：每6小时检查 CONSULTATION_PAID 状态超过30天未到店的订单，自动取消并退款
     */
    @Scheduled(fixedRate = 6 * 60 * 60 * 1000)
    fun cancelExpiredConsultationPaidOrders() {
        val cutoff = LocalDateTime.now().minusDays(CONSULTATION_PAID_TIMEOUT_DAYS)
        val expiredOrders = orderRepository.findByStatusAndPaymentTimeBefore(
            OrderStatusEnum.CONSULTATION_PAID.value, cutoff
        )
        expiredOrders.forEach { order ->
            try {
                orderService.cancelExpiredConsultationPaidOrder(order.id)
            } catch (e: Exception) {
                log.error("取消超时未到店订单[{}]失败: {}", order.id, e.message, e)
            }
        }
        if (expiredOrders.isNotEmpty()) {
            log.info("本次共处理{}条超时未到店订单", expiredOrders.size)
        }
    }

    /**
     * 尾款超时：每10分钟检查 VERIFIED 状态超过2小时未支付尾款的订单，自动取消
     */
    @Scheduled(fixedRate = 600000)
    fun handleBalanceTimeout() {
        val cutoff = LocalDateTime.now().minusHours(BALANCE_TIMEOUT_HOURS)
        val timeoutOrders = orderRepository.findByStatusAndVerifiedAtBefore(
            OrderStatusEnum.VERIFIED.value, cutoff
        )
        timeoutOrders.forEach { order ->
            try {
                orderService.cancelBalanceTimeoutOrder(order.id)
            } catch (e: Exception) {
                log.error("处理尾款超时订单[{}]失败: {}", order.id, e.message, e)
            }
        }
        if (timeoutOrders.isNotEmpty()) {
            log.info("本次共处理{}条尾款超时订单", timeoutOrders.size)
        }
    }

    /**
     * 自动好评：每天凌晨2点检查 COMPLETED 状态且完成时间超过7天未评价的订单，自动进入待结算并创建结算记录
     * 使用 completedAt（用户确认完成时间）而非 createdAt，避免长周期订单完成后被立即评价
     */
    @Scheduled(cron = "0 0 2 * * ?")
    fun autoCompleteReviews() {
        val cutoff = LocalDateTime.now().minusDays(AUTO_REVIEW_DELAY_DAYS)
        val completedOrders = orderRepository.findByStatusAndCompletedAtBefore(
            OrderStatusEnum.COMPLETED.value, cutoff
        )
        completedOrders.forEach { order ->
            if (!order.hasReview) {
                try {
                    orderService.autoCompleteReview(order.id)
                } catch (e: Exception) {
                    log.error("自动好评订单[{}]失败: {}", order.id, e.message, e)
                }
            }
        }
        log.info("自动好评任务执行完毕, 共处理{}条订单", completedOrders.size)
    }

    /**
     * 结算到期：每天凌晨3点按结算 ID 分别释放可用余额，避免一个失败阻塞其他结算。
     */
    @Scheduled(cron = "0 0 3 * * ?")
    fun processSettlements() {
        val now = LocalDateTime.now()
        val dueSettlementIds = settlementRepository.findDueSettlementIds(
            setOf("PENDING", "PARTIALLY_REVERSED"),
            now,
            PageRequest.of(0, DUE_SETTLEMENT_BATCH_SIZE)
        )
        dueSettlementIds.forEach { settlementId ->
            try {
                settlementReleaseService.release(settlementId, now)
            } catch (e: Exception) {
                log.error("释放到期结算[{}]失败: {}", settlementId, e.message, e)
            }
        }
        log.info("结算到期任务执行完毕, 共处理{}条", dueSettlementIds.size)
    }
}
