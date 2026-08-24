package com.joysong.server.notification.service

import com.joysong.server.notification.entity.NotificationEntity
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate

class BusinessNotificationServiceTest {

    @Test
    fun `order creation de-duplicates recipients and keeps the order target contract`() {
        val fixture = fixture()

        fixture.service.orderCreated(
            orderId = "order-1",
            userId = "shared-user",
            consultantId = "shared-user"
        )

        assertEquals(
            listOf(
                Emission(
                    userId = "shared-user",
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
    fun `service activation gives the consultant the conversation target and other recipients the order target`() {
        val fixture = fixture()

        fixture.service.orderServiceActivated(
            orderId = "order-1",
            userId = "user-1",
            consultantId = "consultant-1",
            doctorId = "doctor-1"
        )

        assertEquals(
            listOf(
                Emission("user-1", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "您的订单服务已开启，请查看订单详情。", "order", "order-1"),
                Emission("consultant-1", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "订单服务已开启，请进入服务会话跟进。", "order_service_conversation", "order-1"),
                Emission("doctor-1", "ORDER_SERVICE_ACTIVATED", "行程服务已开启", "您的订单服务已开启，请查看订单详情。", "order", "order-1")
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
    fun `legal representative resolution requires an active role approved non-revoked membership and active user`() {
        val fixture = fixture(legalRepresentatives = listOf("legal-1", "legal-1", "legal-2"))

        val recipients = fixture.service.currentLegalRepresentativeIds("institution-1")

        assertEquals(setOf("legal-1", "legal-2"), recipients)
        assertTrue(fixture.legalRepresentativeQuery.contains("ur.status = 'ACTIVE'"))
        assertTrue(fixture.legalRepresentativeQuery.contains("im.status = 'APPROVED'"))
        assertTrue(fixture.legalRepresentativeQuery.contains("im.revoked_at IS NULL"))
        assertTrue(fixture.legalRepresentativeQuery.contains("u.deleted_at IS NULL"))
    }

    @Test
    fun `empty recipients create no notifications`() {
        val fixture = fixture()

        fixture.service.orderCancelled(orderId = "order-1", userId = "", consultantId = " ")

        assertTrue(fixture.emissions.isEmpty())
    }

    private fun fixture(legalRepresentatives: List<String> = emptyList()): Fixture {
        val notificationService = mockk<NotificationService>()
        val jdbcTemplate = mockk<JdbcTemplate>()
        val emissions = mutableListOf<Emission>()
        var legalRepresentativeQuery = ""
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
            jdbcTemplate.queryForList(any<String>(), String::class.java, "institution-1")
        } answers {
            legalRepresentativeQuery = firstArg()
            legalRepresentatives
        }
        return Fixture(
            service = BusinessNotificationService(notificationService, jdbcTemplate),
            emissions = emissions,
            queryReader = { legalRepresentativeQuery }
        )
    }

    private data class Fixture(
        val service: BusinessNotificationService,
        val emissions: List<Emission>,
        val queryReader: () -> String
    ) {
        val legalRepresentativeQuery: String
            get() = queryReader()
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
