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
        if (orderRepository.count() > 0L) return

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
                doctorId = SeedIds.DOC_ID_1,
                institutionProjectId = SeedIds.IP_ID_1,
                orderNo = "JOY20260709103000001",
                remark = "希望填充苹果肌和鼻唇沟",
                consultationFee = BigDecimal("25.00"),
                remainingAmount = BigDecimal("2974.00"),
                paymentTime = LocalDateTime.of(2026, 7, 9, 10, 31)
            ),
            // 小红 - 皮秒祛斑（COMPLETED：已完成，已评价）
            OrderEntity(
                id = SeedIds.ORDER_ID_2,
                userId = SeedIds.USER_ID_2,
                projectName = "皮秒祛斑",
                institutionName = "北京美丽时光医疗美容",
                price = BigDecimal("1999.00"),
                paidAmount = BigDecimal("1999.00"),
                status = OrderStatusEnum.COMPLETED.value,
                createdAt = LocalDateTime.of(2026, 7, 6, 16, 0),
                appointmentTime = LocalDateTime.of(2026, 7, 7, 10, 0),
                hasReview = true,
                projectId = SeedIds.PROJ_ID_4,
                institutionId = SeedIds.INST_ID_2,
                doctorId = SeedIds.DOC_ID_3,
                institutionProjectId = SeedIds.IP_ID_5,
                orderNo = "JOY20260706160000002",
                consultationFee = BigDecimal("20.00"),
                remainingAmount = BigDecimal("1979.00"),
                paymentTime = LocalDateTime.of(2026, 7, 6, 16, 1),
                verifiedAt = LocalDateTime.of(2026, 7, 7, 10, 10),
                verifyCode = "VRF20260707001",
                balancePaidAt = LocalDateTime.of(2026, 7, 7, 10, 30)
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
                doctorId = SeedIds.DOC_ID_4,
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
                doctorId = SeedIds.DOC_ID_2,
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
                doctorId = SeedIds.DOC_ID_1,
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
                projectId = SeedIds.PROJ_ID_6,
                institutionId = SeedIds.INST_ID_3,
                doctorId = SeedIds.DOC_ID_5,
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
        // 评价（6 条，覆盖 PROJECT/DOCTOR/INSTITUTION 三种维度）
        // ============================================================
        jdbcTemplate.batchUpdate(
            "INSERT IGNORE INTO reviews (id, order_id, user_id, doctor_id, rating, content, tags, images, target_type, target_id, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())",
            listOf(
                // 小美 - 玻尿酸填充项目评价（PROJECT）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_1, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, 5,
                    "效果非常自然！王医生手法很专业，填充苹果肌后脸部饱满了许多，年轻了至少五岁。注射过程不到二十分钟，术后即刻就能看到效果。轻微肿胀三天就消了，完全看不出做过。强烈推荐！",
                    "效果好,自然,专业,推荐", "https://via.placeholder.com/300x300?text=HyaluronicAcidResult1,https://via.placeholder.com/300x300?text=HyaluronicAcidResult2", "PROJECT", SeedIds.PROJ_ID_1),
                // 小红 - 皮秒祛斑项目评价（PROJECT）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_3, 5,
                    "效果非常好！做完皮秒一个月了，脸上的雀斑淡了百分之八十，肤色也均匀了很多。张医生很专业，术后也跟进得很及时。强烈推荐给有斑点困扰的姐妹！",
                    "效果好,专业,推荐,无痛", "https://via.placeholder.com/300x300?text=PicoLaserResult1,https://via.placeholder.com/300x300?text=PicoLaserResult2", "PROJECT", SeedIds.PROJ_ID_4),
                // 小美 - 王医生评价（DOCTOR）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_1, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, 5,
                    "王医生是注射美容方面的专家，面诊时很耐心地给我分析了面部情况，建议的填充方案非常合理。注射手法轻柔精准，几乎没有痛感。术后效果自然，朋友们都说我变好看了但说不出哪里变了，这正是我想要的效果！",
                    "专业,手法好,耐心,推荐", "https://via.placeholder.com/300x300?text=HyaluronicAcidRecovery1", "DOCTOR", SeedIds.DOC_ID_1),
                // 小红 - 张医生评价（DOCTOR）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_3, 5,
                    "张医生是皮肤科出身，对激光设备非常了解。面诊时给我分析了斑的类型，非常实在，不会夸大效果。术后还专门打电话询问恢复情况，很有责任心。",
                    "专业,负责,耐心,推荐", "https://via.placeholder.com/300x300?text=PicoLaserRecovery1", "DOCTOR", SeedIds.DOC_ID_3),
                // 小美 - 上海娇颜颂机构评价（INSTITUTION）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_1, SeedIds.USER_ID_1, SeedIds.DOC_ID_1, 5,
                    "上海娇颜颂的环境非常好，干净整洁，设备也很先进。前台接待热情，等候时间短。护士很贴心，全程陪同。王医生的技术更不用说，非常满意的一次体验，会持续回购！",
                    "环境好,服务好,专业,推荐", "https://via.placeholder.com/300x300?text=HyaluronicAcidClinic1,https://via.placeholder.com/300x300?text=HyaluronicAcidClinic2", "INSTITUTION", SeedIds.INST_ID_1),
                // 小红 - 北京美丽时光机构评价（INSTITUTION）
                arrayOf(UUID.randomUUID().toString(), SeedIds.ORDER_ID_2, SeedIds.USER_ID_2, SeedIds.DOC_ID_3, 5,
                    "北京美丽时光的就诊体验非常棒！预约流程很方便，到店后几乎不用等待。护士很温柔，术后还送了修复面膜，很贴心。已经推荐给闺蜜了！",
                    "环境好,服务贴心,推荐,专业", "https://via.placeholder.com/300x300?text=PicoClinic1,https://via.placeholder.com/300x300?text=PicoClinic2", "INSTITUTION", SeedIds.INST_ID_2)
            )
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
