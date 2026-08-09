package com.joysong.server.settlement.entity

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 结算分账记录实体
 *
 * @author joysong
 * @since 2026-07-30
 */
@Entity
@Table(name = "settlements")
class SettlementEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** 关联订单ID */
    @Column(name = "order_id", nullable = false, length = 36)
    var orderId: String = "",

    /** 订单总金额 */
    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    var totalAmount: BigDecimal = BigDecimal.ZERO,

    /** 平台服务费（40%） */
    @Column(name = "platform_amount", nullable = false, precision = 19, scale = 4)
    var platformAmount: BigDecimal = BigDecimal.ZERO,

    /** 机构分成（约40%） */
    @Column(name = "institution_amount", nullable = false, precision = 19, scale = 4)
    var institutionAmount: BigDecimal = BigDecimal.ZERO,

    /** 医美顾问分账 */
    @Column(name = "consultant_amount", nullable = false, precision = 19, scale = 4)
    var consultantAmount: BigDecimal = BigDecimal.ZERO,

    /** 医生收入（剩余部分） */
    @Column(name = "doctor_amount", nullable = false, precision = 19, scale = 4)
    var doctorAmount: BigDecimal = BigDecimal.ZERO,

    @Column(name = "currency", nullable = false, length = 3, columnDefinition = "char(3)")
    var currency: String = com.joysong.server.common.money.CurrencyCode.DEFAULT_CODE,

    @Column(name = "total_amount_minor") var totalAmountMinor: Long? = null,
    @Column(name = "platform_amount_minor") var platformAmountMinor: Long? = null,
    @Column(name = "institution_amount_minor") var institutionAmountMinor: Long? = null,
    @Column(name = "consultant_amount_minor") var consultantAmountMinor: Long? = null,
    @Column(name = "doctor_amount_minor") var doctorAmountMinor: Long? = null,

    @Column(name = "payout_provider", length = 30) var payoutProvider: String? = null,
    @Column(name = "provider_transfer_id", length = 150) var providerTransferId: String? = null,
    @Column(name = "failure_code", length = 100) var failureCode: String? = null,
    @Column(name = "failure_message", length = 500) var failureMessage: String? = null,

    /** 平台分成比例（百分比，如 40.00 表示 40%） */
    @Column(name = "platform_rate", nullable = false, precision = 5, scale = 2)
    var platformRate: BigDecimal = BigDecimal.ZERO,

    /** 机构分成比例（百分比） */
    @Column(name = "institution_rate", nullable = false, precision = 5, scale = 2)
    var institutionRate: BigDecimal = BigDecimal.ZERO,

    /** 医美顾问分账比例（百分比） */
    @Column(name = "consultant_rate", nullable = false, precision = 5, scale = 2)
    var consultantRate: BigDecimal = BigDecimal.ZERO,

    /** 医生收入比例（百分比） */
    @Column(name = "doctor_rate", nullable = false, precision = 5, scale = 2)
    var doctorRate: BigDecimal = BigDecimal.ZERO,

    /** 状态：PENDING/COMPLETED */
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "PENDING",

    /** 结算完成时间 */
    @Column(name = "settled_at")
    var settledAt: LocalDateTime? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now()
)
