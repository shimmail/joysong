# 后台咨询师绑定 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 管理员从现有普通用户中选择用户和机构，一次性授予 `CONSULTANT` 身份并创建或恢复已通过的机构咨询师绑定。

**Architecture:** 复用 `user_roles` 与 `institution_memberships`。服务端由 `AdminIdentityService` 提供一个 `@Transactional` 原子操作，控制器暴露管理员接口；管理端在机构成员卡片增加独立弹窗。Testcontainers 使用真实 MySQL 8.0.39，并通过仅供新环境使用的 `B1` baseline 与后续生产迁移验证并发事务及物理回滚；原待审核成员绑定流程保持不变。

**Tech Stack:** Kotlin 1.9.22、Spring Boot 3.2.2、Spring JDBC/Transactions、Flyway、JUnit 5、Testcontainers MySQL、React/TypeScript。

## Global Constraints

- 不创建账号、不修改密码、不增加表或迁移。
- 用户必须存在、未注销且 `users.role = 'USER'`。
- 保留用户其他职业身份，只新增或恢复 `CONSULTANT`。
- 同一事务内将职业身份设为 `ACTIVE`、机构关系设为 `APPROVED`。
- 顺序及并发重复请求均须幂等。
- 仅 `/api/admin/**` 的管理员可调用。
- MySQL 集成测试必须执行生产 `B1` baseline migration 与后续 Flyway 迁移，不得复制测试专用表结构。
- 已在共享测试执行的 `V1__init_schema.sql` 不得修改；新空库使用 `B1`，已有 V1 历史的数据库必须保持 checksum 兼容。
- 并发测试必须证明两个独立事务和两个不同 MySQL 连接在开始屏障释放前均已建立。

---

## Task 1: 原子绑定后端契约

**Files:**
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/identity/service/AdminIdentityService.kt`
- Modify: `joysong-server/src/main/kotlin/com/joysong/server/admin/controller/AdminIdentityController.kt`
- Test: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceTest.kt`

**Interfaces:**
- `AdminIdentityService.bindConsultant(userId: String, institutionId: String, confirmerId: String): ConsultantBindingAdminView`
- `POST /api/admin/identity/consultants`，请求 `{ "userId": "...", "institutionId": "..." }`

### TDD 与行为要求

先写失败测试并确认因缺少方法/行为而失败，然后实现最小代码。覆盖：

1. 激活或恢复 `CONSULTANT` 身份并批准机构关系。
2. 保留医生、法人等其他身份。
3. 恢复 `REVOKED` 关系并清空撤销字段。
4. 已存在 `APPROVED` 关系时重复提交返回同一关系，不重复写入。
5. 两个并发首次绑定请求均成功并返回同一最终关系，不泄漏唯一键冲突。
6. 用户不存在、已注销、非普通用户以及机构不存在时在写入前拒绝。
7. 身份 upsert 成功但关系写入失败时整个事务回滚。

### 原子 upsert 设计

1. trim 并校验三个 ID 非空。
2. 查询用户（包含 `deleted_at`）并要求 `role = 'USER'`；查询未删除机构。
3. 对 `user_roles(user_id, role_code)` 执行 `INSERT ... ON DUPLICATE KEY UPDATE`，将 `CONSULTANT` 设为 `ACTIVE`，更新激活时间并清空 `revoked_at`、`revoked_by`、`revoke_reason`，不修改其他角色。
4. 为关系预生成 UUID，并对 `institution_memberships(user_id, institution_id, member_role)` 执行单条 `INSERT ... ON DUPLICATE KEY UPDATE`：新记录直接为 `APPROVED`；已有记录恢复为 `APPROVED`，写入 `confirmed_by`、`confirmed_at = NOW()` 并清空 `revoked_at`。不得使用“先 SELECT 不存在再 INSERT”的竞态流程。
5. upsert 后按唯一键查询最终关系并返回；并发情况下如果数据库报告可重试的死锁，则由测试验证当前 Spring/MySQL 行为，必要时增加有界重试，但不得吞掉其他数据库错误。
6. 返回 `ConsultantBindingAdminView(userId, userName, institutionId, institutionName, membershipId, roleCode = "CONSULTANT", status = "APPROVED")`。

控制器新增 `BindConsultantRequest` 并通过现有 `authentication.adminId()` 传入确认人。

### 验证

运行：

```powershell
$gradleHome = Join-Path $PWD '..\.tmp\gradle-user-home-codex'
$env:GRADLE_USER_HOME = $gradleHome
.\gradlew.bat test --tests com.joysong.server.identity.service.AdminIdentityServiceTest
```

## Task 2: 管理端添加咨询师流程

**File:** `joysong-admin/src/pages/IdentityManagementPage.tsx`

在 `MembershipSection` 中：

1. 增加普通用户候选状态、弹窗状态、提交状态及 `{ userId, institutionId }` 表单。
2. 打开弹窗时加载 `/admin/users`，仅保留 `role === 'USER' && !deletedAt`。
3. 用户选项展示昵称、手机号和 ID，支持搜索。
4. 提交 `/admin/identity/consultants`，成功后关闭弹窗并调用现有 `fetchData()`。
5. 保留“新增任职关系”，旁边增加“添加咨询师”主按钮。
6. 使用现有错误提示模式、`confirmLoading` 与 `destroyOnHidden`。

先增加能导致 TypeScript 合同检查失败的调用/测试点并确认失败，再完成 UI。运行：

```powershell
npm run build
npm run lint
```

## Task 3: 完整验证与审查

1. 服务端完整测试：`.\joysong-server\gradlew.bat -p joysong-server test`
2. 管理端：在 `joysong-admin` 运行 `npm run build`、`npm run lint`
3. 运行 `git diff --check` 和 `git status --short`
4. 独立审查计划符合性、权限边界、事务原子性、并发幂等、错误处理与测试质量。
5. 修复所有 Critical/Important 问题后重新验证。

## Task 4: 真实 MySQL Flyway 与并发事务验证

**Files:**
- Modify: `joysong-server/build.gradle.kts`
- Modify: `joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceMySqlIntegrationTest.kt`
- Create: `joysong-server/src/main/resources/db/migration/B1__init_schema.sql`
- Delete: `joysong-server/src/test/resources/mysql/admin-identity-binding-schema.sql`

**Interfaces:**
- Consumes: `AdminIdentityService.bindConsultant(userId, institutionId, confirmerId)` 及其默认 `@Transactional(REQUIRED)` 事务语义。
- Produces: Gradle `mysqlIntegrationTest` 任务；真实 MySQL 8.0.39 上的 `B1` + V2..V9 Flyway、并发首次绑定、物理回滚和审计幂等验证。

- [ ] **Step 1: 先用断言暴露旧测试结构的缺口**

先保留旧的 `spring.flyway.enabled=false`、`@Sql` 和 worker 结构，仅新增读取 `flyway_schema_history` 的 V1..V9 断言；并在现有 worker 调用服务前检查 `TransactionSynchronizationManager.isActualTransactionActive()`。这两项断言应分别暴露“未执行生产迁移”和“屏障释放时事务尚未开启”。

- [ ] **Step 2: 运行 MySQL 集成测试确认 RED**

Run:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.tmp\gradle-user-home-codex"
.\joysong-server\gradlew.bat -p joysong-server mysqlIntegrationTest --no-daemon --console=plain --rerun-tasks
```

Expected: 第一轮旧测试结构因 `flyway_schema_history` 不存在而失败，并发断言因当前 worker 尚无活动事务而失败；启用 Flyway 后的第二轮 RED 因原始 V1 在空库删除不存在的 `institutions.district` 而失败。两轮失败都必须记录。

- [ ] **Step 3: 完成最小 GREEN 实现**

删除 `@Sql("/mysql/admin-identity-binding-schema.sql")` 和测试 schema；设置 `spring.flyway.enabled=true`、`spring.flyway.locations=classpath:db/migration`、`spring.flyway.baseline-on-migrate=false`、`spring.flyway.validate-on-migrate=true`。新增 `B1__init_schema.sql`，以当前 V1 为基线内容，仅在 `institutions` 初始定义中补入随后会被原脚本删除的 `district VARCHAR(100) DEFAULT ''`；原 V1 必须保持不变。断言版本 1 的历史类型为 `SQL_BASELINE` 且随后 V2..V9 均成功。注入 `PlatformTransactionManager`，每个 worker 使用 `TransactionTemplate` 与 `PROPAGATION_REQUIRES_NEW`，在事务回调内等待开始屏障后调用 Spring 代理 `service.bindConsultant(...)`。保留类级 `Propagation.NOT_SUPPORTED`，不要修改生产服务传播级别。

- [ ] **Step 4: 运行聚焦测试确认 GREEN**

Run:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.tmp\gradle-user-home-codex"
.\joysong-server\gradlew.bat -p joysong-server mysqlIntegrationTest --no-daemon --console=plain --rerun-tasks
```

Expected: Flyway `B1` + V2..V9、两个独立连接的并发绑定、外键失败物理回滚及重复 APPROVED 审计测试全部通过。

- [ ] **Step 5: 完整回归验证**

Run:

```powershell
$env:GRADLE_USER_HOME = "$PWD\.tmp\gradle-user-home-codex"
.\joysong-server\gradlew.bat -p joysong-server test mysqlIntegrationTest --no-daemon --console=plain --rerun-tasks
Set-Location joysong-admin
npm run build
npm run lint
```

Expected: 普通测试、MySQL 集成测试、管理端构建和 lint 均退出 0；仅允许记录已存在的非阻断警告。

- [ ] **Step 6: 提交**

```powershell
git add joysong-server/build.gradle.kts joysong-server/src/main/resources/db/migration/B1__init_schema.sql joysong-server/src/test/kotlin/com/joysong/server/identity/service/AdminIdentityServiceMySqlIntegrationTest.kt docs/superpowers/specs/2026-08-09-admin-consultant-binding-design.md docs/superpowers/plans/2026-08-09-admin-consultant-binding.md
git commit -m "test: verify consultant binding on MySQL"
```
