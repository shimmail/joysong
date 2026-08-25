package com.joysong.server.notification.repository

import com.joysong.server.dm.entity.DmConversationEntity
import com.joysong.server.dm.repository.DmConversationRepository
import com.joysong.server.notification.entity.NotificationEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Paths
import java.time.LocalDateTime

@DataJpaTest(
    properties = [
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
    ]
)
class NotificationRepositoryVisibilityTest {

    @Autowired
    private lateinit var notificationRepository: NotificationRepository

    @Autowired
    private lateinit var conversationRepository: DmConversationRepository

    @Test
    fun `user notification queries exclude inbox messages but retain customer service and business events`() {
        conversationRepository.saveAll(
            listOf(
                conversation("direct-chat", DmConversationEntity.DIRECT, "user-1", "consultant-1"),
                conversation("order-chat", DmConversationEntity.ORDER_SERVICE, "user-1", "consultant-1"),
                conversation("customer-service-chat", DmConversationEntity.DIRECT, "user-1", "CS_ADMIN")
            )
        )
        val baseTime = LocalDateTime.of(2026, 8, 25, 12, 0)
        notificationRepository.saveAll(
            listOf(
                notification("direct-dm", "DM_NEW", "direct-chat", baseTime.plusMinutes(6)),
                notification("order-dm", "DM_NEW", "order-chat", baseTime.plusMinutes(5)),
                notification("orphan-dm", "DM_NEW", "missing-chat", baseTime.plusMinutes(4)),
                notification("customer-service-dm", "DM_NEW", "customer-service-chat", baseTime.plusMinutes(3)),
                notification(
                    "order-created",
                    "ORDER_CREATED",
                    "order-1",
                    baseTime.plusMinutes(2),
                    targetType = "order"
                ),
                notification(
                    "activity",
                    "ACTIVITY",
                    "activity-1",
                    baseTime.plusMinutes(1),
                    targetType = "activity"
                )
            )
        )

        val visible = notificationRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            "user-1",
            PageRequest.of(0, 3)
        )
        val total = notificationRepository.countByUserIdAndIsReadAndDeletedAtIsNull("user-1", false)
        val summary = notificationRepository.summarizeUnreadByUserIdAndTypes(
            "user-1",
            setOf("ACTIVITY", "PROMOTION", "MARKETING", "CAMPAIGN", "OFFER")
        )

        assertEquals(
            listOf("customer-service-dm", "order-created", "activity"),
            visible.map { it.id }
        )
        assertEquals(3, total)
        assertEquals(3, summary.total)
        assertEquals(1, summary.activity)
        assertEquals(2, summary.total - summary.activity)
    }

    private fun conversation(
        id: String,
        type: String,
        userAId: String,
        userBId: String
    ) = DmConversationEntity(
        id = id,
        conversationType = type,
        orderId = if (type == DmConversationEntity.ORDER_SERVICE) "order-1" else null,
        userAId = userAId,
        userBId = userBId
    )

    private fun notification(
        id: String,
        type: String,
        targetId: String,
        createdAt: LocalDateTime,
        targetType: String = "dm_conversation"
    ) = NotificationEntity(
        id = id,
        userId = "user-1",
        type = type,
        title = id,
        targetType = targetType,
        targetId = targetId,
        createdAt = createdAt
    )

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun isolatedDataSource(registry: DynamicPropertyRegistry) {
            val worktreeId = Paths.get(System.getProperty("user.dir"))
                .parent
                .fileName
                .toString()
                .replace(Regex("[^A-Za-z0-9]+"), "_")
                .lowercase()
            val databaseName = "myapp_worktree_$worktreeId"
            registry.add("spring.datasource.url") {
                "jdbc:h2:mem:$databaseName;MODE=MySQL;DB_CLOSE_DELAY=-1"
            }
            println("NOTIFICATION_TEST_DB_HOST=in-memory")
            println("NOTIFICATION_TEST_DB_NAME=$databaseName")
        }
    }
}
