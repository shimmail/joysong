package com.joysong.server.coupon.entity

import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime
import jakarta.persistence.*

/**
 * 优惠券定义实体
 *
 * @author joysong
 * @since 2026-07-30
 */
@Entity
@Table(name = "coupons")
@SQLDelete(sql = "UPDATE coupons SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
class CouponEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    /** 优惠券名称 */
    @Column(name = "name", nullable = false, length = 100)
    var name: String = "",

    /** 类型：FIXED(满减)/PERCENTAGE(折扣) */
    @Column(name = "type", nullable = false, length = 20)
    var type: String = "",

    /** 折扣值：满减为金额，折扣为百分比（如 15.00 表示 85 折） */
    @Column(name = "discount_value", nullable = false, precision = 10, scale = 2)
    var discountValue: BigDecimal = BigDecimal.ZERO,

    /** 最低消费金额（满减门槛） */
    @Column(name = "min_amount", nullable = false, precision = 10, scale = 2)
    var minAmount: BigDecimal = BigDecimal.ZERO,

    /** 适用项目ID列表（逗号分隔，NULL表示全部适用） */
    @Column(name = "applicable_project_ids", length = 500)
    var applicableProjectIds: String? = null,

    /** 适用机构ID列表（逗号分隔，NULL表示全部适用） */
    @Column(name = "applicable_institution_ids", length = 500)
    var applicableInstitutionIds: String? = null,

    /** 发放总量（0表示不限量） */
    @Column(name = "total_count", nullable = false)
    var totalCount: Int = 0,

    /** 已发放数量 */
    @Column(name = "issued_count", nullable = false)
    var issuedCount: Int = 0,

    /** 已使用数量 */
    @Column(name = "used_count", nullable = false)
    var usedCount: Int = 0,

    /** 有效期开始 */
    @Column(name = "start_time", nullable = false)
    var startTime: LocalDateTime = LocalDateTime.now(),

    /** 有效期结束 */
    @Column(name = "end_time", nullable = false)
    var endTime: LocalDateTime = LocalDateTime.now(),

    /** 状态：ACTIVE/INACTIVE/EXPIRED */
    @Column(name = "status", nullable = false, length = 20)
    var status: String = "ACTIVE",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)
