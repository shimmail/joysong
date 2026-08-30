package com.joysong.server.notification.service

import com.joysong.server.notification.entity.NotificationEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import java.time.LocalDateTime

class BusinessNotificationServiceTest {

    @Test
    fun `order creation notifies only the user before payment`() {
        val fixture = fixture()

        fixture.service.orderCreated(
            orderId = "order-1",
            userId = "user-1"
        )

        assertEquals(
            listOf(
                Emission(
                    userId = "user-1",
                    type = "ORDER_CREATED",
                    title = "订单已创建",
                    content = "您的订单已创建，请及时查看订单详情。",
                    targetType = "order",
                    targetId = "order-1"
                )
            ),
            fixture.emissions
        )
    }

    @Test
    fun `service activation sends the doctor a project booking while keeping user and consultant targets`() {
        val fixture = fixture()

        fixture.service.orderServiceActivated(
            orderId = "order-1",
            userId = "user-1",
            consultantId = "consultant-1",
            doctorId = "doctor-1",
            projectName = "热玛吉",
            appointmentTime = null
        )

        val expected = listOf(
            Emission("user-1", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "您的订单服务已开启，请查看订单详情。", "order", "order-1"),
            Emission("consultant-1", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "订单服务已开启，请进入服务会话跟进。", "order_service_conversation", "order-1"),
            Emission(
                "doctor-1",
                "ORDER_SERVICE_ACTIVATED",
                "项目预约",
                "预约项目：热玛吉\n预约时间：待确认",
                "professional_doctor_orders",
                "order-1"
            )
        )
        assertEquals(expected.sortedBy(Emission::userId), fixture.emissions.sortedBy(Emission::userId))
    }

    @Test
    fun `doctor project booking formats appointment time for notification display`() {
        val fixture = fixture()

        fixture.service.orderServiceActivated(
            orderId = "order-1",
            userId = "user-1",
            consultantId = "consultant-1",
            doctorId = "doctor-1",
            projectName = "超声炮",
            appointmentTime = LocalDateTime.of(2026, 9, 15, 14, 30, 45)
        )

        val doctorEmission = fixture.emissions.single { it.userId == "doctor-1" }
        assertEquals("预约项目：超声炮\n预约时间：2026-09-15 14:30", doctorEmission.content)
    }

    @Test
    fun `service activation keeps a shared consultant on conversation while the doctor gets the booking`() {
        val fixture = fixture()

        fixture.service.orderServiceActivated(
            orderId = "order-1",
            userId = "shared-user",
            consultantId = "shared-user",
            doctorId = "doctor-1",
            projectName = "热玛吉",
            appointmentTime = null
        )

        assertEquals(
            listOf(
                Emission("shared-user", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "订单服务已开启，请进入服务会话跟进。", "order_service_conversation", "order-1"),
                Emission("doctor-1", "ORDER_SERVICE_ACTIVATED", "项目预约", "预约项目：热玛吉\n预约时间：待确认", "professional_doctor_orders", "order-1")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `order completion merges current legal representatives without duplicate recipients`() {
        val fixture = fixture(legalRepresentatives = listOf("consultant-1", "legal-1", "legal-1"))

        fixture.service.orderCompleted("order-1", "consultant-1", "doctor-1", "institution-1")

        assertEquals(
            listOf(
                Emission("consultant-1", "ORDER_COMPLETED", "订单已完成", "订单已由用户确认完成。", "order", "order-1"),
                Emission("doctor-1", "ORDER_COMPLETED", "订单已完成", "订单已由用户确认完成。", "order", "order-1"),
                Emission("legal-1", "ORDER_COMPLETED", "订单已完成", "订单已由用户确认完成。", "order", "order-1")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `professional submission and withdrawal use role-specific legal representative review targets`() {
        val fixture = fixture(legalRepresentatives = listOf("legal-1"))

        fixture.service.professionalApplicationSubmitted("institution-1", ProfessionalApplicantRole.DOCTOR, "doctor-request-1")
        fixture.service.professionalApplicationWithdrawn("institution-1", ProfessionalApplicantRole.CONSULTANT, "consultant-request-1")

        assertEquals(
            listOf(
                Emission("legal-1", "PROFESSIONAL_APPLICATION_SUBMITTED", "新的医生机构关系申请", "有新的医生机构关系申请待审核。", "professional_doctor_review", "doctor-request-1"),
                Emission("legal-1", "PROFESSIONAL_APPLICATION_WITHDRAWN", "咨询师机构关系申请已撤回", "一项咨询师机构关系申请已撤回。", "professional_consultant_review", "consultant-request-1")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `consultant approval and rejection keep the consultant application target and rejection note`() {
        val fixture = fixture()

        fixture.service.professionalApplicationApproved("consultant-1", ProfessionalApplicantRole.CONSULTANT, "request-1")
        fixture.service.professionalApplicationRejected("consultant-1", ProfessionalApplicantRole.CONSULTANT, "request-2", "机构名不一致")

        assertEquals(
            listOf(
                Emission("consultant-1", "PROFESSIONAL_APPLICATION_APPROVED", "咨询师机构关系申请已通过", "您的咨询师机构关系申请已通过。", "professional_consultant_application", "request-1"),
                Emission("consultant-1", "PROFESSIONAL_APPLICATION_REJECTED", "咨询师机构关系申请未通过", "您的咨询师机构关系申请未通过：机构名不一致", "professional_consultant_application", "request-2")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `professional rejection includes the review note and applicant target contract`() {
        val fixture = fixture()

        fixture.service.professionalApplicationRejected(
            applicantId = "doctor-1",
            applicantRole = ProfessionalApplicantRole.DOCTOR,
            requestId = "request-1",
            reviewNote = "缺少执业证明"
        )

        assertEquals(
            listOf(
                Emission(
                    userId = "doctor-1",
                    type = "PROFESSIONAL_APPLICATION_REJECTED",
                    title = "医生机构关系申请未通过",
                    content = "您的医生机构关系申请未通过：缺少执业证明",
                    targetType = "professional_doctor_application",
                    targetId = "request-1"
                )
            ),
            fixture.emissions
        )
    }

    @Test
    fun `institution project application lifecycle uses legal review and doctor application targets`() {
        val fixture = fixture(legalRepresentatives = listOf("legal-1", "legal-1", "legal-2"))

        fixture.service.institutionProjectApplicationSubmitted("institution-1", "request-1")
        fixture.service.institutionProjectApplicationApproved("doctor-1", "request-2")
        fixture.service.institutionProjectApplicationRejected("doctor-1", "request-3", " 资料不完整 ")

        assertEquals(
            listOf(
                Emission("legal-1", "INSTITUTION_PROJECT_APPLICATION_SUBMITTED", "新的机构项目申请", "有新的机构项目申请待审核。", "institution_project_review", "request-1"),
                Emission("legal-2", "INSTITUTION_PROJECT_APPLICATION_SUBMITTED", "新的机构项目申请", "有新的机构项目申请待审核。", "institution_project_review", "request-1"),
                Emission("doctor-1", "INSTITUTION_PROJECT_APPLICATION_APPROVED", "机构项目申请已通过", "您的机构项目申请已通过。", "institution_project_application", "request-2"),
                Emission("doctor-1", "INSTITUTION_PROJECT_APPLICATION_REJECTED", "机构项目申请未通过", "您的机构项目申请未通过：资料不完整", "institution_project_application", "request-3")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `institution project submission with no active legal representatives creates no notifications`() {
        val fixture = fixture()

        fixture.service.institutionProjectApplicationSubmitted("institution-1", "request-1")

        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `legal representative resolution requires an active role approved non-revoked membership and active user`() {
        val fixture = fixture(legalRepresentatives = listOf("legal-1", "legal-1", "legal-2"))

        val recipients = fixture.service.currentLegalRepresentativeIds("institution-9")

        assertEquals(setOf("legal-1", "legal-2"), recipients)
        assertEquals(listOf("institution-9"), fixture.legalRepresentativeQueryArguments)
        assertTrue(fixture.legalRepresentativeQuery.contains("SELECT DISTINCT im.user_id"))
        assertTrue(fixture.legalRepresentativeQuery.contains("ur.role_code = 'INSTITUTION_LEGAL_REPRESENTATIVE'"))
        assertTrue(fixture.legalRepresentativeQuery.contains("ur.status = 'ACTIVE'"))
        assertTrue(fixture.legalRepresentativeQuery.contains("im.member_role IN ('INSTITUTION_LEGAL_REPRESENTATIVE', 'LEGAL_REPRESENTATIVE')"))
        assertTrue(fixture.legalRepresentativeQuery.contains("im.status = 'APPROVED'"))
        assertTrue(fixture.legalRepresentativeQuery.contains("im.revoked_at IS NULL"))
        assertTrue(fixture.legalRepresentativeQuery.contains("u.deleted_at IS NULL"))
    }

    @Test
    fun `identity approval and rejection use their distinct navigation contracts`() {
        val fixture = fixture()

        fixture.service.identityApplicationApproved("user-1", "application-1")
        fixture.service.identityApplicationRejected("user-1", "application-2", "证件照片不清晰")

        assertEquals(
            listOf(
                Emission("user-1", "IDENTITY_APPLICATION_APPROVED", "身份认证已通过", "您的身份认证申请已通过。", "identity_management", "application-1"),
                Emission("user-1", "IDENTITY_APPLICATION_REJECTED", "身份认证未通过", "您的身份认证申请未通过：证件照片不清晰", "identity_application", "application-2")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `professional identity revocation includes the normalized role reason and identity target`() {
        val fixture = fixture()

        fixture.service.professionalIdentityRevoked(
            userId = "doctor-1",
            roleCode = " doctor ",
            reason = " 资质已过期 "
        )

        assertEquals(
            listOf(
                Emission(
                    userId = "doctor-1",
                    type = "PROFESSIONAL_IDENTITY_REVOKED",
                    title = "专业身份已撤销",
                    content = "您的医生专业身份已被管理员撤销：资质已过期",
                    targetType = "identity_management",
                    targetId = "DOCTOR"
                )
            ),
            fixture.emissions
        )
    }

    @Test
    fun `membership revocation opens consultant relationships and supports legacy legal membership`() {
        val fixture = fixture()

        fixture.service.institutionMembershipRevoked("consultant-1", "CONSULTANT")
        fixture.service.institutionMembershipRevoked("legal-1", "LEGAL_REPRESENTATIVE")

        assertEquals(
            listOf(
                Emission(
                    userId = "consultant-1",
                    type = "INSTITUTION_MEMBERSHIP_REVOKED",
                    title = "机构成员关系已撤销",
                    content = "您的机构成员关系已被管理员撤销。",
                    targetType = "professional_consultant_relationships",
                    targetId = ""
                ),
                Emission(
                    userId = "legal-1",
                    type = "INSTITUTION_MEMBERSHIP_REVOKED",
                    title = "机构成员关系已撤销",
                    content = "您的机构成员关系已被管理员撤销。",
                    targetType = "identity_management",
                    targetId = ""
                )
            ),
            fixture.emissions
        )
    }

    @Test
    fun `refund lifecycle uses the refund target and preserves event specific content`() {
        val fixture = fixture()

        fixture.service.orderRefundRequested("order-1", "user-1", "", "")
        fixture.service.orderRefundApproved("order-1", "user-1", "", "")
        fixture.service.orderRefundRejected("order-1", "user-1", "", "", "退款材料不全")
        fixture.service.orderRefunded("order-1", "user-1", "", "")

        assertEquals(
            listOf(
                Emission("user-1", "ORDER_REFUND_REQUESTED", "退款申请已提交", "订单退款申请已提交，请查看详情。", "order_refund", "order-1"),
                Emission("user-1", "ORDER_REFUND_APPROVED", "退款申请已通过", "订单退款申请已通过，请查看详情。", "order_refund", "order-1"),
                Emission("user-1", "ORDER_REFUND_REJECTED", "退款申请未通过", "订单退款申请未通过：退款材料不全", "order_refund", "order-1"),
                Emission("user-1", "ORDER_REFUNDED", "退款已完成", "订单退款已完成，请查看详情。", "order", "order-1")
            ),
            fixture.emissions
        )
    }

    @Test
    fun `empty recipients create no notifications`() {
        val fixture = fixture()

        fixture.service.orderCancelled(orderId = "order-1", userId = "", consultantId = " ")

        assertTrue(fixture.emissions.isEmpty())
    }

    @Test
    fun `order cancellation keeps its type content and order target`() {
        val fixture = fixture()

        fixture.service.orderCancelled("order-1", "user-1", "")

        assertEquals(
            listOf(Emission("user-1", "ORDER_CANCELLED", "订单已取消", "订单已取消，请查看订单详情。", "order", "order-1")),
            fixture.emissions
        )
    }

    private fun fixture(legalRepresentatives: List<String> = emptyList()): Fixture {
        val notificationService = mockk<NotificationService>()
        val jdbcTemplate = mockk<JdbcTemplate>()
        val emissions = mutableListOf<Emission>()
        var legalRepresentativeQuery = ""
        val legalRepresentativeQueryArguments = mutableListOf<String>()
        every {
            notificationService.createNotification(any(), any(), any(), any(), any(), any())
        } answers {
            emissions += Emission(
                userId = firstArg(),
                type = secondArg(),
                title = thirdArg(),
                content = arg(3),
                targetType = arg(4),
                targetId = arg(5)
            )
            NotificationEntity()
        }
        every {
            jdbcTemplate.queryForList(any<String>(), String::class.java, any<String>())
        } answers {
            legalRepresentativeQuery = firstArg()
            legalRepresentativeQueryArguments += arg<Array<*>>(2).single() as String
            legalRepresentatives
        }
        return Fixture(
            service = BusinessNotificationService(notificationService, jdbcTemplate),
            emissions = emissions,
            queryReader = { legalRepresentativeQuery },
            queryArgumentsReader = { legalRepresentativeQueryArguments.toList() }
        )
    }

    private data class Fixture(
        val service: BusinessNotificationService,
        val emissions: List<Emission>,
        val queryReader: () -> String,
        val queryArgumentsReader: () -> List<String>
    ) {
        val legalRepresentativeQuery: String
            get() = queryReader()

        val legalRepresentativeQueryArguments: List<String>
            get() = queryArgumentsReader()
    }

    private data class Emission(
        val userId: String,
        val type: String,
        val title: String,
        val content: String,
        val targetType: String,
        val targetId: String
    )
}
