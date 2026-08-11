# 机构法人管理机构档案接口设计

## 1. 目标与范围

本设计为专业管理中心提供机构法人查询、编辑本人已确认管理机构公开档案的专用接口，并据此重配 Flutter VO 与机构档案页面。

本阶段只处理机构档案列表、详情和完整更新三个端点。机构成员审核、项目申请审核、医生项目变更审核、顾问与医生其他操作继续按独立切片设计和实施。

## 2. 现状与问题

当前 Flutter 通过 `GET /api/admin/institutions` 和 `PUT /api/admin/institutions/{id}` 管理法人所属机构。后端虽按 `managedInstitutionIds` 做对象级校验，但更新接口直接绑定并保存完整 `InstitutionEntity`，导致以下问题：

- 专业用户依赖 `/api/admin/**` 历史命名，平台管理与法人自助管理语义混杂。
- 请求 DTO 暴露 `certificationTime`、`userCount`、`caseCount` 等平台字段，Flutter 页面也允许编辑。
- 完整实体保存可能覆盖并发更新的评分、认证状态和统计字段。
- 列表、详情和更新没有统一的专业端响应 VO 与错误语义。
- Flutter 响应 VO 丢失部分只读平台字段，却把不应编辑的字段放入更新草稿。
- 多值字段直接暴露数据库逗号字符串，前端需要了解存储细节。

## 3. 采用方案

新增机构法人专用复数资源：

- `GET /api/management/institutions`
- `GET /api/management/institutions/{institutionId}`
- `PUT /api/management/institutions/{institutionId}`

采用复数资源是因为一个法人账号可以通过多条已批准任职关系管理多个机构。路径中的 `institutionId` 必须属于当前主体的 `managedInstitutionIds`。

旧 `/api/admin/institutions/**` 保留给平台管理员兼容使用；Flutter 专业管理中心迁移后不得继续调用旧机构档案链路。

## 4. 身份与对象级授权

请求必须携带有效 Bearer Token。服务端实时构建 `ManagementActor`，同时满足以下条件才允许访问：

1. 当前用户拥有 `ACTIVE INSTITUTION_LEGAL_REPRESENTATIVE` 职业角色。
2. `institution_memberships` 中存在该用户与目标机构的 `APPROVED` 任职关系。
3. `member_role` 为 `INSTITUTION_LEGAL_REPRESENTATIVE` 或兼容值 `LEGAL_REPRESENTATIVE`。
4. 目标机构未被软删除。

列表仅返回 `managedInstitutionIds` 对应机构。详情和更新均调用对象级校验，不接受请求体中的用户 ID、法人 ID 或管理关系字段。医生、顾问、普通用户、待审核或已撤销法人不能访问；平台管理员继续使用平台管理接口，不复用法人自助接口。

## 5. 字段边界

### 5.1 可编辑公开字段

| API 字段 | 数据库字段 | 请求类型 | 更新规则 |
|---|---|---|---|
| `name` | `name` | String | 必须出现，trim 后不能为空 |
| `address` | `address` | String | 必须出现，可用 `""` 清空 |
| `city` | `city` | String | 必须出现，可用 `""` 清空 |
| `description` | `description` | String | 必须出现，可用 `""` 清空 |
| `coverImage` | `cover_image` | String | 必须出现，可用 `""` 清空 |
| `images` | `images` | List<String> | 必须出现，空数组清空 |
| `establishedYear` | `established_year` | Int? | 必须出现；`null` 清空；非 null 时校验合理年份 |
| `credentials` | `credentials` | String | 必须出现，可用 `""` 清空 |
| `credentialImages` | `credential_images` | List<String> | 必须出现，空数组清空 |
| `specialties` | `specialties` | List<String> | 必须出现，空数组清空 |
| `tags` | `tags` | List<String> | 必须出现，空数组清空 |
| `contactPhone` | `contact_phone` | String | 必须出现，可用 `""` 清空 |
| `businessHours` | `business_hours` | String | 必须出现，可用 `""` 清空 |

字符串保存前 trim。数组逐项 trim、删除空项并保持首次出现顺序；服务端负责数组与数据库逗号字符串互转。URL 中不得包含逗号，以兼容当前存储结构。

`establishedYear` 的 JSON key 必须存在：整数表示更新，`null` 表示清空，缺失为 400。非 null 值不得晚于当前年份，也不得早于 1800。

### 5.2 只读响应字段

以下字段可以返回，但不得出现在更新 DTO 中：

- 标识与时间：`id`、`createdAt`、`updatedAt`
- 平台信任字段：`rating`、`reviewCount`、`isVerified`、`certificationTime`
- 业务统计：`projectCount`、`doctorCount`、`consultationCount`、`userCount`、`caseCount`

软删除字段、法人用户 ID、任职关系内部字段不进入公开响应。平台信任字段只能由审核和统计流程维护。

## 6. 接口契约

所有响应沿用 `BaseResponse<T>`，并同时返回真实 HTTP 状态。

### 6.1 查询本人管理的机构列表

`GET /api/management/institutions`

成功响应的 `data` 为数组；没有已确认机构时返回空数组：

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "institution-uuid",
      "name": "示例医疗美容机构",
      "city": "上海",
      "address": "示例路 1 号",
      "coverImage": "https://cdn.example.com/institution/cover.jpg",
      "isVerified": true,
      "rating": 4.8,
      "reviewCount": 120,
      "projectCount": 18,
      "doctorCount": 9
    }
  ]
}
```

列表使用聚焦的 `ManagedInstitutionSummary`，不返回大图数组和长文本。

### 6.2 查询单个机构档案

`GET /api/management/institutions/{institutionId}`

成功响应 `data` 使用完整 `ManagedInstitutionProfile`：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "institution-uuid",
    "name": "示例医疗美容机构",
    "address": "示例路 1 号",
    "city": "上海",
    "description": "机构公开介绍",
    "coverImage": "https://cdn.example.com/institution/cover.jpg",
    "images": ["https://cdn.example.com/institution/1.jpg"],
    "establishedYear": 2012,
    "credentials": "机构公开资质说明",
    "credentialImages": ["https://cdn.example.com/institution/cert-1.jpg"],
    "specialties": ["皮肤美容", "注射美容"],
    "tags": ["夜间门诊"],
    "contactPhone": "021-12345678",
    "businessHours": "09:00-21:00",
    "rating": 4.8,
    "reviewCount": 120,
    "isVerified": true,
    "certificationTime": "2026-01-02",
    "projectCount": 18,
    "doctorCount": 9,
    "consultationCount": 320,
    "userCount": 28,
    "caseCount": 66,
    "createdAt": "2025-01-01T10:00:00",
    "updatedAt": "2026-08-11T10:00:00"
  }
}
```

### 6.3 完整更新单个机构档案

`PUT /api/management/institutions/{institutionId}`

客户端必须提交全部 13 个可编辑字段：

```json
{
  "name": "示例医疗美容机构",
  "address": "示例路 1 号",
  "city": "上海",
  "description": "机构公开介绍",
  "coverImage": "https://cdn.example.com/institution/cover.jpg",
  "images": ["https://cdn.example.com/institution/1.jpg"],
  "establishedYear": 2012,
  "credentials": "机构公开资质说明",
  "credentialImages": ["https://cdn.example.com/institution/cert-1.jpg"],
  "specialties": ["皮肤美容", "注射美容"],
  "tags": ["夜间门诊"],
  "contactPhone": "021-12345678",
  "businessHours": "09:00-21:00"
}
```

缺少任何 key、字符串字段为 null、数组字段为 null、数组元素非字符串或非法年份均返回 400。请求中出现未知字段或平台只读字段也返回 400，避免客户端误以为已更新。

成功响应返回更新后的完整 `ManagedInstitutionProfile`。服务端必须执行只覆盖上述 13 个字段的定向 UPDATE，再重新查询响应；禁止通过完整实体 `save` 回写平台字段。

## 7. 错误语义

| HTTP 状态 | `code` | 场景 |
|---|---:|---|
| 400 | 400 | JSON 结构错误、缺字段、未知字段、空名称、非法年份或非法数组元素 |
| 401 | 401 | Token 缺失、失效或凭据版本无效 |
| 403 | 403 | 当前主体不是 ACTIVE 法人，或目标机构不属于其可管理范围 |
| 404 | 404 | 已授权的机构记录不存在或已删除 |
| 500 | 500 | 非预期服务端错误；响应不得暴露 SQL 或内部异常消息 |

对未授权对象统一返回 403，不通过 404 泄露其他机构是否存在。列表在合法法人没有任何已确认任职关系时返回空数组。

## 8. 后端结构与一致性

- 新增聚焦的 management controller、service、请求 DTO、列表摘要 VO 和详情 VO，不复用 JPA 实体或管理员请求 DTO。
- `ManagementAccessService` 继续作为角色和机构范围的唯一来源。
- repository 增加 13 字段定向更新方法，更新成功后清理机构详情、发现页及相关列表缓存。
- 定向更新与重新查询在同一事务中完成；若更新行数不是 1，返回 404 或一致性错误。
- 不修改数据库表，不新增 migration。
- 旧管理员接口保留，但其专业用户访问权限在 Flutter 迁移后收紧为 ADMIN；若有其他生产调用，先保留只读兼容并在文档标记弃用，不让法人继续使用旧 PUT。

## 9. Flutter VO 与页面

Flutter 使用三个语义模型：

- `ManagedInstitutionSummary`：机构选择/列表所需字段。
- `ManagedInstitutionProfile`：完整响应，包含公开资料和只读平台字段。
- `ManagedInstitutionProfileUpdate`：只包含 13 个可编辑字段，序列化时字段集合必须精确一致。

Repository 只调用新的 `/management/institutions` 路径。旧 `ManagedInstitutionDraft` 与旧 `/admin/institutions` 法人调用在迁移完成后删除。

页面流程：

1. 法人入口先加载本人管理机构列表。
2. 单机构可直接进入详情；多机构先选择机构。
3. 详情页展示认证状态、评分和统计，只读且不进入表单控件。
4. 编辑表单覆盖 13 个公开字段，支持封面、环境图和公开资质图上传/删除。
5. 保存时提交完整更新 DTO；成功后用响应同步所有表单与只读字段。
6. 页面提供加载、空列表、403、404、保存中、保存失败重试与防重复提交状态。
7. 页面文案接入现有本地化机制，不新增硬编码中英文分支。

公开图片继续使用 `POST /api/upload` 返回的 `data.url`。公开资质图片不等同于平台审核材料，不得显示“平台已核验”等暗示。

## 10. 最小测试与验收

后端只新增能证明契约的聚焦测试：

- ACTIVE 法人列表只返回本人已确认管理机构。
- 法人可查询、完整更新本人机构；其他机构返回 403。
- 13 个字段正确归一化并落库，四个数组正确互转。
- `establishedYear` 的整数、null、缺失和非法范围行为符合契约。
- 缺字段、未知字段、平台字段、空名称和 null 非法字段返回 400。
- 并发或先行平台字段更新后，法人保存不会覆盖认证、评分和统计字段。
- 普通用户、医生、顾问、待审核/撤销法人返回 403；未登录返回 401。
- 非预期异常返回真实 HTTP 500 和安全通用消息。

Flutter 聚焦测试：

- 三个 VO 正确解析/序列化，更新 DTO 精确包含 13 个字段和数组。
- 单机构直达、多机构选择、空列表、加载失败和重试。
- 表单可编辑公开字段并完整保存；平台字段只读且不进入请求。
- 图片上传结果进入对应数组；保存期间禁用重复提交。

先运行受影响的后端测试类，再运行最接近的 Flutter unit/widget 测试与定向 `flutter analyze`。不为已由同一边界测试证明的字段重复增加逐字段测试，不运行无关大套件。

## 11. 文档与兼容迁移

- `doc/用户端API文档.md` 写入完整请求、响应、字段和错误表。
- `docs/FLUTTER_API_CONTRACT.md` 更新专业管理矩阵与数组传输规则。
- `docs/CORE_ROLES_BUSINESS_SWIMLANE.puml` 将法人机构档案旧路径替换为三个新路径，并重新核对端点计数。
- Flutter 迁移后全仓不得再以法人身份调用旧 `/admin/institutions` 档案接口。
- 平台管理员页面继续使用管理员接口；专业自助 VO 与管理员 VO 不共享更新 DTO。

完成本切片后，优先处理顾问专用机构关系契约，再处理医生本人订单与文章，持续沿用“数据库字段边界 → 规范接口 → Flutter VO → 页面”的纵向交付方式。
