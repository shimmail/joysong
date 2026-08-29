package com.joysong.server.order.consultant

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.identity.service.IdentityAuthorizationService
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.Optional

class ConsultantOrderQueryServiceTest {
    private val orders = mockk<OrderRepository>()
    private val conversations = mockk<DmConversationRepository>()
    private val users = mockk<UserRepository>()
    private val identities = mockk<IdentityAuthorizationService>()
    private val service = ConsultantOrderQueryService(
        orders,
        conversations,
        users,
        ConsultantOrderAccessPolicy(identities)
    )

    @BeforeEach
    fun allowConsultant() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns true
    }

    @Test
    fun listUsesLimitPlusOneAndBatchProjectsConversations() {
        val pageable = slot<Pageable>()
        every {
            orders.findActiveConsultantOrders(
                "consultant-1",
                "TRAVEL_GROUND_SERVICE_ONLY",
                null,
                capture(pageable)
            )
        } returns listOf(order("o1"), order("o2"), order("o3"))
        every { users.findAllById(any<Iterable<String>>()) } returns listOf(publicUser())
        every { conversations.findOrderServiceOrderIds(setOf("o1", "o2")) } returns listOf("o1")

        val page = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 7, 2)
        )

        assertEquals(7L, pageable.captured.offset)
        assertEquals(3, pageable.captured.pageSize)
        assertEquals(listOf("o1", "o2"), page.items.map { it.id })
        assertEquals(7, page.offset)
        assertEquals(2, page.limit)
        assertTrue(page.hasMore)
        assertTrue(page.items.first().conversationReadable)
        assertTrue(page.items[1].messageSendable)
        verify(exactly = 1) { conversations.findOrderServiceOrderIds(setOf("o1", "o2")) }
    }

    @Test
    fun emptyPageSkipsConversationProjection() {
        every { orders.findActiveConsultantOrders(any(), any(), any(), any()) } returns emptyList()
        every { users.findAllById(emptyList<String>()) } returns emptyList()

        val page = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 0, 10)
        )

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        verify(exactly = 0) { conversations.findOrderServiceOrderIds(any()) }
    }

    @Test
    fun activeStageMapsToActiveRepositoryWithInstitutionFilter() {
        every {
            orders.findActiveConsultantOrders(
                "consultant-1",
                "TRAVEL_GROUND_SERVICE_ONLY",
                "institution-1",
                any()
            )
        } returns listOf(order("active", status = OrderStatusEnum.SERVICE_ACTIVE.value))
        every { users.findAllById(any<Iterable<String>>()) } returns listOf(publicUser())
        every { conversations.findOrderServiceOrderIds(setOf("active")) } returns emptyList()

        val page = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, "institution-1", 0, 10)
        )

        assertEquals("ACTIVE", page.items.single().stage)
        assertTrue(page.items.single().conversationReadable)
        assertTrue(page.items.single().messageSendable)
        assertFalse(page.items.single().readOnly)
    }

    @Test
    fun pausedStageMapsToPausedStatusesAndDisablesConversationActionsWhenMissing() {
        every {
            orders.findConsultantHistoryOrders(
                "consultant-1",
                "TRAVEL_GROUND_SERVICE_ONLY",
                setOf(OrderStatusEnum.REFUND_REVIEW.value, OrderStatusEnum.REFUND_PROCESSING.value),
                null,
                any()
            )
        } returns listOf(order("paused", status = OrderStatusEnum.REFUND_REVIEW.value))
        every { users.findAllById(any<Iterable<String>>()) } returns listOf(publicUser())
        every { conversations.findOrderServiceOrderIds(setOf("paused")) } returns emptyList()

        val item = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.PAUSED, null, 0, 10)
        ).items.single()

        assertEquals("PAUSED", item.stage)
        assertFalse(item.conversationReadable)
        assertFalse(item.messageSendable)
        assertTrue(item.readOnly)
    }

    @Test
    fun historyStageMapsToHistoryStatusesAndFallsBackToCreatedAt() {
        val createdAt = time(8)
        every {
            orders.findConsultantHistoryOrders(
                "consultant-1",
                "TRAVEL_GROUND_SERVICE_ONLY",
                setOf(OrderStatusEnum.COMPLETED.value, OrderStatusEnum.REFUNDED.value),
                "institution-2",
                any()
            )
        } returns listOf(
            order(
                "history",
                status = OrderStatusEnum.COMPLETED.value,
                createdAt = createdAt,
                updatedAt = null
            )
        )
        every { users.findAllById(any<Iterable<String>>()) } returns listOf(publicUser())
        every { conversations.findOrderServiceOrderIds(setOf("history")) } returns emptyList()

        val item = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.HISTORY, "institution-2", 0, 10)
        ).items.single()

        assertEquals("HISTORY", item.stage)
        assertEquals(createdAt, item.updatedAt)
        assertFalse(item.conversationReadable)
        assertFalse(item.messageSendable)
        assertTrue(item.readOnly)
    }

    @Test
    fun listAnonymizesErasedAndDeletedCustomersAndUsesFallbackForBlankActiveNickname() {
        every { orders.findActiveConsultantOrders(any(), any(), any(), any()) } returns listOf(
            order("erased-order", userId = "erased"),
            order("deleted-order", userId = "deleted"),
            order("blank-order", userId = "blank")
        )
        every { conversations.findOrderServiceOrderIds(any()) } returns emptyList()
        every { users.findAllById(any<Iterable<String>>()) } returns listOf(
            user("erased", "旧昵称", "legacy-erased.png", AccountState.ERASED),
            user("deleted", "旧昵称", "legacy-deleted.png", deletedAt = time(12)),
            user("blank", "   ", "   ")
        )

        val customers = service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 0, 10)
        ).items.associate { it.id to it.customer }

        assertEquals("匿名用户", customers.getValue("erased-order").displayName)
        assertNull(customers.getValue("erased-order").avatar)
        assertEquals("匿名用户", customers.getValue("deleted-order").displayName)
        assertNull(customers.getValue("deleted-order").avatar)
        assertEquals("用户", customers.getValue("blank-order").displayName)
        assertNull(customers.getValue("blank-order").avatar)
    }

    @Test
    fun detailUsesAnyStateCustomerAndExposesOnlyWhitelistedFields() {
        val detailOrder = order(
            "detail",
            userId = "deleted",
            status = OrderStatusEnum.COMPLETED.value,
            createdAt = time(8),
            updatedAt = null
        )
        every { orders.findById("detail") } returns Optional.of(detailOrder)
        every {
            conversations.findByConversationTypeAndOrderId(DmConversationEntity.ORDER_SERVICE, "detail")
        } returns DmConversationEntity(
            id = "conversation-1",
            conversationType = DmConversationEntity.ORDER_SERVICE,
            orderId = "detail",
            userAId = "deleted",
            userBId = "consultant-1"
        )
        every { users.findByIdAnyState("deleted") } returns user(
            "deleted",
            "旧昵称",
            "legacy-deleted.png",
            deletedAt = time(12)
        )

        val detail = service.detail("consultant-1", "detail")
        val json = jacksonObjectMapper().findAndRegisterModules().writeValueAsString(detail)

        assertEquals("HISTORY", detail.stage)
        assertEquals("匿名用户", detail.customer.displayName)
        assertNull(detail.customer.avatar)
        assertTrue(detail.conversationReadable)
        assertFalse(detail.messageSendable)
        assertTrue(detail.readOnly)
        assertTrue(detail.conversation.readable)
        assertFalse(detail.conversation.sendable)
        assertEquals(time(8), detail.updatedAt)
        listOf("userPhone", "verifyCode", "price", "refundAmount", "evidenceUrl").forEach {
            assertFalse(json.contains("\"$it\""), "must not serialize $it")
        }
    }

    private fun order(
        id: String,
        userId: String = "user-1",
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value,
        createdAt: LocalDateTime = time(8),
        updatedAt: LocalDateTime? = time(9)
    ) = OrderEntity(
        id = id,
        userId = userId,
        orderNo = "NO-$id",
        projectId = "project-1",
        projectName = "项目",
        coverImage = "cover.png",
        institutionId = "institution-1",
        institutionName = "机构",
        consultantId = "consultant-1",
        doctorId = "doctor-1",
        doctorName = "医生",
        appointmentTime = time(10),
        price = BigDecimal("999.00"),
        status = status,
        refundStatus = "NONE",
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        serviceActivatedAt = time(7),
        remark = "备注",
        createdAt = createdAt,
        updatedAt = updatedAt,
        userPhone = "13800138000",
        verifyCode = "secret-code",
        refundAmount = BigDecimal("88.00"),
        evidenceUrl = "secret-evidence.png"
    )

    private fun publicUser() = user("user-1", "公开昵称", "avatar.png")

    private fun user(
        id: String,
        nickname: String,
        avatar: String,
        accountState: AccountState = AccountState.ACTIVE,
        deletedAt: LocalDateTime? = null
    ) = UserEntity(
        id = id,
        passwordHash = "test-only",
        nickname = nickname,
        avatar = avatar,
        accountState = accountState,
        deletedAt = deletedAt
    )

    private fun time(hour: Int) = LocalDateTime.of(2026, 8, 29, hour, 0)
}
