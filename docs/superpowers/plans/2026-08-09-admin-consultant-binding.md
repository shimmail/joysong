# 后台咨询师绑定 Implementation Plan

**Goal:** 管理员从现有普通用户中选择用户和机构，一次性授予 `CONSULTANT` 身份并创建或恢复已通过的机构咨询师绑定。

**Architecture:** 复用 `user_roles` 与 `institution_memberships`。服务端由 `AdminIdentityService` 提供一个 `@Transactional` 原子操作，控制器暴露管理员接口；管理端在机构成员卡片增加独立弹窗。原待审核成员绑定流程保持不变。

## Global Constraints

- 不创建账号、不修改密码、不增加表或迁移。
- 用户必须存在、未注销且 `users.role = 'USER'`。
- 保留用户其他职业身份，只新增或恢复 `CONSULTANT`。
- 同一事务内将职业身份设为 `ACTIVE`、机构关系设为 `APPROVED`。
- 顺序及并发重复请求均须幂等。
- 仅 `/api/admin/**` 的管理员可调用。

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
