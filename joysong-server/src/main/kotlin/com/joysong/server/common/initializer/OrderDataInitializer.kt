package com.joysong.server.common.initializer

import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import jakarta.persistence.EntityManager
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.UUID

@Component
@ConditionalOnProperty(prefix = "seed.demo", name = ["enabled"], havingValue = "true")
@Order(6)
class OrderDataInitializer(
    private val orderRepository: OrderRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val entityManager: EntityManager
) : CommandLineRunner {

    @Transactional
    override fun run(args: Array<String>) {
        // OrderEntity uses a soft-delete filter, so repository.count() ignores
        // deleted rows. The demo rows use stable IDs; treating a table that only
        // contains soft-deleted rows as empty would try to insert those IDs again
        // and fail with a duplicate-primary-key error.
        val persistedOrderCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM orders",
            Long::class.java
        ) ?: 0L
        if (persistedOrderCount > 0L) return

        // ============================================================
        // 订单（6 条，覆盖订单生命周期主要状态）
        // ============================================================
        orderRepository.saveAll(listOf(
            // 小美 - 玻尿酸填充（CONSULTATION_PAID：面诊金已付，待到店）
            OrderEntity(
                id = SeedIds.ORDER_ID_1,
                userId = SeedIds.USER_ID_1,
                projectName = "玻尿酸填充",
                institutionName = "上海娇颜颂医美中心",
                price = BigDecimal("2999.00"),
                paidAmount = BigDecimal("25.00"),
                status = OrderStatusEnum.CONSULTATION_PAID.value,
                createdAt = LocalDateTime.of(2026, 7, 9, 10, 30),
                appointmentTime = LocalDateTime.of(2026, 7, 12, 14, 0),
                projectId = SeedIds.PROJ_ID_1,
                institutionId = SeedIds.INST_ID_1,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_1,
                doctorName = "王医生",
                institutionProjectId = SeedIds.IP_ID_1,
                orderNo = "JOY20260709103000001",
                remark = "希望填充苹果肌和鼻唇沟",
                consultationFee = BigDecimal("25.00"),
                remainingAmount = BigDecimal("2974.00"),
                paymentTime = LocalDateTime.of(2026, 7, 9, 10, 31)
            ),
            // 小红 - 皮秒祛斑（PENDING_SETTLEMENT：已评价，待结算）
            OrderEntity(
                id = SeedIds.ORDER_ID_2,
                userId = SeedIds.USER_ID_2,
                projectName = "皮秒祛斑",
                institutionName = "北京美丽时光医疗美容",
                price = BigDecimal("1999.00"),
                paidAmount = BigDecimal("1999.00"),
                status = OrderStatusEnum.PENDING_SETTLEMENT.value,
                createdAt = LocalDateTime.of(2026, 7, 6, 16, 0),
                appointmentTime = LocalDateTime.of(2026, 7, 7, 10, 0),
                hasReview = true,
                projectId = SeedIds.PROJ_ID_4,
                institutionId = SeedIds.INST_ID_2,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_3,
                doctorName = "张医生",
                institutionProjectId = SeedIds.IP_ID_5,
                orderNo = "JOY20260706160000002",
                consultationFee = BigDecimal("20.00"),
                remainingAmount = BigDecimal("1979.00"),
                paymentTime = LocalDateTime.of(2026, 7, 6, 16, 1),
                verifiedAt = LocalDateTime.of(2026, 7, 7, 10, 10),
                verifyCode = "VRF20260707001",
                balancePaidAt = LocalDateTime.of(2026, 7, 7, 10, 30),
                completionRequestedAt = LocalDateTime.of(2026, 7, 7, 11, 0),
                completedAt = LocalDateTime.of(2026, 7, 7, 11, 30),
                settlementAt = LocalDateTime.of(2026, 8, 6, 11, 30)
            ),
            // 小丽 - 热玛吉抗衰（PENDING_PAYMENT：待支付面诊金）
            OrderEntity(
                id = SeedIds.ORDER_ID_3,
                userId = SeedIds.USER_ID_3,
                projectName = "热玛吉抗衰",
                institutionName = "广州悦颜整形医院",
                price = BigDecimal("7999.00"),
                paidAmount = BigDecimal.ZERO,
                status = OrderStatusEnum.PENDING_PAYMENT.value,
                createdAt = LocalDateTime.of(2026, 7, 18, 14, 30),
                appointmentTime = LocalDateTime.of(2026, 7, 22, 15, 0),
                projectId = SeedIds.PROJ_ID_5,
                institutionId = SeedIds.INST_ID_4,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_4,
                doctorName = "陈医生",
                institutionProjectId = SeedIds.IP_ID_6,
                orderNo = "JOY20260718143000003",
                remark = "希望改善面部松弛问题",
                consultationFee = BigDecimal("50.00"),
                remainingAmount = BigDecimal("7949.00")
            ),
            // 小美 - 水光针（VERIFIED：已到店核验，待付尾款）
            OrderEntity(
                id = SeedIds.ORDER_ID_4,
                userId = SeedIds.USER_ID_1,
                projectName = "水光针",
                institutionName = "上海娇颜颂医美中心",
                price = BigDecimal("1280.00"),
                paidAmount = BigDecimal("15.00"),
                status = OrderStatusEnum.VERIFIED.value,
                createdAt = LocalDateTime.of(2026, 7, 20, 9, 0),
                appointmentTime = LocalDateTime.of(2026, 7, 24, 10, 0),
                projectId = SeedIds.PROJ_ID_2,
                institutionId = SeedIds.INST_ID_1,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_2,
                doctorName = "李医生",
                institutionProjectId = SeedIds.IP_ID_3,
                orderNo = "JOY20260720090000004",
                consultationFee = BigDecimal("15.00"),
                remainingAmount = BigDecimal("1265.00"),
                paymentTime = LocalDateTime.of(2026, 7, 20, 9, 1),
                verifiedAt = LocalDateTime.of(2026, 7, 24, 10, 15),
                verifyCode = "VRF20260724001"
            ),
            // 小红 - 双眼皮成形（BALANCE_PAID：全款已付，等待执行）
            OrderEntity(
                id = SeedIds.ORDER_ID_5,
                userId = SeedIds.USER_ID_2,
                projectName = "双眼皮成形",
                institutionName = "上海娇颜颂医美中心",
                price = BigDecimal("3800.00"),
                paidAmount = BigDecimal("3800.00"),
                status = OrderStatusEnum.BALANCE_PAID.value,
                createdAt = LocalDateTime.of(2026, 7, 22, 11, 0),
                appointmentTime = LocalDateTime.of(2026, 7, 26, 9, 0),
                projectId = SeedIds.PROJ_ID_3,
                institutionId = SeedIds.INST_ID_1,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_1,
                doctorName = "王医生",
                institutionProjectId = SeedIds.IP_ID_4,
                orderNo = "JOY20260722110000005",
                consultationFee = BigDecimal("30.00"),
                remainingAmount = BigDecimal("3770.00"),
                paymentTime = LocalDateTime.of(2026, 7, 22, 11, 1),
                verifiedAt = LocalDateTime.of(2026, 7, 26, 9, 10),
                verifyCode = "VRF20260726001",
                balancePaidAt = LocalDateTime.of(2026, 7, 26, 9, 30)
            ),
            // 小丽 - 鼻综合整形（PENDING_SETTLEMENT：待结算，30 天倒计时）
            OrderEntity(
                id = SeedIds.ORDER_ID_6,
                userId = SeedIds.USER_ID_3,
                projectName = "鼻综合整形",
                institutionName = "深圳美莱医疗美容医院",
                price = BigDecimal("12800.00"),
                paidAmount = BigDecimal("12800.00"),
                status = OrderStatusEnum.PENDING_SETTLEMENT.value,
                createdAt = LocalDateTime.of(2026, 7, 10, 13, 0),
                appointmentTime = LocalDateTime.of(2026, 7, 14, 9, 0),
                hasReview = true,
                projectId = SeedIds.PROJ_ID_6,
                institutionId = SeedIds.INST_ID_3,
                consultantId = SeedIds.CONSULTANT_ID,
                consultantName = "安娜咨询师",
                doctorId = SeedIds.DOC_ID_5,
                doctorName = "刘医生",
                institutionProjectId = SeedIds.IP_ID_8,
                orderNo = "JOY20260710130000006",
                consultationFee = BigDecimal("100.00"),
                remainingAmount = BigDecimal("12700.00"),
                paymentTime = LocalDateTime.of(2026, 7, 10, 13, 1),
                verifiedAt = LocalDateTime.of(2026, 7, 14, 9, 15),
                verifyCode = "VRF20260714001",
                balancePaidAt = LocalDateTime.of(2026, 7, 14, 9, 45),
                completionRequestedAt = LocalDateTime.of(2026, 7, 14, 14, 0),
                settlementAt = LocalDateTime.of(2026, 8, 13, 14, 0)
            )
        ))

        // Flush JPA 缓存到数据库，确保 JdbcTemplate 能引用到已保存的数据
        entityManager.flush()

        // ============================================================
        // 收藏（3 条）
        // ============================================================
        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO favorites (id, user_id, target_type, target_id, target_name, target_image, created_at) VALUES (?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_1, "PROJECT", SeedIds.PROJ_ID_1, "玻尿酸填充", ""),
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_1, "DOCTOR", SeedIds.DOC_ID_1, "王医生", ""),
                arrayOf(UUID.randomUUID().toString(), SeedIds.USER_ID_2, "INSTITUTION", SeedIds.INST_ID_2, "北京美丽时光医疗美容", "")
            )
        )

        // ============================================================
        // 评价（2 条，一订单一条 canonical 主评价；其他维度从订单关系聚合）
        // ============================================================
        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO reviews (id, order_id, user_id, doctor_id, rating, content, tags, images, target_type, target_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                // 小红 - 北京美丽时光机构评价（INSTITUTION）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_3, 5,
                    "北京美丽时光的就诊体验非常棒！预约流程很方便，到店后几乎不用等待。护士很温柔，术后还送了修复面膜，很贴心。已经推荐给闺蜜了！",
                    "环境好,服务贴心,推荐,专业", "https://via.placeholder.com/300x300?text=PicoClinic1,https://via.placeholder.com/300x300?text=PicoClinic2", "INSTITUTION", SeedIds.INST_ID_2),
                // 小丽 - 深圳美莱机构评价（INSTITUTION）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_6, SeedIds.USER_ID_3, SeedIds.DOC_ID_5, 5,
                    "鼻综合项目恢复顺利，医生讲解清楚，机构术后回访也很及时，整体体验满意。",
                    "专业,术后服务好,推荐", "https://via.placeholder.com/300x300?text=RhinoplastyClinic1", "INSTITUTION", SeedIds.INST_ID_3)
            )
        )

        // 初始化器在 Flyway 之后运行；写入评价后再次校准演示库聚合字段，保证全新库也一致。
        jdbcTemplate.update(
            """
            UPDATE institutions i
            LEFT JOIN (
                SELECT target_id AS institution_id, COUNT(*) AS review_count, ROUND(AVG(rating), 1) AS rating
                FROM reviews
                WHERE deleted_at IS NULL AND target_type = 'INSTITUTION'
                GROUP BY target_id
            ) stats ON stats.institution_id = i.id
            SET i.review_count = COALESCE(stats.review_count, 0), i.rating = COALESCE(stats.rating, 0.0)
            WHERE i.deleted_at IS NULL
            """.trimIndent()
        )
        jdbcTemplate.update(
            """
            UPDATE doctors d
            LEFT JOIN (
                SELECT doctor_id, COUNT(*) AS review_count, ROUND(AVG(rating), 1) AS rating
                FROM reviews
                WHERE deleted_at IS NULL AND target_type = 'INSTITUTION' AND doctor_id <> ''
                GROUP BY doctor_id
            ) stats ON stats.doctor_id = d.id
            SET d.review_count = COALESCE(stats.review_count, 0), d.rating = COALESCE(stats.rating, 0.0)
            WHERE d.deleted_at IS NULL
            """.trimIndent()
        )
        jdbcTemplate.update(
            """
            UPDATE institution_projects ip
            LEFT JOIN (
                SELECT o.institution_project_id, COUNT(*) AS review_count, ROUND(AVG(r.rating), 1) AS rating
                FROM reviews r
                JOIN orders o ON o.id = r.order_id
                WHERE r.deleted_at IS NULL AND r.target_type = 'INSTITUTION'
                  AND o.deleted_at IS NULL AND o.institution_project_id <> ''
                GROUP BY o.institution_project_id
            ) stats ON stats.institution_project_id = ip.id
            SET ip.review_count = COALESCE(stats.review_count, 0), ip.rating = COALESCE(stats.rating, 0.0)
            WHERE ip.deleted_at IS NULL
            """.trimIndent()
        )

        // ============================================================
        // 支付记录（5 条，覆盖面诊金和尾款两种支付类型）
        // ============================================================
        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO payments (id, order_id, user_id, amount, method, status, paid_at, transaction_id, payment_type, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                // 小美 - 玻尿酸填充（CONSULTATION_PAID：面诊金已付）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_1, SeedIds.USER_ID_1,
                    BigDecimal("25.00"), "WECHAT", "SUCCESS",
                    LocalDateTime.of(2026, 7, 9, 10, 31), "TX20260709103100001", "CONSULTATION_FEE"),
                // 小红 - 皮秒祛斑（COMPLETED：尾款已付）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_2, SeedIds.USER_ID_2,
                    BigDecimal("1979.00"), "ALIPAY", "SUCCESS",
                    LocalDateTime.of(2026, 7, 7, 10, 30), "TX20260707103000002", "BALANCE"),
                // 小美 - 水光针（VERIFIED：面诊金已付）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_4, SeedIds.USER_ID_1,
                    BigDecimal("15.00"), "WECHAT", "SUCCESS",
                    LocalDateTime.of(2026, 7, 20, 9, 1), "TX20260720090100004", "CONSULTATION_FEE"),
                // 小红 - 双眼皮成形（BALANCE_PAID：尾款已付）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_5, SeedIds.USER_ID_2,
                    BigDecimal("3770.00"), "WECHAT", "SUCCESS",
                    LocalDateTime.of(2026, 7, 26, 9, 30), "TX20260726093000005", "BALANCE"),
                // 小丽 - 鼻综合整形（PENDING_SETTLEMENT：尾款已付，等待结算）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_6, SeedIds.USER_ID_3,
                    BigDecimal("12700.00"), "ALIPAY", "SUCCESS",
                    LocalDateTime.of(2026, 7, 14, 9, 45), "TX20260714094500006", "BALANCE")
            )
        )
    }
}
