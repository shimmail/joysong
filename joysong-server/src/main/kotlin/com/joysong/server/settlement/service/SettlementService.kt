package com.joysong.server.settlement.service

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.repository.DoctorInstitutionProjectConfigRepository
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.order.service.OrderSplitRatePolicy
import com.joysong.server.order.service.OrderStatusLogService
import com.joysong.server.settlement.entity.SettlementEntity
import com.joysong.server.settlement.repository.SettlementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import com.joysong.server.payment.domain.Money

/**
 * 结算分账服务
 * 负责订单确认完成后的分账计算、到期结算处理及结算查询
 *
 * @author joysong
 * @since 2026-07-30
 */
@Service
class SettlementService(
    private val settlementRepository: SettlementRepository,
    private val orderRepository: OrderRepository,
    private val splitRatePolicy: OrderSplitRatePolicy,
    private val doctorInstitutionProjectConfigRepository: DoctorInstitutionProjectConfigRepository,
    private val orderStatusLogService: OrderStatusLogService
) {

    companion object {
        private val log = LoggerFactory.getLogger(SettlementService::class.java)
        private val HUNDRED = BigDecimal("100")
        private val SCALE = 2
    }

    /**
     * 创建结算记录
     * 订单确认完成时调用，根据共享策略和医生-机构项目配置计算分账金额
     *
     * 分账计算规则：
     * 1. 读取共享策略获取平台与默认机构比例
     * 2. 读取 DoctorInstitutionProjectConfig 获取机构与医美顾问分账比例
     * 3. doctorRate = 100 - platformRate - institutionRate - consultantRate
     * 4. 各方金额 = totalAmount × rate / 100，使用 HALF_UP 舍入
     *
     * @param orderId 订单ID
     * @return 结算分账记录实体
     */
    @Transactional(rollbackFor = [Exception::class])
    fun saveSettlement(orderId: String): SettlementEntity {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("订单不存在: $orderId") }

        val existing = settlementRepository.findByOrderId(orderId)
        if (existing != null) {
            log.warn("订单[{}]已存在结算记录[id={}]，跳过创建", orderId, existing.id)
            return existing
        }

        check(
            order.institutionId.isNotBlank() &&
                order.institutionName.isNotBlank() &&
                order.institutionProjectId.isNotBlank() &&
                order.consultantId.isNotBlank() &&
                order.consultantName.isNotBlank() &&
                order.doctorId.isNotBlank() &&
                order.doctorName.isNotBlank()
        ) { "订单分账信息不完整，缺少机构、机构项目、医美顾问或医生快照" }

        // The order snapshot is the sole source of party identities. This lookup only
        // resolves the current rate policy for the frozen doctor/project keys.
        val config = doctorInstitutionProjectConfigRepository
            .findByDoctorIdAndInstitutionProjectId(order.doctorId, order.institutionProjectId)
        val rates = splitRatePolicy.resolve(
            institutionRate = config?.institutionRate ?: splitRatePolicy.defaultInstitutionRate(),
            consultantRate = config?.commissionRate ?: BigDecimal.ZERO
        )

        val totalAmount = order.price
        val platformAmount = totalAmount.multiply(rates.platformRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
        val institutionAmount = totalAmount.multiply(rates.institutionRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
        val consultantAmount = totalAmount.multiply(rates.consultantRate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
        val doctorAmount = totalAmount - platformAmount - institutionAmount - consultantAmount

        val settlement = SettlementEntity(
            orderId = orderId,
            totalAmount = totalAmount,
            platformAmount = platformAmount,
            institutionAmount = institutionAmount,
            consultantAmount = consultantAmount,
            doctorAmount = doctorAmount,
            currency = order.currency,
            totalAmountMinor = order.totalAmountMinor ?: Money.toMinor(totalAmount, order.currency),
            platformAmountMinor = Money.toMinor(platformAmount, order.currency),
            institutionAmountMinor = Money.toMinor(institutionAmount, order.currency),
            consultantAmountMinor = Money.toMinor(consultantAmount, order.currency),
            doctorAmountMinor = Money.toMinor(doctorAmount, order.currency),
            platformRate = rates.platformRate,
            institutionRate = rates.institutionRate,
            consultantRate = rates.consultantRate,
            doctorRate = rates.doctorRate,
            status = "PENDING",
            settledAt = order.settlementAt
        )

        val saved = settlementRepository.save(settlement)
        log.info(
            "订单[{}]结算记录创建成功: 总额={}, 平台={}, 机构={}, 医美顾问={}, 医生={}",
            orderId, totalAmount, platformAmount, institutionAmount, consultantAmount, doctorAmount
        )
        return saved
    }

    /**
     * 处理到期结算
     * 定时任务调用，将到期的结算标记为已完成
     */
    @Transactional(rollbackFor = [Exception::class])
    fun processDueSettlements() {
        val now = LocalDateTime.now()
        val dueSettlements = settlementRepository.findByStatusAndSettledAtBefore("PENDING", now)
        dueSettlements.forEach { settlement ->
            settlement.status = "COMPLETED"
            settlement.updatedAt = now
            settlementRepository.save(settlement)
            log.info("结算记录[id={}]已到期完成, 订单ID: {}", settlement.id, settlement.orderId)

            // 同步更新订单状态: PENDING_SETTLEMENT -> SETTLED
            val order = orderRepository.findById(settlement.orderId).orElse(null)
            if (order != null && order.status == OrderStatusEnum.PENDING_SETTLEMENT.value) {
                orderRepository.save(
                    order.copy(
                        status = OrderStatusEnum.SETTLED.value,
                        updatedAt = now
                    )
                )
                orderStatusLogService.logTransition(
                    orderId = order.id,
                    fromStatus = OrderStatusEnum.PENDING_SETTLEMENT.value,
                    toStatus = OrderStatusEnum.SETTLED.value,
                    operatorId = null,
                    operatorType = "SYSTEM",
                    remark = "结算到期自动结算"
                )
                log.info("订单[{}]状态更新: PENDING_SETTLEMENT -> SETTLED", order.id)
            } else {
                log.warn("订单[{}]状态不是PENDING_SETTLEMENT(当前: {}), 跳过状态更新", settlement.orderId, order?.status)
            }
        }
        log.info("本次共处理{}条到期结算", dueSettlements.size)
    }

    /**
     * 查询订单结算详情
     *
     * @param orderId 订单ID
     * @return 结算记录，不存在返回 null
     */
    fun getByOrderId(orderId: String): SettlementEntity? {
        return settlementRepository.findByOrderId(orderId)
    }
}
