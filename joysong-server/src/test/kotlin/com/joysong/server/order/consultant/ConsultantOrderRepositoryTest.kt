package com.joysong.server.order.consultant

import com.joysong.server.common.OffsetPageRequest
import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.order.dto.OrderStatusEnum
import com.joysong.server.order.entity.OrderEntity
import com.joysong.server.order.repository.OrderRepository
import com.joysong.server.support.WorktreeTestDatabase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.math.BigDecimal
import java.time.LocalDateTime

@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class ConsultantOrderRepositoryTest {
    @Autowired
    private lateinit var orders: OrderRepository

    @Autowired
    private lateinit var conversations: DmConversationRepository

    @Test
    fun activeOrdersAreScopedAndNullAppointmentsSortLast() {
        orders.saveAll(
            listOf(
                order("second", "consultant-1", appointment = time(12)),
                order("first", "consultant-1", appointment = time(10)),
                order("last", "consultant-1", appointment = null),
                order("other", "consultant-2", appointment = time(9)),
                order("legacy", "consultant-1", flow = "LEGACY_MEDICAL")
            )
        )

        val result = orders.findActiveConsultantOrders(
            "consultant-1",
            "TRAVEL_GROUND_SERVICE_ONLY",
            null,
            OffsetPageRequest(0, 10)
        )

        assertEquals(listOf("first", "second", "last"), result.map(OrderEntity::id))
    }

    @Test
    fun activeOrdersHonorInstitutionFilterAndArbitraryOffset() {
        orders.saveAll(
            listOf(
                order("first", "consultant-1", appointment = time(10), institutionId = "institution-1"),
                order("second", "consultant-1", appointment = time(11), institutionId = "institution-1"),
                order("third", "consultant-1", appointment = time(12), institutionId = "institution-1"),
                order("other-institution", "consultant-1", appointment = time(9), institutionId = "institution-2")
            )
        )

        val result = orders.findActiveConsultantOrders(
            "consultant-1",
            "TRAVEL_GROUND_SERVICE_ONLY",
            "institution-1",
            OffsetPageRequest(1, 2)
        )

        assertEquals(listOf("second", "third"), result.map(OrderEntity::id))
    }

    @Test
    fun historyOrdersUseStatusScopeAndUpdatedAtFallbackOrdering() {
        orders.saveAll(
            listOf(
                order(
                    "created-fallback",
                    "consultant-1",
                    status = OrderStatusEnum.COMPLETED.value,
                    createdAt = time(13),
                    updatedAt = null
                ),
                order(
                    "updated",
                    "consultant-1",
                    status = OrderStatusEnum.REFUNDED.value,
                    createdAt = time(8),
                    updatedAt = time(12)
                ),
                order(
                    "wrong-status",
                    "consultant-1",
                    status = OrderStatusEnum.SERVICE_ACTIVE.value,
                    createdAt = time(14)
                )
            )
        )

        val result = orders.findConsultantHistoryOrders(
            "consultant-1",
            "TRAVEL_GROUND_SERVICE_ONLY",
            setOf(OrderStatusEnum.COMPLETED.value, OrderStatusEnum.REFUNDED.value),
            null,
            OffsetPageRequest(0, 10)
        )

        assertEquals(listOf("created-fallback", "updated"), result.map(OrderEntity::id))
    }

    @Test
    fun orderServiceConversationProjectionReturnsOnlyRequestedOrderIds() {
        conversations.saveAll(
            listOf(
                orderConversation("c1", "order-1"),
                orderConversation("c2", "order-2"),
                directConversation("direct")
            )
        )

        assertEquals(
            setOf("order-1"),
            conversations.findOrderServiceOrderIds(setOf("order-1", "missing")).toSet()
        )
    }

    private fun order(
        id: String,
        consultantId: String,
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value,
        appointment: LocalDateTime? = null,
        flow: String = "TRAVEL_GROUND_SERVICE_ONLY",
        institutionId: String = "institution-1",
        createdAt: LocalDateTime = time(8),
        updatedAt: LocalDateTime? = time(9)
    ) = OrderEntity(
        id = id,
        userId = "user-$id",
        projectName = "项目",
        price = BigDecimal.ZERO,
        status = status,
        paymentFlow = flow,
        institutionId = institutionId,
        consultantId = consultantId,
        appointmentTime = appointment,
        serviceActivatedAt = time(7),
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    private fun orderConversation(id: String, orderId: String) = DmConversationEntity(
        id = id,
        conversationType = DmConversationEntity.ORDER_SERVICE,
        orderId = orderId,
        userAId = "user-1",
        userBId = "consultant-1"
    )

    private fun directConversation(id: String) = DmConversationEntity(
        id = id,
        conversationType = DmConversationEntity.DIRECT,
        userAId = "user-1",
        userBId = "consultant-1"
    )

    private fun time(hour: Int) = LocalDateTime.of(2026, 8, 29, hour, 0)

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun isolatedDataSource(registry: DynamicPropertyRegistry) {
            val databaseName = WorktreeTestDatabase.databaseName()
            require(databaseName.startsWith("myapp_worktree_"))
            println("CONSULTANT_ORDER_TEST_DB_HOST=in-memory")
            println("CONSULTANT_ORDER_TEST_DB_NAME=$databaseName")
            registry.add("spring.datasource.url") {
                "jdbc:h2:mem:$databaseName;MODE=MySQL;DB_CLOSE_DELAY=-1"
            }
        }
    }
}
