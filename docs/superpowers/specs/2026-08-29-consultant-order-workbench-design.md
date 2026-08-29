# 顾问独立订单工作台设计

**日期：** 2026-08-29

**状态：** 已确认，待实施计划

**分支：** `codex/consultant-order-workbench`

## 1. 背景

当前订单已经固化顾问、机构、医生和项目快照，旅游地接服务费支付成功后会进入 `SERVICE_ACTIVE`，并可创建每笔订单唯一的 `ORDER_SERVICE` 会话。Flutter 已有消费者订单页面、顾问身份与机构关系页面、医生专业订单页面和订单会话页面，但顾问没有安全查询本人服务订单的独立入口。

现有 `/api/management/orders` 服务于管理员和医生的历史医疗订单管理，包含核销及申请完成能力；消费者 `/api/orders` 只允许订单用户查看本人购买订单。把顾问加入任一现有接口都会混合角色、字段和状态机，因此新增独立的顾问订单只读模块。

## 2. 目标

首期解除顾问订单履约入口阻断，提供：

1. 顾问查看本人曾经支付激活、且当前处于允许工作台状态的服务订单。
2. 顾问按服务中、退款处理中和历史订单分页浏览。
3. 顾问查看经过隐私裁剪的订单详情。
4. 顾问从订单详情进入现有订单专属会话。
5. 退款中、已完成和已退款订单保留历史只读能力。
6. 顾问身份失效后立即关闭工作台和订单会话权限。

## 3. 非目标

首期不实现以下能力：

- 顾问接受、拒绝、转交或改派订单。
- 顾问开始服务、提交履约记录、上传凭证或申请完成。
- 顾问确认完成、核销、支付、退款、退款审批、改期或修改订单归属。
- 新的履约表、履约状态机、统计接口、全文搜索或工作台汇总接口。
- 修改消费者确认完成、支付、退款或医生历史医疗订单流程。
- 解决真实 Alipay+、订单创建幂等、自动完成、完成后退款规则等相邻问题。

## 4. 核心决策

### 4.1 独立顾问订单模块

后端在现有订单域内增加 `order/consultant` 包，不新建 Gradle 模块：

- `ConsultantOrderController`：HTTP 参数、认证主体和响应封装。
- `ConsultantOrderQueryService`：授权、查询、阶段映射和 DTO 投影。
- `ConsultantOrderAccessPolicy`：复用在订单查询和订单会话中的顾问身份规则。
- `ConsultantOrderResponse` 及分页 DTO：仅包含顾问履约所需字段。
- `OrderRepository`：增加顾问维度只读分页查询。

Flutter 增加独立 `features/consultant_orders` 功能，不复用消费者 `OrdersController/OrderDetailPage` 或医生 `DoctorOrdersPage`。可继续复用网络客户端、公开媒体解析、双语能力、订单状态枚举和订单会话页面。

### 4.2 不新增履约状态

工作台阶段是订单状态的只读投影，不写回数据库：

| 工作台阶段 | 订单状态 | 会话读取 | 消息发送 |
|---|---|---:|---:|
| `ACTIVE` | `SERVICE_ACTIVE` | 允许 | 允许 |
| `PAUSED` | `REFUND_REVIEW`、`REFUND_PROCESSING` | 已有会话允许 | 禁止 |
| `HISTORY` | `COMPLETED`、`REFUNDED` | 已有会话允许 | 禁止 |

`PENDING_SERVICE_FEE`、旧医疗状态及未知状态不进入顾问工作台。退款被取消或驳回后，订单恢复到其真实原状态，工作台阶段随恢复后的订单状态重新计算。

`SERVICE_ACTIVE` 可以创建或读取订单会话。`PAUSED` 和 `HISTORY` 只能读取订单激活期间已经存在的会话；如果订单从未创建过会话，响应返回 `conversationReadable=false`，不能在只读阶段补建空会话。

## 5. 授权模型

### 5.1 入口能力

`GET /api/management/context` 增加：

```json
{
  "canAccessConsultantOrderWorkbench": true
}
```

该字段只有在账号可用且 `CONSULTANT` 角色有效时为 `true`。Flutter 仅使用它控制入口可见性，不能把它作为对象级授权结果。

### 5.2 对象级授权

每次列表、详情、创建/读取订单会话及发送消息均由服务端重新验证：

1. 请求已经通过统一 JWT 认证；账号存在、未删除、未暂停且可登录。
2. 当前用户具有有效 `CONSULTANT` 角色。
3. `orders.consultant_id` 等于当前认证用户 ID；请求不接受 `consultantId`。
4. `payment_flow = 'TRAVEL_GROUND_SERVICE_ONLY'`。
5. `service_activated_at` 非空且订单处于允许的工作台状态。

访问不存在或不属于当前顾问的订单统一返回 `404`，避免泄露订单存在性。

### 5.3 机构退出与身份失效

订单归属在创建时已经固化。顾问退出原机构后，只要顾问专业身份和账号仍有效，已经激活并绑定给该顾问的订单继续可见，并按订单状态保留发送或只读能力；查询不再关联当前机构成员关系。

顾问账号暂停或账号注销后，现有 JWT 过滤器不会建立认证主体，请求在到达顾问模块前按统一认证规则返回 `401`。顾问身份撤销但账号仍有效时，顾问访问策略返回 `403 CONSULTANT_ROLE_REQUIRED`。以上情况均关闭工作台、订单会话读取和发送；普通订单用户对同一订单和会话的合法访问不受影响。

## 6. API 契约

所有路径均位于 `/api` 下，响应继续使用 `BaseResponse`，并返回与 `code` 一致的真实 HTTP 状态。

### 6.1 顾问订单列表

```http
GET /api/consultant/orders
  ?stage=ACTIVE|PAUSED|HISTORY
  &institutionId={optional}
  &offset=0
  &limit=20
```

规则：

- `stage` 默认 `ACTIVE`。
- `offset` 必须大于等于 `0`。
- `limit` 必须在 `1..100`。
- `institutionId` 只用于过滤当前顾问已有订单，不扩大数据范围。
- 首期不提供关键字搜索和服务端数量统计。

响应示例：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [
      {
        "id": "order-id",
        "orderNo": "JS202608290001",
        "stage": "ACTIVE",
        "status": "SERVICE_ACTIVE",
        "refundStatus": "NONE",
        "project": {
          "id": "project-id",
          "name": "项目快照名称",
          "coverImage": "/images/project.jpg"
        },
        "institution": {
          "id": "institution-id",
          "name": "机构快照名称"
        },
        "customer": {
          "displayName": "用户昵称",
          "avatar": "/images/avatar.jpg"
        },
        "appointmentTime": "2026-09-10T10:30:00",
        "updatedAt": "2026-08-29T12:00:00",
        "conversationReadable": true,
        "messageSendable": true,
        "readOnly": false
      }
    ],
    "offset": 0,
    "limit": 20,
    "hasMore": false
  }
}
```

排序：

- `ACTIVE`：预约时间升序，空预约时间排最后，再按订单 ID 升序保证稳定性。
- `PAUSED`、`HISTORY`：有效更新时间倒序，再按订单 ID 倒序保证稳定性；`updatedAt` 为空时使用 `createdAt`。

仓储查询使用项目内自定义的 offset-based `Pageable`，其 `getOffset()` 直接返回请求的任意 `offset`，不能用 `PageRequest.of(offset / limit, limit)` 模拟；也可以使用等价的 repository/native SQL `LIMIT :limit OFFSET :offset`。查询请求 `limit + 1` 条并返回 `List`，由服务层截断后计算 `hasMore`，不得使用会额外执行 count SQL 的 `Page`。MySQL 的 `ACTIVE` 排序显式使用“预约时间是否为空”、预约时间和订单 ID，保证空值固定排在最后。

列表通过 `conversation_type = 'ORDER_SERVICE' AND order_id IN (:pageOrderIds)` 批量查询当前页已有会话，并以返回的订单 ID 集合完成能力投影，避免逐单查询。详情使用同一“是否已有订单服务会话”规则。`ACTIVE` 即使还没有会话也返回可读可发送，因为点击后可以创建；`PAUSED` 和 `HISTORY` 只有已有会话时才返回可读。

### 6.2 顾问订单详情

```http
GET /api/consultant/orders/{orderId}
```

详情包含列表字段，并增加：

- 医生公开 ID 和订单快照名称。
- 用户预约备注。
- `createdAt`、`serviceActivatedAt`、`completedAt`。
- 稳定的状态代码，由 Flutter 本地化显示状态说明。
- 会话能力对象：

```json
{
  "conversation": {
    "readable": true,
    "sendable": false
  }
}
```

项目、机构和医生信息使用订单快照，避免履约过程中因公开资料变化而漂移。用户只返回当前公开昵称和公开头像；资料缺失或账号注销时返回匿名占位。

列表和详情禁止返回：手机号、真实姓名、核销码、二维码、支付尝试、支付金额、退款理由或证据、内部审核信息、钱包、分账、结算及内部状态日志。

### 6.3 订单沟通

继续复用现有接口：

```http
POST /api/orders/{orderId}/service-conversation
```

请求体为空。相同订单重复调用返回同一 `ORDER_SERVICE` 会话。接口按最新认证、顾问身份、订单归属和订单状态重新鉴权，响应继续返回服务端判定的读取和发送能力。`PAUSED` 和 `HISTORY` 只能返回已经存在的会话，不能新建。

`ConsultantOrderAccessPolicy` 只在请求者等于 `order.consultantId` 时执行；请求者等于 `order.userId` 时保持现有订单用户授权，不要求用户具有顾问角色。该规则必须覆盖 `getOrCreate`、会话列表投影、消息读取、已读、发送及本地隐藏/删除入口，避免从非详情入口绕过身份失效校验。

消息发送接口必须在每次发送时重新执行相同的顾问身份与订单状态校验。Flutter 不得仅依据列表或详情中的布尔字段放行发送。

## 7. 错误模型

稳定错误如下：

| HTTP | `errorCode` | 含义 |
|---:|---|---|
| 400 | `INVALID_CONSULTANT_ORDER_STAGE` | 阶段或分页参数非法 |
| 401 | 可为空 | 未登录、令牌失效、账号暂停或账号注销；由统一认证层处理 |
| 403 | `CONSULTANT_ROLE_REQUIRED` | 没有有效顾问身份或顾问身份已撤销 |
| 404 | `CONSULTANT_ORDER_NOT_FOUND` | 订单不存在或不属于当前顾问 |
| 404 | `ORDER_SERVICE_ACCESS_DENIED` | 订单会话不存在或请求者不是订单参与者 |
| 409 | `ORDER_SERVICE_NOT_ACTIVE` | 服务尚未激活 |
| 409 | `ORDER_SERVICE_READ_ONLY` | 当前状态只允许读取历史消息 |

示例：

```json
{
  "code": 404,
  "message": "订单不存在",
  "errorCode": "CONSULTANT_ORDER_NOT_FOUND",
  "data": null
}
```

顾问订单模块和订单会话使用携带 HTTP 状态及 `errorCode` 的类型化异常，由全局异常处理器转换为真实 HTTP 和 `BaseResponse`，不能继续依赖 `IllegalArgumentException` 的兼容 HTTP 200 路径。顾问订单列表参数由模块显式解析，以确保非法阶段也返回稳定错误码。

除统一认证层的 `401` 外，Flutter 根据稳定的 `errorCode` 显示本地中英文文案，不解析服务端中文消息。Flutter 对任何 HTTP `401` 走现有登录失效处理；收到 `403 CONSULTANT_ROLE_REQUIRED` 时退出工作台、刷新管理上下文并隐藏入口。

## 8. 数据流和一致性

1. Flutter 读取管理上下文并按能力显示“顾问 → 服务订单”。
2. 列表接口按认证用户、支付流程、激活时间和阶段状态执行只读查询。
3. 打开详情时重新请求服务端，不使用列表缓存决定权限。
4. 点击“订单沟通”调用现有会话接口并导航到现有会话页面。
5. 每次发送消息再次校验身份和状态。
6. 退款、完成或身份撤销发生在页面打开期间时，后续详情刷新或发送立即使用新权限。

列表和详情使用只读事务，不锁订单。阶段、`conversationReadable`、`messageSendable` 和 `readOnly` 均在响应时从服务端真实状态计算。

时间继续使用现有无时区 ISO-8601 格式，并统一按 `Asia/Shanghai` 解释；客户端不得附加或假设 `Z`。

## 9. Flutter 设计

### 9.1 模块结构

`features/consultant_orders` 包含：

- `domain`：`ConsultantOrderStage`、列表项、详情和分页模型。
- `data`：专用远程数据源及仓库。
- `presentation`：工作台控制器、详情控制器、列表页、订单卡片和详情页。

管理上下文模型增加 `canAccessConsultantOrderWorkbench`，缺失时默认 `false`，保证旧服务端滚动兼容。

### 9.2 页面行为

“专业管理中心 → 顾问”分组增加“服务订单”入口，不增加底部导航，也不改 Web Admin。

工作台包含：

- 服务中：`ACTIVE`。
- 退款处理中：`PAUSED`。
- 历史订单：`HISTORY`。

每个阶段独立保存分页、加载、错误和 `hasMore` 状态，首次切换时按需加载，支持下拉刷新和触底加载。

订单卡片只显示用户公开昵称/头像、项目、机构、预约时间和服务状态。详情显示项目、机构、医生、用户公开信息、预约时间、预约备注和状态说明。

详情页唯一主操作为“订单沟通”：

- `sendable=true`：进入可发送会话。
- `readable=true && sendable=false`：进入已经存在的历史只读会话并显示只读说明。
- `readable=false`：不展示入口。

页面不得出现确认完成、核销、退款、改期或修改归属按钮。

## 10. 隐私与安全

- 客户端不能传顾问 ID 决定数据范围。
- 查询必须从认证主体开始约束，不能先返回订单再依赖 Flutter 过滤。
- 列表和详情 DTO 使用字段白名单，不从通用 `OrderResponse` 复制后删字段。
- 用户公开昵称和头像来自公开资料投影；私有身份申请、材料、电话和真实姓名永不进入响应。
- 日志记录订单 ID、操作类型和结果，不记录预约备注、私信内容或私密用户资料。
- 顾问退出机构只影响新业务候选，不改变已激活订单快照；身份或账号失效则立即关闭专业访问。

## 11. 性能与数据库

首期不增加数据库迁移。现有 `idx_orders_consultant_id` 可先把查询收敛到单个顾问的数据范围，个人订单规模不足以证明需要复合索引。

实现后检查生成 SQL 和查询计划；只有证据表明分页扫描成为瓶颈时，才单独设计包含顾问、支付流程、状态和排序字段的复合索引。该性能优化不得与首期功能迁移捆绑。

## 12. 测试策略

### 12.1 后端

新增或扩展最小相关测试：

- 顾问只能查询本人、旅游地接且已经激活的订单。
- 三个阶段映射、稳定排序、分页和机构过滤正确。
- 他人订单、待支付订单、旧医疗订单和未知状态不可见。
- 退出机构后，已绑定激活订单仍可访问。
- 账号暂停、身份撤销或账号注销后，列表、详情、会话读取和发送均被拒绝。
- 响应字段白名单不包含手机号、核销码、金额、支付或结算数据。
- 订单退款、完成或退款成功后，会话发送能力立即关闭。
- 普通订单用户的合法会话读取和发送不受顾问身份规则影响。
- 管理上下文能力字段与有效身份一致。
- 未认证、过期令牌及暂停账号按统一认证链返回真实 HTTP `401`；顾问身份撤销返回 `403 CONSULTANT_ROLE_REQUIRED`。
- 顾问订单和订单会话的类型化异常确保 HTTP 状态、`BaseResponse.code` 与 `errorCode` 一致。
- 顾问身份撤销后，普通订单用户仍可创建、读取和发送同一订单会话，不出现顾问专属错误。

优先运行新测试类和受影响的现有测试类；相关测试通过后最多运行一次后端全量测试。任何测试数据库必须遵守 worktree 隔离规则，不连接共享开发库。

### 12.2 Flutter

新增模型、仓库、控制器和 widget 测试：

- API 路径、查询参数、分页和响应解析正确。
- 能力字段控制入口显示。
- 三个页签独立分页、刷新、加载、错误和空态正确。
- 服务中显示可沟通入口，暂停和历史订单显示只读状态。
- `403` 触发退出工作台和上下文刷新。
- 中文、英文、大字体和网络失败场景可用。

完成后运行 `flutter analyze` 和最接近的 Flutter 测试；相关测试通过后最多运行一次全量测试。

## 13. 文档与 UML

用户确认本次实施可不编写或更新 API 文档、MVP 文档、手工验收文档和 UML。本分支以本设计规范作为实现契约，只提交功能代码、测试及必要的计划记录；既有 PlantUML 源码和图片保持不变。

## 14. 实施顺序

1. 固化后端 DTO、错误类型和 API 测试。
2. 实现顾问访问策略、仓储查询、列表和详情接口。
3. 把顾问身份校验接入现有订单会话创建、列表、读取、已读、发送和隐藏路径，并引入类型化会话异常。
4. 扩展管理上下文能力字段及兼容解析。
5. 使用已确认契约实现 Flutter 模型、仓库、控制器和页面。
6. 接入专业管理入口和现有订单会话导航。
7. 完成相关自动化测试、静态检查和独立代码审查。

后端业务规则和对象级授权先落地；Flutter 可在接口契约固定后使用 mock 并行开发，但不得先以客户端过滤替代服务端授权。

## 15. 验收标准

满足以下条件才视为完成：

- 有效顾问能在专业管理中心进入独立服务订单工作台。
- 列表和详情只返回本人已激活的旅游地接订单。
- 服务中订单可进入并发送订单消息；退款中、已完成和已退款订单只能读取历史。
- 顾问退出机构后仍能继续服务并沟通已绑定激活订单；身份或账号失效后立即失去工作台和会话权限。
- 任何顾问都无法访问他人订单，也无法获得消费者、医生或管理员专属动作。
- 响应不泄露用户或财务敏感字段。
- 后端相关测试通过；Flutter 分析和相关测试在具备 SDK 的环境中通过。
- 独立审查确认 API 实现、Flutter 行为、隐私字段与本设计规范一致。

## 16. 开发环境说明

功能开发固定在 `codex/consultant-order-workbench` 分支及独立 worktree 中进行。该 worktree 从已提交的 `master` HEAD 创建，不携带主检出区未提交的用户改动。

创建 worktree 后的基线验证结果：

- 后端 `OrderControllerTest`、`OrderServiceConversationServiceTest`、`ManagementAccessServiceTest` 通过。
- 当前主机的 Flutter SDK 不在 `PATH`，因此基线 `flutter analyze` 未执行；实施完成前必须在可用 Flutter 环境中补齐验证。
