# 娇颜颂 Flutter API 接入契约

> 状态：服务端当前实现基线（2026-08-15）
> 适用：Flutter Android/iOS 用户端，以及医生、顾问、机构法人的专业管理入口
> API 根路径：`{baseUrl}/api`

## 1. 契约来源与接入原则

本文档以 `joysong-server/src/main/kotlin` 的实际控制器和 DTO 为准，是 Flutter 迁移阶段的接口基线。

`joysong-server/openapi.yaml` 和 `openapi.json` 只是旧用户端的 **Legacy/已废弃快照**，缺少 refresh token、身份认证、专业管理、Agent 等接口。**禁止使用这两份文件生成 Flutter 客户端或作为验收依据。** 后续只有在从运行中的服务端自动生成并纳入 CI 校验后，OpenAPI 才能恢复为契约来源。

Flutter 不连接数据库，也不调用管理后台网页。用户态和专业身份态都直接调用服务端 REST API。

### 专业端只读目录与申请撤回

ACTIVE 医生或服务端权限上下文允许的机构法人可进入“专业目录”。Flutter 仅使用既有兼容接口：`GET /admin/institutions`、`GET /admin/institutions/{id}`、`GET /admin/institutions/{id}/doctors`、`GET /admin/institution-projects`、`GET /admin/projects`。医生项目由真实机构项目响应的 `institutionId` 与 `doctors[].id` 在客户端过滤，不调用额外路径。`GET /admin/projects` 仅用于只读兼容目录；管理中心申请表单的项目选择仍遵循其独立 selection/target 契约，二者不可互换。后续详情 id 必须来自前序可见响应；403 与 404 显示相同不可用状态并允许重试。

医生本人状态为 `PENDING`、类型为 `JOIN` 或 `PROFILE_UPDATE` 的项目申请可调用 `POST /admin/institution-project-requests/{id}/withdraw`；成功刷新列表，失败保留原项。专业 Flutter 不创建、修改或删除项目/机构项目。管理员系统、管理员接口实现与页面不变 / Admin system, admin API implementation, and pages are unchanged.

`AppShell` 是 Flutter 唯一的账号作用域组合根；账号相关的 repository 和 controller 只能由它按当前登录会话创建、复用和销毁。

开发地址建议：

- Android 模拟器：`http://10.0.2.2:8080`
- iOS 模拟器：`http://127.0.0.1:8080`
- 真机：使用电脑局域网地址；生产环境必须使用 HTTPS
- 图片 URL 由服务端直接返回完整 URL，客户端不要自行拼接 `/images`。公开媒体切换至阿里云 OSS/CDN 后仍沿用此契约；私有媒体由服务端签发短期 URL，Flutter 仅通过 `PublicMediaUrlResolver` 解析，不持有 OSS AccessKey 或 Secret。

## 2. 统一响应与错误处理

除 SSE 和文件下载外，接口统一返回：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

客户端必须同时判断 HTTP 状态和响应体 `code`：

1. 仅当 HTTP 可解析且 `code == 200` 时视为业务成功。
2. 兼容期内，部分用户端业务错误仍可能是 HTTP 200、但 `code` 为 400/403/404/500。
3. 参数校验、认证失败、权限失败以及多数 `/api/admin/**` 错误会使用真实 HTTP 400/401/403/500。
4. 错误提示优先显示 `message`；没有响应体时再使用网络层提示。
5. HTTP 或 envelope `code == 401` 时只允许自动刷新一次；`403` 是权限不足，不能清除登录态或循环刷新。

建议 Flutter 定义：

```dart
class ApiEnvelope<T> {
  final int code;
  final String message;
  final T? data;
}
```

所有写接口都应防止按钮重复提交。支付、下单、身份申请、项目申请和分账确认不能由网络重试器自动重放。

## 3. 登录、令牌刷新与退出

### 3.1 登录接口

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/auth/send-code` | 发送登录/注册短信验证码 |
| POST | `/auth/register` | 手机号、验证码、密码注册；注册后始终是普通用户 |
| POST | `/auth/login` | 手机号和密码登录 |
| POST | `/auth/login-with-code` | 手机号和短信验证码登录 |
| POST | `/auth/login-with-google` | Google ID Token 登录 |
| POST | `/auth/reset-password` | 短信验证码重置密码 |
| GET | `/auth/check-phone-registered?phone=...` | 检查手机号是否注册 |
| POST | `/management/login` | 管理员、已认证医生、已确认机构法人登录专业管理入口 |

手机号统一在 Flutter 内归一化为 E.164，例如 `+8613800000000`。密码长度 8–128 位；短信验证码为 6 位数字。

普通登录成功的 `data`：

```json
{
  "token": "legacy access token",
  "accessToken": "JWT access token",
  "refreshToken": "opaque refresh token",
  "tokenType": "Bearer",
  "expiresIn": 86400,
  "user": {
    "id": "user-id",
    "phone": "+8613800000000",
    "role": "USER",
    "hasPassword": true
  }
}
```

新客户端只读取 `accessToken`；`token` 仅为旧 Android 兼容字段。`expiresIn` 单位为秒。

### 3.2 刷新和退出

| 方法 | 路径 | 请求体 |
|---|---|---|
| POST | `/auth/refresh` | `{ "refreshToken": "..." }` |
| POST | `/auth/logout` | `{ "refreshToken": "..." }` |

刷新令牌采用轮换机制：刷新成功后，旧 refresh token 立即失效，必须原子替换 access/refresh 两个令牌。Flutter 网络层必须实现 single-flight：多个并发 401 共用同一次刷新请求，后续请求等待结果。

存储要求：

- access token 可保存在内存，并在应用恢复时从安全存储恢复。
- refresh token 只能放入 `flutter_secure_storage` 对应的 Android Keystore/iOS Keychain。
- 请求头：`Authorization: Bearer {accessToken}`。
- 刷新失败、账号停用或凭证变更后清空本地账户数据并回到登录页。
- 主动退出先调用 `/auth/logout`，无论网络结果如何都清除本地令牌。

密码、手机号、平台角色变更以及账号注销会使既有令牌失效。

### 3.3 账户接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/PUT | `/user/profile` | 当前用户资料 |
| PUT | `/user/avatar` | `{ "avatar": "url" }` |
| POST | `/user/bind-phone` | 仅第三方账号首次绑定手机号 |
| PUT | `/user/password` | 已有密码时修改密码 |
| PUT | `/user/password/set` | 第三方账号首次设置密码 |
| POST | `/user/phone-change/send-current-code` | 发送原手机号验证码 |
| POST | `/user/phone-change/verify-current-code` | 验证原手机号 |
| POST | `/user/phone-change/send-new-code` | 发送新手机号验证码 |
| PUT | `/user/phone-change` | 更换手机号 |
| DELETE | `/user/account` | 注销账户 |

邮箱验证码和邮箱绑定当前未开放；Flutter 不展示可提交的邮箱绑定入口，也不要调用旧 `/user/bind-email`。

## 4. 平台角色、职业身份和管理上下文

`users.role` 只表示平台权限：

- `USER`：所有注册用户，包括医生、医美顾问和机构法人
- `ADMIN`：平台管理员

职业身份在 `user_roles` 中独立维护：

- `DOCTOR`
- `CONSULTANT`
- `INSTITUTION_LEGAL_REPRESENTATIVE`
- `INSTITUTION_CUSTOMER_SERVICE`（目前仅由后台维护，不能自助申请）

职业身份状态至少包括 `PENDING`、`ACTIVE`、`REVOKED`。身份被撤销后用户仍是普通 `USER`，普通用户功能和历史数据保留；管理能力立即按服务端结果回退。

Flutter 不能依据本地缓存角色自行授权。每次进入专业管理中心时调用：

- `POST /management/login`：返回登录令牌、用户和 `context`
- `GET /management/context`：刷新当前权限视图

`context` 字段：

```json
{
  "userId": "...",
  "platformRole": "USER",
  "activeRoles": ["DOCTOR"],
  "doctorId": "...",
  "managedInstitutionIds": [],
  "visibleInstitutionIds": ["..."],
  "canManageDoctors": true,
  "canManageInstitutions": false,
  "canManageInstitutionProjects": true,
  "canManageArticles": true,
  "canManageSplitConfigs": true,
  "canManageOrders": true
}
```

界面入口按布尔能力显示，服务端仍做最终对象级校验。不能只依据 `activeRoles` 推导机构或医生数据范围。

## 5. 官方身份认证

### 5.1 用户端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/identity/overview` | 当前身份与申请记录 |
| POST | `/identity/files` | 私有认证材料上传 |
| DELETE | `/identity/files/{fileId}` | 删除尚未提交的私有材料 |
| POST | `/identity/applications` | 提交身份申请 |

私有文件上传使用 `multipart/form-data`：

- `purpose`：材料类型
- `file`：JPG、PNG、WebP 或 PDF，最大 10 MB

返回 `fileId`，不返回公开 URL。身份材料不能使用公共 `/upload`，也不能在普通图片组件中预览 URL。

材料类型：`BUSINESS_LICENSE`、`ID_CARD_FRONT`、`ID_CARD_BACK`、`ID_CARD_HANDHELD`、`DOCTOR_QUALIFICATION`、`DOCTOR_PRACTICE_CERTIFICATE`、`CONSULTANT_PROOF`。

申请请求：

```json
{
  "roleCode": "DOCTOR",
  "applicationData": {
    "realName": "张三",
    "idNumber": "证件号码"
  },
  "documents": [
    { "fileId": "...", "documentType": "ID_CARD_FRONT" }
  ]
}
```

各身份额外必填信息和材料：

- 机构法人：`phone`、`institutionName`、`businessLicenseNo`、`region`、`address`；营业执照、身份证正反面。
- 医生：`hospitalName`、`department`、`title`、`qualificationNo`、`practiceNo`、`reason`；身份证正反面及手持照、医师资格证、医师执业证。
- 医美顾问：`phone`、`experience`、`proofDescription`、`reason`；身份证正反面、医美顾问证明。

同一身份只能存在一个待审核申请。审核通过后才创建职业能力；提交过的文件不能由用户删除。

### 5.2 公开证书图片与认证材料严格隔离

医生详情中的 `credentialImages` 是医生自主上传的主页展示图片，默认为空，不代表平台审核。Flutter 文案应使用“医生上传的证书图片/展示材料”，不得标注“资质保险箱”“查资质”或“平台已核验”。平台认证状态只能依据身份和医生档案返回的认证字段展示。

## 6. 用户端模块接口矩阵

下表列出 Flutter 首期应使用的实际接口族。详情字段以响应 DTO 为准；不应从旧 OpenAPI 生成类型。

| 模块 | 接口 |
|---|---|
| 首页 | `GET /home/banners`、`/hot-projects`、`/expert-articles`、`/user-diaries`、`/recommended-institution-projects` |
| 发现 | `GET /discover/filter-options`、`/projects`、`/diaries`、`/doctors`、`/institutions`、`/articles` 及对应 `/{id}` |
| 机构详情 | `GET /discover/institutions/{id}/projects`、`/diaries`、`/doctors`、`/institutions/{institutionId}/projects/{projectId}` |
| 项目医生 | `GET /discover/institution-projects/{institutionProjectId}/doctors`、`/consultation-fee` |
| 公共用户 | `GET /users/{id}/profile`、`/users/{id}/diaries` |
| 日记 | `GET /diaries/my`、`POST /diaries`、`PUT/DELETE /diaries/{id}` |
| 评论 | `GET /comments`、`/comments/replies`；`POST /comments`；`DELETE /comments/{id}` |
| 点赞 | `POST /likes`、`DELETE/GET /likes/{targetType}/{targetId}` |
| 收藏 | `GET/POST /favorites`、`DELETE /favorites/{type}/{targetId}`、`GET /favorites/{type}/{targetId}` |
| 举报 | `POST /reports`、`GET /reports/check/{targetType}/{targetId}` |
| 通知 | `GET /notifications`、`/unread-count`；`PUT /notifications/{id}/read`、`/read-all` |
| 优惠券 | `GET /coupons/available`、`/coupons/my`、`/coupons/{id}/discount` |
| 评价 | `GET /reviews/order/{orderId}`、`PUT/DELETE /reviews/{id}` |
| 私信 | `/dm/conversations`、`/dm/conversations/{id}/messages`、`/dm/messages/{id}` |
| 客服 | `/cs/conversations`、`/cs/conversations/{id}/messages`、`/read` |
| 翻译 | `POST /translations` |

`POST /translations` 请求：

```json
{
  "text": "待翻译内容",
  "targetLanguage": "en-US",
  "contentType": "general"
}
```

`targetLanguage` 使用 BCP 47；文本最多 12,000 字符。`contentType` 可为 `diary`、`comment`、`article`、`article_html`、`project`、`project_html`、`institution`、`doctor`、`message`、`general`。HTML 类型返回内容只能交给受限 HTML 渲染器，禁止执行脚本。

## 7. 分页和增量加载

### 7.1 offset/limit

发现列表、评论、日记、收藏、用户订单等已支持：

```text
?offset=0&limit=50
```

- `offset >= 0`
- `limit` 范围 1–100
- 当前响应仍是数组，没有 `total` 和 `nextCursor`
- Flutter 以“返回数量小于 limit”判断暂时到达末尾

### 7.2 时间游标

私信、客服消息和聊天历史使用：

```text
?limit=30&before=2026-08-05T15:30:00
```

`before` 是上一页最早消息的 `createdAt`，ISO-8601 本地时间。服务端返回一页按时间升序排列的消息，客户端按消息 ID 去重。聊天默认 100 条，私信默认 30 条，客服默认 50 条；单页不得超过 100。Agent 聊天是例外：Flutter 只请求并保留最新 20 条，不使用 `before` 进行永久历史分页。

会话列表等少量接口仍一次返回全部结果。Flutter 首期可以对接，但数据量扩大前需要补服务端游标，不得在客户端伪造 total。

## 8. 公共图片上传

`POST /upload`，`multipart/form-data`：

- `file`：必填，仅 jpg/jpeg/png/webp/gif，最大 10 MB，并校验文件签名
- `folder`：可选，只允许字母、数字、下划线和连字符，最长 64
- `customFileName`：可选；非管理员只能等于当前 `userId`

响应：`data.url`。头像、日记图片、文章封面、医生主页展示证书等使用此接口。

多图字段目前是逗号分隔字符串，例如 `"url1,url2"`，Flutter 数据层统一转换为 `List<String>`；提交时再 `join(',')`。不得把带逗号的 URL 写入该字段。

## 9. AI Chat、Agent 与 SSE

### 9.1 Chat

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/chat/sessions` | 创建会话 |
| GET | `/chat/sessions?persona=...` | 会话列表 |
| POST | `/chat/sessions/{id}/messages` | 非流式发送 |
| GET | `/chat/sessions/{id}/messages` | 历史消息；Flutter Agent 固定 `limit=20` 且不翻页 |
| POST | `/chat/sessions/{id}/messages/stream` | 禁用的兼容端点；始终返回 HTTP 404 / `AGENT_STREAMING_DISABLED` |
| DELETE | `/chat/sessions/{id}` | 删除会话 |
| DELETE | `/chat/sessions?persona=...` | 清理指定 persona 会话 |
| DELETE | `/chat/sessions/{id}/messages` | 清空会话消息 |
| DELETE | `/chat/messages/{id}` | 删除单条消息 |

创建请求：

```json
{
  "persona": "BESTIE",
  "contextType": "GENERAL",
  "contextId": "",
  "title": ""
}
```

- `persona`：`BESTIE` 或 `CONSULTANT`
- `contextType`：`GENERAL`、`DOCTOR`、`PROJECT`、`INSTITUTION`
- 非 `GENERAL` 必须传 `contextId`
- 消息 `content` 非空，最多 5,000 字符

同步发送请求：

```json
{
  "content": "请给出建议",
  "idempotencyKey": "optional-client-key"
}
```

`idempotencyKey` 是可选字段；不传的旧版客户端仍符合契约。Flutter Agent 对每次发送只调用一次同步 `POST /chat/sessions/{id}/messages`，失败后显示服务端错误，不自动重放 POST。界面只加载和显示最新 20 条消息，没有“加载更早消息”或其他永久历史分页入口。

不要调用旧 `/ai/chat` 或 `/ai/quick-questions`，服务端不存在这些路由。

### 9.2 Flutter 固定关闭 SSE

Flutter Agent 的 SSE 配置固定为关闭，运行时仅使用 9.1 中的同步 REST 发送接口。`/messages/stream` 不是可用的服务端 SSE 能力，而是始终返回 HTTP 404 / `AGENT_STREAMING_DISABLED` 的禁用兼容端点。客户端可保留独立 decoder 作为未来兼容性代码，但当前配置不得打开 SSE，也不将流式终态、断线重连或 POST 重放纳入客户端行为。

### 9.3 Agent

| 方法 | 路径 |
|---|---|
| GET/PUT | `/agent/profile` |
| POST | `/agent/profile/confirm` |
| POST | `/agent/assessments` |
| POST | `/agent/assessments/{assessmentId}/plans` |
| GET/DELETE | `/agent/plans` |
| GET/DELETE | `/agent/plans/{planId}` |
| POST | `/agent/catalog/report` |

Agent 调试仅使用受控、脱敏的结构化日志；不存在 traces API，Flutter 不得调用 `/agent/traces`。Agent 输出是辅助决策，不是医疗诊断。Flutter 必须展示风险、限制、替代方案和需要确认项，不能只展示推荐项目名称。大模型密钥未配置时，接口可能使用规则或降级结果；客户端不要根据 `provider` 文案推断医疗可靠性。

## 10. 订单、支付占位与核销流程

### 10.1 用户接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/orders` | 创建订单 |
| GET | `/orders?status=&offset=&limit=` | 当前用户订单 |
| GET | `/orders/{id}` | 订单详情 |
| POST | `/orders/{id}/pay-consultation` | 支付面诊金（当前占位） |
| POST | `/orders/{id}/verification-code` | 生成首次到店 6 位核销码 |
| POST | `/orders/{id}/pay-balance` | 支付尾款（当前占位） |
| POST | `/orders/{id}/confirm-completion` | 用户确认完成 |
| POST | `/orders/{id}/refund` | 申请退款 |
| GET | `/orders/{id}/refund` | 退款详情 |
| POST | `/orders/{id}/cancel-refund` | 取消退款申请 |
| POST | `/orders/{id}/review` | 提交评价 |
| GET | `/orders/{id}/settlement` | 结算详情 |
| GET | `/orders/{id}/status-logs` | 状态日志 |
| POST | `/orders/{id}/cancel` | 取消待支付订单 |
| DELETE | `/orders/{id}` | 删除已结束订单 |

创建订单请求：

```json
{
  "projectId": "base-project-id",
  "institutionProjectId": "institution-project-id",
  "doctorId": "doctor-user-id",
  "quantity": 1,
  "remark": "",
  "userCouponId": 123,
  "appointmentTime": "2026-08-06T10:00:00"
}
```

选择医生时必须使用 `/discover/institution-projects/{institutionProjectId}/doctors` 返回的医生；服务端会再次校验医生与机构项目绑定。价格由服务端计算，客户端金额只用于展示。

### 10.2 状态与双方动作

```text
PENDING_PAYMENT
  -> CONSULTATION_PAID
  -> VERIFIED
  -> BALANCE_PAID
  -> PENDING_COMPLETION
  -> COMPLETED
  -> PENDING_SETTLEMENT
  -> SETTLED
```

另有 `CANCELLED`、`REFUNDED`、`DISPUTE_MEDIATION` 分支。

核销职责必须分离：

1. 用户在 `CONSULTATION_PAID` 状态调用 `/verification-code`，向现场人员展示核销码。
2. 订单所属医生调用 `POST /management/orders/{id}/verify`，请求体 `{ "verificationCode": "123456" }`，推进到 `VERIFIED`。
3. 用户支付尾款后进入 `BALANCE_PAID` 并获得第二次核销码。
4. 订单所属医生调用 `POST /management/orders/{id}/request-completion`，推进到 `PENDING_COMPLETION`。
5. 用户调用 `/confirm-completion` 最终确认。

旧 `POST /orders/{id}/verify` 仅为旧 Android 兼容，当前语义也只是生成核销码。Flutter 禁止使用该旧路径。用户端绝不能调用专业端核销接口，也不能自行确认自己的核销码。

### 10.3 支付边界

支付方式、第三方下单、验签、异步回调、幂等通知和退款通道尚未落地。现有 `pay-consultation`、`pay-balance` 与兼容 `/pay` 仅用于内部流程占位；`/pay` 的 `method` 当前不会真正选择渠道。

Flutter 可以完成页面和接口抽象，但生产发布前必须等待支付服务契约确定，并至少补齐：支付预下单、渠道参数、支付查询、服务端回调验签、回调幂等、退款查询。客户端不能把“接口返回成功”等同于渠道到账。

### 10.4 收入台账与结算查询

结算记录和钱包是平台内部收入台账：金额一律使用最小货币单位整数 `minor` 与 ISO-4217 三位 `currency`，同一金额对象不得混用元/浮点数与 `minor`。`PENDING`、`AVAILABLE`、`PARTIALLY_REVERSED`、`REVERSED` 是分账状态；钱包分别维护 `pending`、`available`、`frozen` 三个余额桶。`PENDING` 表示待释放的内部余额，`AVAILABLE` 表示已释放到内部可用余额，部分或完全冲正分别为 `PARTIALLY_REVERSED`、`REVERSED`。

| 作用域 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 订单所属用户 | GET | `/orders/{id}/settlement` | 仅返回本人订单的消费者安全结算摘要 |
| 当前登录用户 | GET | `/wallets/me` | 服务端按认证主体返回独立钱包；普通用户成功返回空列表 |
| 当前登录用户 | GET | `/wallets/me/ledger?walletId={walletId}&page=0&size=20` | 查询一个已授权钱包的账本；`walletId` 必填，`size` 为 1–100 |
| 管理员 | GET | `/api/admin/settlements?page=0&size=20` | 结算列表 |
| 管理员 | GET | `/api/admin/settlements/{id}` | 结算详情 |
| 管理员 | GET | `/api/admin/settlements/{id}/allocations?page=0&size=20` | 分账明细 |
| 管理员 | GET | `/api/admin/ledger/{id}` | 单条账本记录 |
| 管理员 | GET | `/api/admin/reconciliation-issues?page=0&size=20` | 对账异常列表 |
| 管理员 | GET | `/api/admin/reconciliation-issues/{id}` | 对账异常详情 |

钱包概览固定为 `{"currency":"USD","wallets":[...]}`；每项有 `walletId`、`ownerType`、`ownerId`、`displayName`、`ownerName`、`pendingMinor`、`availableMinor`、`frozenMinor`，全部金额为整数最小货币单位。`displayName` 是与 `ownerType` 相同的稳定语义代码，Flutter 按 locale 本地化。`walletId` 只能选择当前主体已授权的一个钱包，绝不能合并多身份余额。

账本分页固定为 `content`、`page`、`size`、`totalElements`、`totalPages`、`last`。每项有 `id`、`walletId`、`entryType`、`title`、`description`、`sourceType`、`sourceId`、`amountMinor`、三个余额快照、`currency`、`createdAt`。`title == entryType`，`description == sourceType + ':' + sourceId`是兼容形状；Flutter 以 `entryType`/`sourceType` 本地化并显示 `sourceId`。这些是公开语义代码，不是且不得暴露内部 `operationKey`。`RELEASE` 的 `amountMinor` 固定为 `0`，不得作为收入重复累计。

`GET /orders/{id}/settlement` 的消费者安全摘要固定为 `settlementId`、`orderId`、`currency`、`grossTotalPaid`、`netSettled`、`state`、`settlementDueAt`、`settlementCreatedAt`、`releasedAt`。金额对象为 `{minor, currency}`；409 `SETTLEMENT_NOT_GENERATED` 表示结算生成中，应呈现非致命等待状态，不能转成空结算或零金额。

`SETTLED`/`AVAILABLE` 只表示内部结算台账已释放，**不表示**款项已经通过 Airwallex 或其他外部通道打款。Airwallex、收款人/beneficiary 与 KYC、FX、提现/withdrawal 和真实 payout 均不在当前 API 契约范围内；客户端不得据此展示出金成功、收款账户或换汇状态。

## 11. 医生与机构专业管理接口

专业入口使用受限接口，服务端按 `ManagementContext` 做对象级过滤。医生本人档案是单资源，不经过管理员医生列表或带 ID 的管理路由。

| 能力 | 接口 |
|---|---|
| 医生本人档案 | `GET /management/doctor-profile`、`PUT /management/doctor-profile` |
| 法人自助机构档案 | `GET /management/institutions`、`GET /management/institutions/{institutionId}`、`PUT /management/institutions/{institutionId}` |
| 医生/顾问本人机构关系申请 | `GET /management/institution-membership-requests/owned`、`GET /management/institution-membership-candidates`、`POST /management/institution-membership-requests`、`POST /management/institution-membership-requests/{requestType}/{id}/withdraw` |
| 机构关系审核 | `GET /management/institution-membership-requests/reviewable`、`POST /management/institution-membership-requests/{requestType}/{id}/review` |
| 顾问滚动兼容接口 | `GET/POST /management/consultant-memberships`；新 Flutter 不调用，不能作为新关系状态机 |
| 已认证专业用户只读项目目录 | `GET /management/projects` |
| 医生项目创建申请 | `GET /management/project-requests`、`POST /management/project-requests/platform`、`POST /management/project-requests/institutions/{institutionId}`、`GET /management/project-requests/institution-form-config`、`POST /management/project-requests/{id}/review`、`GET /admin/project-requests`、`POST /admin/project-requests/{id}/review` |
| 专业端兼容只读机构数据（迁移期） | `GET /admin/institutions`、`GET /admin/institutions/{id}`、`GET /admin/institutions/{id}/doctors`、`GET /admin/institutions/{id}/projects`、`GET /admin/institution-projects`；已认证专业用户仅可读 `visibleInstitutionIds` 范围 |
| 专业端兼容只读项目目录（迁移期） | `GET /admin/projects`；这是不含机构写权限的全局项目目录 |
| 平台管理员全量机构 CRUD | `GET/POST /admin/institutions`、`GET/PUT/DELETE /admin/institutions/{id}`；其中写操作仅限 `ADMIN`，GET 对专业用户仅提供下行所述对象级兼容读取 |
| 医生本人文章 | `GET/POST /management/doctor-articles`、`PUT/DELETE /management/doctor-articles/{id}` |
| 机构项目 | `GET/POST /admin/institution-projects`、`PUT/DELETE /admin/institution-projects/{id}` |
| 医生项目协作 | `GET /admin/institution-project-requests/profile-update-targets`、`GET/POST /admin/institution-project-requests`、`POST /{id}/review`、`/{id}/withdraw` |
| 当前分账 | `GET /admin/doctor-institution-project-configs` |
| 分账提案 | `GET/POST /admin/doctor-institution-project-config-proposals`、`POST /{id}/confirm`、`/reject`、`/withdraw` |
| 相关订单 | `GET /management/orders?status=...`、`GET /management/orders/{id}`、核销接口见上节 |

### 11.1 医生/顾问机构关系申请统一契约

Flutter 的医生、顾问申请页和机构法人审核页统一使用以下规范接口。所有路径均位于 `/api` 下；根
`GET /management/institution-membership-requests` 与
`GET/POST /management/consultant-memberships` 仅为旧客户端滚动兼容接口，不是新 Flutter 的读取、写入或状态判断来源。

| 方法 | 路径 | 语义与权限 |
|---|---|---|
| GET | `/management/institution-membership-requests/owned` | 当前登录用户以本人已激活的 `DOCTOR` 和/或 `CONSULTANT` 身份提交的完整账本历史 |
| GET | `/management/institution-membership-requests/reviewable` | 已确认机构法人仅取得 `managedInstitutionIds` 内的医生/顾问申请；平台管理员取得全部申请 |
| GET | `/management/institution-membership-candidates` | 当前专业身份的 `JOIN`/`LEAVE` 候选机构分页 |
| POST | `/management/institution-membership-requests` | 本人提交医生或顾问 `JOIN`/`LEAVE` 申请 |
| POST | `/management/institution-membership-requests/{requestType}/{id}/withdraw` | 仅申请人本人撤回仍为 `PENDING` 的申请 |
| POST | `/management/institution-membership-requests/{requestType}/{id}/review` | 目标机构已确认法人或平台管理员批准/驳回申请 |

提交请求必须且只能包含这些字段（`requestNote` 可省略，服务端按空字符串处理）：

```json
{
  "requestType": "DOCTOR",
  "institutionId": "institution-id",
  "action": "JOIN",
  "requestNote": "申请说明"
}
```

- `requestType` 只允许 `DOCTOR`、`CONSULTANT`；`action` 只允许 `JOIN`、`LEAVE`。
- `institutionId` 去除首尾空白后不能为空；`requestNote` 去除首尾空白后最多 1000 个字符。
- `JOIN` 要求申请人当前没有目标机构的有效关系；`LEAVE` 要求当前关系有效。同一专业身份与机构同时最多一条 `PENDING`。
- 医生必须是本人已认证且激活的医生；顾问必须是本人已激活的顾问。候选接口和提交/审核都会在服务端重新校验，客户端列表不是权限边界。

审核请求必须且只能包含：

```json
{
  "decision": "REJECTED",
  "reviewNote": "驳回原因"
}
```

`decision` 只允许 `APPROVED`、`REJECTED`；`reviewNote` 去除首尾空白后最多 1000 个字符，`REJECTED` 时不能为空，`APPROVED` 时可省略或为空。非平台管理员只能审核目标 `institutionId` 属于本人 `managedInstitutionIds` 的申请；审核人本人提交给其他机构的申请不会混入 `/reviewable`。

候选查询参数是 `requestType=DOCTOR|CONSULTANT`、`action=JOIN|LEAVE`、可选 `query`、默认
`offset=0` 和默认 `limit=20`。`offset` 必须大于等于 0，`limit` 必须大于 0且服务端最多返回 100；响应为：

```json
{
  "items": [{ "id": "institution-id", "name": "机构名称" }],
  "offset": 0,
  "limit": 20,
  "hasMore": false
}
```

`JOIN` 候选只含已认证、未删除、有非空名称、当前未绑定且没有待审申请的机构；`LEAVE` 候选只含当前有效绑定且没有待审申请的机构。`query` 去除首尾空白后按机构名进行不区分大小写的字面量包含查询，`%`、`_`、反斜杠不会被当作通配符；结果按机构名、机构 ID 稳定升序。Flutter 必须使用响应中的 `offset`、`limit`、`hasMore` 驱动分页，并按 ID 去重。

`/owned`、`/reviewable` 以及所有三个写接口的 `data` 使用同一个严格 17 字段对象；列表接口的 `data` 是该对象数组：

```json
{
  "id": "request-id",
  "requestType": "CONSULTANT",
  "applicantId": "user-id",
  "applicantName": "顾问姓名",
  "institutionId": "institution-id",
  "institutionName": "机构名称",
  "action": "LEAVE",
  "status": "PENDING",
  "relationshipStatus": "APPROVED",
  "requestNote": "申请退出",
  "reviewNote": "",
  "submittedBy": "user-id",
  "reviewedBy": null,
  "submittedAt": "2026-08-15T10:00:00",
  "reviewedAt": null,
  "createdAt": "2026-08-15T10:00:00",
  "updatedAt": "2026-08-15T10:00:00"
}
```

字段必须全部存在；只有 `reviewedBy`、`reviewedAt` 可显式为 `null`。新账本状态严格为
`PENDING`、`APPROVED`、`REJECTED`、`WITHDRAWN`。`relationshipStatus` 是关系结果快照：`/owned`、`/reviewable` 在查询时读取当前投影，写接口返回该写事务完成时应成立的关系结果；它不是长期不变的缓存字段，后续合法关系变更后客户端必须刷新管理上下文和账本列表：

- `APPROVED`：当前关系仍有效；例如 `LEAVE/PENDING`、`LEAVE/REJECTED`、`LEAVE/WITHDRAWN`。
- `NONE`：当前没有有效关系；例如未批准的 `JOIN`，或已批准的 `LEAVE`。

Flutter 展示当前机构时必须读取 `/management/context` 的 `doctorInstitutionIds` 或
`consultantInstitutionIds`；不得用历史 `APPROVED` 申请推断当前绑定。申请重提会新增账本记录，不覆盖已处理历史。

状态效果与事务边界：撤回、驳回以及仍待审的申请都不改变关系投影；批准 `JOIN` 创建或重新激活唯一有效关系；批准 `LEAVE` 只撤销目标机构关系，并只清理目标机构上的 active 专业会话。关系投影、目标会话清理和账本关闭处于同一事务，任一步失败全部回滚。新请求绝不把 `PENDING` 或 `REJECTED` 写入 `doctor_institutions` 或 `institution_memberships`。

本接口族使用真实 HTTP 状态：未登录为 401；参数/枚举/分页非法、未知 JSON 字段、说明过长或驳回原因空白为 400；专业身份不匹配、非本人撤回或跨机构审核为 403；机构或账本申请不存在为 404；关系状态不满足、重复待审、重复/并发处理或审核时状态已变化为 409。写请求不得自动重放；409 后刷新管理上下文、候选和账本列表。

#### V27、滚动兼容与部署顺序

`V27__add_consultant_institution_change_requests.sql` 新增独立顾问申请账本
`consultant_institution_change_requests`。它把旧 `institution_memberships` 的顾问
`PENDING`、`REJECTED`、`APPROVED` 行分别回填为 `JOIN` 同状态账本记录并沿用原 ID；旧
`REVOKED` 关系只保留关系历史，不伪造用户主动 `LEAVE`。迁移不更新或删除既有 membership，因此现有
`APPROVED`/`REVOKED` 顾问绑定无需重建。旧 `PENDING`/`REJECTED` membership 行也暂时保留用于滚动兼容，但新关系投影读取和写入不再依赖它们。

部署必须按“停止并排空旧写入实例 → 运行 V27 → 启动新写入实例”执行。V27 完成后不得再让旧实例向
`institution_memberships` 写入顾问 `PENDING`/`REJECTED`；新写入中该表只保存顾问当前/历史关系投影，所有新顾问申请写入独立账本。根 `GET /management/institution-membership-requests` 仍合并受权限约束的账本与无同机构账本历史的旧关系投影；它额外包含旧 `userId` 字段，旧投影还可能返回 `REVOKED` 等原始关系状态，严禁交给四状态规范模型解析。`GET /management/consultant-memberships` 仍为旧 Flutter 保留包括 legacy `REVOKED` 在内的兼容视图，兼容 POST 固定转发为严格的 `CONSULTANT/JOIN`。这些兼容入口不提供第二套更宽松校验，也不能替代 `/owned`、`/reviewable`、候选和 typed mutation 路径。

### 11.2 医生项目创建申请（平台项目与机构项目）

本节只定义医生发起的**项目创建**申请；它不替代 11.1 的机构关系 `JOIN`/`LEAVE` 账本，也不改变既有 `/admin/institution-project-requests` 的 `PROFILE_UPDATE` 契约和其 `CHANGES_REQUESTED` 决策。两条创建申请均为不可编辑的提交快照：审核人只能批准或驳回，驳回后须以新申请重新提交。

| 方法 | 路径 | 权限与结果 |
|---|---|---|
| GET | `/management/projects` | 拥有至少一个活跃 `DOCTOR`、`CONSULTANT` 或 `INSTITUTION_LEGAL_REPRESENTATIVE` 身份的只读全局目录；不因同时具备 `ADMIN` 身份而拒绝，按 `name`、`id` 排序，无查询参数、无写权限 |
| GET | `/management/project-requests` | 本人申请；机构法人额外可见其当前 `managedInstitutionIds` 内的机构项目申请；平台管理员可见全部 |
| POST | `/management/project-requests/platform` | 活跃认证医生提交平台项目创建申请；仅平台管理员审核 |
| POST | `/management/project-requests/institutions/{institutionId}` | 已有目标机构有效已批准执业关系的活跃认证医生提交机构项目创建申请 |
| GET | `/management/project-requests/institution-form-config` | 仅活跃认证医生；返回当前服务端 `platformRate` |
| POST | `/management/project-requests/{id}/review` | 目标机构当前已批准法人审核机构项目申请；平台管理员也可审核 |
| GET | `/admin/project-requests` | 平台管理员的完整审核列表 |
| POST | `/admin/project-requests/{id}/review` | 仅平台管理员审核平台项目申请 |

`GET /management/projects` 返回的每项都完整包含 13 个继承源字段：`id`、`name`、`category`、`description`、`tags`、`categoryTags`、`coverImage`、`referencePrice`、`currency`、`slogan`、`detailContent`、`images`、`salesCount`。机构项目申请的可继承显示字段以此目录/服务端锁定的当前平台项目为准，不能用本地缓存伪造。

#### 平台项目申请：精确 13 键请求体

`POST /api/management/project-requests/platform` 的 JSON 请求体必须且只能有下列 13 个键：

```json
{
  "name": "项目名称",
  "category": "面部护理",
  "description": "项目说明",
  "referencePrice": 1280.00,
  "currency": "USD",
  "slogan": "项目标语",
  "salesCount": 0,
  "coverImage": "https://cdn.example/cover.jpg",
  "images": ["https://cdn.example/1.jpg"],
  "detailContent": "多行详情",
  "tags": ["舒缓"],
  "categoryTags": ["皮肤管理"],
  "notes": "申请备注"
}
```

服务端直接比较 JSON 对象的键集合：上列 13 个键**每个都必须出现**，不能漏传；所有可空字段（当前为 `detailContent`）没有值时也必须显式发送 `null`，不能省略。`name`、`category`、`description` 去除首尾空白后必填；`images`、`tags`、`categoryTags` 是 JSON 字符串数组，绝不能改为遗留逗号分隔字段。申请人医生不来自 JSON：服务端只使用当前已认证医生身份，且不接受 `doctorId`、`doctorIds`、`doctorBindings`。同样禁止 `rating`、`reviewCount`、`platformRate`、`doctorRate` 及任何未知键。

#### 机构项目申请：路径机构 + 精确 18 键请求体

`POST /api/management/project-requests/institutions/{institutionId}` 的 `institutionId` 只在路径中出现；请求体必须且只能有下列 18 个键，不能在 body 重复 `institutionId`：

```json
{
  "projectId": "platform-project-id",
  "name": null,
  "category": null,
  "description": null,
  "tags": null,
  "slogan": null,
  "detailContent": null,
  "price": 1280.00,
  "originalPrice": null,
  "currency": "USD",
  "coverImage": null,
  "images": null,
  "salesCount": 0,
  "isActive": true,
  "consultationFee": 100.00,
  "commissionRate": 10.00,
  "institutionRate": 20.00,
  "notes": "申请备注"
}
```

服务端同样直接比较键集合：上列 18 个键**每个都必须出现**。所有可空覆盖字段以及可空的 `originalPrice` 没有值时，nullable 字段也必须显式发送 `null`，不能省略。`name`、`category`、`description`、`tags`、`slogan`、`detailContent`、`coverImage`、`images` 是独立的机构展示覆盖项；`null` 或空白字符串继承平台项目对应显示值，`tags` 与 `images` 为 `null` **或空数组 `[]`**时也继承，只有非空数组才作为 JSON 字符串数组覆盖值。`price`、`consultationFee`、`commissionRate`、`institutionRate` 必须提交。禁止医生选择、评分/评价计数和 `platformRate`/`doctorRate` 输入，与平台申请相同也禁止 `doctorId`、`doctorIds`、`doctorBindings` 和未知键；批准时唯一绑定的是已认证申请医生。

#### 表单配置、校验与分账

Flutter 必须先调用 `GET /api/management/project-requests/institution-form-config`，响应为：

```json
{ "platformRate": 10.00 }
```

`platformRate` 只读且由服务端当前策略提供。表单只能编辑 `consultationFee`、`commissionRate`（医美顾问比例）和 `institutionRate`（机构比例）；只读展示：

```text
doctorRate = 100 - platformRate - institutionRate - commissionRate
```

`doctorRate` 不得小于 0，且绝不发送给服务端。`referencePrice`、`price`、可选 `originalPrice` 和 `consultationFee` 都必须在 `0..99999999.99`，最多两位小数；`salesCount` 必须为 `0..2147483647` 的整数。`commissionRate` 与 `institutionRate` 均为 `0..100`、最多两位小数，连同当前 `platformRate` 的总和不得超过 100。文本经 trim 后的上限为：名称 200、分类 100、描述 5000、标语 500、封面 URL 500、详情 20000、备注 2000；`tags`、`categoryTags`、`images` 各最多 20 项，标签项最多 100 字符、图片项最多 500 字符，且每项不得为空。除 JSON 数组本身外，写入目标表时的逗号序列化还受长度限制：`tags`、`categoryTags` 目标逗号序列化后最多 500 个字符，`images` 目标逗号序列化后最多 2000 个字符。

提交时创建 `PENDING` 账本快照。批准机构申请时服务端在同一事务中再次校验申请医生的有效机构关系、目标机构、平台项目、机构项目唯一性、金额和当前平台比例；平台比例漂移而使方案无效时返回 409。任何客户端写操作不得自动重试。

#### 不可变审核视图与审核决定

`GET /api/management/project-requests` 和 `GET /api/admin/project-requests` 的 `data` 是 `ProfessionalProjectRequestView` 数组。`id`、`doctorId`、`institutionId`、`projectId` 及提交的项目内容、机构覆盖项、价格与申请备注来自请求账本的不可变快照；状态、审核信息、结果 ID 和生命周期时间字段则反映申请后续处理结果。`doctorName`、`institutionName`、`projectName` 是查询时通过当前 JOIN 取得的**当前名称**，不是提交时冻结的快照。每个对象完整包含：

```text
id, requestType, doctorId, doctorName, institutionId, institutionName,
projectId, projectName, name, category, description, tags, slogan,
detailContent, currency, coverImage, images, salesCount, referencePrice,
categoryTags, price, originalPrice, isActive, institutionSplit, notes,
status, reviewNote, reviewedBy, reviewedAt, resultingProjectId,
resultingInstitutionProjectId, submittedAt, updatedAt
```

`institutionSplit` 仅在 `requestType: "INSTITUTION"` 时存在，且精确包含五个数值字段：

```json
{
  "consultationFee": 100.00,
  "commissionRate": 10.00,
  "institutionRate": 20.00,
  "platformRate": 10.00,
  "doctorRate": 60.00
}
```

这里的五个字段不应统称为“不可变 split 快照”：`consultationFee`、`commissionRate`、`institutionRate` 是提交时保存、不可由审核人编辑的申请快照；`platformRate` 是列表读取时从当前服务端策略取得的比例，`doctorRate` 在该次读取时按当前 `platformRate` 和上述三个已提交比例重新推导。平台策略变化时，同一申请在列表中可显示新的平台率和医生净比例；批准前服务端仍会按当前平台率再次校验，若方案不再有效则返回 409，账本申请保持未处理且不会产生部分目标记录。审核 UI 若缺少主键、按申请类型必需的目标 ID、已提交的关键快照字段，或机构申请缺少 `institutionSplit`/其中三个已提交比例，必须视为协议错误并 **fail closed**：不得用当前目录、默认值或其他医生数据补齐，也不得显示批准/驳回动作。

列表中的申请快照不允许审核人修改。创建申请审核 body 使用 `decision` 和 `reviewNote`：`decision` 只允许 `APPROVED`、`REJECTED`，`REJECTED` 的去除首尾空白后的 `reviewNote` 必须非空；`APPROVED` 的备注可为空。创建申请的新状态仅为 `PENDING`、`APPROVED`、`REJECTED`。这不会改变 11.1 的 `JOIN` 决策，也不会移除 `PROFILE_UPDATE` 的既有 `CHANGES_REQUESTED`；数据库只为历史创建申请可读性保留 legacy `CHANGES_REQUESTED`，新 API 不会创建或提供该审核动作。

平台项目批准会在同一事务创建一条 `projects` 记录，并**显式初始化** `rating: 0`、`reviewCount: 0`。机构项目批准在同一事务中按固定顺序：创建 `institution_projects`（同样显式 `rating: 0`、`reviewCount: 0`）→ 仅创建申请医生的 `doctor_projects` 绑定及其有效继承显示内容 → 创建该申请医生的 `doctor_institution_project_configs` → 关闭申请并记录结果 ID。任一步失败会回滚项目、申请医生绑定、配置和申请状态，不会留下部分数据，也绝不影响同一项目的其他医生。

本接口族使用真实 HTTP 状态：400 表示精确 body/未知字段、金额/比例/数量/文本/数组非法、审核决定非法或拒绝备注空白；403 表示非认证医生提交、未批准机构关系、跨机构法人审核或非管理员审核平台申请；404 表示申请、目标机构或平台项目不存在；409 表示重复 `PENDING`、机构已公开该平台项目、并发/已处理审核、申请医生关系失效或当前平台比例导致分账无效。409 后刷新申请、项目目录与表单配置，保留草稿并提示重新提交；不得自动重试或自动重放任何提交/审核 POST。

#### V28 部署边界

`V28__expand_professional_project_requests.sql` 是**仅 DDL**迁移：不包含删除、清理或改写真实申请行。部署前运营人员必须手动清理旧的真实机构项目创建申请；该人工操作不由 Flutter、迁移或初始化器执行。直接管理员项目/机构项目创建表单、既有 JOIN 和 PROFILE_UPDATE 流程均不受此迁移改变。

### 11.3 其他专业接口权限规则


- 医生文章必须使用专业端 `/management/doctor-articles`，不得调用管理员文章接口。四个路由分别是列表、创建、完整更新和逻辑删除；没有文章详情 GET。每次请求均重新校验当前 `ACTIVE DOCTOR`，普通医生只能读写 `doctorId == self` 的文章。管理员端接口保持其既有契约，专业端新增路由不替代、不修改管理员系统行为。
- `DoctorArticleDraft` 请求必须且只能序列化 `title`、`summary`、`coverImage`、`publishDate`、`content` 五个非 null 字段；`publishDate` 为 `yyyy-MM-dd`。响应 `DoctorArticle` 精确包含 `id`、`title`、`authorName`、`summary`、`coverImage`、`publishDate`、`content`、`readCount`、`doctorId`、`createdAt`、`updatedAt`。后六类身份、计数和时间字段均只读。创建为 HTTP 201；其余成功为 200；参数错误 400、越权 403、不存在 404、并发/状态冲突 409。列表支持 `keyword`、`offset`、`limit`，编辑页需要详情时从本人列表按 id 定位。
- `DoctorOrder` 复用管理订单 JSON 字段：`id`、`orderNo`、`userId`、`projectId`、`institutionId`、`consultantId`、`doctorId`、`institutionProjectId`、`projectName`、`institutionName`、`consultantName`、`coverImage`、`amount`、`price`、`currency`、`paidAmount`、`couponId`、`userCouponId`、`discountAmount`、`status`、`quantity`、`remark`、`consultationFee`、`remainingAmount`、`transactionMethod`、`userPhone`、`appointmentTime`、`paymentTime`、`verifyCode`、`qrCode`、`evidenceUrl`、`hasReview`、`refundStatus`、`refundAmount`、`doctorName`、`createdAt`、`updatedAt`、`completedAt`、`canVerify`、`canRequestCompletion`。`price` 是 `amount` 的兼容别名；金额按十进制定点解析。管理响应的 `verifyCode` 恒为 `null`，UI 不能展示或缓存核销码。
- 订单列表接受 `status`、`offset`、`limit`，详情与列表返回同一 VO。普通医生只能访问 `doctorId == self`；法人和顾问没有订单权限。`canVerify` 仅在 `CONSULTATION_PAID` 为 true，`canRequestCompletion` 仅在 `BALANCE_PAID` 为 true，Flutter 只能按服务端标志显示动作，不得单凭本地状态推断。
- 两个订单动作请求体都必须且只能是 `{ "verificationCode": "123456" }`，核销码须为 6 位数字。服务端加锁后再次校验对象、状态和核销码：不存在 404、对象越界/身份失效 403、核销码格式或不匹配 400、状态不允许 409。`CONSULTATION_PAID -> VERIFIED` 与 `BALANCE_PAID -> PENDING_COMPLETION` 成功后清除核销码并各写一次状态日志；已处于对应目标状态且时间戳存在的直接重放返回当前对象，不重复写日志，后续其他状态仍为 409。

- 医生仅通过 `/management/doctor-profile` 读取和完整更新自己的单个医生档案；请求不发送 `id` 或 `userId`。PUT 必须带齐 `name`、`title`、`bio`、`avatar`、`contactPhone`、`specialties`、`credentials`、`credentialImages`、`certificationTags` 九个非 null String 字段。`name` 不得为空白，其余八项可用 `""` 清空；缺失或 `null` 为 400。
- `certificationTags` 保留为传输字段名，但 UI 必须称为“展示标签 / Display tags”并使用中性视觉，不能与平台 `isVerified` 徽章混同。服务端会在忽略大小写、空白和标点归一化后拒绝“平台认证”“官方认证”“安颜认证”“娇颜颂认证”“已认证”“platform verified”“official verified”及等价项目品牌认证声明；“主任医师”等普通医疗职称允许使用。
- 响应为单个对象而非数组。`id`、`userId`、机构摘要、评分、评价数、认证状态和统计计数是只读平台/关联字段；客户端不得在 PUT 中发送或本地伪造这些字段。
- 法人自助机构档案要求活跃 `INSTITUTION_LEGAL_REPRESENTATIVE` 角色；`/management/context` 的 `managedInstitutionIds` 仅包含 APPROVED 法人成员关系。活跃法人尚无已批准关系时，列表仍返回 200 空数组；详情与 PUT 必须指定集合中的对象，否则为 403。先用该集合决定详情/编辑入口，但服务端仍做最终对象校验。平台管理员不能借这组自助接口操作全量机构。
- 法人机构列表返回摘要数组；每项固定包含 `id`、`name`、`address`、`city`、`coverImage`、`rating`、`reviewCount`、`isVerified`、`projectCount`、`doctorCount`。`projectCount` 与 `doctorCount` 是服务端返回的整数只读计数，Flutter 不得本地写回或用列表长度替代。
- 法人 PUT 是严格完整替换：请求必须且只能发送 `name`、`address`、`city`、`description`、`coverImage`、`images`、`establishedYear`、`credentials`、`credentialImages`、`specialties`、`tags`、`contactPhone`、`businessHours` 这 13 个键。`establishedYear` 键必须存在，值可为 `null` 或 1800 至当前年的整数；`images`、`credentialImages`、`specialties`、`tags` 必须为 JSON 字符串数组，不能改用遗留的逗号分隔格式。
- 法人机构档案响应中的 `id`、`createdAt`、`updatedAt`、`rating`、`reviewCount`、`isVerified`、`certificationTime`、`projectCount`、`doctorCount`、`consultationCount`、`userCount`、`caseCount` 是只读字段。`credentialImages` 是机构自行上传的公开展示材料，UI 不得暗示这些图片已经由平台核验。
- 法人可审核本机构的成员关系和机构项目申请；不能编辑医生档案，也不能绕过项目申请/审核流程直接创建、修改或删除机构项目。旧专业端 `/admin/institutions/{id}` PUT 已移除；`POST/PUT/DELETE /api/admin/institutions...` 等机构写操作及平台全量 CRUD 始终仅限 `ADMIN`。上表列出的机构范围 GET 旧读路径仅在后续切换完成前向已认证专业用户兼容，并由服务端按 `visibleInstitutionIds` 做对象级只读过滤；`GET /admin/projects` 则只提供全局项目目录。所有兼容 GET 均不授予任何写权限。
- 医生和顾问机构关系统一遵循 11.1 的独立申请账本契约；两种身份都可提交 `JOIN`/`LEAVE` 并撤回本人 `PENDING`，法人从 `/reviewable` 审核本机构申请。旧顾问接口只承担滚动兼容，已处理申请不会被重提覆盖。
- Flutter 专业项目选择统一使用 `GET /management/projects`，不得继续调用 `/admin/projects`。该目录只检查用户是否拥有至少一个活跃 `DOCTOR`、`CONSULTANT` 或 `INSTITUTION_LEGAL_REPRESENTATIVE` 身份；同时具有 `ADMIN` 身份不会改变该授权结果。它返回全局只读数组，按 `name`、`id` 排序。每项完整固定包含 13 个字段：`id`、`name`、`category`、`description`、`tags`、`categoryTags`、`coverImage`、`referencePrice`、`currency`、`slogan`、`detailContent`、`images`、`salesCount`；不接受查询参数，也不授予任何项目写能力。
- `POST /admin/institution-project-requests` 的 `PROFILE_UPDATE` 是医生修改本人医生级项目资料、价格、面诊费和分账的唯一生效前申请入口。Flutter 请求 VO 必须精确包含 12 个键：`institutionProjectId`、固定值 `requestType: PROFILE_UPDATE`、`serviceDescription`、`priceSuggestion`、`notes`、`serviceTags`、`scheduleNote`、`coverImage`、`images`、`consultationFee`、`commissionRate`、`institutionRate`；其中 `serviceTags`、`images` 是 JSON 字符串数组，不能发送 `doctorId`、`platformRate`、`doctorRate` 或基线字段。
- `GET /admin/institution-project-requests/profile-update-targets` 是表单唯一的当前值来源，只返回已认证医生本人仍有效的医生—机构项目。响应是数组，每项 `DoctorProjectProfileUpdateTargetView` 精确包含：`institutionProjectId`、`projectName`、`institutionId`、`institutionName`、`currentPrice`、`serviceDescription`、`serviceTags`、`scheduleNote`、`coverImage`、`images`、`consultationFee`、`commissionRate`、`institutionRate`、`platformRate`、`doctorRate`。15 个字段全部非 null；无有效配置时返回面诊费 0、顾问率 0、策略默认机构率以及当前平台率和推导医生率。Flutter 不得用机构项目价或本地默认比例伪造基线。
- `serviceDescription` 非空且最长 5000；`notes`/`scheduleNote`/`coverImage` 最长分别为 2000/500/500；两个数组各最多 20 项，标签每项非空且最长 100，图片每项最长 500。`priceSuggestion`、`consultationFee` 为 `0..99999999.99` 的最多两位小数；`commissionRate`（兼容字段名，语义为顾问率）和 `institutionRate`（机构率）均为 `0..100` 的最多两位小数。
- `platformRate` 是服务端配置、不可编辑；`doctorRate = 100 - platformRate - institutionRate - commissionRate` 由服务端推导且不得小于 0。Flutter 只读展示响应中的 `platformRate` 与 `doctorRate`，文案必须明确区分顾问率、机构率、平台率和医生净比例。
- 提交成功只创建 `PENDING` 申请。机构法人通过 `POST /admin/institution-project-requests/{id}/review` 且显式发送 `force: false` 批准后，服务端才按 `(doctorId, institutionProjectId)` 在同一事务中更新本人 `doctor_projects` 与本人分账配置；基线变化返回 409，绝不波及同项目其他医生。平台管理员可显式发送 `force: true` 强制处理，但必须填写 `reviewNote`，响应以 `forceProcessed`、`reviewedBy`、`reviewedAt` 留痕。
- `DoctorProjectChangeView`/Flutter 响应 VO 字段固定为：`id`、`doctorId`、`doctorName`、`institutionId`、`institutionName`、`institutionProjectId`、`projectName`、`requestType`、`serviceDescription`、`priceSuggestion`、`notes`、`serviceTags`、`scheduleNote`、`coverImage`、`images`、`consultationFee`、`commissionRate`、`institutionRate`、`platformRate`、`doctorRate`、`currentPrice`、`currentServiceDescription`、`currentServiceTags`、`currentScheduleNote`、`currentCoverImage`、`currentImages`、`currentConsultationFee`、`currentCommissionRate`、`currentInstitutionRate`、`currentPlatformRate`、`currentDoctorRate`、`forceProcessed`、`status`、`submittedBy`、`reviewedBy`、`reviewerName`、`reviewNote`、`submittedAt`、`reviewedAt`、`updatedAt`。V17/V18 先增加可空提案、基线与 11 个 `current*` 列，V18 回填升级前的 `PROFILE_UPDATE`，V19 最后启用范围和判别约束。新申请的 `current*` 是提交事务固化的 before 快照；升级前历史申请无法还原提交时原值，因此 V18 按 `(doctorId, institutionProjectId)` 捕获**迁移执行时**的当前医生项目与未软删 config，明确属于近似快照。缺 config 使用面诊费 0、顾问率 0、机构率 40、平台率 10 和推导医生率；缺医生项目使用价格 0 与空资料。审核页使用已固化字段，不能在审批时重新读取当前表冒充原值。非 `PROFILE_UPDATE` 的这些字段仍为 null。
- 该接口族使用真实 HTTP 状态：400 参数错误、403 身份/对象/强制处理越权、409 重复 `PENDING`/基线变化/已处理或并发冲突、500 服务端异常。写请求不得自动重试；409 应刷新申请与当前项目配置后提示用户重新提交。
- 医生不能直接修改机构项目价格、销量、评分、评价数或上下架状态。
- 医生和机构法人都不能修改自己的评分、评价数和认证状态；服务端会保留原值。
- 医生档案的 `credentials` 与 `credentialImages` 是医生自主维护的公开展示材料，和私有身份审核材料相互独立；UI 只能使用“医生上传的证书图片/展示材料”等中性文案，不得写“资质保险箱”“查资质”或“平台已核验”。公开图片经 `POST /upload` 上传并使用 `data.url`，多图再以逗号拼接提交；这条遗留传输规则不适用于法人机构档案的 JSON 数组字段。
- 通用分账调整仍通过提案完成；医生项目 `PROFILE_UPDATE` 是受法人审核和基线保护的专用例外。两种流程都不能由专业用户直接写生效配置。
- 管理员可查看和管理全量数据，客户端不得把管理员专属页面暴露给专业用户。

`/api/admin/doctors` 及 `/api/admin/doctors/{id}` 保留为平台管理员兼容路由，医生专业中心不得再调用。管理端的 `/api/admin/**` 前缀是历史命名，不代表专业用户拥有管理员权限；除上表明确列出的迁移期对象级只读 GET 外，其他 `/admin/**` 需要平台管理员角色。

## 12. 数据表示兼容规则

- ID：多数业务 ID 是字符串 UUID；优惠券等少数字段是 64 位整数。Dart 使用 `String` 与 `int`，不要尝试统一转 int。
- 金额：JSON 中多数为十进制 number，个别优惠计算响应可能是字符串。Flutter 金额层须兼容 `num`/`String` 输入，并转换为十进制定点表示；禁止使用 double 直接累计结算金额。
- 时间：当前服务端主要返回无时区的 ISO 本地时间，如 `2026-08-05T15:30:00`，按 `Asia/Shanghai` 解释。不要直接追加 `Z`。
- 日期：生日使用 `yyyy-MM-dd`。
- 多图、标签、擅长领域：部分遗留字段为逗号分隔字符串，数据层统一适配为列表。
- HTML：文章和项目详情可能包含富文本；使用白名单渲染，禁用脚本、外部 iframe 和任意跳转。
- 空值：后端可能返回 `null`、空字符串或缺字段；DTO 对非关键展示字段提供安全默认值，但主键和状态缺失应视为协议错误。
- 枚举：未知枚举值必须降级为“未知状态”，不能导致应用崩溃；同时上报日志。

## 13. 当前未开放或依赖部署配置的能力

| 能力 | 状态 | Flutter 处理 |
|---|---|---|
| 微信/支付宝等真实支付与回调 | 未落地 | 保留支付抽象，不宣称真实到账 |
| 邮箱验证码、邮箱绑定 | 未开放 | 隐藏提交入口 |
| AI 流式输出 | Flutter 固定关闭 | 仅使用同步 REST，不做 SSE 回退或重放 |
| 短信发送 | 依赖短信供应商配置 | 开发环境可能只记录验证码 |
| 远程对象存储/CDN | 依赖部署配置 | 使用服务端返回 URL，不假定域名 |
| iOS 推送/APNs | 尚无稳定契约 | 不纳入首期接口验收 |
| 完整、可生成客户端的 OpenAPI | 尚未生成 | 以本文档和实际 DTO 为准 |

## 14. Flutter 网络层最低验收清单

- [ ] baseUrl 按 Android 模拟器、iOS 模拟器、真机和生产环境分离。
- [ ] 所有 JSON 接口统一解析 `ApiEnvelope<T>`，同时检查 HTTP 状态和 envelope code。
- [ ] Bearer access token 自动注入；401 single-flight 刷新且每个请求最多重试一次。
- [ ] refresh token 轮换后原子保存；403 不清登录态。
- [ ] refresh token 使用安全存储；日志、崩溃上报和调试面板不输出令牌、身份证号或认证文件。
- [ ] 写操作默认不自动重试；下单、支付、核销、身份申请和分账操作有防重复提交。
- [ ] multipart 公共图片和私有身份材料使用两个独立客户端方法。
- [ ] offset/limit 与 before 游标封装成两种分页器，按 ID 去重；Agent 聊天仅保留最新 20 条且不翻页。
- [ ] Agent 仅使用同步 REST，SSE 固定关闭，失败时不自动重放 POST。
- [ ] 金额使用十进制定点模型；时间按 Asia/Shanghai 兼容解析。
- [ ] 多身份 UI 始终以 `/identity/overview` 和 `/management/context` 的服务端结果为准。
- [ ] 不导入、不生成、不引用旧 `openapi.yaml/json`。
