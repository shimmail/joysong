# 医生机构项目资料变更申请设计

## 1. 目标与边界

扩展现有 `POST /api/admin/institution-project-requests` 的 `PROFILE_UPDATE`，让已认证医生一次申请替换本人在一个机构项目下的：

- 医生级项目价格；
- 项目展示资料；
- 面诊费；
- 医美顾问、机构分账比例。

所属机构法人批准后，上述医生级数据在一个数据库事务中同时生效。平台管理员可以正常审核，也可显式强制处理。任何操作都必须精确限定在 `(doctor_id, institution_project_id)`，不得影响同项目的其他医生。

不新建提交路径，不把本功能拆回独立的分账提案。`JOIN`、`LEAVE` 及既有 `split_config_proposals` 工作流保持兼容。

## 2. 当前数据模型核对

### 2.1 生效数据

| 数据 | 表与键 | 当前字段 |
| --- | --- | --- |
| 医生级价格和展示资料 | `doctor_projects`，复合主键 `(doctor_id, institution_project_id)` | `price`, `service_description`, `service_tags`, `schedule_note`, `cover_image`, `images` |
| 面诊费和可配置分账 | `doctor_institution_project_configs`，唯一键 `(doctor_id, institution_project_id)` | `consultation_fee`, `commission_rate`, `institution_rate` |
| 平台比例 | 应用配置 `OrderSplitProperties.platformRate` | 不由医生申请修改 |

字段语义以 `OrderSplitRatePolicy` 为准：

- `commissionRate` 是**医美顾问分账比例**，不是平台比例；
- `institutionRate` 是**合作医疗机构分账比例**；
- `platformRate` 由平台配置提供；
- 医生净比例为 `100 - platformRate - institutionRate - commissionRate`。

因此申请不接收 `doctorRate` 或 `platformRate`，服务端用当前平台比例推导医生净比例并校验非负。

### 2.2 现有申请工作流的缺口

`doctor_project_change_requests` 已有 `PROFILE_UPDATE`、目标医生/机构/机构项目、价格建议、展示资料、审核人与时间、`pending_key` 唯一键。现有实现还存在以下缺口：

1. `PROFILE_UPDATE` 审批刻意保留原 `doctor_projects.price`，尚不能变更医生价格；
2. 申请表没有三项配置快照，无法审计或原子应用面诊费和分账；
3. 审批只锁申请行，没有锁生效的医生项目/config 行，也没有提交时基线版本；
4. Flutter 仅有 JOIN 申请 VO/页面，分账另走 `split_config_proposals`，与本次统一申请不符。

## 3. 数据库变更

需要新 migration；不能把金额/比例塞进 `notes` 或不受约束的 JSON。

向 `doctor_project_change_requests` 增加：

| 列 | 类型 | 用途 |
| --- | --- | --- |
| `consultation_fee` | `DECIMAL(10,2) NULL` | 申请的面诊费 |
| `commission_rate` | `DECIMAL(5,2) NULL` | 申请的顾问比例 |
| `institution_rate` | `DECIMAL(5,2) NULL` | 申请的机构比例 |
| `base_doctor_project_updated_at` | `TIMESTAMP NULL` | 提交时医生项目版本 |
| `base_config_id` | `VARCHAR(36) NULL` | 提交时有效 config；无 config 时为 null |
| `base_config_updated_at` | `TIMESTAMP NULL` | 提交时 config 版本；无 config 时为 null |

约束：

- 三个新业务值对 `PROFILE_UPDATE` 必须同时非 null，对 `JOIN/LEAVE` 必须为 null；
- 面诊费非负且不超过 `99999999.99`；比例均为 `0..100` 且最多两位小数；
- 应用层继续用 `OrderSplitRatePolicy` 校验平台、机构、顾问合计不超过 100；
- 保留现有 `pending_key` 唯一键，从数据库层保证每个 `(doctor_id, institution_project_id)` 最多一个 PENDING。

升级顺序固定为：V17/V18 仅增加可空的提案、基线和 before 列；V18 再回填既有 `PROFILE_UPDATE`；V19 最后增加范围与判别约束。历史申请无法还原提交时原值，故按 `(doctor_id, institution_project_id)` 从迁移执行时的 `doctor_projects` 和未软删 config 精确捕获可审计近似快照。缺 config 使用服务默认面诊费 0、顾问率 0、机构率 40、平台率 10 及推导医生率；缺 doctor project 使用价格 0 与空资料。fresh migration 与 V15 历史 fixture 升级均须在名称以 `myapp_worktree_` 开头的隔离 MySQL 数据库验证。

## 4. API 契约

### 4.1 提交 PROFILE_UPDATE

`POST /api/admin/institution-project-requests`

请求体采用全量替换语义，必须恰好提交以下键；未知键和缺失键返回 400：

```json
{
  "requestType": "PROFILE_UPDATE",
  "institutionProjectId": "ip-uuid",
  "priceSuggestion": 12800.00,
  "serviceDescription": "医生针对该项目的服务说明",
  "serviceTags": ["自然", "精细化"],
  "scheduleNote": "每周二、四面诊",
  "coverImage": "https://cdn.example/cover.jpg",
  "images": ["https://cdn.example/1.jpg"],
  "consultationFee": 300.00,
  "commissionRate": 10.00,
  "institutionRate": 40.00,
  "notes": "请审核本次整体调整"
}
```

字段规则：

- 所有 12 个键均必填且均不接受 null；`notes`、`scheduleNote`、`coverImage` 可为空字符串；数组可为空数组，用于清空；
- `serviceDescription` 去除首尾空白后必须非空，最长 5000；`notes` 最长 2000；`scheduleNote` 最长 500；URL 单项最长 500；标签单项非空、最长 100，最多 20 项；图片最多 20 项；
- 金额使用 JSON number、最多两位小数：`priceSuggestion`、`consultationFee` 为 `0..99999999.99`；
- `commissionRate`、`institutionRate` 为 `0..100`、最多两位小数；医生净比例按第 2.1 节推导且不得为负；
- 服务端从认证主体取得 `doctorId`，客户端不得提交医生或机构 ID；
- 目标必须是未删除机构项目，医生必须已经加入该项目，且提交时仍有该机构的 APPROVED 有效执业关系；
- 展示数组在 API 中为数组，在现有 VARCHAR 字段中按项目既有 canonical 编解码规则存储；禁止客户端自行拼接逗号字符串。

成功返回 200 及完整申请 VO。PENDING 期间生效表不变。

### 4.2 申请 VO

现有 `DoctorProjectChangeView` 增加：

```json
{
  "consultationFee": 300.00,
  "commissionRate": 10.00,
  "institutionRate": 40.00,
  "platformRate": 10.00,
  "doctorRate": 40.00,
  "forceProcessed": false
}
```

`platformRate`、`doctorRate` 为响应推导值，不能提交。`forceProcessed` 来自审核审计字段（见第 5 节）。列表权限保持：管理员可见全部；医生仅本人；法人仅自己管理机构。

### 4.3 审核与强制处理

复用 `POST /api/admin/institution-project-requests/{id}/review`：

```json
{
  "decision": "APPROVED",
  "reviewNote": "同意整体调整",
  "force": false
}
```

- `decision` 仍为 `APPROVED | REJECTED | CHANGES_REQUESTED`；非 APPROVED 必须有 `reviewNote`；
- `force` 键必填、默认业务语义为 false，但客户端仍需显式发送；
- 法人只能 `force=false`，且申请快照 `institution_id` 必须属于其当前 `managedInstitutionIds`；
- 管理员可正常审核；只有管理员可传 `force=true`；
- `force=true` 只允许管理员在审批时跳过“医生执业关系已失效”或“提交基线已漂移”的阻断，用于人工处置；仍必须执行字段范围、目标机构项目/医生项目存在、复合键一致、状态 CAS 和原子事务校验；
- 强制处理也写审核人、审核时间、说明与强制标记，不能伪装成普通审核。

为审计强制处理，migration 另增加 `force_processed BOOLEAN NOT NULL DEFAULT FALSE`。若 `force=true`，`reviewNote` 必须非空。

### 4.4 错误响应

沿用 `BaseResponse`，规范状态：

| HTTP | 场景 |
| --- | --- |
| 400 | exact shape、长度、金额、比例或状态不合法 |
| 401 | 未认证 |
| 403 | 非认证医生提交、目标非本人、非法法人审核、非管理员 force |
| 404 | 申请、机构项目或医生项目不存在 |
| 409 | 已有 PENDING、申请已处理、基线版本漂移/并发 CAS 失败 |

重复审批不得返回伪成功。

## 5. 提交与审批算法

### 5.1 提交

在事务中：

1. 解析 actor，取得本人 `doctorId`；验证 exact request；
2. 查询目标机构项目及其 `institution_id/project_id`；
3. 以复合键读取本人 `doctor_projects`，绝不接受客户端 doctor ID；
4. 验证有效机构关系；读取有效 config（允许尚不存在，此时页面以系统默认值初始化）；
5. 将所有申请值和两张生效表的基线标识/`updated_at` 写入 request；
6. 依赖 `uk_dpcr_single_pending` 处理竞态，并将重复键映射为 409。

### 5.2 APPROVED 原子应用

单个 `@Transactional` 事务按固定顺序执行：

1. `SELECT request ... FOR UPDATE`，确认 PENDING；
2. 校验法人对象权限或管理员权限；
3. `SELECT doctor_projects ... WHERE doctor_id=? AND institution_project_id=? FOR UPDATE`；
4. `SELECT doctor_institution_project_configs ... WHERE doctor_id=? AND institution_project_id=? FOR UPDATE`（包含逻辑删除行）；
5. 非 force：验证当前有效关系、医生项目 `updated_at`、config 的存在性/id/`updated_at` 与提交基线一致；
6. 再次运行金额/比例策略；
7. 精确更新该医生的 `doctor_projects`；精确 upsert/恢复该医生的 config；
8. 用 `WHERE id=? AND status='PENDING'` CAS 更新申请状态、审核审计和 `force_processed`；
9. 任一步失败则整体回滚。

禁止使用 `findByInstitutionProjectId()` 后批量 save，也禁止只以 `institution_project_id` 更新。精确 SQL/JPA 条件必须同时包含 doctor ID，以此保证同机构项目其他医生不变。

`REJECTED/CHANGES_REQUESTED` 只 CAS 更新申请和审计，不写生效表。

## 6. Flutter 适配

### 6.1 VO 与 repository

- 新增专用 `DoctorProjectProfileUpdateDraft` 和扩展后的 `DoctorProjectChangeRequest`；不要继续把 PROFILE_UPDATE 塞进只覆盖 JOIN 的 `InstitutionProjectJoinRequestDraft`；
- Dart 金额/比例序列化保持 number，并与服务端相同地校验有限值、两位小数和范围；
- `serviceTags/images` 使用 `List<String>`；
- repository 继续 POST `/admin/institution-project-requests`；审核继续 POST `/{id}/review` 并显式发送 `force`。

### 6.2 页面

- 医生“我的机构项目”进入编辑页，先加载本人当前医生项目与有效 config，表单一次编辑价格、展示资料、面诊费、顾问比例、机构比例；
- 提交按钮明确显示“提交整体变更申请 / Submit profile update request”，成功后展示 PENDING，不把草稿误显示为已生效；
- 法人审核详情同时显示当前值与申请值，并显示推导的平台/医生比例；
- 管理员页面提供普通审核与二次确认后的“强制批准”，法人页面不显示 force；
- 所有新增标题、字段、校验、成功/失败/冲突提示提供中英文；
- 保存中禁用重复提交；409 提示刷新当前数据后重新申请。

## 7. 关键验证（控制测试数量）

最小但足够的测试集合：

1. 服务测试：医生只能为本人已加入项目提交，完整快照写入且重复 PENDING 为 409；
2. MySQL 事务集成测试：法人批准后两张生效表和 request 同时更新，注入中途失败时全部回滚；
3. 隔离测试：审批医生 A 后，同一 `institution_project_id` 的医生 B 的价格、资料、config 完全不变；
4. 权限/并发测试：错误法人 403、普通基线漂移 409、管理员 `force=true` 成功、非管理员 force 403、并发审批仅一个 CAS 成功；
5. Flutter contract/widget：exact JSON、医生整表提交与 PENDING 状态、法人审核、管理员 force 可见性及中英文关键文案。

迁移测试必须使用从 worktree 名派生的空数据库 `myapp_worktree_doctor_profile_update_request`，执行前打印数据库 host/name；不得连接或重置共享开发库。

## 8. 非目标与兼容性

- 不允许医生修改平台比例；
- 不修改机构项目公共价格或公共资料；
- 不改订单既有金额/分账快照；批准只影响此后使用新配置创建的业务；
- 不删除既有 `split_config_proposals` API，它仍服务其他独立协商场景；本功能的 PROFILE_UPDATE 不再额外创建 split proposal，避免“一份申请半生效”；
- JOIN/LEAVE 的旧请求形状和处理保持兼容，但新的服务端 DTO 应按 requestType 做判别式严格校验。
