# 娇颜颂 Flutter API 接入契约

> 状态：服务端当前实现基线（2026-08-05）  
> 适用：Flutter Android/iOS 用户端，以及医生、机构法人的专业管理入口  
> API 根路径：`{baseUrl}/api`

## 1. 契约来源与接入原则

本文档以 `joysong-server/src/main/kotlin` 的实际控制器和 DTO 为准，是 Flutter 迁移阶段的接口基线。

`joysong-server/openapi.yaml` 和 `openapi.json` 只是旧用户端的 **Legacy/已废弃快照**，缺少 refresh token、身份认证、专业管理、Agent 等接口。**禁止使用这两份文件生成 Flutter 客户端或作为验收依据。** 后续只有在从运行中的服务端自动生成并纳入 CI 校验后，OpenAPI 才能恢复为契约来源。

Flutter 不连接数据库，也不调用管理后台网页。用户态和专业身份态都直接调用服务端 REST API。

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
2. 医生或机构法人调用 `POST /management/orders/{id}/verify`，请求体 `{ "verificationCode": "123456" }`，推进到 `VERIFIED`。
3. 用户支付尾款后进入 `BALANCE_PAID` 并获得第二次核销码。
4. 医生或机构法人调用 `POST /management/orders/{id}/request-completion`，推进到 `PENDING_COMPLETION`。
5. 用户调用 `/confirm-completion` 最终确认。

旧 `POST /orders/{id}/verify` 仅为旧 Android 兼容，当前语义也只是生成核销码。Flutter 禁止使用该旧路径。用户端绝不能调用专业端核销接口，也不能自行确认自己的核销码。

### 10.3 支付边界

支付方式、第三方下单、验签、异步回调、幂等通知和退款通道尚未落地。现有 `pay-consultation`、`pay-balance` 与兼容 `/pay` 仅用于内部流程占位；`/pay` 的 `method` 当前不会真正选择渠道。

Flutter 可以完成页面和接口抽象，但生产发布前必须等待支付服务契约确定，并至少补齐：支付预下单、渠道参数、支付查询、服务端回调验签、回调幂等、退款查询。客户端不能把“接口返回成功”等同于渠道到账。

## 11. 医生与机构专业管理接口

专业入口使用受限接口，服务端按 `ManagementContext` 做对象级过滤。医生本人档案是单资源，不经过管理员医生列表或带 ID 的管理路由。

| 能力 | 接口 |
|---|---|
| 医生本人档案 | `GET /management/doctor-profile`、`PUT /management/doctor-profile` |
| 法人自助机构档案 | `GET /management/institutions`、`GET /management/institutions/{institutionId}`、`PUT /management/institutions/{institutionId}` |
| 法人兼容只读机构目录（迁移期） | `GET /admin/institutions`、`GET /admin/institutions/{id}`、`GET /admin/institutions/{id}/doctors`、`GET /admin/institutions/{id}/projects`、`GET /admin/institution-projects`、`GET /admin/projects` |
| 平台管理员机构 CRUD | `GET/POST /admin/institutions`、`GET/PUT/DELETE /admin/institutions/{id}` |
| 文章 | `GET/POST /admin/articles`、`PUT/DELETE /admin/articles/{id}` |
| 机构项目 | `GET/POST /admin/institution-projects`、`PUT/DELETE /admin/institution-projects/{id}` |
| 医生项目协作 | `GET/POST /admin/institution-project-requests`、`POST /{id}/review`、`/{id}/withdraw` |
| 当前分账 | `GET /admin/doctor-institution-project-configs` |
| 分账提案 | `GET/POST /admin/doctor-institution-project-config-proposals`、`POST /{id}/confirm`、`/reject`、`/withdraw` |
| 相关订单 | `GET /management/orders?status=...`、`GET /management/orders/{id}`、核销接口见上节 |

权限规则：

- 医生仅通过 `/management/doctor-profile` 读取和完整更新自己的单个医生档案；请求不发送 `id` 或 `userId`。PUT 必须带齐 `name`、`title`、`bio`、`avatar`、`contactPhone`、`specialties`、`credentials`、`credentialImages`、`certificationTags` 九个非 null String 字段。`name` 不得为空白，其余八项可用 `""` 清空；缺失或 `null` 为 400。
- `certificationTags` 保留为传输字段名，但 UI 必须称为“展示标签 / Display tags”并使用中性视觉，不能与平台 `isVerified` 徽章混同。服务端会在忽略大小写、空白和标点归一化后拒绝“平台认证”“官方认证”“安颜认证”“娇颜颂认证”“已认证”“platform verified”“official verified”及等价项目品牌认证声明；“主任医师”等普通医疗职称允许使用。
- 响应为单个对象而非数组。`id`、`userId`、机构摘要、评分、评价数、认证状态和统计计数是只读平台/关联字段；客户端不得在 PUT 中发送或本地伪造这些字段。
- 法人自助机构档案仅适用于同时拥有活跃 `INSTITUTION_LEGAL_REPRESENTATIVE` 角色和 APPROVED 法人成员关系的账户。先用 `/management/context` 的 `managedInstitutionIds` 决定入口；每次 GET/PUT 仍由服务端校验目标对象，不在该集合中为 403。平台管理员不能借这组自助接口操作全量机构。
- 法人 PUT 是严格完整替换：请求必须且只能发送 `name`、`address`、`city`、`description`、`coverImage`、`images`、`establishedYear`、`credentials`、`credentialImages`、`specialties`、`tags`、`contactPhone`、`businessHours` 这 13 个键。`establishedYear` 键必须存在，值可为 `null` 或 1800 至当前年的整数；`images`、`credentialImages`、`specialties`、`tags` 必须为 JSON 字符串数组，不能改用遗留的逗号分隔格式。
- 法人机构档案响应中的 `id`、`createdAt`、`updatedAt`、`rating`、`reviewCount`、`isVerified`、`certificationTime`、`projectCount`、`doctorCount`、`consultationCount`、`userCount`、`caseCount` 是只读字段。`credentialImages` 是机构自行上传的公开展示材料，UI 不得暗示这些图片已经由平台核验。
- 法人可审核本机构的成员关系和机构项目申请；不能编辑医生档案，也不能绕过项目申请/审核流程直接创建、修改或删除机构项目。旧专业端 `/admin/institutions/{id}` PUT 已移除；上表列出的旧读路径仅在后续切换完成前兼容。`/api/admin/institutions/**` 的 CRUD 始终是平台管理员权限。
- 医生不能直接修改机构项目价格、销量、评分、评价数或上下架状态。
- 医生和机构法人都不能修改自己的评分、评价数和认证状态；服务端会保留原值。
- 医生档案的 `credentials` 与 `credentialImages` 是医生自主维护的公开展示材料，和私有身份审核材料相互独立；UI 只能使用“医生上传的证书图片/展示材料”等中性文案，不得写“资质保险箱”“查资质”或“平台已核验”。公开图片经 `POST /upload` 上传并使用 `data.url`，多图再以逗号拼接提交；这条遗留传输规则不适用于法人机构档案的 JSON 数组字段。
- 分账调整通过提案完成，医生方与机构方都确认后才替换当前配置；专业用户不能直接写生效配置。
- 管理员可查看和管理全量数据，客户端不得把管理员专属页面暴露给专业用户。

`/api/admin/doctors` 及 `/api/admin/doctors/{id}` 保留为平台管理员兼容路由，医生专业中心不得再调用。管理端的 `/api/admin/**` 前缀是历史命名，不代表专业用户拥有管理员权限；其他 `/admin/**` 需要平台管理员角色。

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
