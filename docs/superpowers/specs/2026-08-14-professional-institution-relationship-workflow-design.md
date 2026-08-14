# 医生与顾问机构关系申请闭环设计

日期：2026-08-14

## 1. 目标

在 Flutter 专业管理中心提供完整、可审计的机构关系业务闭环：

- 已认证医生可以选择机构申请加入，也可以对当前有效机构申请退出。
- 已认证顾问可以选择机构申请加入，也可以对当前有效机构申请退出。
- 医生和顾问都可以撤回本人尚未处理的申请。
- 机构法人只能审核本人管理机构的申请，并且只能批准或驳回。
- 驳回必须填写原因。
- 退出申请审核期间，现有机构关系继续有效；只有批准退出后才解除关系。
- 申请历史与当前关系分离保存，后续重提不能覆盖旧记录。

## 2. 已确认的产品规则

1. 申请动作只有 `JOIN` 和 `LEAVE`。
2. 申请状态只有 `PENDING`、`APPROVED`、`REJECTED` 和 `WITHDRAWN`。
3. `JOIN` 仅允许当前没有有效关系的医生或顾问提交。
4. `LEAVE` 仅允许当前存在有效关系的医生或顾问提交。
5. 同一专业身份、同一机构在任意时刻最多存在一条 `PENDING` 申请。
6. 只有申请人本人可以撤回本人提交的 `PENDING` 申请。
7. 非平台管理员审核人必须是目标机构已确认的法人。
8. 审核决定只有 `APPROVED` 和 `REJECTED`；驳回说明不能为空。
9. 机构法人不能邀请医生或顾问，本流程始终由专业人员本人发起。
10. 平台管理员保留兼容性的全局审核能力，但 Flutter 机构审核入口只面向机构法人。

## 3. 当前问题

### 3.1 医生链路

医生已经使用 `doctor_institution_change_requests` 保存 `JOIN/LEAVE` 申请，并使用
`doctor_institutions` 保存当前关系。后端状态机基本完整，但 Flutter 同时存在简化申请页和完整关系页，
法人也存在两套审核页面。简化审核模型会丢弃医生申请的 `action`，可能把 `LEAVE` 错误显示为普通加入申请。

### 3.2 顾问链路

顾问当前直接使用 `institution_memberships` 的同一行同时表示申请和关系：

- `PENDING` 表示加入申请；
- `APPROVED` 表示有效绑定；
- `REJECTED` 表示驳回；
- `REVOKED` 表示历史关系。

这个模型不能同时表达“关系仍为 `APPROVED`”和“退出申请为 `PENDING`”。如果提交退出时把关系行改为
`PENDING`，权限系统会在审核前立即移除顾问的机构归属；如果继续保持 `APPROVED`，单个 `status` 又无法记录
待审申请。当前重提还会覆盖同一行的旧审核信息，无法保存完整历史。

### 3.3 列表范围

当前统一 GET 接口返回“本人申请或本人管理机构的申请”。双身份用户会在法人审核页看到自己提交给其他机构、
但无权审核的记录，最终只能依赖服务端 403 阻止。申请人列表和机构审核列表必须从接口层明确分离。

### 3.4 候选机构

当前 Flutter 直接使用公开发现机构列表。该列表不保证机构已认证，也不会排除已加入机构或已有待审申请，
导致用户可以选择服务端最终无法批准的目标。

## 4. 方案选择

采用“申请账本 + 当前关系投影”方案：

- 医生继续使用现有 `doctor_institution_change_requests` 和 `doctor_institutions`。
- 顾问新增 `consultant_institution_change_requests`，申请不再直接改变当前关系。
- 顾问当前关系仍由 `institution_memberships` 中 `member_role='CONSULTANT'` 的行表示。
- 法人审核端使用统一响应模型，但医生和顾问的持久化服务保持独立。

不采用以下方案：

- 不迁移医生数据到新的多态共用表，避免破坏已运行的医生历史和外键完整性。
- 不在 `institution_memberships` 增加 `pending_action/pending_status`，避免一行承载两套正交状态机并继续覆盖历史。
- 不取消 `(user_id, institution_id, member_role)` 唯一约束，避免产生重复有效关系。

## 5. 数据模型

### 5.1 新表 `consultant_institution_change_requests`

字段：

| 字段 | 含义 |
| --- | --- |
| `id` | 申请 ID |
| `consultant_id` | 申请顾问用户 ID |
| `institution_id` | 目标机构 ID |
| `action` | `JOIN` 或 `LEAVE` |
| `status` | `PENDING`、`APPROVED`、`REJECTED`、`WITHDRAWN` |
| `request_note` | 申请说明，最多 1000 字符 |
| `review_note` | 审核说明；驳回时必填 |
| `submitted_by` | 提交人，必须等于顾问本人 |
| `reviewed_by` | 审核人，可空 |
| `submitted_at` | 提交时间 |
| `reviewed_at` | 审核时间，可空 |
| `created_at` | 创建时间 |
| `updated_at` | 更新时间 |
| `pending_key` | `PENDING` 时生成的顾问与机构组合键 |

约束：

- `UNIQUE(pending_key)` 保证同一顾问与机构最多一条待审申请。
- `action IN ('JOIN', 'LEAVE')`。
- `status IN ('PENDING', 'APPROVED', 'REJECTED', 'WITHDRAWN')`。
- `status='REJECTED'` 时 `review_note` 不能为空。
- 顾问、机构、提交人和审核人均建立外键。

### 5.2 当前关系

顾问的有效关系仍满足：

```text
institution_memberships.user_id = consultant_id
institution_memberships.institution_id = institution_id
institution_memberships.member_role = 'CONSULTANT'
institution_memberships.status = 'APPROVED'
institution_memberships.revoked_at IS NULL
```

现有 `ManagementAccessService`、机构顾问列表、订单顾问归属校验继续以该关系为准。新申请表不直接授予任何权限。

## 6. V27 增量迁移

新增 `V27__add_consultant_institution_change_requests.sql`，不修改已经发布的 V13、V14 或 V26。

迁移步骤：

1. 创建新申请表、外键、检查约束和待审唯一键。
2. 仅回填 `institution_memberships.member_role='CONSULTANT'` 的记录。
3. 旧 `PENDING` 映射为 `JOIN/PENDING`。
4. 旧 `REJECTED` 映射为 `JOIN/REJECTED`。
5. 旧 `APPROVED` 映射为 `JOIN/APPROVED`，保留原确认人和时间。
6. 旧 `REVOKED` 继续作为关系历史保留，不伪造用户主动退出申请。
7. 回填申请沿用原 membership ID，保证旧客户端持有的待审 ID 仍可审核。
8. 本版本保留旧 `PENDING/REJECTED` membership 行作为滚动兼容数据，但新代码不再创建这两种顾问关系行。

已经被旧实现覆盖的多次顾问申请无法从数据库恢复，迁移只保证保留当前可见的最近状态；V27 之后的每次申请均独立保存。

迁移验证必须覆盖：

- 从 B26 基线创建的全新空数据库。
- 包含顾问 `PENDING/APPROVED/REJECTED/REVOKED` 以及法人 membership 的历史数据库。
- 法人及其他 `member_role` 行在迁移前后完全一致。

测试数据库必须使用 worktree 隔离命名，执行前打印解析后的主机和数据库名，禁止连接共享开发数据库。

## 7. 后端结构

### 7.1 顾问申请服务

新增聚焦的顾问申请 store/service：

- 验证申请人具有 active `CONSULTANT` 身份。
- `JOIN` 要求没有有效关系。
- `LEAVE` 要求存在有效关系。
- 两种动作都要求机构已认证且未删除。
- 提交与审核时都重新验证当前关系，不能只相信提交时状态。
- 重复待审和审核竞态转换为稳定的 409 冲突。

### 7.2 顾问关系服务

新增 `ConsultantInstitutionRelationshipService`，集中维护 `institution_memberships`：

- 批准 `JOIN`：创建或恢复唯一顾问关系为 `APPROVED`，记录确认人和确认时间。
- 批准 `LEAVE`：将目标关系改为 `REVOKED` 并记录撤销时间。
- 退出一个机构不能影响顾问在其他机构的关系。
- 不撤销全局顾问身份、钱包、历史订单、结算或分账记录。
- 退出后清除该机构上的 active consultant session；权限上下文下一次读取立即失去该机构。

关系更新、会话清理和申请状态更新必须在同一事务内完成，任一操作失败全部回滚。

### 7.3 医生兼容加固

- 医生继续使用现有 change-request service。
- 提交阶段即要求机构已认证，和批准阶段保持一致。
- 申请说明限制为 1000 字符。
- 停止任何生产路径向最终关系表 `doctor_institutions` 写入 `PENDING`。
- 旧管理员医生执业审核不得绕过 change-request 账本或 LEAVE 清理逻辑。

## 8. API 契约

保留 `/api/management/institution-membership-requests` 命名，增加明确范围：

### 8.1 本人申请

```text
GET /api/management/institution-membership-requests/owned
```

只返回当前登录用户以 active 医生或顾问身份提交的申请。

### 8.2 可审核申请

```text
GET /api/management/institution-membership-requests/reviewable
```

- 法人只返回 `institutionId` 属于本人 `managedInstitutionIds` 的记录。
- 平台管理员可返回全部记录。
- 不混入审核人本人提交给其他机构的申请。

### 8.3 提交

```text
POST /api/management/institution-membership-requests
```

请求：

```json
{
  "requestType": "DOCTOR",
  "institutionId": "institution-id",
  "action": "JOIN",
  "requestNote": "申请说明"
}
```

`requestType` 只允许 `DOCTOR` 或 `CONSULTANT`；`action` 只允许 `JOIN` 或 `LEAVE`。

### 8.4 撤回

```text
POST /api/management/institution-membership-requests/{requestType}/{id}/withdraw
```

医生和顾问都支持，只允许申请人本人撤回 `PENDING`。

### 8.5 审核

```text
POST /api/management/institution-membership-requests/{requestType}/{id}/review
```

请求：

```json
{
  "decision": "REJECTED",
  "reviewNote": "驳回原因"
}
```

统一响应字段：

- `id`
- `requestType`
- `applicantId`
- `applicantName`
- `institutionId`
- `institutionName`
- `action`
- `status`
- `relationshipStatus`
- `requestNote`
- `reviewNote`
- `submittedBy`
- `reviewedBy`
- `submittedAt`
- `reviewedAt`
- `createdAt`
- `updatedAt`

`relationshipStatus` 表示当前关系是否仍有效，不能用历史申请状态推断当前绑定。

### 8.6 候选机构

```text
GET /api/management/institution-membership-candidates
    ?requestType=DOCTOR|CONSULTANT
    &action=JOIN|LEAVE
    &query=...
    &offset=0
    &limit=20
```

- `JOIN`：只返回已认证、未删除、当前未绑定且没有待审申请的机构。
- `LEAVE`：只返回本人当前有效绑定且没有待审申请的机构。
- 所有提交接口仍进行相同的服务端校验，候选接口不是权限边界。

### 8.7 兼容接口

- 现有根 GET 暂保留为兼容适配器，新 Flutter 不再调用。
- `GET/POST /api/management/consultant-memberships` 暂保留。
- 顾问旧 POST 不传 `action` 时默认 `JOIN`；内部调用新的顾问申请服务。
- 兼容 GET 从新申请账本生成历史响应，不能继续只查询 membership 行。
- 兼容接口不得拥有第二套更宽松的校验规则。

## 9. HTTP 错误与并发

- 未登录：401。
- 缺少对应 active 专业身份或跨机构审核：403。
- 参数非法、驳回原因为空、申请说明过长：400。
- 机构或申请不存在：404。
- 已绑定时申请加入、未绑定时申请退出、重复待审、重复审核或过期状态：409。

申请读取使用明确 scope；审核使用行锁和条件状态更新。数据库唯一键负责兜底并发提交，服务层将唯一键冲突转换为同一 409 业务错误。

## 10. Flutter 体验

### 10.1 页面收敛

只保留一套机构关系页面实现，并由专业管理中心和个人中心快捷入口共同复用：

- 医生入口：当前机构、申请加入、申请退出、撤回待审申请、全部历史。
- 顾问入口：当前机构、申请加入、申请退出、撤回待审申请、全部历史。
- 法人入口：本机构成员关系审核。
- 多身份用户从对应身份分组进入时必须携带显式 scope，不根据 active role 顺序推断。

现有简化医生申请页和重复法人审核页不再承担业务逻辑；若保留类名用于兼容，只能转发到统一页面。

### 10.2 申请人页面

- 当前有效机构与申请历史分区展示。
- 操作明确显示“申请加入”或“申请退出”。
- 选择机构使用管理端候选接口，支持搜索和分页。
- 待审申请显示撤回按钮。
- 退出待审时机构仍显示为当前归属，并标记“退出审核中”。
- 申请记录显示机构名、动作、状态、申请说明、审核意见和时间。
- 成功提交、撤回或刷新后重新加载管理上下文和本人申请。

### 10.3 法人审核页面

- 只加载 `reviewable`。
- 分为“待审核”和“审核历史”。
- 每条记录显示医生/顾问徽标以及加入/退出动作。
- 批准前显示动作影响说明，避免把退出误认为加入。
- 驳回对话框必须填写原因。
- 处理成功后立即从待审区移入历史区并刷新管理上下文。

### 10.4 国际化与状态

新增文案必须同时提供中英文。页面包含加载、空态、重试、提交中、审核中以及稳定业务错误提示；不得直接向用户展示 UUID 代替机构或申请人名称。

## 11. 测试策略

### 11.1 后端单元和控制器测试

- 医生、顾问分别提交合法 `JOIN/LEAVE`。
- 医生、顾问分别撤回本人 `PENDING`。
- 非本人撤回和跨机构审核返回 403。
- 驳回必须有原因。
- `owned` 与 `reviewable` 互不混入。
- 响应始终包含真实 `requestType/action/applicantName/institutionName`。
- 非法状态返回稳定 400/404/409。

### 11.2 持久化与事务测试

- 顾问提交 LEAVE 后 membership 仍为 `APPROVED`。
- LEAVE 驳回或撤回不改变 membership。
- LEAVE 批准后 membership 为 `REVOKED`，其他机构关系不变。
- JOIN 批准创建或恢复唯一 `APPROVED` 关系。
- 顾问退出不改变专业身份、钱包和历史订单。
- 关系更新、会话更新或申请更新任一步失败时完整回滚。
- 并发提交只有一条 PENDING；重复审核只有一次成功。
- 医生关系表没有任何生产路径写入 `PENDING`。

### 11.3 Flutter 测试

- 医生和顾问入口传入正确显式 scope。
- 两种身份都可以选择 JOIN 候选机构并提交。
- 两种身份都只能从当前有效关系选择 LEAVE。
- 两种身份都能撤回待审申请。
- 法人列表只展示 managed institution 请求。
- 法人能区分医生/顾问和 JOIN/LEAVE。
- 驳回原因为空时不能提交。
- 处理成功后待审和历史区域正确更新。
- 双身份用户的本人申请不会混入其他机构审核队列。

### 11.4 验证顺序

1. 先运行新增或修改的最小测试，并确认 TDD 的失败原因来自缺失行为。
2. 修复失败时只重跑对应测试类或 Flutter 测试文件。
3. 相关测试通过后，后端和 Flutter 各最多运行一次更广验证。
4. Flutter 运行 `flutter analyze`；SDK 不可用时记录环境证据，不重复无效重试。
5. 全量测试超过 10 分钟停止并报告进度。

## 12. 部署与兼容

- V27 是增量、非破坏迁移，现有 `APPROVED/REVOKED` 顾问关系原样保留。
- 新服务部署后，所有新顾问申请只写 request 表；membership 只在审核批准时更新关系。
- 旧接口保留兼容，避免旧 Flutter 立即失效。
- 迁移完成后无需重新绑定现有机构和顾问。
- 清理旧顾问 `PENDING/REJECTED` membership 行和删除兼容接口属于后续独立版本，不在本次破坏性执行。

## 13. 非目标

- 机构主动邀请或移除医生、顾问。
- 自动创建医生机构项目绑定。
- 撤销顾问全局专业身份。
- 改写历史订单、结算、钱包或分账记录。
- 将医生、顾问迁移到同一个多态申请表。
- 在本次版本删除旧 API 或历史兼容行。

## 14. 验收标准

功能完成必须同时满足：

1. 医生和顾问都能从专业管理中心选择合法机构提交 JOIN 或 LEAVE。
2. 申请人可以撤回本人 PENDING。
3. 法人只看到本人机构的待审和历史申请，并能明确区分身份与动作。
4. 法人可以批准或驳回；驳回必须填写原因。
5. LEAVE 待审、驳回或撤回期间当前关系保持有效；批准后才解除。
6. 所有申请历史独立保存，后续重提不覆盖旧记录。
7. 现有已批准机构—顾问绑定无需重新创建且继续被权限、机构列表和订单校验识别。
8. 关键状态、权限、并发、迁移和 Flutter 交互均有自动化测试证据。
