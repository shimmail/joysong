package com.joysong.server.notification.service

import com.joysong.server.notification.controller.NotificationController
import com.joysong.server.notification.entity.NotificationEntity
import com.joysong.server.notification.repository.NotificationUnreadCountSummary
import com.joysong.server.notification.repository.NotificationRepository
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.i18n.LocaleContextHolder
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Locale

class NotificationServiceLocalizationTest {

    @AfterEach
    fun clearLocale() {
        LocaleContextHolder.resetLocaleContext()
    }

    @Test
    fun `English notification list localizes every business event from persisted Chinese text`() {
        val repository = mockk<NotificationRepository>()
        val stored = cases.mapIndexed { index, case -> case.toEntity(index) }
        every {
            repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc("user-1", any())
        } returns stored
        LocaleContextHolder.setLocale(Locale.ENGLISH)

        val responses = NotificationService(repository).getNotifications("user-1")

        assertEquals(
            cases.map { LocalizedText(it.expectedTitle, it.expectedContent) },
            responses.map { LocalizedText(it.title, it.content) }
        )
        assertEquals(
            "订单退款申请未通过：退款材料不全",
            stored.single { it.type == "ORDER_REFUND_REJECTED" }.content
        )
    }

    @Test
    fun `Chinese locale and unknown English types keep persisted notification text`() {
        val repository = mockk<NotificationRepository>()
        val service = NotificationService(repository)
        val order = cases.first().toEntity(1)
        val unknown = NotificationEntity(
            id = "notification-unknown",
            userId = "user-1",
            type = "LEGACY_SYSTEM",
            title = "保留的标题",
            content = "保留的正文",
            targetType = "legacy",
            targetId = "legacy-1"
        )
        every {
            repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc("user-1", any())
        } returns listOf(order, unknown)

        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE)
        val chinese = service.getNotifications("user-1")
        LocaleContextHolder.setLocale(Locale.ENGLISH)
        val english = service.getNotifications("user-1")

        assertEquals(LocalizedText("订单已创建", "您的订单已创建，请及时查看订单详情。"), chinese[0].text())
        assertEquals(LocalizedText("保留的标题", "保留的正文"), chinese[1].text())
        assertEquals(LocalizedText("保留的标题", "保留的正文"), english[1].text())
    }

    @Test
    fun `English direct message notifications localize system labels but preserve message excerpts`() {
        val repository = mockk<NotificationRepository>()
        every {
            repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc("user-1", any())
        } returns listOf(
            directMessage("dm-1", "新私信", "你好"),
            directMessage("dm-2", "客服回复", "[图片]"),
            directMessage("dm-3", "用户咨询", "Can you help?")
        )
        LocaleContextHolder.setLocale(Locale.ENGLISH)

        val responses = NotificationService(repository).getNotifications("user-1")

        assertEquals(
            listOf(
                LocalizedText("New direct message", "你好"),
                LocalizedText("Customer service reply", "[Image]"),
                LocalizedText("User inquiry", "Can you help?")
            ),
            responses.map { it.text() }
        )
    }

    @Test
    fun `notification endpoint binds Accept-Language to the requested projection`() {
        val repository = mockk<NotificationRepository>()
        every {
            repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc("user-1", any())
        } returns listOf(cases.first().toEntity(1))
        val mvc = MockMvcBuilders.standaloneSetup(
            NotificationController(NotificationService(repository))
        ).build()
        val principal = UsernamePasswordAuthenticationToken("user-1", "", emptyList())

        mvc.perform(
            get("/api/notifications")
                .principal(principal)
                .header("Accept-Language", "en-US")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].title").value("Order created"))
            .andExpect(
                jsonPath("$.data[0].content")
                    .value("Your order has been created. View the order details.")
            )

        mvc.perform(
            get("/api/notifications")
                .principal(principal)
                .header("Accept-Language", "zh-CN")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].title").value("订单已创建"))
            .andExpect(jsonPath("$.data[0].content").value("您的订单已创建，请及时查看订单详情。"))
    }

    @Test
    fun `unread counts endpoint separates system and activity notifications`() {
        val repository = mockk<NotificationRepository>()
        val summary = mockk<NotificationUnreadCountSummary>()
        every { summary.total } returns 8
        every { summary.activity } returns 3
        every {
            repository.summarizeUnreadByUserIdAndTypes(
                "user-1",
                setOf("ACTIVITY", "PROMOTION", "MARKETING", "CAMPAIGN", "OFFER")
            )
        } returns summary
        val mvc = MockMvcBuilders.standaloneSetup(
            NotificationController(NotificationService(repository))
        ).build()
        val principal = UsernamePasswordAuthenticationToken("user-1", "", emptyList())

        mvc.perform(get("/api/notifications/unread-counts").principal(principal))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.total").value(8))
            .andExpect(jsonPath("$.data.system").value(5))
            .andExpect(jsonPath("$.data.activity").value(3))
    }

    @Test
    fun `English rejection projection accepts legacy separators without losing unknown content`() {
        val repository = mockk<NotificationRepository>()
        every {
            repository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc("user-1", any())
        } returns listOf(
            refundRejection("rejection-fullwidth", "订单退款申请未通过：材料不全"),
            refundRejection("rejection-ascii", "订单退款申请未通过:材料过期"),
            refundRejection("rejection-empty", "订单退款申请未通过。"),
            refundRejection("rejection-legacy", "旧版退款结果：请人工处理")
        )
        LocaleContextHolder.setLocale(Locale.ENGLISH)

        val responses = NotificationService(repository).getNotifications("user-1")

        assertEquals(
            listOf(
                "The order refund request was rejected. Reason: 材料不全",
                "The order refund request was rejected. Reason: 材料过期",
                "The order refund request was rejected.",
                "旧版退款结果：请人工处理"
            ),
            responses.map { it.content }
        )
    }

    private fun directMessage(id: String, title: String, content: String) = NotificationEntity(
        id = id,
        userId = "user-1",
        type = "DM_NEW",
        title = title,
        content = content,
        targetType = "dm_conversation",
        targetId = "conversation-$id"
    )

    private fun refundRejection(id: String, content: String) = NotificationEntity(
        id = id,
        userId = "user-1",
        type = "ORDER_REFUND_REJECTED",
        title = "退款申请未通过",
        content = content,
        targetType = "order_refund",
        targetId = "order-$id"
    )

    private fun NotificationCase.toEntity(index: Int) = NotificationEntity(
        id = "notification-$index",
        userId = "user-1",
        type = type,
        title = storedTitle,
        content = storedContent,
        targetType = targetType,
        targetId = "target-$index"
    )

    private fun com.joysong.server.notification.dto.NotificationResponse.text() =
        LocalizedText(title, content)

    private data class NotificationCase(
        val type: String,
        val targetType: String,
        val storedTitle: String,
        val storedContent: String,
        val expectedTitle: String,
        val expectedContent: String
    )

    private data class LocalizedText(val title: String, val content: String)

    companion object {
        private val cases = listOf(
            NotificationCase(
                "ORDER_CREATED",
                "order",
                "订单已创建",
                "您的订单已创建，请及时查看订单详情。",
                "Order created",
                "Your order has been created. View the order details."
            ),
            NotificationCase(
                "ORDER_SERVICE_ACTIVATED",
                "order",
                "行程服务已开启",
                "您的订单服务已开启，请查看订单详情。",
                "Travel service started",
                "Your order service has started. View the order details."
            ),
            NotificationCase(
                "ORDER_SERVICE_ACTIVATED",
                "order_service_conversation",
                "行程服务已开启",
                "订单服务已开启，请进入服务会话跟进。",
                "Travel service started",
                "The order service has started. Open the service conversation to follow up."
            ),
            NotificationCase(
                "ORDER_COMPLETED",
                "order",
                "订单已完成",
                "订单已由用户确认完成。",
                "Order completed",
                "The user has confirmed the order as completed."
            ),
            NotificationCase(
                "ORDER_REFUND_REQUESTED",
                "order_refund",
                "退款申请已提交",
                "订单退款申请已提交，请查看详情。",
                "Refund request submitted",
                "The order refund request has been submitted. View details."
            ),
            NotificationCase(
                "ORDER_REFUND_APPROVED",
                "order_refund",
                "退款申请已通过",
                "订单退款申请已通过，请查看详情。",
                "Refund request approved",
                "The order refund request has been approved. View details."
            ),
            NotificationCase(
                "ORDER_REFUND_REJECTED",
                "order_refund",
                "退款申请未通过",
                "订单退款申请未通过：退款材料不全",
                "Refund request rejected",
                "The order refund request was rejected. Reason: 退款材料不全"
            ),
            NotificationCase(
                "ORDER_REFUNDED",
                "order",
                "退款已完成",
                "订单退款已完成，请查看详情。",
                "Refund completed",
                "The order refund has been completed. View details."
            ),
            NotificationCase(
                "ORDER_CANCELLED",
                "order",
                "订单已取消",
                "订单已取消，请查看订单详情。",
                "Order cancelled",
                "The order has been cancelled. View the order details."
            ),
            NotificationCase(
                "PROFESSIONAL_APPLICATION_SUBMITTED",
                "professional_doctor_review",
                "新的医生机构关系申请",
                "有新的医生机构关系申请待审核。",
                "New doctor institution relationship application",
                "A new doctor institution relationship application is awaiting review."
            ),
            NotificationCase(
                "PROFESSIONAL_APPLICATION_WITHDRAWN",
                "professional_consultant_review",
                "咨询师机构关系申请已撤回",
                "一项咨询师机构关系申请已撤回。",
                "Consultant institution relationship application withdrawn",
                "A consultant institution relationship application has been withdrawn."
            ),
            NotificationCase(
                "PROFESSIONAL_APPLICATION_APPROVED",
                "professional_doctor_application",
                "医生机构关系申请已通过",
                "您的医生机构关系申请已通过。",
                "Doctor institution relationship application approved",
                "Your doctor institution relationship application has been approved."
            ),
            NotificationCase(
                "PROFESSIONAL_APPLICATION_REJECTED",
                "professional_consultant_application",
                "咨询师机构关系申请未通过",
                "您的咨询师机构关系申请未通过：机构名不一致",
                "Consultant institution relationship application rejected",
                "Your consultant institution relationship application was rejected. Reason: 机构名不一致"
            ),
            NotificationCase(
                "IDENTITY_APPLICATION_APPROVED",
                "identity_management",
                "身份认证已通过",
                "您的身份认证申请已通过。",
                "Identity verification approved",
                "Your identity verification application has been approved."
            ),
            NotificationCase(
                "IDENTITY_APPLICATION_REJECTED",
                "identity_application",
                "身份认证未通过",
                "您的身份认证申请未通过：证件照片不清晰",
                "Identity verification rejected",
                "Your identity verification application was rejected. Reason: 证件照片不清晰"
            )
        )
    }
}
