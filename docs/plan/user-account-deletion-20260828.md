# 用户注销功能重构实施计划（开发环境）

> 实施位置：分支 `codex/account-deletion`，worktree `D:\code\kotlin\joysong\.worktrees\account-deletion`。本轮以开发环境快速验收为目标，不迁移、不修复、不依赖原有数据库内容。

## 范围与硬约束

- 交付不可逆注销闭环：预检、短信或 Google 二次验证、最终阻断复检、原子匿名化与内容清理、会话失效、提交后媒体删除、Flutter 本地清理和崩溃续作。
- 账号状态仅使用 `ACTIVE / ADMIN_SUSPENDED / ERASED`；`ERASED` 永不恢复，同一手机号或 Google 邮箱只能注册为全新 userId。
- 沿用当前 `B33 / V34 / V35` Flyway 链，只新增 `V36__add_account_lifecycle_foundation.sql`。`V1__mvp_schema.sql` 方案已弃用，不修改既有迁移。
- 不迁移或回填旧数据。`users.deleted_at` 完全忽略，仅保留兼容映射；账号生命周期新逻辑不读、不写、不判断它，也不保留用户实体上的 `@SQLDelete`、`@Where` 或自动复活行为。
- 订单、支付、退款、钱包、结算、评价和私信的代码、表、测试与状态机均零改动；交易阻断能力只通过 `AccountDeletionBlockerPort` 边界接入，非交易阻断在账户域内评估。
- 配置固定为 `app.account-deletion.enabled=false`、`allow-commerce-bypass=false`。开发环境可显式开启；生产 profile 无论环境变量如何配置都必须硬关闭并返回 503。
- 开发绕过仅用于确认没有交易数据的测试账号。绕过时不读取交易表；测试人员负责账号前置条件，禁止将绕过配置用于生产或真实账号。
- 日志和指标只使用 userId、requestId 与稳定 errorCode；不得记录短信码、Google Token、删除授权、Bearer Token、手机号、邮箱或用户内容。

## 接口契约

| 接口 | 契约 |
|---|---|
| `POST /api/user/account-deletion/preflight` | 返回 `requestId`、`eligible`、`stepUpMethod`、脱敏凭证、`policyVersion` 和不含敏感 ID 的阻断项 |
| `POST /api/user/account-deletion/send-sms-code` | Body 包含 `requestId`；验证码 300 秒有效、60 秒后可重发、最多尝试 5 次 |
| `POST /api/user/account-deletion/step-up` | 提交 `requestId` 及 SMS 验证码或 Google ID Token；成功返回 600 秒有效的一次性 `deletionAuthorization` |
| `POST /api/user/account-deletion/confirm` | Headers 包含 `Idempotency-Key`、`X-Account-Deletion-Authorization`；Body 为 `requestId`、`policyVersion`、`confirmation: "DELETE"` |
| 旧 DELETE 接口 | `/api/auth/account` 与 `/api/user/account` 均返回 `410 ACCOUNT_DELETION_LEGACY_ENDPOINT_RETIRED`，且无副作用 |

- 预检发现普通业务阻断时仍返回 200，并以 `eligible=false` 和 `blockers` 表达。其他错误使用真实 HTTP 状态：验证失败 400、未认证 401、最终复检阻断或幂等冲突 409、授权过期或已消费 410、短信限流 429、功能关闭或阻断服务不可用 503。
- 同一 requestId、幂等键和授权的重试返回原终态；参数不完全匹配则拒绝。
- 首次确认必须携带有效 Bearer。账号注销导致 Bearer 失效后，仅允许携带 pending 中完全匹配的 requestId、幂等键、授权和确认参数，通过同一 `POST /confirm` 只读重取终态；不得再次执行写操作。

## 后端实施

### 1. 生命周期与持久化

- V36 为 `users` 增加 `account_state`、`erased_at` 及不可反查的手机号/邮箱 HMAC 摘要，并创建 `account_deletion_requests`、`user_media_assets`。
- 请求表只保存验证码、授权、幂等键的哈希及状态、次数、有效期、策略版本和终态；媒体表记录新启用数据库后的用户文件归属、存储键、删除状态和重试信息。
- 认证、刷新和 JWT 过滤仅接受 `ACTIVE`。管理员只允许 `ACTIVE ↔ ADMIN_SUSPENDED`；`ERASED` 只读且不可恢复。
- `AccountLifecycleGuard.requireActiveForWrite(userId)` 与注销协调器使用同一用户行锁，保护本轮涉及的资料、内容、社交和 AI 写入口，不扩散到交易域。

### 2. 预检与二次验证

- `AccountDeletionBlockerPort.evaluate(userId)` 默认返回 `Unavailable`。只有非生产环境且两个开关均显式开启时才允许开发绕过。
- 有邮箱的 Google 账号使用 Google 重认证，校验 issuer、audience、`email_verified` 及邮箱与当前账号完全一致；其余账号使用绑定手机号短信验证。
- 同一用户、同一验证方式下复用尚未终结且未过期的预检请求，避免通过重复预检绕过短信重发和尝试限制。短信摘要同时绑定当前手机号，换绑后旧验证码自动失效。
- `dev` profile 可显式配置固定验收短信码；该能力在生产或其他 profile 不可启用，固定码不返回、不记录日志，数据库仍只保存摘要。
- 授权、验证码和幂等键使用高熵随机值与带密钥哈希；原文只返回客户端一次，不落库、不入日志。

### 3. 原子注销与媒体清理

- `AccountDeletionCoordinator` 是唯一注销事务入口。最终确认时锁定用户、复检阻断项、校验并消费一次性授权、撤销全部刷新令牌。
- 同一事务内撤销日记分享、下架并清空日记正文，删除非交易评论互动、收藏、关注、通知、私密社交数据、AI 会话及未提交身份或职业草稿。
- 已提交认证、处理中申请或有效职业/机构关系必须阻断并返回操作入口。订单及其评价、私信完全不读写。
- 清空 PII、密码、头像和资料，释放登录凭证，写 HMAC 摘要、`erased_at` 和 `ERASED`。任何数据库步骤失败则整体回滚。
- 媒体只在事务提交后由持久化任务删除；文件不存在视为成功，失败按退避策略重试并在阈值处告警。定时扫描 `PENDING/RETRY`，避免提交成功但唤醒任务失败造成遗漏。

## 客户端与管理端

- Flutter 页面状态机：预检 → 阻断展示 → SMS/Google 重认证 → 输入 `DELETE` → 幂等确认 → 本地清理。中英文只映射稳定 errorCode，不展示服务端原始异常。
- 确认前在安全存储写 pending，至少包含 requestId、policyVersion、幂等键及继续查询终态所需的删除授权。仅在服务端终态成功且本地清理全部完成后删除 pending。
- 应用重启时先续作 pending，再恢复普通登录：成功则继续本地清理；明确阻断或授权过期则恢复原账号；网络或未知错误保持未登录并等待同一请求重试。
- `AuthController.completeAccountDeletion(userId)` 清除当前令牌、该用户 saved-account、登录偏好和密码、账号级消息偏好、Google 本地会话及账号缓存；保留其他已保存账号、语言和主题。
- 管理端展示三种状态；仅 `ADMIN_SUSPENDED` 提供恢复，`ERASED` 不显示恢复或永久删除动作。

## 文档、测试与验收

- OpenAPI YAML/JSON 与实现同步；`design/ACCOUNT_DELETION_WORKFLOW.puml` 随开发变化更新。流程图图片由用户手动生成，每次流程调整后需提醒用户重新生成图片。
- 最小相关测试优先；修复失败时只运行失败类。相关测试通过后，每个技术栈最多执行一次全量测试，超过 10 分钟停止并报告进度及最慢测试；不重复运行已通过命令。
- 数据库测试不得连接共享开发库。由 worktree 目录生成 `WORKTREE_ID=worktree_account_deletion`，数据库使用 `myapp_worktree_account_deletion`，Docker Compose project 使用 `myapp-worktree-account-deletion`；SQLite 文件只放 `.runtime/`。
- 迁移前打印解析后的 host 与 database。迁移只在新建空隔离库验证；任何 drop/reset 的库名必须以 `myapp_worktree_` 开头。
- Flutter SDK 缺失属于环境阻断，保留证据并报告，不安装依赖、不伪造结果。测试结束清理临时文件。
- 最终以 `git diff` 验证交易相关模块零改动，并验收两种二次验证路径、PII 与可删除内容消失、旧令牌失效、媒体任务完成、本地数据清理，以及同凭证重新注册获得不同 userId。

## 发布 gates

- 当前仅为开发环境候选版本，不可宣称生产或商店就绪。
- 上线前必须完成真实交易阻断适配器、法律留存期限和隔离清理机制、安全评审、隐私文案与审计告警。
- Apple 要求应用内提供账号删除且不能以停用代替；依法需留存的数据必须隔离处理：[Apple Account Deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/)、[个人信息保护法第四十七条](https://www.npc.gov.cn/WZWSREL25wYy9jMi9jMzA4MzQvMjAyMTA4L3QyMDIxMDgyMF8zMTMwODguaHRtbD9yZWY9aW1i)。
- 本轮不建设站外注销页；Google Play 仍要求应用内入口及外部网页请求入口：[Google Play Account Deletion](https://support.google.com/googleplay/android-developer/answer/13327111?hl=en)。
