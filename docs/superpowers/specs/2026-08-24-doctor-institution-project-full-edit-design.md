# 医生机构项目完整编辑与法人审核设计

## 背景

当前医生“编辑机构项目”使用 `PROFILE_UPDATE` 申请，但只允许修改医生价格、展示说明、服务标签、排期、封面和图库。审批通过后也只更新申请医生的 `doctor_projects` 与价格兼容配置，不会更新机构项目共享资料。

本次将该链路扩展为完整的机构项目编辑申请：医生可在不改变机构及平台项目关联的前提下，修改与“新增机构项目”页面一致的业务字段；法人审核通过后，机构共享字段影响该机构项目，医生专属字段只影响申请医生。

## 目标

- 医生编辑页复用“新增机构项目”页面的共享资料输入与校验；最终可编辑范围以本规格的字段清单为准，避免把新增页中的机构级状态、原价或分账字段误带入医生申请。
- 医生只能用 `institutionProjectId` 标识编辑目标；机构与所属平台项目仅展示，客户端不得提交或修改 `institutionId`、平台 `projectId` 或医生 ID。
- 移除排期说明，不再进入版本 2 记录的新申请、审核详情或批准写回；历史版本 1 记录继续按兼容规则处理。
- 机构共享字段与医生专属字段通过一笔申请、一次审核原子生效。
- 法人审核列表按机构折叠展示关键摘要，详情复用机构项目详情的主体排版并正常显示图片。
- 增加医生级上架状态，并在公开展示、医生候选及下单校验中生效。
- 保留完整的提交时当前值与申请值快照，防止并发审核覆盖新数据。

## 非目标

- 不允许修改机构、平台项目或机构项目之间的关联。
- 不开放评分、评价数、平台币种或分账比例编辑。
- 不恢复医疗尾款、面诊金或其他支付链路。
- 不删除历史排期字段或历史申请数据；旧字段不再由版本 2 写入或展示，版本 1 待审记录保留旧版兼容语义。
- 不将机构共享字段拆成多个独立审核申请。
- 不重构现有 `LEAVE` 申请及法人批准离开项目的业务语义，只保证新版编辑申请与待处理离开申请继续互斥。

## 字段归属

| 类型 | 字段 | 编辑与批准规则 |
| --- | --- | --- |
| 只读关联 | `institutionProjectId`、机构、平台项目 | 由服务端根据目标机构项目解析；客户端不得提交 `institutionId`、`projectId` 或 `doctorId` |
| 机构共享 | 名称、分类、说明、标签、标语、详情、展示销量、封面、项目图片 | 医生可提交修改；批准后更新一次 `institution_projects`，同机构项目下所有医生共享结果 |
| 医生专属 | 医生项目价格、医生级上架状态 | 批准后只更新申请医生的 `doctor_projects` |
| 申请信息 | 申请说明 | 只进入审核账本，不写入业务目标表 |
| 固定或派生 | 币种、旅游地接服务费、医疗价格兼容值 | 币种固定为 USD；服务费按医生价格与服务端平台比例计算，当前业务比例为 40%；兼容价格由医生价格派生 |
| 系统数据 | 评分、评价数、机构项目关联 | 不允许医生编辑 |

“新增机构项目”页面目前没有原价输入，因此本次编辑页也不新增原价输入。机构项目中原有的 `originalPrice` 保持不变。`salesCount` 明确表示可由机构运营维护的展示销量，不替代也不回写真实支付订单统计。

## 继承字段语义

机构项目允许从平台项目继承名称、分类、说明、标签、标语、详情、封面和图库。编辑页应把当前生效值放入输入框，而不是单独只读展示继承内容。

为避免医生没有改动时意外把继承值固化成机构覆盖值：

- 目标接口同时返回原始机构覆盖值、当前生效值、平台项目名称和只读 `baseRevision` 并发令牌。
- 表单以生效值预填。
- 原始覆盖值为空且输入值未变化时，客户端继续提交“继承”意图。
- 输入值发生变化时，提交显式机构覆盖值。
- 将可继承文本清空、标签清空或图库清空表示恢复继承平台项目，而不是创建不可见的空覆盖值。
- 线上契约中，可继承字段以 `null` 表示继承、以非空值表示显式机构覆盖；客户端把空白文本和清空后的标签、封面或图库归一化为 `null`，不使用空字符串或空数组表达第三种状态。
- `salesCount` 不参与继承，必须显式提交非负整数；`price` 与 `doctorActive` 也必须显式提交。
- 服务端先应用继承后再按新增机构项目的规则校验有效值；缺少必填有效值时返回 422，不能把不完整快照送审。
- 服务端在提交时解析并固化申请批准后会展示的完整有效快照，同时记录平台继承源 hash。当 `sharedChanged=true` 且仍有任一 proposed 字段保持继承时，平台继承源在普通批准前发生变化必须返回 409，不能让待审预览静默漂移；纯医生私有申请不写共享资料，不受该 hash 阻塞。

## 医生端流程

1. “编辑机构项目”继续按医生所属机构折叠展示全部已加入项目。
2. 医生级停用项目仍保留在本人编辑列表中，确保医生可以申请重新上架。
3. 项目行显示机构项目名称、医生价格、医生上架状态和待审核状态。
4. 点击编辑进入独立表单：
   - 顶部只读展示机构与所属平台项目；
   - 编辑名称、分类、说明、标签、标语、详情、医生价格、销量、封面、图库和医生级上架状态；
   - 继续显示旅游地接服务费只读预览；
   - 保留申请说明；
   - 不显示排期说明、URL 输入框或底部历史申请列表。
   - 封面和图库通过现有上传组件取得服务端资源地址，请求仍保存资源地址，但用户不手工输入 URL。
5. 同一医生在同一机构项目存在待处理 `PROFILE_UPDATE` 或 `LEAVE` 时，禁止重复编辑或离开申请。
6. 提交成功后返回列表，项目保留并标记待审核。

## 请求契约

### 路径与旧客户端兼容

现有版本 1 路径保持不变，用于旧客户端和历史申请：

```text
/api/admin/institution-project-requests
```

新增版本 2 路径，保留相同的子资源结构：

```text
GET  /api/v2/admin/institution-project-requests/profile-update-targets
GET  /api/v2/admin/institution-project-requests
POST /api/v2/admin/institution-project-requests
POST /api/v2/admin/institution-project-requests/{id}/review
POST /api/v2/admin/institution-project-requests/{id}/withdraw
```

端点与申请类型的兼容矩阵如下：

| 操作 | 版本 1 路径 | 版本 2 路径 |
| --- | --- | --- |
| profile target | 返回现有扁平 target | 返回版本 2 完整 target、`baseRevision` 与定价策略版本 |
| list | 只返回 `payloadVersion=1`，保持扁平响应 | 返回版本 1 与版本 2，以 `payloadVersion` 区分并适配 |
| submit `JOIN` | 保持现有请求与 `payloadVersion=1` | 接受同一精确请求体并创建 `payloadVersion=1` |
| submit `PROFILE_UPDATE` | 受兼容开关控制，创建 `payloadVersion=1` | 只接受本规格版本 2 请求体并创建 `payloadVersion=2` |
| submit `LEAVE` | 保持现有请求与 `payloadVersion=1` | 接受同一精确请求体并创建 `payloadVersion=1` |
| review | 只处理 `payloadVersion=1` | 可处理 `JOIN`、`LEAVE`、版本 1 与版本 2 `PROFILE_UPDATE` |
| withdraw | 只处理 `payloadVersion=1` | 可处理申请人自己的任意版本 PENDING 记录 |

版本 2 `JOIN` 继续只允许 `requestType`、`institutionProjectId`、`serviceDescription`、`priceSuggestion`、`notes`；版本 2 `LEAVE` 只允许 `requestType`、`institutionProjectId`。它们不升级业务 payload，目的是让新客户端统一走版本 2 路由而不重构本次范围外的加入/离开语义。

版本 2 review 请求必须且只能包含 `decision`、`reviewNote`、`force`、`forceBaseRevision`。普通批准、驳回或要求修改时 `forceBaseRevision=null`；`force=true` 时必须回传审核详情最新下发的非空 `latestRevision`。服务端在锁内重算不一致时返回 409 `FORCE_BASE_STALE`，要求管理员重新查看最新差异，避免刷新后到强批提交前再次变化而误覆盖。

- 新 Flutter 医生端、Flutter 法人端和 Web 后台只使用版本 2 路径。
- 版本 2 列表可读取版本 1 与版本 2 记录，并通过 `payloadVersion` 选择严格解析器；历史版本 1 继续用旧版展示与 apply 语义。
- 版本 1 的 target/list/submit/review/withdraw 响应字段保持不变，不混入版本 2 必填结构。版本 1 列表不返回版本 2 记录，版本 1 review/withdraw 端点不得处理版本 2 ID，防止旧页面在看不到完整共享快照时误操作；此情况返回 426 和稳定错误码 `CLIENT_UPGRADE_REQUIRED`。
- 版本 1 的 `JOIN`、`LEAVE` 和历史审核保持可用。版本 1 `PROFILE_UPDATE` 提交由服务端兼容开关 `app.project-change.v1-profile-update-enabled` 控制，本次默认开启；新客户端完成受控发布后关闭，随后返回 426，但不物理删除旧路径。
- 发布顺序为后端版本 2、审核端更新、医生端更新、最后关闭版本 1 `PROFILE_UPDATE` 提交，避免产生无人可见的待审申请。

### 版本 2 `PROFILE_UPDATE`

请求必须且只能包含以下字段：

```text
requestType
institutionProjectId
baseRevision
name
category
description
tags
slogan
detailContent
price
salesCount
doctorActive
coverImage
images
notes
```

其中：

- `requestType` 固定为 `PROFILE_UPDATE`。
- `baseRevision` 是目标接口下发的只读不透明并发令牌。服务端以固定顺序对机构项目关联与版本、平台继承源、医生项目更新时间以及兼容配置 ID/更新时间计算；提交时重算不一致则返回 409 `EDIT_BASE_STALE`。
- `price` 是申请医生的 USD 项目价格。
- `doctorActive` 是申请医生在该机构项目下的可见状态，不能与机构级 `institution_projects.is_active` 混用。
- 可继承字段使用 `null` 提交继承意图；其他必填字段按新增机构项目规则校验。
- 请求中不得出现 `institutionId`、`projectId`、`doctorId`、排期或分账字段。
- 平台比例、旅游地接服务费、兼容价格及现有分账配置全部由服务端读取或派生，不能信任客户端回传。
- 旅游地接服务费按 `price × 40%` 计算，并沿用服务端现有 USD 金额精度与舍入规则；客户端结果只用于预览。

目标接口采用结构化字段，至少包含：

- `payloadVersion=2`、不可变关联与展示名称；
- `baseRevision`；
- 当前机构项目的原始覆盖值和有效值；
- 当前医生价格、医生上架状态、平台比例、`pricingPolicyRevision` 与旅游地接服务费预览。

版本 2 申请响应至少包含：

- `payloadVersion`、不可变关联与展示名称；
- `currentProject`：提交时机构项目原始值和有效值；
- `proposedProject`：申请覆盖意图及批准后的有效预览；
- `latestProject`：审核详情刷新时的当前实时值，不替代不可变申请快照；
- `latestRevision`：审核详情实时值对应的不透明并发令牌，只用于强制批准二次确认；
- `sharedChanged`：由服务端比较原始覆盖意图和展示销量后计算，客户端不得指定；
- `currentDoctorPrice` / `proposedDoctorPrice`；
- `currentDoctorActive` / `proposedDoctorActive`；
- 平台比例与旅游地接服务费预览；
- `requestStatus`、申请说明和审核元数据；医生上架状态始终使用 `currentDoctorActive` / `proposedDoctorActive`，不复用模糊的 `status`。

客户端对待审核申请快照缺字段时应关闭审核操作，而不是猜测默认值。

### 版本 2 快照结构

`current_project_snapshot` 与 `proposed_project_snapshot` 使用同一严格 schema：

```json
{
  "schemaVersion": 2,
  "association": {
    "institutionProjectId": "string",
    "institutionId": "string",
    "platformProjectId": "string"
  },
  "rawOverrides": {
    "name": "string|null",
    "category": "string|null",
    "description": "string|null",
    "tags": ["string"],
    "slogan": "string|null",
    "detailContent": "string|null",
    "coverImage": "string|null",
    "images": ["string"]
  },
  "effective": {
    "name": "string",
    "category": "string",
    "description": "string|null",
    "tags": ["string"],
    "slogan": "string|null",
    "detailContent": "string|null",
    "salesCount": 0,
    "coverImage": "string|null",
    "images": ["string"]
  },
  "source": {
    "institutionProjectVersion": 0,
    "platformInheritanceHash": "sha256"
  }
}
```

示例中的 `"string|null"` 只表示字段类型；实际 JSON 必须是字符串或 `null`。`rawOverrides.tags/images` 实际值必须是非空数组或 `null`，`effective.tags/images` 必须是数组且不得缺省。`association` 只由服务端生成。未知 `schemaVersion`、缺失键、额外键、非法 JSON 或无法得到必填有效值时均 fail closed：详情显示数据异常，批准按钮关闭，服务端审核返回 422，而不是回退到默认值。

### 并发令牌规范

`baseRevision`、`latestRevision` 和平台继承源 hash 只由服务端计算，客户端将其视为不透明字符串。所有服务实例复用同一个 canonical helper，规则固定如下：

- 输出 UTF-8、无空白 JSON，字段顺序严格按本节列出的顺序，hash 使用 SHA-256 小写十六进制；不能依赖普通 Map 的迭代顺序。
- ID 使用数据库读取值并去除首尾空白；文本先按现有业务读取规则解码，再做 Unicode NFC。必填名称和分类去除首尾空白，可选空白文本归一化为 `null`。
- 标签和图片先通过现有兼容解码器把旧逗号字段或 JSON 转为数组，再逐项去除首尾空白、移除空项、做 Unicode NFC；保留显示顺序和重复项，不排序。
- 金额和比例使用 `BigDecimal.toPlainString()`；定价比例先转为小数比例并固定 6 位。时间使用数据库读回的 `Instant` 与 `DateTimeFormatter.ISO_INSTANT`，不在应用层再次截断精度。
- `configId` 和 `configUpdatedAt` 同时为 `null` 表示当前没有兼容配置；其他 null、空字符串或空数组不允许互换。

平台继承源 canonical JSON 精确字段顺序为：

```text
schemaVersion, platformProjectId, name, category, description, tags,
slogan, detailContent, coverImage, images
```

它覆盖全部可继承平台字段。`baseRevision` canonical JSON 精确字段顺序为：

```text
schemaVersion, institutionProjectId, institutionId, platformProjectId,
institutionProjectVersion, platformInheritanceHash,
doctorProjectUpdatedAt, configId, configUpdatedAt, pricingPolicyRevision
```

固定测试向量如下，换行只用于文档说明，实际输入是一行：

```text
platformCanonical={"schemaVersion":1,"platformProjectId":"pp-1","name":"Face Lift","category":"Surgery","description":null,"tags":["face","lift"],"slogan":"Natural result","detailContent":"Detail","coverImage":"https://cdn.example/cover.jpg","images":["https://cdn.example/1.jpg"]}
platformHash=f4168ca8ed8e9d41b62aef5f215a78cd2c018e1a3e8b7384f4311216fdeaea81

baseCanonical={"schemaVersion":1,"institutionProjectId":"ip-1","institutionId":"inst-1","platformProjectId":"pp-1","institutionProjectVersion":7,"platformInheritanceHash":"f4168ca8ed8e9d41b62aef5f215a78cd2c018e1a3e8b7384f4311216fdeaea81","doctorProjectUpdatedAt":"2026-08-24T01:02:03.456Z","configId":null,"configUpdatedAt":null,"pricingPolicyRevision":"travel-ground-service-rate:0.400000"}
baseRevision=314a4110fbe7a30bbd69054b85e25fd0f6b5c646dce0f5df35d1f2913ed635f1
```

### 定价策略一致性

40% 仍由服务端配置提供，但规格化为 `pricingPolicyRevision=travel-ground-service-rate:0.400000`。该 revision 纳入 `baseRevision` 并在版本 2 申请中固化；提交时同时保存实际平台比例和按现有舍入规则得到的旅游地接服务费。批准前服务端重新读取配置，revision 变化返回 409 `PRICING_POLICY_STALE`，必须重新提交，普通或强制批准均不能绕过。这样多实例滚动发布或运行时配置变化不会让审核预览与实际写入金额不一致。

## 数据库设计

### `doctor_projects`

新增医生级字段：

```text
is_active BOOLEAN NOT NULL DEFAULT TRUE
```

该字段只控制当前医生在此机构项目下是否对用户可见，不改变 `institution_projects.is_active`。

历史医生项目统一回填为 `TRUE`。医生自己的编辑目标查询不能过滤该字段。

### `institution_projects`

新增单调递增的共享资料版本：

```text
version BIGINT NOT NULL DEFAULT 0
```

所有直接后台修改和审核批准都必须在更新共享字段时递增版本，用于防止两个医生或后台编辑互相覆盖。

### `doctor_project_change_requests`

保留现有申请表和历史字段，新增版本化完整快照：

- `payload_version`：迁移时历史申请回填为 1，新完整申请由服务端显式写入 2；
- `base_institution_project_version`：提交时共享资料版本；
- `base_platform_inheritance_hash`：提交时平台继承源的稳定 hash；
- `pricing_policy_revision`：提交时 40% 定价策略的规格化 revision；
- 版本 2 沿用现有 `current_platform_rate` 固化提交时平台比例，并新增 `proposed_travel_ground_service_fee` 固化 proposed 旅游地接服务费；
- `shared_changed`：服务端计算的共享覆盖或展示销量是否变化；
- `current_project_snapshot`：提交时的机构原始值、有效值和平台项目身份；
- `proposed_project_snapshot`：申请的覆盖意图及批准后有效预览；
- `current_doctor_is_active` / `proposed_doctor_is_active`；
- 沿用现有 `current_price`、申请价格、医生项目更新时间及兼容配置基线保存医生私有前后值；
- `approval_audit_snapshot`：每次成功批准都保存批准前实时值、实际写入值、是否强制及批准前后版本；强制批准时还必须包含被覆盖的漂移字段。

共享快照采用 JSON 存储以避免继续为每个共享字段增加两套重复列；服务端通过版本化 DTO 严格解析、校验和输出。`approval_audit_snapshot` 至少包含 `beforeVersion`、`afterVersion`、`latestBefore`、`actualApplied` 和 `force`。申请人、审核人、申请状态、说明、`force_processed` 与时间继续使用现有审计列，不混入业务快照。历史 `schedule_note/current_schedule_note` 等列保留，只供版本 1 记录读取。

迁移必须在新建的隔离数据库验证。本 worktree 使用 `WORKTREE_ID=worktree_doctor_institution_project_full_edit`、数据库 `myapp_worktree_doctor_institution_project_full_edit` 和 Docker Compose 项目名 `myapp-worktree-doctor-institution-project-full-edit`；执行迁移前必须打印解析后的主机和数据库名。

## 提交与审批

### 提交

1. 从登录身份取得医生，不接受请求中的医生 ID。
2. 锁定并读取目标机构项目、所属平台项目、申请医生绑定与当前配置。
3. 以同一 canonical 算法重算 `baseRevision`；与请求不一致时返回 409，要求客户端重载表单，防止加载页面后发生的后台更新被医生旧表单无感覆盖。
4. 实时验证医生仍具有该机构的有效执业关系且已加入目标项目。
5. 校验完整表单与 USD 价格，读取 `pricingPolicyRevision`，并按服务端当前 40% 规则重算及固化旅游地接服务费。
6. 比较原始机构覆盖值、展示销量与 proposed 值，由服务端计算 `sharedChanged`。
7. 固化关联、机构项目版本、平台继承源 hash、共享前后快照、医生价格及状态前后值。
8. 继续使用数据库约束防止同一医生、同一机构项目重复待处理申请。

不同医生可以基于同一机构项目版本提交申请。只有 `sharedChanged=true` 的批准才写机构共享字段并递增共享版本：两个都修改共享资料的申请先批准者成功，后批准者返回 409；只修改医生价格或医生上架状态的申请不写、不递增也不校验共享版本，因此不同医生的私有修改可以独立批准。

### 法人批准

批准必须在一个事务中完成：

1. `FOR UPDATE` 锁定待审申请并确认仍为 `PENDING`。
2. 实时锁定并验证审核人仍是目标机构法人；平台管理员保留管理权限。
3. 实时验证申请医生仍具有有效执业关系、仍加入目标项目，且机构、平台项目关联未变。
4. 锁定机构项目、申请医生的项目绑定及兼容价格配置，校验医生私有基线。
5. 当 `sharedChanged=true` 时校验机构共享版本；proposed 中仍有继承字段时还必须校验平台继承源 hash。任一不一致返回 409。
6. 重新验证 USD 价格、40% 服务费及服务端配置；定价 revision 漂移返回 409，不能批准。
7. 当 `sharedChanged=true` 时更新机构共享字段，并以锁内版本或 `WHERE version = ?` CAS 方式递增 `institution_projects.version`；否则完全不写机构项目。
8. 只更新申请医生的价格与 `is_active`，同步兼容价格；其他医生的价格、状态和配置不变。
9. 最后以状态条件更新审核记录；任何一步失败均整体回滚，申请保持待审。
10. 事务提交后再失效实际发生变化的发现页、首页及项目相关缓存。

驳回或要求修改不写业务表，并强制填写审核意见。`CHANGES_REQUESTED` 是当前申请的终态；医生必须基于最新目标重新创建一条申请，旧记录继续保留审计，数据库的“仅一条 PENDING”约束不阻止重新提交。

### 平台管理员强制批准

强制批准只用于已经检测到机构共享版本或医生私有基线漂移的申请：

- 机构法人不能强制批准，只有具备现有平台管理权限的管理员可以执行；
- 客户端必须先刷新最新当前值并重新展示与原申请值的差异，再提供二次确认；请求必须回传该详情的 `latestRevision`，服务端锁内重算再次漂移则返回 409；
- 请求必须显式携带强制标记、非空审计说明和 `forceBaseRevision`；
- 强制批准只跳过机构版本或医生私有值基线一致性检查，不能绕过申请状态、不可变关联、医生绑定、有效执业关系、实时审核权限、平台继承源 hash、定价策略 revision、字段合法性或金额重算；平台继承源或定价策略已变化时必须要求修改或重新提交；
- 服务端仍须锁定最新记录，在一个事务中仅当 `sharedChanged=true` 时把原申请的完整 proposed 覆盖意图写入最新机构共享值并递增版本，同时只写申请医生私有值；
- 审核账本必须记录强制操作人、说明、批准前最新版本和批准后版本。

平台后台直接编辑机构共享资料同样必须在事务内锁行或使用 `WHERE version = ?` CAS，并递增版本；两个后台管理员并发编辑时后提交者得到 409，不能只做无条件 `version = version + 1` 后覆盖前者。

## 公开展示与下单

`institution_projects.is_active` 继续控制整个机构项目是否公开；`doctor_projects.is_active` 只控制当前医生服务。

机构级停用优先级更高：即使医生级为上架，也不能公开或下单。机构项目本身上架但没有任何符合机构关系、机构级状态和医生级状态的可预约医生时，从首页、搜索及目录发现结果移除；直接访问详情可以展示共享资料，但必须显示“当前暂无可预约医生”并关闭下单入口。医生重新上架并满足其他条件后自动恢复发现资格。

医生级停用后必须：

- 从机构项目详情的可预约医生列表移除；
- 从医生公开详情的该机构项目服务中移除；
- 不参与首页或目录中的医生最低价计算；
- 不能被新订单选中，服务端下单时再次校验；
- 仍出现在医生本人管理页面及法人审核历史中。

已存在订单不因后续停用自动取消或退款。

## 法人及后台审核界面

Flutter 法人端与 Web 后台共享以下信息结构：

1. 按机构使用折叠卡片分组。
2. 每条申请摘要显示：机构项目名称、所属平台项目名称、医生名称、医生 USD 价格、申请状态和申请后的医生上架状态；两种状态使用不同标签与文案。
3. 点击“查看详情”进入只读审核详情。
4. 详情默认展示“申请后效果”，复用用户端机构项目详情的主体排版：图片轮播、名称、机构、价格、销量、标签、标语、说明和详情。
5. 审核预览隐藏预约、收藏、医生选择、日记、评价、AI 等消费者交互。
6. 图片必须显示为真实图片，支持加载失败占位，不显示原始 URL 文本。
7. 详情同时提供当前值与申请值对比，并明确提示共享字段会影响同机构项目全部医生，价格与上架只影响申请医生。
8. 待审核操作放在详情底部，复用批准、驳回和要求修改；提交期间禁止重复操作。
9. 审核发生 409 时刷新最新申请，保留明确冲突提示，不把旧快照继续当作可审核内容。

新增机构项目创建申请也复用相同的机构分组卡片和纯展示详情主体组件，避免图片和排版再次形成两套实现；创建申请通过 adapter 转成统一 `InstitutionProjectPreviewModel`，不复用版本 2 编辑快照解析器、前后对比或审批业务逻辑，因为创建申请没有 before 快照。

## 错误处理

- 申请列表与平台项目目录独立加载；详情辅助数据失败不能隐藏整个审核队列。
- 图片加载失败显示占位图，不回退显示 URL。
- 响应快照不完整时标记数据异常并关闭审核按钮。
- 权限撤销返回 403；客户端刷新管理上下文后退出无权页面。
- 加载后基线漂移返回 409 `EDIT_BASE_STALE`；批准基线漂移返回 409 `APPROVAL_BASE_STALE`；平台继承源漂移返回 409 `INHERITANCE_SOURCE_STALE`；定价策略漂移返回 409 `PRICING_POLICY_STALE`；强批确认后再次漂移返回 409 `FORCE_BASE_STALE`。
- 重复待审申请返回 409 `REQUEST_ALREADY_PENDING`，重复或并发审核返回 409 `REQUEST_ALREADY_HANDLED`；客户端按稳定错误码刷新对应页面，不解析中文文案做判断。
- 旧客户端尝试读取或审核版本 2 记录、或在兼容窗口关闭后提交版本 1 `PROFILE_UPDATE`，返回 426 `CLIENT_UPGRADE_REQUIRED`。
- 批准事务失败不得产生部分共享更新、部分医生更新或错误缓存失效。

## 兼容与迁移

- 历史版本 1 申请继续按旧快照只读展示和审核，并按旧版 apply 语义处理；它不更新机构共享字段或机构版本。排期仅在版本 1 详情和版本 1 批准兼容逻辑中保留，不进入新版表单。
- 新客户端只创建版本 2 申请。
- 服务端对版本 2 使用严格允许字段集合，拒绝关联 ID、排期、分账或任意额外字段。
- 管理后台直接修改机构项目时使用锁或 CAS 并同步递增共享版本，避免绕过审核并发保护。
- 不删除旧医生资料副本列；新版批准不再把共享字段写入 `doctor_projects.service_description/service_tags/cover_image/images`。
- 现有 `LEAVE` 提交、法人批准和离开后的公开过滤保持不变，并补一条回归测试确认与版本 2 编辑申请互斥。

## 测试与验证

### 后端

- 精确请求字段测试：允许完整字段，要求 `baseRevision` 与 `doctorActive`，拒绝旧 `isActive`、排期、关联 ID、分账及额外字段。
- 路由矩阵测试：版本 2 `JOIN`/`LEAVE` 创建版本 1 payload，版本 2 review/withdraw 可处理允许的两种 payload，版本 1 list/review/withdraw 看不到或不能操作版本 2。
- 继承契约测试：未改继续继承、清空恢复继承、显式覆盖恰好等于平台值、空白与空数组归一化为 `null`，不完整有效值返回 422。
- canonical 测试：平台 hash 和 `baseRevision` 必须匹配文档固定向量；null、Unicode、数组顺序、旧逗号字段和时间精度分别覆盖。
- 编辑并发测试：机构、平台继承源、医生项目、兼容配置或定价策略在目标加载后改变时，旧 `baseRevision` 提交均返回 409。
- 提交权限测试：未认证医生、无有效机构关系、未加入项目均拒绝。
- 快照测试：机构共享和医生专属前后值完整且不可变。
- 审批并发测试：两个共享修改先批准成功、后批准返回 409；两个不同医生的纯私有修改都可批准且不递增共享版本；平台继承源在提交后变化时普通和强制批准都拒绝。
- 后台并发测试：两个管理员 CAS，及后台更新与法人批准交错时均只有一个共享写入成功。
- 强制批准矩阵：只有平台管理员可用且必须填写说明与 `forceBaseRevision`；刷新确认后再次漂移返回 409；可跳过共享版本或医生私有基线，不能跳过申请状态、关联、执业关系、权限、继承源、定价策略、字段和金额检查；审计快照完整。
- 原子性测试：在共享、医生、配置或状态更新阶段注入失败，所有写入回滚。
- 权限竞态测试：法人打开页面后权限被撤销，批准返回 403 且零写入。
- 医生状态测试：机构级与医生级停用组合、无活跃医生时的发现与直达详情、停用后重新上架；公开列表、最低价、候选医生和下单均正确过滤，本人管理列表仍包含。
- 金额测试：医生 USD 价格按服务端 40% 生成旅游地接服务费，并沿用现有金额舍入规则。
- 定价漂移测试：提交后比例 revision 变化时普通与强制批准都返回 409，申请保持 PENDING 且零写入。
- 缓存测试：只在事务提交成功后失效。
- 迁移测试：在空的隔离数据库验证迁移，并确认历史申请和历史排期数据保留。
- 快照损坏测试：未知版本、缺失或额外键、非法 JSON 均关闭审核且不能写业务表。
- 兼容测试：版本 1 路径保持扁平响应，版本 2 不泄露给版本 1 列表或审核；兼容开关开/关时版本 1 `PROFILE_UPDATE` 分别成功/返回 426；版本 1 待审申请的批准、驳回、要求修改及排期沿用旧语义；版本 2 不读写排期。
- 状态测试：`CHANGES_REQUESTED` 后旧行终态保留、允许基于最新目标新建申请；现有 `LEAVE` 仍可正常批准且与 PENDING 编辑申请互斥。

### Flutter

- 表单测试：只读关联、全部可编辑字段预填、排期不存在、图片上传删除、价格与费用预览、上架开关及完整提交。
- 契约测试：只调用版本 2 路径、精确 JSON、`baseRevision` 回传、结构化快照按版本解析、缺字段关闭审核。
- 列表测试：机构分组、关键摘要、申请状态与医生上架状态分离、待审核防重复、停用项目仍可编辑。
- 审核测试：真实图片组件、详情预览、前后对比、影响范围提示、审核动作与 409 刷新。
- 运行最小相关 widget/contract 测试后执行一次 `flutter analyze`。

### Web 后台

- 类型与严格解析测试覆盖版本 1 adapter 与版本 2 快照，并确认只调用版本 2 路径。
- 卡片分组、创建申请 preview adapter、详情图片、USD 价格、两种状态标签、前后对比、权限和并发提示测试。
- 运行相关页面测试和一次 typecheck/lint。

遵循项目测试规则：失败时只重跑失败用例或相关测试类；相关测试通过后最多运行一次全量测试；全量超过十分钟停止并报告。

## 文档同步

实现完成后同步更新：

- Flutter/服务端接口契约文档；
- `design/` 下医生机构项目申请与审核 UML；
- 既有医生项目客户端管理设计中“服务端尚未支持”的阶段性边界。
