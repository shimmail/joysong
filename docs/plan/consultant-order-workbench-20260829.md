# 顾问独立订单工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (<code>- [ ]</code>) syntax for tracking.

**Goal:** 为有效顾问提供只查看本人已激活旅游地接订单的独立工作台、隐私裁剪详情和订单服务会话入口，并在顾问身份失效后立即关闭顾问侧订单与会话权限。

**Architecture:** 后端在现有订单域内新增 <code>order/consultant</code> 高内聚查询模块，以认证主体和固化的 <code>orders.consultant_id</code> 做对象级授权；现有订单会话继续由 <code>OrderServiceConversationService</code> 统一执行读取和发送校验。Flutter 新增独立 <code>features/consultant_orders</code> 功能，通过管理上下文能力显示入口，并只复用现有网络、媒体解析和订单会话导航能力。

**Tech Stack:** Kotlin 1.9、Spring Boot 3、Spring Security、Spring Data JPA、MockK、JUnit 5、MockMvc、Flutter/Dart、ChangeNotifier、flutter_test。

**Spec:** <code>docs/superpowers/specs/2026-08-29-consultant-order-workbench-design.md</code>

## Global Constraints

- 固定在分支 <code>codex/consultant-order-workbench</code> 和 worktree <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench</code> 中实施；不得修改主检出区的用户改动。
- 首期只处理 <code>TRAVEL_GROUND_SERVICE_ONLY</code> 且 <code>service_activated_at</code> 非空的订单；不新增数据库迁移和履约状态。
- 阶段固定为 <code>ACTIVE=SERVICE_ACTIVE</code>、<code>PAUSED=REFUND_REVIEW|REFUND_PROCESSING</code>、<code>HISTORY=COMPLETED|REFUNDED</code>。
- 顾问退出机构不影响已经激活并固化给该顾问的订单；顾问角色撤销返回 <code>403 CONSULTANT_ROLE_REQUIRED</code>；账号暂停或注销由现有 JWT 链返回 <code>401</code>。
- 普通订单用户路径不要求顾问角色；顾问专属策略只在请求者等于 <code>order.consultantId</code> 时执行。
- 列表和详情使用字段白名单，不返回手机号、真实姓名、核销码、二维码、金额、支付、退款证据、分账、结算或状态日志。
- <code>ACTIVE</code> 可创建、读取并发送订单消息；<code>PAUSED</code> 和 <code>HISTORY</code> 只可读取已经存在的 <code>ORDER_SERVICE</code> 会话，不能补建会话或发送消息。
- Flutter 不解析服务端中文消息；401 走现有登录失效流程，顾问角色错误只依据稳定 <code>errorCode</code> 处理。
- 用户已确认本次不编写或更新 API 文档、MVP 文档、手工验收文档和 UML；除本计划与已批准设计规范外，不产生文档类改动。
- 所有后端命令的工作目录固定为 <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-server</code>；所有 Flutter 命令的工作目录固定为 <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-flutter</code>。
- Gradle 的可写 <code>GRADLE_USER_HOME</code> 固定在当前 worktree 的 <code>joysong-server\.tmp\gradle-user-home-codex</code>。首次运行只可把主检出区现有缓存复制进该目录作为只读种子，不得把主检出区缓存目录设为可写 Gradle Home。
- 测试先运行最小相关类，修复时只运行失败类；相关测试通过后后端和 Flutter 各最多运行一次全量测试。全量超过 10 分钟立即停止并报告。
- 不连接共享开发数据库。内存数据库名必须以 <code>myapp_worktree_</code> 开头；本功能无迁移，不运行生产或共享库迁移。

---

## File Structure

### Backend

- <code>order/consultant/ConsultantOrderContract.kt</code>：阶段、列表查询参数和稳定查询解析。
- <code>order/consultant/ConsultantOrderDtos.kt</code>：顾问专用字段白名单 DTO。
- <code>order/consultant/ConsultantOrderAccessPolicy.kt</code>：顾问身份、订单归属和会话参与者规则。
- <code>order/consultant/ConsultantOrderQueryService.kt</code>：只读分页、详情、用户公开投影和会话能力投影。
- <code>order/consultant/ConsultantOrderController.kt</code>：两个 GET 端点及认证主体提取。
- <code>order/service/OrderContractException.kt</code>：顾问订单和订单服务会话共享的真实 HTTP 错误契约。
- 现有 <code>OrderRepository</code> 只增加受限查询；现有 <code>DmConversationRepository</code> 只增加当前页订单会话批量投影。

### Flutter

- <code>features/consultant_orders/domain</code>：顾问订单模型和仓库接口。
- <code>features/consultant_orders/data</code>：专用 API 数据源及薄仓库实现。
- <code>features/consultant_orders/presentation</code>：三阶段列表状态、详情状态、卡片、列表页和详情页。
- 管理中心只负责能力入口和导航装配；AppShell 继续唯一持有订单会话打开逻辑。

---

### Task 1: 固化后端错误、阶段与顾问访问策略

**Files:**

- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderContractException.kt</code>
- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderContract.kt</code>
- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderAccessPolicy.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderAccessPolicyTest.kt</code>

**Interfaces:**

- Produces: <code>OrderContractException(status: HttpStatus, errorCode: OrderContractErrorCode, message: String)</code>.
- Produces: <code>ConsultantOrderStage.parse(String?): ConsultantOrderStage</code>.
- Produces: <code>ConsultantOrderListQuery.parse(stage, institutionId, offset, limit)</code>.
- Produces: <code>ConsultantOrderAccessPolicy.requireActiveConsultant</code>、<code>requireWorkbenchOrder</code>、<code>requireConversationParticipant</code>.

- [ ] **Step 1: 写顾问策略失败测试**

~~~kotlin
class ConsultantOrderAccessPolicyTest {
    private val identities = mockk<IdentityAuthorizationService>()
    private val policy = ConsultantOrderAccessPolicy(identities)

    @Test
    fun revokedConsultantGetsStableForbiddenError() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns false

        val error = assertThrows<OrderContractException> {
            policy.requireActiveConsultant("consultant-1")
        }

        assertEquals(HttpStatus.FORBIDDEN, error.status)
        assertEquals(OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED, error.errorCode)
    }

    @Test
    fun consumerParticipantDoesNotRequireConsultantRole() {
        policy.requireConversationParticipant(order(userId = "user-1"), "user-1")
        verify(exactly = 0) { identities.hasActiveRole(any(), any()) }
    }

    @Test
    fun consultantParticipantIsRevalidatedOnEveryConversationAccess() {
        every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns true
        policy.requireConversationParticipant(order(consultantId = "consultant-1"), "consultant-1")
        verify(exactly = 1) { identities.hasActiveRole("consultant-1", "CONSULTANT") }
    }

    private fun order(
        userId: String = "user-1",
        consultantId: String = "consultant-1",
        status: String = OrderStatusEnum.SERVICE_ACTIVE.value
    ) = OrderEntity(
        id = "order-1",
        userId = userId,
        consultantId = consultantId,
        projectName = "项目",
        price = BigDecimal.ZERO,
        status = status,
        paymentFlow = "TRAVEL_GROUND_SERVICE_ONLY",
        serviceActivatedAt = LocalDateTime.of(2026, 8, 29, 10, 0)
    )
}
~~~

- [ ] **Step 2: 运行测试并确认红灯**

Run from the worktree <code>joysong-server</code>. Before the first backend test, seed the worktree-local cache once:

~~~powershell
$seedGradleCache = 'D:\code\kotlin\joysong\joysong-server\.tmp\gradle-user-home-codex'
$worktreeGradleCache = Join-Path $PWD '.tmp\gradle-user-home-codex'
New-Item -ItemType Directory -Force -Path $worktreeGradleCache | Out-Null
foreach ($cachePart in @('caches', 'wrapper')) {
    $sourcePart = Join-Path $seedGradleCache $cachePart
    $targetPart = Join-Path $worktreeGradleCache $cachePart
    if ((Test-Path -LiteralPath $sourcePart) -and
        -not (Test-Path -LiteralPath $targetPart)) {
        Copy-Item -LiteralPath $sourcePart -Destination $targetPart -Recurse
    }
}
$env:GRADLE_USER_HOME = $worktreeGradleCache
.\gradlew.bat --offline test --tests com.joysong.server.order.consultant.ConsultantOrderAccessPolicyTest
~~~

Expected: compilation fails because the contract and policy types do not exist.

- [ ] **Step 3: 实现共享错误类型**

~~~kotlin
enum class OrderContractErrorCode {
    INVALID_CONSULTANT_ORDER_STAGE,
    CONSULTANT_ROLE_REQUIRED,
    CONSULTANT_ORDER_NOT_FOUND,
    ORDER_SERVICE_ACCESS_DENIED,
    ORDER_SERVICE_NOT_ACTIVE,
    ORDER_SERVICE_READ_ONLY
}

class OrderContractException(
    val status: HttpStatus,
    val errorCode: OrderContractErrorCode,
    message: String
) : RuntimeException(message) {
    companion object {
        fun invalidQuery() = OrderContractException(
            HttpStatus.BAD_REQUEST,
            OrderContractErrorCode.INVALID_CONSULTANT_ORDER_STAGE,
            "顾问订单查询参数不正确"
        )
        fun roleRequired() = OrderContractException(
            HttpStatus.FORBIDDEN,
            OrderContractErrorCode.CONSULTANT_ROLE_REQUIRED,
            "顾问身份无效"
        )
        fun consultantOrderNotFound() = OrderContractException(
            HttpStatus.NOT_FOUND,
            OrderContractErrorCode.CONSULTANT_ORDER_NOT_FOUND,
            "订单不存在"
        )
        fun serviceAccessDenied() = OrderContractException(
            HttpStatus.NOT_FOUND,
            OrderContractErrorCode.ORDER_SERVICE_ACCESS_DENIED,
            "订单会话不存在"
        )
        fun serviceNotActive() = OrderContractException(
            HttpStatus.CONFLICT,
            OrderContractErrorCode.ORDER_SERVICE_NOT_ACTIVE,
            "订单服务尚未激活"
        )
        fun serviceReadOnly() = OrderContractException(
            HttpStatus.CONFLICT,
            OrderContractErrorCode.ORDER_SERVICE_READ_ONLY,
            "当前订单会话只允许读取"
        )
    }
}
~~~

- [ ] **Step 4: 实现阶段与查询参数解析**

~~~kotlin
enum class ConsultantOrderStage(val statuses: Set<String>) {
    ACTIVE(setOf(OrderStatusEnum.SERVICE_ACTIVE.value)),
    PAUSED(setOf(
        OrderStatusEnum.REFUND_REVIEW.value,
        OrderStatusEnum.REFUND_PROCESSING.value
    )),
    HISTORY(setOf(
        OrderStatusEnum.COMPLETED.value,
        OrderStatusEnum.REFUNDED.value
    ));

    companion object {
        fun parse(raw: String?): ConsultantOrderStage {
            val wire = raw ?: ACTIVE.name
            return entries.firstOrNull { it.name == wire }
                ?: throw OrderContractException.invalidQuery()
        }

        fun fromStatus(status: String): ConsultantOrderStage? =
            entries.firstOrNull { status in it.statuses }
    }
}

data class ConsultantOrderListQuery(
    val stage: ConsultantOrderStage,
    val institutionId: String?,
    val offset: Int,
    val limit: Int
) {
    companion object {
        fun parse(
            stage: String?,
            institutionId: String?,
            offset: String?,
            limit: String?
        ): ConsultantOrderListQuery {
            val parsedOffset = (offset ?: "0").toIntOrNull()
                ?: throw OrderContractException.invalidQuery()
            val parsedLimit = (limit ?: "20").toIntOrNull()
                ?: throw OrderContractException.invalidQuery()
            if (parsedOffset < 0 || parsedLimit !in 1..100) {
                throw OrderContractException.invalidQuery()
            }
            return ConsultantOrderListQuery(
                stage = ConsultantOrderStage.parse(stage),
                institutionId = institutionId?.trim()?.takeIf(String::isNotEmpty),
                offset = parsedOffset,
                limit = parsedLimit
            )
        }
    }
}
~~~

- [ ] **Step 5: 实现对象级访问策略**

~~~kotlin
@Service
class ConsultantOrderAccessPolicy(
    private val identities: IdentityAuthorizationService
) {
    fun requireActiveConsultant(userId: String) {
        if (!identities.hasActiveRole(userId, "CONSULTANT")) {
            throw OrderContractException.roleRequired()
        }
    }

    fun requireWorkbenchOrder(
        order: OrderEntity,
        consultantId: String
    ): ConsultantOrderStage {
        requireActiveConsultant(consultantId)
        val stage = ConsultantOrderStage.fromStatus(order.status)
        if (
            order.consultantId != consultantId ||
            order.paymentFlow != "TRAVEL_GROUND_SERVICE_ONLY" ||
            order.serviceActivatedAt == null ||
            stage == null
        ) {
            throw OrderContractException.consultantOrderNotFound()
        }
        return stage
    }

    fun requireConversationParticipant(order: OrderEntity, requesterId: String) {
        when (requesterId) {
            order.userId -> Unit
            order.consultantId -> requireActiveConsultant(requesterId)
            else -> throw OrderContractException.serviceAccessDenied()
        }
    }
}
~~~

- [ ] **Step 6: 运行策略测试并确认通过**

Run the Task 1 command again. Expected: PASS.

- [ ] **Step 7: 提交 Task 1**

~~~powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/order/service/OrderContractException.kt joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderContract.kt joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderAccessPolicy.kt joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderAccessPolicyTest.kt
git commit -m "feat(order): define consultant order access contract"
~~~

### Task 2: 实现顾问订单受限查询、分页与字段白名单

**Files:**

- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderDtos.kt</code>
- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderQueryService.kt</code>
- Modify: <code>joysong-server/src/main/kotlin/com/joysong/server/order/repository/OrderRepository.kt</code>
- Modify: <code>joysong-server/src/main/kotlin/com/joysong/server/dm/repository/DmConversationRepository.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderRepositoryTest.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderQueryServiceTest.kt</code>

**Interfaces:**

- Consumes: Task 1 stage, query and policy types.
- Produces: <code>ConsultantOrderQueryService.list(consultantId, query): ConsultantOrderPageResponse</code>.
- Produces: <code>ConsultantOrderQueryService.detail(consultantId, orderId): ConsultantOrderDetailResponse</code>.
- Produces: repository methods returning <code>List&lt;OrderEntity&gt;</code>, never <code>Page</code>.

- [ ] **Step 1: 写仓储过滤和排序红灯测试**

Use the existing isolated H2/MySQL-mode pattern and print the resolved host/name before persistence:

~~~kotlin
@DataJpaTest(properties = [
    "spring.flyway.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect"
])
class ConsultantOrderRepositoryTest {
    @Autowired lateinit var orders: OrderRepository
    @Autowired lateinit var conversations: DmConversationRepository

    @Test
    fun activeOrdersAreScopedAndNullAppointmentsSortLast() {
        orders.saveAll(listOf(
            order("second", "consultant-1", SERVICE_ACTIVE, appointment = time(12)),
            order("first", "consultant-1", SERVICE_ACTIVE, appointment = time(10)),
            order("last", "consultant-1", SERVICE_ACTIVE, appointment = null),
            order("other", "consultant-2", SERVICE_ACTIVE, appointment = time(9)),
            order("legacy", "consultant-1", SERVICE_ACTIVE, flow = "LEGACY_MEDICAL")
        ))

        val result = orders.findActiveConsultantOrders(
            "consultant-1",
            "TRAVEL_GROUND_SERVICE_ONLY",
            null,
            OffsetPageRequest(0, 10)
        )

        assertEquals(listOf("first", "second", "last"), result.map(OrderEntity::id))
    }

    @Test
    fun orderServiceConversationProjectionReturnsOnlyRequestedOrderIds() {
        conversations.saveAll(listOf(
            orderConversation("c1", "order-1"),
            orderConversation("c2", "order-2"),
            directConversation("direct")
        ))
        assertEquals(
            setOf("order-1"),
            conversations.findOrderServiceOrderIds(setOf("order-1", "missing")).toSet()
        )
    }
}
~~~

Dynamic database configuration:

~~~kotlin
companion object {
    @JvmStatic
    @DynamicPropertySource
    fun isolatedDataSource(registry: DynamicPropertyRegistry) {
        val databaseName = WorktreeTestDatabase.databaseName()
        println("CONSULTANT_ORDER_TEST_DB_HOST=in-memory")
        println("CONSULTANT_ORDER_TEST_DB_NAME=$databaseName")
        registry.add("spring.datasource.url") {
            "jdbc:h2:mem:$databaseName;MODE=MySQL;DB_CLOSE_DELAY=-1"
        }
    }
}
~~~

Import <code>com.joysong.server.common.OffsetPageRequest</code>, <code>com.joysong.server.support.WorktreeTestDatabase</code>, <code>DynamicPropertyRegistry</code> and <code>DynamicPropertySource</code>. Keep the companion object inside <code>ConsultantOrderRepositoryTest</code>; <code>@JvmStatic</code> must not be placed on a top-level Kotlin function.

- [ ] **Step 2: 写查询服务红灯测试**

Cover all of these assertions in <code>ConsultantOrderQueryServiceTest</code>:

~~~kotlin
@Test
fun listUsesLimitPlusOneAndBatchProjectsConversations() {
    every { identities.hasActiveRole("consultant-1", "CONSULTANT") } returns true
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
    assertTrue(page.hasMore)
    assertTrue(page.items.first().conversationReadable)
    assertTrue(page.items[1].messageSendable)
}
~~~

Also assert: three stage mappings, optional institution filter, HISTORY fallback from <code>updatedAt</code> to <code>createdAt</code>, PAUSED/HISTORY without a conversation has all conversation actions false, and DTO serialization contains none of <code>userPhone</code>, <code>verifyCode</code>, <code>price</code>, <code>refundAmount</code>, <code>evidenceUrl</code>. Construct one customer with <code>AccountState.ERASED</code> and another with non-null <code>deletedAt</code>, retaining nonblank legacy nickname/avatar in both fixtures; each response must still be <code>匿名用户/null</code>.

- [ ] **Step 3: 运行两个新测试并确认红灯**

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline test --tests com.joysong.server.order.consultant.ConsultantOrderRepositoryTest --tests com.joysong.server.order.consultant.ConsultantOrderQueryServiceTest
~~~

Expected: compilation fails because the query methods, DTOs and service do not exist.

- [ ] **Step 4: 增加无 count 的仓储查询**

~~~kotlin
@Query("""
    SELECT o FROM OrderEntity o
    WHERE o.consultantId = :consultantId
      AND o.paymentFlow = :paymentFlow
      AND o.serviceActivatedAt IS NOT NULL
      AND o.status = 'SERVICE_ACTIVE'
      AND (:institutionId IS NULL OR o.institutionId = :institutionId)
    ORDER BY CASE WHEN o.appointmentTime IS NULL THEN 1 ELSE 0 END,
             o.appointmentTime ASC,
             o.id ASC
""")
fun findActiveConsultantOrders(
    @Param("consultantId") consultantId: String,
    @Param("paymentFlow") paymentFlow: String,
    @Param("institutionId") institutionId: String?,
    pageable: Pageable
): List<OrderEntity>

@Query("""
    SELECT o FROM OrderEntity o
    WHERE o.consultantId = :consultantId
      AND o.paymentFlow = :paymentFlow
      AND o.serviceActivatedAt IS NOT NULL
      AND o.status IN :statuses
      AND (:institutionId IS NULL OR o.institutionId = :institutionId)
    ORDER BY COALESCE(o.updatedAt, o.createdAt) DESC, o.id DESC
""")
fun findConsultantHistoryOrders(
    @Param("consultantId") consultantId: String,
    @Param("paymentFlow") paymentFlow: String,
    @Param("statuses") statuses: Set<String>,
    @Param("institutionId") institutionId: String?,
    pageable: Pageable
): List<OrderEntity>
~~~

Add the conversation projection:

~~~kotlin
@Query("""
    SELECT conversation.orderId
    FROM DmConversationEntity conversation
    WHERE conversation.conversationType = 'ORDER_SERVICE'
      AND conversation.orderId IN :orderIds
""")
fun findOrderServiceOrderIds(
    @Param("orderIds") orderIds: Set<String>
): List<String>
~~~

Do not call this method for an empty ID set.

- [ ] **Step 5: 定义白名单 DTO**

~~~kotlin
data class ConsultantOrderPageResponse(
    val items: List<ConsultantOrderSummaryResponse>,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

data class ConsultantOrderSummaryResponse(
    val id: String,
    val orderNo: String,
    val stage: String,
    val status: String,
    val refundStatus: String,
    val project: ConsultantOrderProjectResponse,
    val institution: ConsultantOrderInstitutionResponse,
    val customer: ConsultantOrderCustomerResponse,
    val appointmentTime: LocalDateTime?,
    val updatedAt: LocalDateTime,
    val conversationReadable: Boolean,
    val messageSendable: Boolean,
    val readOnly: Boolean
)

data class ConsultantOrderDetailResponse(
    val id: String,
    val orderNo: String,
    val stage: String,
    val status: String,
    val refundStatus: String,
    val project: ConsultantOrderProjectResponse,
    val institution: ConsultantOrderInstitutionResponse,
    val customer: ConsultantOrderCustomerResponse,
    val doctor: ConsultantOrderDoctorResponse,
    val appointmentTime: LocalDateTime?,
    val remark: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val serviceActivatedAt: LocalDateTime,
    val completedAt: LocalDateTime?,
    val conversationReadable: Boolean,
    val messageSendable: Boolean,
    val readOnly: Boolean,
    val conversation: ConsultantOrderConversationResponse
)

data class ConsultantOrderProjectResponse(val id: String, val name: String, val coverImage: String)
data class ConsultantOrderInstitutionResponse(val id: String, val name: String)
data class ConsultantOrderCustomerResponse(val displayName: String, val avatar: String?)
data class ConsultantOrderDoctorResponse(val id: String?, val name: String)
data class ConsultantOrderConversationResponse(val readable: Boolean, val sendable: Boolean)
~~~

- [ ] **Step 6: 实现查询服务**

~~~kotlin
@Service
class ConsultantOrderQueryService(
    private val orders: OrderRepository,
    private val conversations: DmConversationRepository,
    private val users: UserRepository,
    private val accessPolicy: ConsultantOrderAccessPolicy
) {
    @Transactional(readOnly = true)
    fun list(
        consultantId: String,
        query: ConsultantOrderListQuery
    ): ConsultantOrderPageResponse {
        accessPolicy.requireActiveConsultant(consultantId)
        val pageable = OffsetPageRequest(query.offset.toLong(), query.limit + 1)
        val fetched = when (query.stage) {
            ConsultantOrderStage.ACTIVE -> orders.findActiveConsultantOrders(
                consultantId, TRAVEL_FLOW, query.institutionId, pageable
            )
            ConsultantOrderStage.PAUSED,
            ConsultantOrderStage.HISTORY -> orders.findConsultantHistoryOrders(
                consultantId, TRAVEL_FLOW, query.stage.statuses, query.institutionId, pageable
            )
        }
        val visible = fetched.take(query.limit)
        val conversationOrderIds = visible.map(OrderEntity::id).toSet()
            .takeIf { it.isNotEmpty() }
            ?.let(conversations::findOrderServiceOrderIds)
            ?.toSet()
            ?: emptySet()
        val customers = users.findAllById(visible.map(OrderEntity::userId).distinct())
            .associateBy { it.id }
        return ConsultantOrderPageResponse(
            items = visible.map { order ->
                order.toSummary(query.stage, customers[order.userId], order.id in conversationOrderIds)
            },
            offset = query.offset,
            limit = query.limit,
            hasMore = fetched.size > query.limit
        )
    }

    @Transactional(readOnly = true)
    fun detail(consultantId: String, orderId: String): ConsultantOrderDetailResponse {
        val order = orders.findById(orderId).orElse(null)
            ?: throw OrderContractException.consultantOrderNotFound()
        val stage = accessPolicy.requireWorkbenchOrder(order, consultantId)
        val hasConversation = conversations.findByConversationTypeAndOrderId(
            DmConversationEntity.ORDER_SERVICE,
            order.id
        ) != null
        return order.toDetail(stage, users.findByIdAnyState(order.userId), hasConversation)
    }
}
~~~

Mapping rules must be literal:

- <code>ACTIVE</code>: readable/sendable true even when no conversation exists.
- <code>PAUSED/HISTORY</code>: readable only when an existing order-service conversation is found; sendable false.
- <code>readOnly = !messageSendable</code>.
- <code>updatedAt = order.updatedAt ?: order.createdAt</code>.
- <code>user == null || user.deletedAt != null || user.accountState != AccountState.ACTIVE</code>: <code>displayName = "匿名用户"</code>, <code>avatar = null</code>; never return the erased row's previous nickname or avatar.
- active customer with blank nickname: use <code>"用户"</code>; do not expose phone or ID.

Implement the privacy rule once and call it from both summary and detail mapping:

~~~kotlin
private fun publicCustomer(user: UserEntity?): ConsultantOrderCustomerResponse {
    if (
        user == null ||
        user.deletedAt != null ||
        user.accountState != AccountState.ACTIVE
    ) {
        return ConsultantOrderCustomerResponse("匿名用户", null)
    }
    return ConsultantOrderCustomerResponse(
        displayName = user.nickname.trim().ifEmpty { "用户" },
        avatar = user.avatar.trim().takeIf(String::isNotEmpty)
    )
}
~~~

- [ ] **Step 7: 运行仓储和查询服务测试**

Run the Task 2 command again. Expected: PASS and printed database name begins with <code>myapp_worktree_</code>.

- [ ] **Step 8: 提交 Task 2**

~~~powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderDtos.kt joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderQueryService.kt joysong-server/src/main/kotlin/com/joysong/server/order/repository/OrderRepository.kt joysong-server/src/main/kotlin/com/joysong/server/dm/repository/DmConversationRepository.kt joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderRepositoryTest.kt joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderQueryServiceTest.kt
git commit -m "feat(order): add consultant order queries"
~~~

### Task 3: 暴露顾问订单 HTTP API 与真实错误状态

**Files:**

- Create: <code>joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderController.kt</code>
- Modify: <code>joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderControllerHttpTest.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderHttpSecurityTest.kt</code>

**Interfaces:**

- Consumes: Task 1 query parser and Task 2 query service.
- Produces: <code>GET /api/consultant/orders</code>.
- Produces: <code>GET /api/consultant/orders/{orderId}</code>.
- Produces: one global handler mapping <code>OrderContractException</code> to matching HTTP, <code>BaseResponse.code</code> and <code>errorCode</code>.

- [ ] **Step 1: 写 HTTP 红灯测试**

Keep controller-contract and authentication-chain coverage in two different Spring slices:

~~~kotlin
@WebMvcTest(controllers = [ConsultantOrderController::class])
@Import(GlobalExceptionHandler::class)
class ConsultantOrderControllerHttpTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @MockBean lateinit var service: ConsultantOrderQueryService
}
~~~

This class uses <code>@WithMockUser</code> and does not import the real security chain. Cover the controller contract with:

~~~kotlin
@Test
@WithMockUser(username = "consultant-1")
fun listDerivesConsultantFromAuthenticationAndParsesOffset() {
    every {
        service.list(
            "consultant-1",
            ConsultantOrderListQuery(ConsultantOrderStage.ACTIVE, null, 7, 20)
        )
    } returns emptyPage(offset = 7, limit = 20)

    mvc.perform(get("/api/consultant/orders").param("offset", "7"))
        .andExpect(status().isOk)
        .andExpect(jsonPath("$.data.offset").value(7))
    verify(exactly = 1) { service.list("consultant-1", any()) }
}

@Test
@WithMockUser(username = "consultant-1")
fun invalidStageAndPaginationReturnStableHttp400() {
    listOf(
        "/api/consultant/orders?stage=active",
        "/api/consultant/orders?offset=-1",
        "/api/consultant/orders?limit=101",
        "/api/consultant/orders?limit=nope"
    ).forEach { path ->
        mvc.perform(get(path))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(400))
            .andExpect(jsonPath("$.errorCode").value("INVALID_CONSULTANT_ORDER_STAGE"))
    }
}

@Test
@WithMockUser(username = "user-1")
fun revokedConsultantUsesRealHttp403() {
    every { service.detail("user-1", "order-1") } throws
        OrderContractException.roleRequired()
    mvc.perform(get("/api/consultant/orders/order-1"))
        .andExpect(status().isForbidden)
        .andExpect(jsonPath("$.code").value(403))
        .andExpect(jsonPath("$.errorCode").value("CONSULTANT_ROLE_REQUIRED"))
}
~~~

Create a separate real-filter slice:

~~~kotlin
@WebMvcTest(controllers = [ConsultantOrderController::class])
@Import(SecurityConfig::class, JwtAuthenticationFilter::class)
class ConsultantOrderHttpSecurityTest @Autowired constructor(
    private val mvc: MockMvc
) {
    @MockBean lateinit var service: ConsultantOrderQueryService
    @MockBean lateinit var tokenProvider: JwtTokenProvider
    @MockBean lateinit var userRepository: UserRepository
}
~~~

Follow <code>SecurityConfigLegalDocumentsTest</code> and <code>JwtAuthenticationFilterTest</code> for token and user fixtures. Cover these cases with no <code>@WithMockUser</code> bypass:

- no bearer token returns HTTP 401 and never calls the service;
- a token accepted by <code>JwtTokenProvider</code> whose current <code>UserEntity.accountState</code> is <code>ADMIN_SUSPENDED</code> or <code>ERASED</code> returns HTTP 401 before the controller;
- both state cases verify <code>service</code> has zero interactions so the 401 is proven to occur before the controller.

- [ ] **Step 2: 运行 HTTP 测试并确认红灯**

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline test --tests com.joysong.server.order.consultant.ConsultantOrderControllerHttpTest
.\gradlew.bat --offline test --tests com.joysong.server.order.consultant.ConsultantOrderHttpSecurityTest
~~~

Expected: compilation fails because the controller and handler do not exist.

- [ ] **Step 3: 实现控制器**

~~~kotlin
@RestController
@RequestMapping("/api/consultant/orders")
class ConsultantOrderController(
    private val service: ConsultantOrderQueryService
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) stage: String?,
        @RequestParam(required = false) institutionId: String?,
        @RequestParam(required = false) offset: String?,
        @RequestParam(required = false) limit: String?,
        authentication: Authentication
    ): BaseResponse<ConsultantOrderPageResponse> =
        BaseResponse.success(
            service.list(
                authentication.principal as String,
                ConsultantOrderListQuery.parse(stage, institutionId, offset, limit)
            )
        )

    @GetMapping("/{orderId}")
    fun detail(
        @PathVariable orderId: String,
        authentication: Authentication
    ): BaseResponse<ConsultantOrderDetailResponse> =
        BaseResponse.success(
            service.detail(authentication.principal as String, orderId)
        )
}
~~~

Do not accept a <code>consultantId</code> parameter and do not add a special <code>SecurityConfig</code> matcher; <code>anyRequest().authenticated()</code> remains the only route gate.

- [ ] **Step 4: 增加类型化异常处理**

~~~kotlin
@ExceptionHandler(OrderContractException::class)
fun handleOrderContract(
    error: OrderContractException
): ResponseEntity<BaseResponse<Nothing>> =
    ResponseEntity.status(error.status).body(
        BaseResponse.error(
            error.message ?: "订单请求失败",
            error.status.value(),
            error.errorCode.name
        )
    )
~~~

Do not add <code>/api/consultant/**</code> or <code>/api/orders/**</code> to the legacy URI whitelist; typed errors must bypass the compatibility HTTP-200 path directly.

- [ ] **Step 5: 运行 HTTP 测试并确认通过**

Run the Task 3 command again. Expected: PASS.

- [ ] **Step 6: 提交 Task 3**

~~~powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/order/consultant/ConsultantOrderController.kt joysong-server/src/main/kotlin/com/joysong/server/common/GlobalExceptionHandler.kt joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderControllerHttpTest.kt joysong-server/src/test/kotlin/com/joysong/server/order/consultant/ConsultantOrderHttpSecurityTest.kt
git commit -m "feat(order): expose consultant order API"
~~~

### Task 4: 将顾问身份策略接入订单服务会话

**Files:**

- Modify: <code>joysong-server/src/main/kotlin/com/joysong/server/dm/service/OrderServiceConversationService.kt</code>
- Modify: <code>joysong-server/src/test/kotlin/com/joysong/server/dm/service/OrderServiceConversationServiceTest.kt</code>
- Modify: <code>joysong-server/src/test/kotlin/com/joysong/server/dm/service/DmServiceTest.kt</code>
- Modify: <code>joysong-server/src/test/kotlin/com/joysong/server/migration/TravelGroundServicePaymentMigrationTest.kt</code>
- Create: <code>joysong-server/src/test/kotlin/com/joysong/server/dm/controller/OrderServiceConversationHttpTest.kt</code>
- Verify unchanged: <code>joysong-server/src/main/kotlin/com/joysong/server/dm/controller/DmController.kt</code>

**Interfaces:**

- Consumes: Task 1 access policy and shared typed error.
- Preserves: <code>getOrCreate</code>、<code>requireReadAccess</code>、<code>requireSendAccess</code>、<code>responseIfReadable</code> public signatures.
- Preserves: consumer participant access without any consultant-role lookup.

- [ ] **Step 1: 把现有服务测试改为类型化错误并增加身份撤销覆盖**

~~~kotlin
@Test
fun revokedConsultantCannotReadOrSendButConsumerStillCan() {
    every { orderRepository.findById("order-1") } returns Optional.of(order())
    every { orderRepository.findByIdForUpdate("order-1") } returns order()
    every {
        policy.requireConversationParticipant(order(), "consultant-1")
    } throws OrderContractException.roleRequired()
    every {
        policy.requireConversationParticipant(order(), "user-1")
    } returns Unit

    assertThrows<OrderContractException> {
        service.requireReadAccess(conversation(), "consultant-1")
    }
    assertThrows<OrderContractException> {
        service.requireSendAccess(conversation(), "consultant-1")
    }
    service.requireReadAccess(conversation(), "user-1")
    service.requireSendAccess(conversation(), "user-1")
}

@ParameterizedTest
@ValueSource(strings = ["REFUND_REVIEW", "REFUND_PROCESSING", "COMPLETED", "REFUNDED"])
fun readonlyStateReturnsExistingConversationButNeverCreates(status: String) {
    val order = order(status = status)
    every { orderRepository.findByIdForUpdate("order-1") } returns order
    every { policy.requireConversationParticipant(order, "consultant-1") } returns Unit
    every {
        conversationRepository.findByConversationTypeAndOrderId("ORDER_SERVICE", "order-1")
    } returns conversation()

    assertEquals(conversation().id, service.getOrCreate("order-1", "consultant-1").id)
    verify(exactly = 0) { conversationRepository.saveAndFlush(any()) }
}
~~~

Also add the inverse: PAUSED/HISTORY without an existing conversation throws <code>409 ORDER_SERVICE_READ_ONLY</code>; unpaid/legacy/unknown status throws <code>409 ORDER_SERVICE_NOT_ACTIVE</code>.

Retain the complete activation predicate. Add explicit fixtures proving a blank assigned consultant and an order assigned back to the consumer both return <code>409 ORDER_SERVICE_NOT_ACTIVE</code> and never call <code>saveAndFlush</code>:

~~~kotlin
val activated = order.paymentFlow == "TRAVEL_GROUND_SERVICE_ONLY" &&
    order.serviceActivatedAt != null &&
    order.consultantId.isNotBlank() &&
    order.consultantId != order.userId
~~~

- [ ] **Step 2: 写真实 HTTP 会话错误测试**

Use <code>OrderServiceConversationController</code> and <code>DmController</code> with mocked services plus <code>GlobalExceptionHandler</code>. Assert:

~~~kotlin
@Test
@WithMockUser(username = "consultant-1")
fun serviceConversationAndDmEndpointsExposeTypedStatus() {
    every { orderConversation.getOrCreate("order-1", "consultant-1") } throws
        OrderContractException.serviceReadOnly()
    mvc.perform(post("/api/orders/order-1/service-conversation"))
        .andExpect(status().isConflict)
        .andExpect(jsonPath("$.errorCode").value("ORDER_SERVICE_READ_ONLY"))

    every { dm.getMessages("conversation-1", "consultant-1", 30, null) } throws
        OrderContractException.roleRequired()
    mvc.perform(get("/api/dm/conversations/conversation-1/messages"))
        .andExpect(status().isForbidden)
        .andExpect(jsonPath("$.errorCode").value("CONSULTANT_ROLE_REQUIRED"))
}
~~~

Add equivalent typed propagation for send, mark-read and message-delete. The existing <code>DmController</code> catches only <code>IllegalArgumentException</code>, so the new runtime type must reach the global handler without modifying direct-message compatibility behavior.

- [ ] **Step 3: 运行会话测试并确认红灯**

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline test --tests com.joysong.server.dm.service.OrderServiceConversationServiceTest --tests com.joysong.server.dm.service.DmServiceTest --tests com.joysong.server.dm.controller.OrderServiceConversationHttpTest
~~~

Expected: tests fail because the service still throws <code>IllegalArgumentException</code> and does not inject the policy.

The migration slice is tagged <code>mysql-integration</code>, so the default <code>test</code> task excludes it. Run it separately once; <code>WorktreeTestDatabase</code> must print the resolved container host and a database beginning with <code>myapp_worktree_</code> before Flyway runs:

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline mysqlIntegrationTest --tests com.joysong.server.migration.TravelGroundServicePaymentMigrationTest
~~~

- [ ] **Step 4: 重写订单会话授权核心**

Constructor:

~~~kotlin
class OrderServiceConversationService(
    private val orderRepository: OrderRepository,
    private val conversationRepository: DmConversationRepository,
    private val consultantAccessPolicy: ConsultantOrderAccessPolicy
)
~~~

The <code>getOrCreate</code> order must remain locked and follow this exact branch:

~~~kotlin
val order = orderRepository.findByIdForUpdate(orderId)
    ?: throw OrderContractException.serviceAccessDenied()
requireActivatedService(order)
consultantAccessPolicy.requireConversationParticipant(order, userId)
if (order.status !in READABLE_STATUSES) {
    throw OrderContractException.serviceNotActive()
}
val existing = conversationRepository.findByConversationTypeAndOrderId(
    DmConversationEntity.ORDER_SERVICE,
    orderId
)
if (existing != null) {
    requireConversationMatchesOrder(existing, order)
    return existing.toResponseFor(order)
}
if (order.status != OrderStatusEnum.SERVICE_ACTIVE.value) {
    throw OrderContractException.serviceReadOnly()
}
return conversationRepository.saveAndFlush(newConversation(order)).toResponseFor(order)
~~~

In <code>authorize</code>:

- invalid/mismatched conversation or unrelated participant becomes <code>serviceAccessDenied()</code>;
- invalid flow, missing activation or a non-readable status becomes <code>serviceNotActive()</code>;
- a send attempt in a readable non-active state becomes <code>serviceReadOnly()</code>;
- call <code>consultantAccessPolicy.requireConversationParticipant</code> after loading the current order and before returning it.

<code>responseIfReadable</code> catches <code>OrderContractException</code> and returns null only for inbox projection; direct message reads, explicit reads, sends, read receipts and message-delete paths continue to receive the real error.

- [ ] **Step 5: 更新显式构造和切片测试装配**

Update every direct <code>OrderServiceConversationService</code> construction to pass a policy mock. In <code>TravelGroundServicePaymentMigrationTest</code>, use this exact slice assembly so the new constructor can be created:

~~~kotlin
@Import(
    OrderServiceConversationService::class,
    ConsultantOrderAccessPolicy::class,
    IdentityAuthorizationService::class
)
~~~

Keep the existing consumer <code>getOrCreate</code> assertion and verify it completes without a consultant-role lookup; do not replace the real policy with a permissive test bean.

- [ ] **Step 6: 明确现有删除/隐藏边界**

Do not create a new conversation-hide endpoint. Existing coverage means:

- inbox projection uses <code>responseIfReadable</code>;
- message history and read receipt use <code>requireReadAccess</code>;
- send uses <code>requireSendAccess</code>;
- existing message delete first uses <code>requireReadAccess</code> and remains otherwise forbidden for order messages;
- <code>canHide</code> response projection remains true only for completed/refunded history as it is today.

- [ ] **Step 7: 运行会话测试并确认通过**

Run the regular Task 4 command again, then run the separate <code>mysqlIntegrationTest</code> command once. Expected: PASS for both. Do not assume the default <code>test</code> task executes the tagged migration slice.

- [ ] **Step 8: 提交 Task 4**

~~~powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/dm/service/OrderServiceConversationService.kt joysong-server/src/test/kotlin/com/joysong/server/dm/service/OrderServiceConversationServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/dm/service/DmServiceTest.kt joysong-server/src/test/kotlin/com/joysong/server/migration/TravelGroundServicePaymentMigrationTest.kt joysong-server/src/test/kotlin/com/joysong/server/dm/controller/OrderServiceConversationHttpTest.kt
git commit -m "fix(dm): revalidate consultant order conversations"
~~~

### Task 5: 发布管理上下文能力

**Files:**

- Modify: <code>joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt</code>
- Modify: <code>joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt</code>

**Interfaces:**

- Produces: <code>ManagementContextView.canAccessConsultantOrderWorkbench: Boolean</code>.
- Preserves: <code>canManageOrders</code> remains doctor/admin-only.

- [ ] **Step 1: 写能力字段红灯测试**

Extend the existing consultant and admin tests:

~~~kotlin
assertTrue(consultantContext.canAccessConsultantOrderWorkbench)
assertFalse(consultantContext.canManageOrders)
assertFalse(legalRepresentativeContext.canAccessConsultantOrderWorkbench)
assertTrue(adminWithConsultantRole.canAccessConsultantOrderWorkbench)
assertFalse(adminWithoutConsultantRole.canAccessConsultantOrderWorkbench)
~~~

- [ ] **Step 2: 运行最小测试并确认红灯**

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline test --tests com.joysong.server.identity.service.ManagementAccessServiceTest
~~~

Expected: compilation fails because the field does not exist.

- [ ] **Step 3: 增加字段并从有效角色计算**

Add to <code>ManagementContextView</code> and <code>toContext()</code>:

~~~kotlin
val canAccessConsultantOrderWorkbench: Boolean
~~~

~~~kotlin
canAccessConsultantOrderWorkbench = CONSULTANT_ROLE in activeRoles
~~~

Do not require a current institution membership and do not change <code>ManagementAccessController</code>; both context and management-login responses already serialize the same DTO.

- [ ] **Step 4: 运行测试并确认通过**

Run the Task 5 command again. Expected: PASS.

- [ ] **Step 5: 提交 Task 5**

~~~powershell
git add -- joysong-server/src/main/kotlin/com/joysong/server/identity/service/ManagementAccessService.kt joysong-server/src/test/kotlin/com/joysong/server/identity/service/ManagementAccessServiceTest.kt
git commit -m "feat(identity): expose consultant order capability"
~~~

### Task 6: 实现 Flutter 顾问订单领域模型和远程仓库

**Files:**

- Create: <code>joysong-flutter/lib/features/consultant_orders/domain/consultant_order_models.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/domain/consultant_orders_repository.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/data/consultant_orders_remote_data_source.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/data/consultant_orders_repository_impl.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/domain/consultant_order_models_test.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/data/consultant_orders_remote_data_source_test.dart</code>

**Interfaces:**

- Produces: <code>ConsultantOrderStage</code>、<code>ConsultantOrderPage</code>、<code>ConsultantOrderSummary</code>、<code>ConsultantOrderDetail</code>.
- Produces: <code>ConsultantOrdersRepository.getOrders</code> and <code>getOrder</code>.
- No dependency on consumer <code>Order</code>, doctor <code>ProfessionalOrder</code> or payment models.

- [ ] **Step 1: 写严格模型解析红灯测试**

~~~dart
test('parses a redacted active order page', () {
  final page = ConsultantOrderPage.fromJson(activePageJson);
  expect(page.items.single.stage, ConsultantOrderStage.active);
  expect(page.items.single.messageSendable, isTrue);
  expect(page.items.single.customer.displayName, '用户一');
  expect(page.hasMore, isFalse);
});

test('rejects unknown stages and missing required fields', () {
  expect(
    () => ConsultantOrderSummary.fromJson({...summaryJson, 'stage': 'UNKNOWN'}),
    throwsFormatException,
  );
  expect(
    () => ConsultantOrderPage.fromJson({'items': [], 'offset': 0}),
    throwsFormatException,
  );
});
~~~

Build <code>activePageJson</code> and <code>summaryJson</code> as literal maps containing the exact Task 2 DTO keys. Recursively inspect the fixture/decoded JSON keys and reject this deny-list before parsing:

~~~dart
const forbiddenKeys = {
  'phone',
  'realName',
  'verificationCode',
  'qrCode',
  'amount',
  'price',
  'payment',
  'refundEvidence',
  'split',
  'settlement',
  'statusLogs',
};
expect(jsonKeys(activePageJson).intersection(forbiddenKeys), isEmpty);
expect(summaryJson.keys.toSet(), approvedSummaryKeys);
~~~

Do not write a runtime assertion about Dart properties that do not exist. Privacy leakage is instead guarded by the literal response fixture above, Task 2 DTO serialization assertions, and the final import/diff review proving this feature does not import consumer/payment models.

- [ ] **Step 2: 写 API 路径和媒体解析红灯测试**

Use a recording subclass of <code>ApiClient</code> rather than adding a mocking package:

~~~dart
final class RecordingApiClient extends ApiClient {
  RecordingApiClient(this.responses)
      : super(apiRoot: Uri.parse('https://api.example.com/api/'));

  final Map<String, Object?> responses;
  final List<({String path, Map<String, Object?> query})> requests = [];

  @override
  Future<T?> get<T>(
    String requestPath, {
    Map<String, Object?> query = const {},
    required T Function(Object? json) decodeData,
  }) async {
    requests.add((path: requestPath, query: Map.unmodifiable(query)));
    return decodeData(responses[requestPath]);
  }
}
~~~

Assert:

~~~dart
expect(client.requests.first.path, 'consultant/orders');
expect(client.requests.first.query, {
  'stage': 'PAUSED',
  'institutionId': 'institution-1',
  'offset': 20,
  'limit': 20,
});
expect(page.items.single.project.coverImage, 'https://api.example.com/images/p.jpg');
~~~

Also assert detail uses <code>consultant/orders/order-1</code> and no request includes <code>consultantId</code>.

- [ ] **Step 3: 运行 Flutter 新测试并确认红灯**

Run from <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-flutter</code> in an environment where Flutter is available:

~~~powershell
flutter test test/features/consultant_orders/domain/consultant_order_models_test.dart test/features/consultant_orders/data/consultant_orders_remote_data_source_test.dart
~~~

Expected: compilation fails because the feature files do not exist. On the current host, record the existing environment limitation instead of repeatedly retrying if <code>flutter</code> is unavailable.

- [ ] **Step 4: 实现领域模型**

~~~dart
enum ConsultantOrderStage {
  active('ACTIVE'),
  paused('PAUSED'),
  history('HISTORY');

  const ConsultantOrderStage(this.wireValue);
  final String wireValue;

  static ConsultantOrderStage fromWire(Object? value) =>
      values.where((stage) => stage.wireValue == value).firstOrNull ??
      (throw const FormatException('顾问订单阶段无效'));
}

final class ConsultantOrderPage {
  const ConsultantOrderPage({
    required this.items,
    required this.offset,
    required this.limit,
    required this.hasMore,
  });
  final List<ConsultantOrderSummary> items;
  final int offset;
  final int limit;
  final bool hasMore;
}

final class ConsultantOrderDetail {
  const ConsultantOrderDetail({
    required this.summary,
    required this.doctor,
    required this.remark,
    required this.createdAt,
    required this.serviceActivatedAt,
    required this.completedAt,
    required this.conversation,
  });
  final ConsultantOrderSummary summary;
  final ConsultantOrderDoctor doctor;
  final String remark;
  final DateTime createdAt;
  final DateTime serviceActivatedAt;
  final DateTime? completedAt;
  final ConsultantOrderConversationAccess conversation;
}
~~~

Define exact nested types for project, institution, customer, doctor and conversation access. Parsing rules:

- required strings must be nonblank;
- <code>appointmentTime</code> and <code>completedAt</code> may be null;
- timestamps use <code>DateTime.parse</code> without appending <code>Z</code>;
- <code>ConsultantOrderDetail.fromJson</code> builds its summary projection from the detail response's same flat <code>id/orderNo/stage/status/refundStatus/project/institution/customer/appointmentTime/updatedAt/conversationReadable/messageSendable/readOnly</code> fields, then parses the nested <code>conversation</code> object;
- nested <code>conversation.readable/sendable</code> must equal top-level <code>conversationReadable/messageSendable</code>, and top-level <code>readOnly</code> must equal <code>!messageSendable</code>; any mismatch throws <code>FormatException</code>;
- unknown stage is rejected, while unknown order <code>status</code> remains a displayable raw code only if the server still returned a valid stage.

- [ ] **Step 5: 实现仓库契约和 API 数据源**

~~~dart
abstract interface class ConsultantOrdersRepository {
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  });

  Future<ConsultantOrderDetail> getOrder(String orderId);
}
~~~

~~~dart
final class ApiConsultantOrdersRemoteDataSource
    implements ConsultantOrdersRemoteDataSource {
  ApiConsultantOrdersRemoteDataSource(this._apiClient)
      : _mediaResolver = ApiPublicMediaUrlResolver(_apiClient.apiRoot);

  final ApiClient _apiClient;
  final PublicMediaUrlResolver _mediaResolver;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) async {
    final result = await _apiClient.get<ConsultantOrderPage>(
      'consultant/orders',
      query: {
        'stage': stage.wireValue,
        'institutionId': institutionId,
        'offset': offset,
        'limit': limit,
      },
      decodeData: (json) => ConsultantOrderPage.fromJson(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      ),
    );
    if (result == null) throw const FormatException('顾问订单列表 data 为空');
    return result;
  }

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) async {
    final result = await _apiClient.get<ConsultantOrderDetail>(
      'consultant/orders/${Uri.encodeComponent(orderId.trim())}',
      decodeData: (json) => ConsultantOrderDetail.fromJson(
        resolvePublicMediaUrlsInJson(json, resolver: _mediaResolver),
      ),
    );
    if (result == null) throw const FormatException('顾问订单详情 data 为空');
    return result;
  }
}
~~~

The repository implementation is a pure delegate; it adds no filtering or authorization.

- [ ] **Step 6: 格式化并运行 Task 6 测试**

Run from the exact worktree Flutter package directory named in Global Constraints.

~~~powershell
dart format lib/features/consultant_orders test/features/consultant_orders
flutter test test/features/consultant_orders/domain/consultant_order_models_test.dart test/features/consultant_orders/data/consultant_orders_remote_data_source_test.dart
~~~

Expected: PASS when Flutter SDK is available.

- [ ] **Step 7: 提交 Task 6**

~~~powershell
git add -- joysong-flutter/lib/features/consultant_orders/domain/consultant_order_models.dart joysong-flutter/lib/features/consultant_orders/domain/consultant_orders_repository.dart joysong-flutter/lib/features/consultant_orders/data/consultant_orders_remote_data_source.dart joysong-flutter/lib/features/consultant_orders/data/consultant_orders_repository_impl.dart joysong-flutter/test/features/consultant_orders/domain/consultant_order_models_test.dart joysong-flutter/test/features/consultant_orders/data/consultant_orders_remote_data_source_test.dart
git commit -m "feat(flutter): add consultant order data layer"
~~~

### Task 7: 实现三个阶段的独立分页状态和详情状态

**Files:**

- Create: <code>joysong-flutter/lib/features/consultant_orders/presentation/consultant_orders_controller.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_detail_controller.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/presentation/consultant_orders_controller_test.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/presentation/consultant_order_detail_controller_test.dart</code>

**Interfaces:**

- Consumes: Task 6 repository.
- Produces: one list state per stage with items, nextOffset, hasMore, loading and error.
- Produces: role-required callback that presentation integration can use to refresh management context and exit.

- [ ] **Step 1: 写三个阶段互不污染的红灯测试**

~~~dart
test('each stage owns independent offset and loading state', () async {
  final repository = FakeConsultantOrdersRepository()
    ..pages[ConsultantOrderStage.active] = [page(offset: 0, ids: ['a1'], hasMore: true)]
    ..pages[ConsultantOrderStage.paused] = [page(offset: 0, ids: ['p1'])];
  final controller = ConsultantOrdersController(
    repository,
    onConsultantRoleRequired: () async {},
  );

  await controller.load(ConsultantOrderStage.active);
  await controller.load(ConsultantOrderStage.paused);

  expect(controller.stateFor(ConsultantOrderStage.active).items.single.id, 'a1');
  expect(controller.stateFor(ConsultantOrderStage.paused).items.single.id, 'p1');
  expect(controller.stateFor(ConsultantOrderStage.history).items, isEmpty);
});

test('loadMore uses server offset and stops on an empty page', () async {
  await controller.load(ConsultantOrderStage.active);
  await controller.loadMore(ConsultantOrderStage.active);
  expect(repository.requests.last.offset, 1);
  expect(controller.stateFor(ConsultantOrderStage.active).hasMore, isFalse);
});

test('loadMore stops when a nonempty page makes no offset progress', () async {
  repository.pages[ConsultantOrderStage.active] = [
    page(offset: 0, ids: List.generate(20, (index) => 'a$index'), hasMore: true),
    page(offset: 0, ids: ['duplicate-window'], hasMore: true),
  ];
  await controller.load(ConsultantOrderStage.active);
  await controller.loadMore(ConsultantOrderStage.active);
  expect(controller.stateFor(ConsultantOrderStage.active).hasMore, isFalse);
});
~~~

Also test refresh generation discards an older in-flight response, duplicate IDs are removed and a load-more error retains existing items. Start ACTIVE and PAUSED requests concurrently, make both fail with <code>CONSULTANT_ROLE_REQUIRED</code>, and assert all stages enter terminal <code>accessRevoked</code>, the management callback runs exactly once, and later <code>load/loadMore/refresh</code> perform no repository call.

- [ ] **Step 2: 写详情状态红灯测试**

~~~dart
test('detail controller surfaces a server-refreshed detail', () async {
  final controller = ConsultantOrderDetailController(
    repository,
    orderId: 'o1',
    onConsultantRoleRequired: () async {},
  );
  await controller.load();
  expect(controller.detail?.summary.id, 'o1');
  expect(controller.status, ConsultantOrderDetailLoadStatus.ready);
});

test('role-required detail error invokes access callback', () async {
  repository.detailError = const ApiException(
    message: 'forbidden',
    httpStatus: 403,
    businessCode: 403,
    errorCode: 'CONSULTANT_ROLE_REQUIRED',
  );
  await controller.load();
  expect(roleRequiredCalls, 1);
});
~~~

- [ ] **Step 3: 运行控制器测试并确认红灯**

Run from <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-flutter</code>.

~~~powershell
flutter test test/features/consultant_orders/presentation/consultant_orders_controller_test.dart test/features/consultant_orders/presentation/consultant_order_detail_controller_test.dart
~~~

Expected: compilation fails because the controllers do not exist.

- [ ] **Step 4: 实现列表状态算法**

Use local presentation enums; never store <code>ApiException.message</code> in state:

~~~dart
typedef ConsultantRoleRequiredCallback = Future<void> Function();

// Both controllers require this callback; tests may pass an async no-op.
ConsultantOrdersController(
  ConsultantOrdersRepository repository, {
  required ConsultantRoleRequiredCallback onConsultantRoleRequired,
});

enum ConsultantOrderListStatus { idle, loading, ready, failure, accessRevoked }

enum ConsultantOrderFailure { unavailable, invalidResponse, retryRequired }

final class ConsultantOrderListState {
  const ConsultantOrderListState({
    this.status = ConsultantOrderListStatus.idle,
    this.items = const [],
    this.nextOffset = 0,
    this.hasMore = true,
    this.isLoadingMore = false,
    this.failure,
    this.loadMoreFailure,
  });
  final ConsultantOrderListStatus status;
  final List<ConsultantOrderSummary> items;
  final int nextOffset;
  final bool hasMore;
  final bool isLoadingMore;
  final ConsultantOrderFailure? failure;
  final ConsultantOrderFailure? loadMoreFailure;
}
~~~

Controller rules:

- initialize all three enum keys;
- <code>load(stage)</code> only loads an idle stage unless <code>force=true</code>;
- <code>refresh(stage)</code> increments that stage generation and requests offset 0;
- <code>loadMore(stage)</code> exits if loading or <code>hasMore=false</code>;
- after every page, calculate <code>nextOffset = page.offset + page.items.length</code> and <code>hasMore = page.hasMore &amp;&amp; nextOffset &gt; requestedOffset</code>; this prevents a buggy server from causing an infinite repeated window;
- merge by order ID while retaining server order;
- if server returns an empty page, force <code>hasMore=false</code>;
- map transport/status/format failures to <code>ConsultantOrderFailure</code>; widgets translate that enum with <code>context.localized</code> and never display the server message;
- maintain a controller-wide <code>_accessRevoked</code> terminal flag, per-stage generations and <code>_roleCallbackDispatched</code>. The first role-required failure increments every generation, marks all stage states <code>accessRevoked</code>, notifies once and awaits the callback once; concurrent failures become no-ops;
- maintain <code>_disposed</code>, set it before <code>super.dispose()</code>, and guard every post-await state write, notification and role callback. Once revoked or disposed, <code>load/loadMore/refresh</code> return immediately.

- [ ] **Step 5: 实现详情控制器**

Use the exact constructor <code>ConsultantOrderDetailController(repository, orderId: orderId, onConsultantRoleRequired: callback)</code> and <code>idle/loading/ready/failure/accessRevoked</code> states with <code>ConsultantOrderFailure?</code>, not a server string. Every <code>load()</code> increments a generation and fetches the canonical detail. On <code>ApiException.errorCode == "CONSULTANT_ROLE_REQUIRED"</code>, increment the generation again, enter terminal <code>accessRevoked</code>, notify if alive, and await the injected role-required callback once. Guard all post-await work with generation and <code>_disposed</code>; once revoked or disposed, later loads are no-ops.

- [ ] **Step 6: 格式化并运行 Task 7 测试**

Run from the exact worktree Flutter package directory named in Global Constraints.

~~~powershell
dart format lib/features/consultant_orders/presentation test/features/consultant_orders/presentation
flutter test test/features/consultant_orders/presentation/consultant_orders_controller_test.dart test/features/consultant_orders/presentation/consultant_order_detail_controller_test.dart
~~~

Expected: PASS.

- [ ] **Step 7: 提交 Task 7**

~~~powershell
git add -- joysong-flutter/lib/features/consultant_orders/presentation/consultant_orders_controller.dart joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_detail_controller.dart joysong-flutter/test/features/consultant_orders/presentation/consultant_orders_controller_test.dart joysong-flutter/test/features/consultant_orders/presentation/consultant_order_detail_controller_test.dart
git commit -m "feat(flutter): manage consultant order states"
~~~

### Task 8: 构建顾问订单工作台、卡片和详情页

**Files:**

- Create: <code>joysong-flutter/lib/features/consultant_orders/presentation/consultant_orders_page.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_card.dart</code>
- Create: <code>joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_detail_page.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/presentation/consultant_orders_page_test.dart</code>
- Create: <code>joysong-flutter/test/features/consultant_orders/presentation/consultant_order_detail_page_test.dart</code>

**Interfaces:**

- Consumes: Task 6 repository and Task 7 controllers.
- Produces: <code>ConsultantOrdersPage(repository, onOpenServiceConversation, onConsultantRoleRequired)</code>.
- Produces: detail page with exactly one possible primary action: order conversation.

- [ ] **Step 1: 写工作台 widget 红灯测试**

~~~dart
testWidgets('renders three localized tabs with independent empty states', (tester) async {
  await tester.pumpWidget(testApp(
    ConsultantOrdersPage(
      repository: repository,
      onOpenServiceConversation: (_) async {},
      onConsultantRoleRequired: () async {},
    ),
  ));
  await tester.pumpAndSettle();

  expect(find.text('服务中'), findsOneWidget);
  expect(find.text('退款处理中'), findsOneWidget);
  expect(find.text('历史订单'), findsOneWidget);
  expect(find.byKey(const Key('consultant-orders-active-list')), findsOneWidget);
});
~~~

Add tests for English labels, pull-to-refresh, a load-more failure that keeps cards, empty/error/retry states and large text scale without overflow. The large-text case wraps the page with <code>MediaQuery.withClampedTextScaling</code> or a copied <code>MediaQueryData(textScaler: const TextScaler.linear(2))</code>, scrolls through every card/action, and asserts <code>tester.takeException()</code> is null.

- [ ] **Step 2: 写详情操作和隐私红灯测试**

~~~dart
testWidgets('active detail opens conversation and exposes no forbidden actions', (tester) async {
  await pumpDetail(sendableDetail);
  expect(find.text('订单沟通'), findsOneWidget);
  expect(find.text('确认完成'), findsNothing);
  expect(find.text('核销'), findsNothing);
  expect(find.text('退款'), findsNothing);
  await tester.tap(find.text('订单沟通'));
  await tester.pump();
  expect(openedOrderIds, ['order-1']);
});

testWidgets('readonly history shows existing conversation but no send claim', (tester) async {
  await pumpDetail(readonlyDetail);
  expect(find.text('仅可查看历史消息'), findsOneWidget);
  expect(find.text('订单沟通'), findsOneWidget);
});

testWidgets('detail without an existing conversation hides the action', (tester) async {
  await pumpDetail(unreadableHistoryDetail);
  expect(find.text('订单沟通'), findsNothing);
});
~~~

Also test that a role-required error thrown by <code>onOpenServiceConversation</code> awaits <code>onConsultantRoleRequired</code> exactly once and renders no toast/state update after navigation removes the page, while another error shows a local bilingual transient message.

- [ ] **Step 3: 运行 widget 测试并确认红灯**

Run from <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-flutter</code>.

~~~powershell
flutter test test/features/consultant_orders/presentation/consultant_orders_page_test.dart test/features/consultant_orders/presentation/consultant_order_detail_page_test.dart
~~~

Expected: compilation fails because the widgets do not exist.

- [ ] **Step 4: 实现工作台页面**

Use explicit ticker and controller ownership; do not create a <code>TabController</code> without <code>vsync</code>:

~~~dart
class _ConsultantOrdersPageState extends State<ConsultantOrdersPage>
    with SingleTickerProviderStateMixin {
  late final TabController _tabController;
  late ConsultantOrdersController _ordersController;
  int _selectedStageIndex = 0;

  @override
  void initState() {
    super.initState();
    _ordersController = ConsultantOrdersController(
      widget.repository,
      onConsultantRoleRequired: widget.onConsultantRoleRequired,
    );
    _tabController = TabController(
      length: ConsultantOrderStage.values.length,
      vsync: this,
    )..addListener(_handleTabChanged);
    unawaited(_ordersController.load(ConsultantOrderStage.active));
  }

  void _handleTabChanged() {
    if (_tabController.indexIsChanging ||
        _selectedStageIndex == _tabController.index) {
      return;
    }
    _selectedStageIndex = _tabController.index;
    unawaited(
      _ordersController.load(
        ConsultantOrderStage.values[_selectedStageIndex],
      ),
    );
  }

  @override
  void didUpdateWidget(covariant ConsultantOrdersPage oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.repository != widget.repository ||
        oldWidget.onConsultantRoleRequired !=
            widget.onConsultantRoleRequired) {
      _ordersController.dispose();
      _ordersController = ConsultantOrdersController(
        widget.repository,
        onConsultantRoleRequired: widget.onConsultantRoleRequired,
      );
      unawaited(
        _ordersController.load(
          ConsultantOrderStage.values[_selectedStageIndex],
        ),
      );
    }
  }

  @override
  void dispose() {
    _tabController
      ..removeListener(_handleTabChanged)
      ..dispose();
    _ordersController.dispose();
    super.dispose();
  }
}
~~~

Import <code>dart:async</code> for <code>unawaited</code>. Initial load is ACTIVE; a settled tab-index change loads that stage once. Each tab owns a <code>RefreshIndicator</code> and scroll notification:

~~~dart
if (metrics.extentAfter < 240) {
  controller.loadMore(stage);
}
~~~

Cards render a nonblank project cover with <code>OptimizedNetworkImage(url: ..., width: 72, height: 72)</code> and a fixed icon placeholder on error/blank. Render the customer avatar with <code>CircleAvatar(radius: 18, foregroundImage: avatar.isEmpty ? null : NetworkImage(avatar), onForegroundImageError: avatar.isEmpty ? null : (_, __) {})</code> plus an initials/person fallback child. Do not instantiate <code>OptimizedNetworkImage</code> without its required dimensions. Cards show only:

- customer display name/avatar;
- project name/cover;
- institution name;
- appointment time;
- stable localized service state.

Do not import consumer <code>orders_page.dart</code> or doctor <code>professional_pages.dart</code>.

Add one widget test where the list repository returns <code>CONSULTANT_ROLE_REQUIRED</code> and assert the page-level callback is awaited once. Remove the page from the tree after pumping and assert <code>tester.takeException()</code> is null, proving both the list controller and ticker are disposed without a late notification.

- [ ] **Step 5: 实现详情页**

The list pushes only the selected order ID; it never passes a stale detail object. Use this exact detail boundary:

~~~dart
ConsultantOrderDetailPage(
  repository: repository,
  orderId: summary.id,
  onOpenServiceConversation: onOpenServiceConversation,
  onConsultantRoleRequired: onConsultantRoleRequired,
)
~~~

The state creates <code>ConsultantOrderDetailController</code> in <code>initState</code>, calls <code>load()</code> once, recreates it only when repository/order ID changes in <code>didUpdateWidget</code>, and disposes it. It renders a scrollable white-list detail. The button rule is exact:

~~~dart
final showConversation = detail.conversation.readable;
final readOnly = showConversation && !detail.conversation.sendable;
~~~

When <code>showConversation=false</code>, render no action. When true, keep the label “订单沟通 / Order conversation”; for read-only state add “仅可查看历史消息 / History is read-only”. Before navigation, call the injected callback. Handle role loss in this order:

~~~dart
} on ApiException catch (error) {
  if (error.errorCode == 'CONSULTANT_ROLE_REQUIRED') {
    await widget.onConsultantRoleRequired();
    if (!mounted) return;
    return;
  }
  if (!mounted) return;
  showTransientMessage(context, _localizedConversationFailure(context));
} on Object {
  if (!mounted) return;
  showTransientMessage(context, _localizedConversationFailure(context));
}
~~~

Do not call <code>setState</code>, notify a controller, or show a generic message after the role callback has removed this route.

- [ ] **Step 6: 格式化并运行 Task 8 测试**

Run from the exact worktree Flutter package directory named in Global Constraints.

~~~powershell
dart format lib/features/consultant_orders/presentation test/features/consultant_orders/presentation
flutter test test/features/consultant_orders/presentation/consultant_orders_page_test.dart test/features/consultant_orders/presentation/consultant_order_detail_page_test.dart
~~~

Expected: PASS.

- [ ] **Step 7: 提交 Task 8**

~~~powershell
git add -- joysong-flutter/lib/features/consultant_orders/presentation/consultant_orders_page.dart joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_card.dart joysong-flutter/lib/features/consultant_orders/presentation/consultant_order_detail_page.dart joysong-flutter/test/features/consultant_orders/presentation/consultant_orders_page_test.dart joysong-flutter/test/features/consultant_orders/presentation/consultant_order_detail_page_test.dart
git commit -m "feat(flutter): build consultant order workbench"
~~~

### Task 9: 接入管理中心能力、仓库和现有订单会话导航

**Files:**

- Modify: <code>joysong-flutter/lib/features/identity/domain/identity_models.dart:309-407</code>
- Modify: <code>joysong-flutter/lib/features/identity/presentation/identity_pages.dart:541-1151</code>
- Modify: <code>joysong-flutter/lib/features/profile/presentation/profile_page.dart:20-52,318-331</code>
- Modify: <code>joysong-flutter/lib/features/shell/presentation/app_shell.dart:102-186,300-321,926-957</code>
- Create: <code>joysong-flutter/test/features/identity/domain/management_context_test.dart</code>
- Create: <code>joysong-flutter/test/features/identity/presentation/management_center_page_test.dart</code>

**Interfaces:**

- Consumes: backend <code>canAccessConsultantOrderWorkbench</code> and Task 6 repository.
- Passes: <code>Future&lt;void&gt; Function(String orderId)</code> from AppShell to consultant detail.
- On role loss: single-flight refresh management context, then pop all child routes back to the captured <code>ManagementCenterPage</code> route.

- [ ] **Step 1: 写管理上下文兼容红灯测试**

~~~dart
test('missing consultant order capability defaults to false', () {
  final context = ManagementContext.fromJson(oldServerContextJson);
  expect(context.canAccessConsultantOrderWorkbench, isFalse);
});

test('consultant order capability participates in hasAnyCapability', () {
  final context = ManagementContext.fromJson({
    ...baseContextJson,
    'activeRoles': ['CONSULTANT'],
    'canAccessConsultantOrderWorkbench': true,
  });
  expect(context.hasAnyCapability, isTrue);
});
~~~

- [ ] **Step 2: 写管理中心入口和身份失效红灯测试**

Use hand-written fakes and no new mocking dependency:

~~~dart
final class FakeIdentityRepository implements IdentityRepository {
  FakeIdentityRepository(this.contexts) : assert(contexts.isNotEmpty);

  final List<ManagementContext> contexts;
  int loadManagementContextCalls = 0;

  @override
  Future<ManagementContext> loadManagementContext() async {
    final index = loadManagementContextCalls < contexts.length
        ? loadManagementContextCalls
        : contexts.length - 1;
    loadManagementContextCalls += 1;
    return contexts[index];
  }

  @override
  dynamic noSuchMethod(Invocation invocation) =>
      super.noSuchMethod(invocation);
}

final class FakeConsultantOrdersRepository
    implements ConsultantOrdersRepository {
  FakeConsultantOrdersRepository({
    required this.page,
    required this.detail,
  });

  final ConsultantOrderPage page;
  final ConsultantOrderDetail detail;

  @override
  Future<ConsultantOrderPage> getOrders({
    required ConsultantOrderStage stage,
    String? institutionId,
    required int offset,
    required int limit,
  }) async => page;

  @override
  Future<ConsultantOrderDetail> getOrder(String orderId) async => detail;
}
~~~

Construct it with the existing empty ACTIVE page fixture and one valid detail fixture; the initial page load must return a real value rather than relying on an undefined helper or null.

Assert:

- active consultant plus capability true shows key <code>management-consultant-orders</code>;
- consultant role alone with capability false does not show the entry;
- capability true still hides/disables the entry when <code>consultantOrdersRepository</code> is null;
- tapping the entry opens <code>ConsultantOrdersPage</code>;
- when list/detail/conversation report role-required concurrently, management context reloads once and the workbench/detail routes are popped to the management route without a post-dispose exception;
- call the management role-loss handler twice back-to-back before the scheduled refresh starts, assert both calls return the same in-flight future, then pump and assert <code>loadManagementContextCalls</code> increases only once. This covers synchronous listener re-entry, not only already-awaited concurrency.

- [ ] **Step 3: 运行集成测试并确认红灯**

Run from <code>D:\code\kotlin\joysong\.worktrees\consultant-order-workbench\joysong-flutter</code>.

~~~powershell
flutter test test/features/identity/domain/management_context_test.dart test/features/identity/presentation/management_center_page_test.dart
~~~

Expected: tests fail because the field, entry and callback chain do not exist.

- [ ] **Step 4: 增加管理上下文字段**

Add with a rolling-compatible default:

~~~dart
this.canAccessConsultantOrderWorkbench = false,
~~~

~~~dart
canAccessConsultantOrderWorkbench:
    _boolean(map['canAccessConsultantOrderWorkbench']),
~~~

Add the field to <code>hasAnyCapability</code>. The entry condition is:

~~~dart
isConsultant && context.canAccessConsultantOrderWorkbench
~~~

Do not reuse <code>context.canManageOrders</code>.

- [ ] **Step 5: 装配仓库和导航回调**

AppShell state gains:

~~~dart
ConsultantOrdersRepository? _consultantOrdersRepository;
~~~

Initialize it with:

~~~dart
_consultantOrdersRepository = ConsultantOrdersRepositoryImpl(
  ApiConsultantOrdersRemoteDataSource(apiClient),
);
~~~

Clear it when <code>apiClient == null</code>. Pass the repository and <code>_openOrderServiceConversation</code> through <code>ProfilePage</code> and <code>ManagementCenterPage</code>; do not construct another <code>MessagingRepository</code>.

Implement the complete constructor chain, including every stored field and forwarding call:

~~~dart
// AppShell -> ProfilePage
ProfilePage(
  consultantOrdersRepository: _consultantOrdersRepository,
  onOpenConsultantOrderServiceConversation:
      _consultantOrdersRepository == null
          ? null
          : _openConsultantOrderServiceConversation,
  // existing arguments unchanged
)

// ProfilePage constructor/fields -> _openManagementCenter
final ConsultantOrdersRepository? consultantOrdersRepository;
final Future<void> Function(String orderId)?
    onOpenConsultantOrderServiceConversation;

ManagementCenterPage(
  consultantOrdersRepository: widget.consultantOrdersRepository,
  onOpenConsultantOrderServiceConversation:
      widget.onOpenConsultantOrderServiceConversation,
  // existing arguments unchanged
)

// ManagementCenterPage constructor/fields -> _ManagementCapabilities
_ManagementCapabilities(
  consultantOrdersRepository: widget.consultantOrdersRepository,
  onOpenConsultantOrderServiceConversation:
      widget.onOpenConsultantOrderServiceConversation,
  onConsultantRoleRequired: _handleConsultantRoleRequired,
  // existing arguments unchanged
)
~~~

In <code>_createDependencies</code>, clear <code>_consultantOrdersRepository</code> in the <code>apiClient == null</code> branch and recreate it from the new client in the non-null branch. Do not retain a repository bound to an old client after <code>didUpdateWidget</code>.

- [ ] **Step 6: 增加顾问分组入口和失效回退**

Add <code>_ManagementAction.consultantOrders</code>, key <code>management-consultant-orders</code> and localized label “服务订单 / Service orders”. The route creates:

~~~dart
ConsultantOrdersPage(
  repository: consultantOrdersRepository!,
  onOpenServiceConversation: onOpenConsultantOrderServiceConversation!,
  onConsultantRoleRequired: onConsultantRoleRequired,
)
~~~

Add the repository/open callback/role callback to <code>_ManagementCapabilities</code>' constructor and fields. The action is enabled only when all three product gates hold:

~~~dart
enabled: consultantOrdersRepository != null &&
    onOpenConsultantOrderServiceConversation != null &&
    isConsultant &&
    context.canAccessConsultantOrderWorkbench
~~~

Import <code>dart:async</code> in <code>identity_pages.dart</code>. In <code>_ManagementCenterPageState</code>, assign a placeholder future before starting any controller work so synchronous <code>notifyListeners()</code> cannot re-enter a gap:

~~~dart
Future<void>? _consultantRoleRevocation;

Future<void> _handleConsultantRoleRequired() {
  final inFlight = _consultantRoleRevocation;
  if (inFlight != null) return inFlight;
  final managementRoute = ModalRoute.of(context);
  final completer = Completer<void>();
  final operation = completer.future;
  _consultantRoleRevocation = operation;
  unawaited(Future<void>(() async {
    try {
      await _controller.enter();
      if (mounted && managementRoute != null && managementRoute.isActive) {
        Navigator.of(context).popUntil((route) => route == managementRoute);
      }
      if (!completer.isCompleted) completer.complete();
    } on Object catch (error, stackTrace) {
      if (!completer.isCompleted) completer.completeError(error, stackTrace);
    } finally {
      if (identical(_consultantRoleRevocation, operation)) {
        _consultantRoleRevocation = null;
      }
    }
  }));
  return operation;
}
~~~

This callback must be shared by list, detail and conversation errors.

- [ ] **Step 7: 让订单会话导航只上抛顾问角色错误**

Import <code>core/network/api_exception.dart</code>. Extend the private AppShell method with <code>propagateConsultantRoleRequired = false</code> while preserving <code>fallbackToOrdersOnFailure</code>. Add a one-argument adapter so the downstream callback type is exact:

~~~dart
Future<void> _openConsultantOrderServiceConversation(String orderId) =>
    _openOrderServiceConversation(
      orderId,
      propagateConsultantRoleRequired: true,
    );
~~~

Split the catch branch so only the stable role error is rethrown:

~~~dart
} on ApiException catch (error) {
  if (propagateConsultantRoleRequired &&
      error.errorCode == 'CONSULTANT_ROLE_REQUIRED') {
    rethrow;
  }
  if (!mounted) return;
  showTransientMessage(
    context,
    context.localized(
      '暂时无法打开订单沟通，请稍后重试',
      'Unable to open this order conversation. Please try again.',
    ),
  );
  if (fallbackToOrdersOnFailure) await _openOrders();
} on Object {
  if (!mounted) return;
  showTransientMessage(
    context,
    context.localized(
      '暂时无法打开订单沟通，请稍后重试',
      'Unable to open this order conversation. Please try again.',
    ),
  );
  if (fallbackToOrdersOnFailure) await _openOrders();
}
~~~

The workbench receives the one-argument adapter. Existing notification and consumer callers keep calling <code>_openOrderServiceConversation</code> with the default false value and retain their existing fallback behavior.

- [ ] **Step 8: 格式化并运行 Task 9 测试**

Run from the exact worktree Flutter package directory named in Global Constraints.

~~~powershell
dart format lib/features/identity/domain/identity_models.dart lib/features/identity/presentation/identity_pages.dart lib/features/profile/presentation/profile_page.dart lib/features/shell/presentation/app_shell.dart test/features/identity/domain/management_context_test.dart test/features/identity/presentation/management_center_page_test.dart
flutter test test/features/identity/domain/management_context_test.dart test/features/identity/presentation/management_center_page_test.dart
~~~

Expected: PASS.

- [ ] **Step 9: 提交 Task 9**

~~~powershell
git add -- joysong-flutter/lib/features/identity/domain/identity_models.dart joysong-flutter/lib/features/identity/presentation/identity_pages.dart joysong-flutter/lib/features/profile/presentation/profile_page.dart joysong-flutter/lib/features/shell/presentation/app_shell.dart joysong-flutter/test/features/identity/domain/management_context_test.dart joysong-flutter/test/features/identity/presentation/management_center_page_test.dart
git commit -m "feat(flutter): expose consultant service orders"
~~~

### Task 10: 完成验证与独立审查

**Files:**

- No production file changes; this task only verifies Tasks 1–9.

**Interfaces:**

- Verifies the exact backend/Flutter contract delivered by Tasks 1–9.
- Produces no documentation, UML, generated image or database migration.

- [ ] **Step 1: 只补跑最后一次通过后又被影响的后端测试**

Review the implementation log and branch diff first. Every Task 1–5 test class must have one recorded PASS after the last production file it covers changed. Do not repeat an already-green selector unchanged. If a later task or review fix touched a backend dependency after its last PASS, return to that file's owning task and rerun only its already-specified exact focused command: Task 1 for contract/policy, Task 2 for query/repository, Task 3 for controller/security, Task 4 for conversation/DM, or Task 5 for management capability. Run it from the exact worktree <code>joysong-server</code> directory with the worktree-local <code>GRADLE_USER_HOME</code>.

Expected: PASS for each actually required selector. The tagged <code>TravelGroundServicePaymentMigrationTest</code> is not part of <code>test</code>; rely on its Task 4 <code>mysqlIntegrationTest</code> PASS unless its slice imports or service dependencies changed afterward, in which case rerun that tagged command exactly once and confirm the printed isolated database again.

- [ ] **Step 2: 只补跑受影响的 Flutter 测试并做一次分析**

From the exact worktree <code>joysong-flutter</code> directory, rerun only test files whose production dependency changed after their Task 6–9 PASS. Then run analysis once:

~~~powershell
flutter analyze
~~~

Expected: PASS where Flutter SDK is available. If <code>flutter</code> is unavailable, record that single environment limitation and do not repeatedly retry; completion remains pending until these commands run on a configured Flutter host.

- [ ] **Step 3: 最多各运行一次全量验证**

After all targeted tests pass and only if the environment supports it:

~~~powershell
$env:GRADLE_USER_HOME = Join-Path $PWD '.tmp\gradle-user-home-codex'
.\gradlew.bat --offline test
~~~

Then, from the exact worktree Flutter package directory:

~~~powershell
flutter test
~~~

Run each full command at most once. Stop the backend full run at 10 minutes, report completed/slow tests, and do not restart it. The default backend full run excludes <code>mysql-integration</code>; do not count it as tagged-test evidence. Do not run any additional migration because this feature adds none.

- [ ] **Step 4: 做静态交付检查**

~~~powershell
git diff --check
git status --short
git diff --name-only master...HEAD
rg -n "TO[D]O|T[B]D|FIXM[E]" joysong-server/src joysong-flutter/lib joysong-flutter/test
rg -n "features/(orders|professional_management)|features\\(orders|professional_management)" joysong-flutter/lib/features/consultant_orders
~~~

Expected:

- no whitespace errors;
- no migration file in the branch diff;
- no documentation or UML changes outside the approved design specification and implementation plan;
- no consumer order, doctor order, payment or settlement model imports inside <code>features/consultant_orders</code>;
- no accidental changes outside the listed backend/Flutter files and the approved design/plan records;
- primary worktree remains untouched.

- [ ] **Step 5: 请求独立代码审查**

Request separate backend authorization/error review and Flutter navigation/privacy review. Any accepted finding must get the smallest failing test before the fix; do not perform unrelated refactors.

---

## Spec Coverage Matrix

| Approved design requirement | Implemented by |
|---|---|
| Independent <code>/api/consultant/orders</code> module | Tasks 1–3 |
| Own consultant, travel flow, activation and stage scope | Tasks 1–3 |
| Arbitrary offset, limit+1, no count, stable sorting | Task 2 |
| Field whitelist and anonymous erased customer | Task 2 |
| Existing-order conversation, read-only history | Task 4 |
| Role loss 403, account loss 401, consumer unaffected | Tasks 1, 3, 4 |
| Management capability and rolling compatibility | Tasks 5, 9 |
| Independent Flutter models and per-tab state | Tasks 6–8 |
| Only order-conversation action and 403 ejection | Tasks 8–9 |
| Chinese/English, large text, loading/error/empty states | Task 8 |
| API documentation, manual-test documents and UML | Excluded by the user's confirmed scope |
| No migration and isolated verification | Global Constraints, Tasks 2 and 10 |

## Execution Notes

- Recommended execution is subagent-driven: one fresh implementation agent per task, followed by specification review and code-quality review before the next task.
- Tasks 1–5 are the backend contract checkpoint. Tasks 6–8 may start only after Task 3 fixes the wire contract; Task 9 requires both backend capability and completed Flutter feature APIs.
- A task is complete only after its focused test command passes and its exact commit exists. Environment-blocked Flutter commands remain explicit verification debt and prevent an unconditional “all tests pass” claim.
