# 已认证医生本人档案接口设计

## 1. 目标与范围

本设计为专业管理中心提供已认证医生查询、编辑本人公开主页（医生档案）的专用接口，并据此重配 Flutter VO 和医生档案页面。

本阶段只处理医生本人档案这一条纵向链路。机构法人、顾问及医生的其他专业操作继续沿用相同的接口规范，但分别设计和实施。

## 2. 现状与问题

当前 Flutter 通过 `GET /api/admin/doctors` 和 `PUT /api/admin/doctors/{id}` 管理医生本人档案。后端虽然通过 `ManagementAccessService` 限制非管理员只能访问本人，但该契约存在以下问题：

- 本人查询返回数组，客户端需要自行取第一项。
- 客户端需要传递 `id` 和 `userId`，存在对象越权和绑定关系误用风险。
- 本人编辑复用管理员请求 DTO，暴露评分、认证状态、机构关系和统计字段。
- 现有 PUT 请求字段带默认空值，漏传可编辑字段可能清空已有数据。
- 接口文档仍将该接口描述为 ADMIN 能力，与实际权限不一致。
- Flutter 页面尚未提供头像和主页展示材料的编辑入口。

## 3. 方案

新增医生本人专用资源：

- `GET /api/management/doctor-profile`
- `PUT /api/management/doctor-profile`

接口从当前认证主体派生医生 ID，不接受路径 ID 或请求体 `userId`。现有 `/api/admin/doctors` 管理接口暂时保留，避免影响平台管理员和旧客户端；Flutter 专业管理中心迁移到新接口。

## 4. 数据与字段边界

数据源为 `doctors` 表及医生的已批准机构关系。`doctors.id` 当前等于认证用户 ID，但新接口不要求客户端依赖该实现细节。

### 4.1 医生可编辑字段

| 字段 | 数据库字段 | 类型 | 规则 |
|---|---|---|---|
| `name` | `doctors.name` | String | 必填，trim 后不能为空 |
| `title` | `doctors.title` | String | 可为空，保存前 trim |
| `bio` | `doctors.bio` | String | 可为空，保存前 trim |
| `avatar` | `doctors.avatar` | String | 可为空；使用公开上传接口返回的 URL |
| `contactPhone` | `doctors.contact_phone` | String | 可为空；仅为公开联系电话，不是登录手机号 |
| `specialties` | `doctors.specialties` | String | 逗号分隔，保存前逐项 trim、去除空项 |
| `credentials` | `doctors.credentials` | String | 可为空；医生填写的公开资历说明 |
| `credentialImages` | `doctors.credential_images` | String | 逗号分隔的公开图片 URL |
| `certificationTags` | `doctors.certification_tags` | String | 逗号分隔；仅保存允许医生维护的展示标签 |

`credentialImages` 和 `credentials` 均为医生自主维护的主页展示内容，不属于 `identity_applications` 或 `private_files` 中的平台认证材料，不得据此展示“平台已核验”。

### 4.2 只读响应字段

以下字段可随响应返回，但不得出现在更新请求 DTO 中：

- 标识：`id`、`userId`
- 机构关系：`institutionId`、`institutionName`、`institutions`、`primaryInstitution`、`institutionCount`
- 平台与业务统计：`rating`、`reviewCount`、`isVerified`、`consultationCount`、`caseCount`

医生更新公开档案时，以上字段必须保留数据库现值。机构变更继续走机构关系申请流程，认证状态继续由身份审核流程维护。

## 5. 鉴权与数据流

1. Spring Security 要求请求携带有效 Bearer Token。
2. `ManagementAccessService.actor(authentication)` 从当前用户和实时 `ACTIVE` 专业角色构建管理主体。
3. 仅当主体具有 `ACTIVE DOCTOR` 且存在对应医生档案时，允许访问本人档案。
4. 服务端使用主体中的 `doctorId` 查询和更新 `doctors` 记录。
5. 更新只覆盖医生可编辑字段，并清理 `doctors` 与 `discover` 缓存。
6. 响应组装医生档案及已批准机构摘要。

普通用户、顾问、仅具机构法人身份的用户以及已撤销/待审核医生均不能访问。机构法人不能代替医生修改档案。

## 6. 接口契约

所有响应沿用项目统一的 `BaseResponse<T>`：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

### 6.1 查询本人医生档案

`GET /api/management/doctor-profile`

请求头：

```http
Authorization: Bearer <access-token>
```

成功响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "doctor-user-uuid",
    "userId": "doctor-user-uuid",
    "name": "王医生",
    "title": "主任医师",
    "bio": "从事医疗美容临床工作 15 年。",
    "avatar": "https://cdn.example.com/doctor/avatar.jpg",
    "contactPhone": "13800000000",
    "specialties": "玻尿酸,肉毒素,注射美容",
    "credentials": "医师资格及执业经历说明",
    "credentialImages": "https://cdn.example.com/doctor/cert-1.jpg",
    "certificationTags": "主任医师,注射美容",
    "institutionId": "institution-uuid-1",
    "institutionName": "示例医疗美容机构",
    "institutions": [
      {"id": "institution-uuid-1", "name": "示例医疗美容机构"}
    ],
    "primaryInstitution": {
      "id": "institution-uuid-1",
      "name": "示例医疗美容机构"
    },
    "institutionCount": 1,
    "rating": 4.9,
    "reviewCount": 86,
    "isVerified": true,
    "consultationCount": 120,
    "caseCount": 35
  }
}
```

### 6.2 更新本人医生档案

`PUT /api/management/doctor-profile`

PUT 采用完整替换语义：客户端必须提交全部九个可编辑字段。除 `name` 外的字段允许传空字符串。请求中即使出现平台字段，也不参与 DTO 绑定或更新，服务端始终保留其数据库现值。

请求体：

```json
{
  "name": "王医生",
  "title": "主任医师",
  "bio": "从事医疗美容临床工作 15 年。",
  "avatar": "https://cdn.example.com/doctor/avatar.jpg",
  "contactPhone": "13800000000",
  "specialties": "玻尿酸,肉毒素,注射美容",
  "credentials": "医师资格及执业经历说明",
  "credentialImages": "https://cdn.example.com/doctor/cert-1.jpg",
  "certificationTags": "主任医师,注射美容"
}
```

成功响应与查询接口的 `data` 结构一致，并返回更新后的完整档案。

### 6.3 错误语义

| HTTP 状态 | `code` | 场景 | `message` |
|---|---:|---|---|
| 400 | 400 | JSON 结构错误、缺少完整 PUT 字段或可编辑字段格式非法 | 明确指出非法字段 |
| 401 | 401 | Token 缺失、失效或凭据版本无效 | `登录状态已失效，请重新登录` |
| 403 | 403 | 当前主体不是 ACTIVE DOCTOR | `当前账号没有有效医生身份` |
| 404 | 404 | ACTIVE DOCTOR 对应档案不存在 | `医生档案不存在` |

接口必须同时设置真实 HTTP 状态和 `BaseResponse.code`，避免只在业务体中返回错误码。

## 7. 后端结构

- 新增独立的 `DoctorProfileController`，挂载在 `/api/management/doctor-profile`。
- 新增医生本人档案请求 DTO，禁止复用 `DoctorAdminRequest`。
- 复用医生查询/保存、机构摘要与缓存清理能力；响应 DTO 可提取为医生档案共享响应模型，避免复制字段映射。
- 控制器只负责编排鉴权、输入和响应；医生本人字段更新逻辑放入聚焦的 service 方法，便于单元测试。
- 不修改数据库表，不新增 migration。

## 8. Flutter VO 与页面

Flutter 将原 `ManagedDoctorProfile` 重命名或替换为语义明确的本人档案模型：

- `DoctorSelfProfile`：解析完整响应，包括可编辑字段和只读平台字段。
- `DoctorSelfProfileUpdate`：只序列化九个可编辑字段，不包含 `id`、`userId` 或平台字段。
- Repository 使用 `GET /management/doctor-profile` 返回单对象，使用 `PUT /management/doctor-profile` 更新。

医生档案页面：

- 直接加载单个 `DoctorSelfProfile`，不再从数组取第一项。
- 保留姓名、职称、简介、公开联系电话、擅长项目、公开资历和展示标签编辑。
- 增加头像和主页展示材料上传/删除入口，上传复用 `POST /api/upload` 的 `data.url`。
- 保存时提交完整九字段更新对象。
- 将公开材料文案统一为“医生上传的证书图片/展示材料”，移除“资质保险箱”“查资质”和任何平台核验暗示。
- 评分、评价数、认证状态、机构关系及业务统计只读展示或不在编辑表单展示。

## 9. 测试与验收

后端最小相关测试覆盖：

- ACTIVE DOCTOR 查询本人档案成功并返回单对象。
- ACTIVE DOCTOR 更新九个公开字段成功。
- 更新后平台字段和机构关系保持不变。
- 未登录返回 401。
- 普通用户、顾问、仅机构法人以及非 ACTIVE DOCTOR 返回 403。
- 医生身份存在但档案缺失返回 404。
- 缺少完整 PUT 字段、空姓名及非法字段格式返回 400。
- 请求体尝试携带平台字段时不会修改对应数据库字段。

Flutter 最小相关测试覆盖：

- 查询响应正确解析为单个 `DoctorSelfProfile`。
- 更新 VO 只序列化九个可编辑字段。
- 页面加载、编辑、保存成功及失败提示。
- 头像和主页展示材料上传后进入更新请求。
- 页面不显示“资质保险箱”“查资质”或“平台已核验”等误导文案。

完成后先运行后端相关测试类，再运行 Flutter 相关单元/Widget 测试和 `flutter analyze`；相关测试通过后，每个技术栈最多执行一次全量测试，并遵守十分钟停止规则。

## 10. 兼容与后续工作

- 现有 `/api/admin/doctors` 接口本阶段不删除，平台管理员和旧客户端不受影响。
- Flutter 专业管理中心迁移完成后，不再调用旧的医生本人 GET/PUT 链路。
- 医生档案切片验收后，以相同模板继续梳理医生其他操作、机构法人操作和顾问操作，逐项形成规范接口文档、VO 与页面适配。
