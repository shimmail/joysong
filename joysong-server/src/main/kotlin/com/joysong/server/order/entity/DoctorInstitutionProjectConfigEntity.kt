package com.joysong.server.order.entity

import jakarta.persistence.*
import org.hibernate.annotations.SQLDelete
import org.hibernate.annotations.Where
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 医生-机构项目价格配置实体
 *
 * 按「不同医生的不同机构项目」维度配置面诊金和医美顾问分账比例，由后台管理系统维护。
 *
 * @author joysong
 * @since 2026-07-30
 */
@Entity
@Table(name = "doctor_institution_project_configs")
@SQLDelete(sql = "UPDATE doctor_institution_project_configs SET deleted_at = NOW() WHERE id = ?")
@Where(clause = "deleted_at IS NULL")
class DoctorInstitutionProjectConfigEntity(
    @Id
    @Column(name = "id", length = 36)
    val id: String = java.util.UUID.randomUUID().toString(),

    /** 医生ID */
    @Column(name = "doctor_id", nullable = false, length = 36)
    var doctorId: String = "",

    /** 机构项目ID */
    @Column(name = "institution_project_id", nullable = false, length = 36)
    var institutionProjectId: String = "",

    /** 面诊金（不同医生在不同机构项目上可设不同值） */
    @Column(name = "consultation_fee", nullable = false, precision = 10, scale = 2)
    var consultationFee: BigDecimal = BigDecimal.ZERO,

    /** 医美顾问分账比例（百分比，如 10.00 表示 10%） */
    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    var commissionRate: BigDecimal = BigDecimal.ZERO,

    /** 合作医疗机构分成比例（百分比，如 40.00 表示 40%） */
    @Column(name = "institution_rate", nullable = false, precision = 5, scale = 2)
    var institutionRate: BigDecimal = BigDecimal("40.00"),

    /** 医疗套餐优惠前金额（美元） */
    @Column(name = "medical_list_price", nullable = false, precision = 19, scale = 2)
    var medicalListPrice: BigDecimal = BigDecimal.ZERO,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    /** 逻辑删除时间，非空表示已删除 */
    @Column(name = "deleted_at")
    var deletedAt: LocalDateTime? = null
)
