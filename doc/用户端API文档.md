# 娇颜颂 API 文档

> Base URL: `http://localhost:8080`
> 服务端口：8080
> 认证方式：JWT Bearer Token

---

## 一、概述

### 1.1 统一响应格式（BaseResponse）

所有接口返回统一的 `BaseResponse` 格式：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| code | Int | 200=成功，400=请求错误，404=未找到，500=服务器错误 |
| message | String | 提示信息 |
| data | Any? | 响应数据，失败时为 null |

### 1.2 认证鉴权规则

| 类型 | 路径匹配 | 说明 |
|------|----------|------|
| 公开接口 | `/api/auth/**` | 无需认证 |
| 公开接口 | `/api/home/**` | 无需认证 |
| 公开接口 | `/api/discover/**` | 无需认证 |
| 公开接口 | `/images/**` | 静态图片资源 |
| 公开接口 | `/actuator/**` | 健康检查 |
| ADMIN 角色 | `/api/admin/**` | 需要 JWT + `ROLE_ADMIN` |
| 需认证 | 其余所有接口 | 需要 JWT Bearer Token |

需要认证的接口，请求头需携带：

```
Authorization: Bearer <token>
```

### 1.3 认证用户身份获取

认证接口通过 `Authentication.principal` 获取当前用户 ID（String 类型的 UUID）。

---

## 二、认证模块 `/api/auth`

> 所有接口均 **无需认证**。

### 2.1 POST /api/auth/login

**描述：** 手机号密码登录

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号 |
| password | String | 是 | 密码（明文） |

**响应 data：**

```json
{
  "token": "eyJhbGciOiJIUzM4NCJ9...",
  "user": {
    "id": "uuid",
    "phone": "13800138000",
    "email": null,
    "nickname": "小美",
    "avatar": "",
    "gender": "",
    "city": "上海",
    "bio": "",
    "birthday": ""
  }
}
```

---

### 2.2 POST /api/auth/register

**描述：** 手机号注册

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号 |
| code | String | 是 | 验证码 |
| password | String | 是 | 密码 |

**响应 data：** 同登录响应（返回 token + user 信息）。

---

### 2.3 POST /api/auth/send-code

**描述：** 发送验证码

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号 |

**响应 data（已启用短信服务时）：**

```json
{
  "message": "验证码已发送"
}
```

**响应 data（开发环境 / 短信服务未启用时）：**

```json
{
  "message": "验证码已发送",
  "code": "123456"
}
```

> 生产环境启用阿里云短信服务后不再返回 `code` 字段；开发环境未配置短信时仍会返回验证码便于调试。

---

### 2.4 POST /api/auth/login-with-code

**描述：** 验证码登录（自动注册）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号 |
| code | String | 是 | 验证码 |

**响应 data：** 同登录响应。

---

### 2.5 POST /api/auth/login-with-google

**描述：** Google 登录（自动注册）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| idToken | String | 是 | Google ID Token |

**响应 data：**

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "uuid",
    "phone": null,
    "email": "user@gmail.com",
    "nickname": "John Doe",
    "avatar": "https://lh3.googleusercontent.com/...",
    "gender": "",
    "city": "",
    "bio": "",
    "birthday": ""
  }
}
```

> 未注册的 Google 账号将自动注册，nickname 取自 Google 账号姓名，avatar 取自 Google 头像。

---

### 2.6 POST /api/auth/reset-password

**描述：** 通过手机号+验证码重置密码（无需登录）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号（含国际区号，如 +8613800138000） |
| code | String | 是 | 6 位验证码 |
| newPassword | String | 是 | 新密码 |

**响应 data：**

```json
{
  "message": "密码重置成功"
}
```

**错误响应：**
- 400：验证码无效或已过期、该手机号未注册

---

### 2.7 GET /api/auth/check-phone-registered

**描述：** 检查手机号是否已注册（用于重置密码前校验）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 手机号（含国际区号） |

**响应 data：**

```json
{
  "registered": true
}
```

---

## 三、用户模块 `/api/user`

> 需要 Bearer Token 认证。

### 3.1 GET /api/user/profile

**描述：** 获取当前用户资料

**请求参数：** 无

**响应 data：**

```json
{
  "id": "uuid",
  "phone": "13800138000",
  "email": null,
  "nickname": "小美",
  "avatar": "",
  "gender": "",
  "city": "上海",
  "bio": "",
  "birthday": "",
  "hasPassword": true
}
```

> `hasPassword` 表示用户是否已设置密码。验证码注册的新用户该字段为 `false`。

---

### 3.2 PUT /api/user/profile

**描述：** 更新当前用户资料

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| nickname | String? | 否 | 昵称 |
| avatar | String? | 否 | 头像 URL |
| gender | String? | 否 | 性别（MALE/FEMALE/OTHER） |
| city | String? | 否 | 城市 |
| bio | String? | 否 | 个人简介 |
| birthday | String? | 否 | 生日（yyyy-MM-dd） |

> 仅传递需要更新的字段，未传递的字段保持不变。

**响应 data：** 更新后的用户资料（同 GET /api/user/profile）。

---

### 3.3 PUT /api/user/avatar

**描述：** 单独更新头像

**请求体：**

```json
{
  "avatar": "https://example.com/avatar.jpg"
}
```

**响应 data：** 更新后的用户资料。

---

### 3.4 PUT /api/user/password

**描述：** 修改密码（需已登录，且已设置密码）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| oldPassword | String | 是 | 当前密码 |
| newPassword | String | 是 | 新密码 |

**响应 data：**

```json
{
  "message": "密码修改成功"
}
```

**错误响应：**
- 400：旧密码不正确、尚未设置密码

---

### 3.5 PUT /api/user/password/set

**描述：** 未设置密码的用户通过手机号验证码设置密码（需登录）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| phone | String | 是 | 账号关联的手机号（含国际区号） |
| code | String | 是 | 6 位验证码 |
| newPassword | String | 是 | 新密码 |

**响应 data：**

```json
{
  "message": "密码设置成功"
}
```

**错误响应：**
- 400：验证码无效或已过期、手机号与账号关联手机号不一致、已设置密码

---

### 3.6 DELETE /api/user/account

**描述：** 注销账号（永久删除用户数据，不可恢复）

**请求参数：** 无

**响应 data：**

```json
{
  "message": "账号已注销"
}
```

> 注销后该用户的所有数据（订单、日记、收藏等）将被清除，Token 同时失效。
>
> **注销副作用：**
> - 用户日记的 `author_name` 更新为 `"已注销用户"`，`author_avatar` 清空
> - 用户评论的 `userName` / `replyToUserName` 在查询时返回 `"已注销用户"`（用户表 `@Where` 过滤已删除用户，查询结果为 null 时 fallback）

---

## 四、首页模块 `/api/home`

> 所有接口 **无需认证**。

### 4.1 GET /api/home/banners

**描述：** 获取 Banner 列表（按 sort_order 升序）

**响应 data：** BannerEntity 数组

```json
[
  {
    "id": "uuid",
    "title": "新客专享",
    "subtitle": "首次到店即享 8 折优惠",
    "imageUrl": "",
    "accentColor": "#E8A0BF",
    "sortOrder": 1
  }
]
```

---

### 4.2 GET /api/home/hot-projects

**描述：** 获取热门项目列表

**响应 data：** ProjectEntity 数组

---

### 4.3 GET /api/home/expert-articles

**描述：** 获取专家文章列表

**响应 data：** ArticleEntity 数组

---

### 4.4 GET /api/home/user-diaries

**描述：** 获取用户日记列表

**响应 data：** DiaryEntity 数组

---

### 4.5 GET /api/home/recommended-institution-projects

**描述：** 获取首页推荐机构项目列表（按销量倒序返回前 8 个，合并项目模板信息）

**响应 data：** RecommendedInstitutionProjectDto 数组

```json
[
  {
    "institutionProjectId": "uuid",
    "institutionId": "uuid",
    "projectId": "uuid",
    "projectName": "玻尿酸填充",
    "institutionName": "上海娇颜颂医美中心",
    "price": 2980,
    "originalPrice": 3980,
    "coverImage": "url",
    "category": "注射美容",
    "salesCount": 120
  }
]
```

> 价格字段（`price`、`originalPrice`）为 BigDecimal 精确数字，非浮点数。

---

## 五、发现模块 `/api/discover`

> 所有接口 **无需认证**。

### 5.1 GET /api/discover/filter-options

**描述：** 获取筛选选项（类别/标签/城市）

**响应 data：**

```json
{
  "categories": ["注射美容", "眼部整形", "皮肤管理", "抗衰紧致", "鼻部整形"],
  "tags": ["玻尿酸", "填充", "塑形", "水光针", "补水", "嫩肤", "双眼皮", "皮秒", "祛斑", "美白", "热玛吉", "抗衰", "紧致", "鼻综合", "隆鼻"],
  "cities": ["上海", "北京", "深圳", "广州"]
}
```

---

### 5.1a GET /api/discover/projects

**描述：** 项目列表（含机构项目，支持分类/城市/关键词/标签筛选）

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| categories | String | 否 | "" | 项目分类（逗号分隔，精确匹配，支持多选） |
| cities | String | 否 | "" | 城市筛选（逗号分隔，精确匹配机构城市；同时过滤每个项目下不符合城市的嵌套机构项目） |
| query | String | 否 | "" | 关键词（项目名称/分类模糊搜索） |
| tags | String | 否 | "" | 标签筛选（逗号分隔，精确匹配，支持多选） |

**响应 data：** ProjectWithInstitutionsResponse 数组

```json
[
  {
    "id": "xxx",
    "name": "玻尿酸填充",
    "category": "注射美容",
    "description": "...",
    "tags": "玻尿酸,填充,塑形",
    "categoryTags": "注射美容,填充",
    "coverImage": "...",
    "images": "...",
    "referencePrice": 3800,
    "slogan": "...",
    "detailContent": null,
    "salesCount": 80,
    "rating": 4.8,
    "reviewCount": 126,
    "institutionProjects": [
      {
        "id": "ip-1",
        "institutionId": "inst-1",
        "institutionName": "上海娇颜颂医美中心",
        "institutionCity": "上海",
        "projectId": "proj-1",
        "price": 3800,
        "originalPrice": 5800,
        "coverImage": "...",
        "images": "...",
        "salesCount": 80,
        "isActive": true
      }
    ]
  }
]
```

> 价格字段（`referencePrice`、`price`、`originalPrice`）均为 BigDecimal 精确数字。

---

### 5.3 GET /api/discover/diaries

**描述：** 日记列表（支持关键词搜索）

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| query | String | 否 | "" | 关键词（标题/标签模糊搜索） |

**响应 data：** DiaryEntity 数组

---

### 5.4 GET /api/discover/doctors

**描述：** 医生列表（支持关键词搜索）

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| query | String | 否 | "" | 关键词（姓名/专业领域模糊搜索） |

**响应 data：** DoctorEntity 数组

```json
[
  {
    "id": "uuid",
    "name": "王医生",
    "title": "主任医师",
    "bio": "从事医美行业 15 年",
    "avatar": "",
    "institutionId": "uuid",
    "institutionName": "上海娇颜颂医美中心",
    "rating": 4.9,
    "reviewCount": 86,
    "specialties": "玻尿酸,肉毒素,注射美容",
    "isVerified": true,
    "consultationCount": 120,
    "credentials": "资质证书说明",
    "credentialImages": "url1,url2",
    "caseCount": 50,
    "certificationTags": "中华医学会会员,国际认证医师"
  }
]
```

---

### 5.5 GET /api/discover/institutions

**描述：** 机构列表（支持关键词搜索）

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| query | String | 否 | "" | 关键词（名称/城市模糊搜索） |

**响应 data：** InstitutionEntity 数组

```json
[
  {
    "id": "uuid",
    "name": "上海娇颜颂医美中心",
    "address": "上海市静安区南京西路 1266 号",
    "city": "上海",
    "description": "高端医美连锁品牌",
    "coverImage": "",
    "rating": 4.8,
    "reviewCount": 256,
    "isVerified": true,
    "images": "url1,url2",
    "projectCount": 12,
    "doctorCount": 5,
    "consultationCount": 800,
    "credentials": "医疗机构执业许可证",
    "credentialImages": "url1,url2",
    "specialties": "注射美容,皮肤管理,眼部整形",
    "establishedYear": 2010,
    "certificationTime": "2020-01",
    "userCount": 5000,
    "tags": "连锁品牌,高端定制",
    "contactPhone": "021-12345678",
    "businessHours": "09:00-21:00",
    "caseCount": 3000
  }
]
```

---

### 5.6 GET /api/discover/articles

**描述：** 文章列表（支持关键词搜索）

**请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| query | String | 否 | "" | 关键词（标题/作者名模糊搜索） |

**响应 data：** ArticleEntity 数组

---

### 5.7 GET /api/discover/projects/{id}

**描述：** 项目详情（包含关联机构项目、日记信息）

**路径参数：** `id` — 项目 ID

**响应 data：** 项目详情对象（ProjectDetailDto），404 时返回错误

> **机构项目详情规则：** `institutionProjects[].institutionProject` 是关联记录及其原始覆盖字段，字段可以为 `null`；其中的 `project` 为已合并后的有效项目详情。对名称、分类、简介、评分、评价数、标签、宣传语、详情内容、封面和图集，优先使用机构项目配置，未配置（`null` 或空白）时回退关联项目模板。客户端展示机构项目时应使用有效详情，不应以原始空字段覆盖模板内容。

```json
{
  "project": {
    "id": "uuid",
    "name": "玻尿酸填充",
    "coverImage": "",
    "category": "注射美容",
    "description": "进口玻尿酸，塑形自然持久。",
    "rating": 4.8,
    "reviewCount": 62,
    "tags": "玻尿酸,填充,微整",
    "createdAt": "2026-07-24T10:30:00",
    "images": "url1,url2",
    "referencePrice": 2999,
    "slogan": "自然塑形，持久美丽",
    "detailContent": "<html>...</html>",
    "salesCount": 128,
    "categoryTags": "注射美容,填充"
  },
  "institutionProjects": [
    {
      "institutionProject": {
        "id": "uuid",
        "institutionId": "uuid",
        "projectId": "uuid",
        "price": 2999,
        "originalPrice": 3999,
        "coverImage": "",
        "images": "url1,url2",
        "salesCount": 50,
        "isActive": true,
        "createdAt": "2026-07-24T10:30:00"
      },
      "institution": { "id": "uuid", "name": "上海娇颜颂医美中心", "..." : "..." }
    }
  ],
  "diaries": [
    { "id": "uuid", "title": "我的水光针体验", "..." : "..." }
  ]
}
```

> 价格字段（`referencePrice`、`price`、`originalPrice`）均为 BigDecimal 精确数字。

> 404 时：
> ```json
> { "code": 404, "message": "Project not found", "data": null }
> ```

---

### 5.8 GET /api/discover/diaries/{id}

**描述：** 日记详情

**路径参数：** `id` — 日记 ID

**响应 data：** DiaryEntity 对象

```json
{
  "id": "uuid",
  "title": "我的水光针体验",
  "userId": "uuid",
  "authorName": "小美",
  "authorAvatar": "",
  "content": "今天去做了水光针...",
  "coverImage": "",
  "images": "url1,url2",
  "likeCount": 15,
  "commentCount": 3,
  "tags": "水光针,体验",
  "publishDate": "2026-07-24",
  "status": "published",
  "doctorId": "uuid",
  "projectId": "uuid",
  "institutionId": "uuid",
  "institutionProjectId": "uuid",
  "beforeImages": "url1",
  "afterImages": "url2",
  "orderId": "uuid",
  "rating": 5
}
```

---

### 5.8a GET /api/discover/institution-projects/{institutionProjectId}/doctors

**描述：** 查询某机构项目关联的医生列表

**路径参数：** `institutionProjectId` — 机构项目 ID

**响应 data：** DoctorEntity 数组

```json
[
  {
    "id": "uuid",
    "name": "王医生",
    "title": "主任医师",
    "bio": "从事医美行业 15 年",
    "avatar": "",
    "institutionId": "uuid",
    "institutionName": "上海娇颜颂医美中心",
    "rating": 4.9,
    "reviewCount": 86,
    "specialties": "玻尿酸,肉毒素,注射美容",
    "isVerified": true,
    "consultationCount": 120,
    "credentials": "资质证书说明",
    "credentialImages": "url1,url2",
    "caseCount": 50,
    "certificationTags": "中华医学会会员,国际认证医师"
  }
]
```

---

### 5.9 GET /api/discover/doctors/{id}

**描述：** 医生详情（包含关联机构项目、日记、机构信息）

> **多机构字段：** 响应中的 `institution` 保持为医生主展示机构，以兼容旧客户端；新增 `institutions` 数组返回该医生全部有效出诊机构，主机构排在首位。机构详情和机构下医生列表均按医生—机构关联关系查询。具体可预约项目仍以 `institutionProjects` 内的机构项目绑定为准。

**路径参数：** `id` — 医生 ID

**响应 data：** 医生详情对象（DoctorDetailDto），404 时返回错误

```json
{
  "doctor": {
    "id": "uuid",
    "name": "王医生",
    "title": "主任医师",
    "bio": "从事医美行业 15 年",
    "avatar": "",
    "institutionId": "uuid",
    "institutionName": "上海娇颜颂医美中心",
    "rating": 4.9,
    "reviewCount": 86,
    "specialties": "玻尿酸,肉毒素,注射美容",
    "isVerified": true,
    "consultationCount": 120,
    "credentials": "资质证书说明",
    "credentialImages": "url1,url2",
    "caseCount": 50,
    "certificationTags": "中华医学会会员,国际认证医师"
  },
  "institutionProjects": [
    {
      "institutionProjectId": "uuid",
      "projectId": "uuid",
      "projectName": "玻尿酸填充",
      "institutionId": "uuid",
      "institutionName": "上海娇颜颂医美中心",
      "price": 2999,
      "originalPrice": 3999,
      "coverImage": "",
      "salesCount": 50
    }
  ],
  "diaries": [
    { "id": "uuid", "title": "我的水光针体验", "..." : "..." }
  ],
  "institution": {
    "id": "uuid",
    "name": "上海娇颜颂医美中心",
    "..." : "..."
  }
}
```

> **注意：** `institutionProjects` 替代了原来的 `projects` 字段，返回 `DoctorInstitutionProjectInfo` 列表，包含机构项目 ID、项目名、机构名、价格等完整信息。价格字段为 BigDecimal 精确数字。

---

### 5.10 GET /api/discover/institutions/{id}

**描述：** 机构详情（嵌套 InstitutionDetailDto，含项目、医生、日记、评价列表）

**路径参数：** `id` — 机构 ID

**响应 data（InstitutionDetailDto）：** 404 时返回错误

```json
{
  "institution": {
    "id": "uuid",
    "name": "上海娇颜颂医美中心",
    "address": "上海市静安区南京西路 1266 号",
    "city": "上海",
    "description": "高端医美连锁品牌",
    "coverImage": "",
    "rating": 4.8,
    "reviewCount": 256,
    "isVerified": true,
    "images": "url1,url2",
    "projectCount": 12,
    "doctorCount": 5,
    "consultationCount": 800,
    "credentials": "医疗机构执业许可证",
    "establishedYear": 2010,
    "certificationTime": "2020-01",
    "userCount": 5000,
    "credentialImages": "url1,url2",
    "specialties": "注射美容,皮肤管理,眼部整形",
    "tags": "连锁品牌,高端定制",
    "contactPhone": "021-12345678",
    "businessHours": "09:00-21:00",
    "caseCount": 3000
  },
  "projects": [
    {
      "institutionProjectId": "uuid",
      "projectId": "uuid",
      "projectName": "玻尿酸填充",
      "price": 2980,
      "originalPrice": 3980,
      "coverImage": "url",
      "description": "玻尿酸填充项目简介",
      "category": "注射美容",
      "categoryTags": "玻尿酸,填充",
      "salesCount": 120
    }
  ],
  "doctors": [
    { "id": "uuid", "name": "王医生", "..." : "..." }
  ],
  "diaries": [
    { "id": "uuid", "title": "我的水光针体验", "..." : "..." }
  ],
  "reviews": [
    { "id": "uuid", "rating": 5, "content": "效果很好", "..." : "..." }
  ]
}
```

> 404 时：
> ```json
> { "code": 404, "message": "Institution not found", "data": null }
> ```

> 价格字段（`price`、`originalPrice`）均为 BigDecimal 精确数字。

---

### 5.11 GET /api/discover/articles/{id}

**描述：** 文章详情

**路径参数：** `id` — 文章 ID

**响应 data：** ArticleEntity 对象

---

### 5.12 GET /api/discover/institutions/{institutionId}/projects/{projectId}

**描述：** 机构项目详情（包含项目模板、机构信息、关联日记）

**路径参数：**
- `institutionId` — 机构 ID
- `projectId` — 项目 ID

**响应 data：** 机构项目详情对象，404 时返回错误

> **详情字段来源：** `institutionProject` 保留原始机构项目配置（可空），`project` 返回已按“机构项目优先、项目模板回退”规则合并后的有效详情。接口调用方应以 `project` 的名称、分类、简介、评分、评价数、标签、宣传语、详情内容和图片字段作为展示内容。

```json
{
  "institutionProject": {
    "id": "uuid",
    "institutionId": "uuid",
    "projectId": "uuid",
    "price": 2999,
    "originalPrice": 3999,
    "coverImage": "",
    "images": "url1,url2",
    "salesCount": 50,
    "isActive": true,
    "createdAt": "2026-07-24T10:30:00"
  },
  "project": {
    "id": "uuid",
    "name": "玻尿酸填充",
    "coverImage": "",
    "category": "注射美容",
    "description": "进口玻尿酸，塑形自然持久。",
    "rating": 4.8,
    "reviewCount": 62,
    "tags": "玻尿酸,填充,微整",
    "referencePrice": 2999,
    "slogan": "自然塑形，持久美丽",
    "detailContent": "<html>...</html>",
    "salesCount": 128,
    "categoryTags": "注射美容,填充"
  },
  "institution": { "id": "uuid", "name": "上海娇颜颂医美中心", "..." : "..." },
  "diaries": [
    { "id": "uuid", "title": "我的水光针体验", "..." : "..." }
  ]
}
```

> 404 时：
> ```json
> { "code": 404, "message": "Institution project not found", "data": null }
> ```

> 价格字段（`price`、`originalPrice`、`referencePrice`）均为 BigDecimal 精确数字。

---

### 5.13 GET /api/discover/institutions/{id}/projects

**描述：** 获取指定机构下的项目列表

**路径参数：** `id` — 机构 ID

**响应 data：** InstitutionProjectEntity 数组（带项目信息）

---

### 5.14 GET /api/discover/institutions/{id}/diaries

**描述：** 获取指定机构相关的日记列表

**路径参数：** `id` — 机构 ID

**响应 data：** DiaryEntity 数组

---

### 5.15 GET /api/discover/institutions/{id}/doctors

**描述：** 获取指定机构下的医生列表

**路径参数：** `id` — 机构 ID

**响应 data：** DoctorEntity 数组

---

## 六、日记模块 `/api/diaries`

> 需要 Bearer Token 认证。

### 6.1 GET /api/diaries/my

**描述：** 获取当前用户的日记列表（按发布日期倒序）

**响应 data：** DiaryEntity 数组

---

### 6.2 POST /api/diaries

**描述：** 发布新日记

**请求体（PublishDiaryRequest）：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | String | 是 | 日记标题 |
| content | String | 是 | 日记正文 |
| images | String | 否 | 配图列表（逗号分隔 URL），第一张自动设为封面图 |
| tags | String | 否 | 标签（逗号分隔） |
| rating | Int | 否 | 主观评分（1-5 星），默认 0 |
| doctorId | String | 否 | 关联医生 ID |
| projectId | String | 否 | 关联项目 ID |
| institutionId | String | 否 | 关联机构 ID |
| institutionProjectId | String | 否 | 关联机构项目 ID（自动回填机构和项目信息） |
| orderId | String | 否 | 关联订单 ID（自动关联日记到订单） |
| beforeImages | String | 否 | 术前照片（逗号分隔 URL），若提供则第一张自动设为封面图 |
| afterImages | String | 否 | 术后照片（逗号分隔 URL） |

**响应 data：** 创建的 DiaryEntity 对象

```json
{
  "id": "uuid",
  "title": "我的水光针体验",
  "userId": "uuid",
  "authorName": "小美",
  "authorAvatar": "",
  "content": "今天去做了水光针...",
  "coverImage": "https://example.com/img1.jpg",
  "images": "https://example.com/img1.jpg,https://example.com/img2.jpg",
  "tags": "水光针,体验",
  "publishDate": "2026-07-24",
  "status": "published",
  "rating": 5,
  "doctorId": "uuid",
  "projectId": "uuid",
  "institutionId": "uuid",
  "orderId": "uuid",
  "beforeImages": "url1",
  "afterImages": "url2"
}
```

---

### 6.3 PUT /api/diaries/{id}

**描述：** 编辑日记（仅作者本人）

**路径参数：** `id` — 日记 ID

**请求体（UpdateDiaryRequest，所有字段可选，仅传需要更新的字段）：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| title | String? | 否 | 日记标题 |
| content | String? | 否 | 日记正文 |
| images | String? | 否 | 配图列表（逗号分隔 URL） |
| tags | String? | 否 | 标签（逗号分隔） |
| rating | Int? | 否 | 主观评分（1-5 星） |
| doctorId | String? | 否 | 关联医生 ID |
| projectId | String? | 否 | 关联项目 ID |
| institutionId | String? | 否 | 关联机构 ID |
| institutionProjectId | String? | 否 | 关联机构项目 ID（自动回填机构和项目信息） |
| orderId | String? | 否 | 关联订单 ID |
| beforeImages | String? | 否 | 术前照片（逗号分隔 URL） |
| afterImages | String? | 否 | 术后照片（逗号分隔 URL） |

**响应 data：** 更新后的 DiaryEntity 对象

**错误响应：**
- 404：日记不存在
- 403：无权编辑他人日记

---

### 6.4 DELETE /api/diaries/{id}

**描述：** 删除日记（逻辑删除，仅作者本人）

**路径参数：** `id` — 日记 ID

**响应 data：**

```json
{
  "message": "删除日记成功"
}
```

**错误响应：**
- 404：日记不存在
- 403：无权删除他人日记

---

## 七、评论模块 `/api/comments`（含楼中楼回复）

> 需要 Bearer Token 认证。

### 7.1 GET /api/comments?diaryId=xxx

**描述：** 获取指定日记的顶级评论列表（不含楼中楼回复，按时间倒序）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| diaryId | String | 是 | 日记 ID |

**响应 data：** CommentResponse 数组

```json
[
  {
    "id": "uuid",
    "diaryId": "uuid",
    "userId": "uuid",
    "userName": "小美",
    "userAvatar": "",
    "content": "效果真好！",
    "parentId": null,
    "replyToUserId": null,
    "replyToUserName": null,
    "createdAt": "2026-07-24T10:30:00"
  }
]
```

> `userName`、`userAvatar` 通过 JOIN users 表实时获取，`replyToUserName` 仅在回复子评论时非空。

---

### 7.2 GET /api/comments/replies?parentId=xxx

**描述：** 获取某顶级评论的所有楼中楼回复（按时间正序）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| parentId | String | 是 | 父评论 ID |

**响应 data：** CommentResponse 数组（`parentId` 均相同，`replyToUserName` 可能非空）

---

### 7.3 POST /api/comments

**描述：** 发表评论或楼中楼回复

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| diaryId | String | 是 | 日记 ID |
| content | String | 是 | 评论内容 |
| parentId | String? | 否 | 父评论 ID，回复评论时必填（始终指向顶级评论） |
| replyToUserId | String? | 否 | 被回复用户 ID，回复子评论时可选填 |

**响应 data：** CommentResponse 对象

> 发表后日记的 `comment_count` 自动 +1。回复子评论时，`parentId` 始终为顶级评论 ID，`replyToUserId` 为具体被回复人。

---

### 7.4 DELETE /api/comments/{id}

**描述：** 删除评论（逻辑删除，仅作者本人）

**路径参数：** `id` — 评论 ID

**响应 data：**

```json
{
  "message": "删除评论成功",
  "deletedCount": 3
}
```

> 如果删除的是父评论，会级联软删除所有子回复，`deletedCount` 为实际删除总数。删除后日记的 `comment_count` 自动减去 `deletedCount`。

**错误响应：**
- 404：评论不存在
- 403：无权删除他人评论

---

## 八、收藏模块 `/api/favorites`

> 需要 Bearer Token 认证。

### 8.1 GET /api/favorites

**描述：** 获取当前用户所有收藏列表

**响应 data：** FavoriteEntity 数组

---

### 8.2 POST /api/favorites

**描述：** 添加收藏

> 当 targetType 为 "DIARY" 时，会自动同步 `diaries.favorite_count + 1`。

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| targetType | String | 是 | 收藏目标类型（doctor/institution/project/article/diary） |
| targetId | String | 是 | 收藏目标 ID |

**响应 data：**

```json
{
  "message": "收藏成功"
}
```

---

### 8.3 DELETE /api/favorites/{type}/{targetId}

**描述：** 取消收藏

> 当 type 为 "DIARY" 时，会自动同步 `diaries.favorite_count - 1`（最小为 0）。

**路径参数：**
- `type` — 收藏目标类型
- `targetId` — 收藏目标 ID

**响应 data：**

```json
{
  "message": "取消收藏成功"
}
```

---

### 8.4 GET /api/favorites/{type}/{targetId}

**描述：** 检查是否已收藏 & 获取收藏总数

**路径参数：**
- `type` — 收藏目标类型
- `targetId` — 收藏目标 ID

**响应 data（FavoriteResponse）：**

```json
{
  "favorited": true,
  "count": 42
}
```

---

## 九、订单模块 `/api/orders`

> 需要 Bearer Token 认证。

### 9.1 POST /api/orders

**描述：** 创建新订单

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| projectId | String | 是 | 项目 ID |
| doctorId | String | 否 | 医生 ID |
| institutionProjectId | String | 否 | 机构项目 ID（关联具体机构的定价） |
| userCouponId | Long | 否 | 用户优惠券记录 ID（使用优惠券时传入） |
| quantity | Int | 否 | 购买数量，默认 1 |
| remark | String | 否 | 用户备注 |

**响应 data（OrderResponse）：**

```json
{
  "id": "uuid",
  "orderNo": "ORD20260724001",
  "userId": "uuid",
  "projectId": "uuid",
  "institutionId": "uuid",
  "doctorId": "uuid",
  "institutionProjectId": "uuid",
  "projectName": "玻尿酸填充",
  "institutionName": "上海娇颜颂医美中心",
  "coverImage": "",
  "amount": 2999,
  "paidAmount": 0,
  "discountAmount": 0,
  "status": "PENDING_PAYMENT",
  "quantity": 1,
  "remark": "",
  "consultationFee": 25,
  "remainingAmount": 2974,
  "transactionMethod": "",
  "userPhone": "",
  "appointmentTime": null,
  "paymentTime": null,
  "evidenceUrl": null,
  "hasReview": false,
  "refundStatus": "NONE",
  "refundAmount": 0,
  "doctorName": "王医生",
  "createdAt": "2026-07-24T10:30:00",
  "updatedAt": null,
  "price": 2999
}
```

> - `price` 字段通过 `@JsonGetter` 兼容，与 `amount` 值相同（Android 端使用 `price` 字段名）。
> - 金额字段（`amount`、`paidAmount`、`consultationFee`、`remainingAmount`、`refundAmount`、`discountAmount`、`price`）均为 BigDecimal 精确数字。
> - `paidAmount` 为累计已付金额，创建时为 0，随付款累加。
> - `appointmentTime`、`paymentTime` 为 `LocalDateTime` 格式（ISO 8601），未设置时为 `null`。
> - `hasReview` 表示该订单是否已提交评价。

**订单状态枚举：**

| 值 | 说明 |
|----|------|
| `PENDING_PAYMENT` | 待支付面诊金 |
| `CONSULTATION_PAID` | 面诊金已付，待到店 |
| `VERIFIED` | 已到店核验，待付尾款 |
| `BALANCE_PAID` | 全款已付，等待执行 |
| `PENDING_COMPLETION` | 机构申请完成，等待用户确认 |
| `COMPLETED` | 已完成 |
| `PENDING_SETTLEMENT` | 待结算（30 天倒计时） |
| `SETTLED` | 已结算分账 |
| `DISPUTE_MEDIATION` | 纠纷调解中 |
| `CANCELLED` | 已取消 |
| `REFUNDED` | 已退款 |

---

### 9.2 GET /api/orders

**描述：** 获取当前用户订单列表（可按状态筛选）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 按订单状态筛选（PENDING/PAID/COMPLETED 等） |

**响应 data：** OrderResponse 数组

---

### 9.3 GET /api/orders/{id}

**描述：** 获取订单详情（仅限本人订单）

**路径参数：** `id` — 订单 ID

**响应 data：** OrderResponse 对象（404 时返回错误）

---

### 9.4 POST /api/orders/{id}/pay

**描述：** 支付订单

**路径参数：** `id` — 订单 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| method | String | 是 | 支付方式（WECHAT/ALIPAY 等） |

**响应 data：** OrderResponse 对象（更新后的订单信息）

---

### 9.5 POST /api/orders/{id}/refund

**描述：** 申请退款

**路径参数：** `id` — 订单 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| reason | String | 是 | 退款原因 |
| description | String | 否 | 退款说明 |
| evidenceUrl | String | 否 | 凭证图片 URL |

**响应 data：** RefundEntity 对象

---

### 9.6 GET /api/orders/{id}/refund

**描述：** 获取订单退款详情

**路径参数：** `id` — 订单 ID

**响应 data：** RefundEntity 对象，未找到时返回 404

---

### 9.7 POST /api/orders/{id}/cancel-refund

**描述：** 取消订单退款申请（退款状态为 PENDING 或 APPROVED 时允许取消），订单恢复到退款前的原始状态

**路径参数：** `id` — 订单 ID

**响应 data：** 字符串（“退款已取消”）

---

### 9.8 POST /api/orders/{id}/review

**描述：** 提交评价

**路径参数：** `id` — 订单 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| rating | Int | 是 | 评分（1-5） |
| content | String | 是 | 评价内容 |
| tags | String | 否 | 评价标签（逗号分隔） |
| images | String | 否 | 评价图片（逗号分隔 URL） |

**响应 data：** ReviewEntity 对象

---

### 9.9 POST /api/orders/{id}/pay-consultation

**描述：** 支付面诊金，订单状态从 `PENDING_PAYMENT` → `CONSULTATION_PAID`

**路径参数：** `id` — 订单 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| method | String | 是 | 支付方式（WECHAT/ALIPAY 等） |

**响应 data：** OrderResponse 对象（更新后的订单信息）

**错误响应：**
- 400：订单状态不允许支付面诊金
- 404：订单不存在

---

### 9.10 POST /api/orders/{id}/pay-balance

**描述：** 支付尾款，订单状态从 `VERIFIED` → `BALANCE_PAID`

**路径参数：** `id` — 订单 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| method | String | 是 | 支付方式（WECHAT/ALIPAY 等） |

**响应 data：** OrderResponse 对象（更新后的订单信息）

**错误响应：**
- 400：订单状态不允许支付尾款（需先完成到店核验）
- 404：订单不存在

---

### 9.10A DELETE /api/orders/{id}

**描述：** 删除订单（软删除），仅允许删除已结束状态的订单（已取消、已完成、已退款、已结算、待结算）

**路径参数：** `id` — 订单 ID

**响应 data：** 字符串（“订单已删除”）

**错误响应：**
- 400：当前状态不允许删除
- 403：无权操作该订单
- 404：订单不存在

---

### 9.11 POST /api/orders/{id}/verify

**描述：** 到店核验，订单状态从 `CONSULTATION_PAID` → `VERIFIED`，自动生成核销码

**路径参数：** `id` — 订单 ID

**请求参数：** 无

**响应示例：**

```json
{
  "code": 200,
  "message": "核验成功",
  "data": {
    "id": "uuid",
    "orderNo": "JOY20260720090000004",
    "status": "VERIFIED",
    "verifyCode": "VRF20260724001",
    "verifiedAt": "2026-07-24T10:15:00"
  }
}
```

**错误响应：**
- 400：订单状态不允许核验（需先支付面诊金）
- 404：订单不存在

---

### 9.12 POST /api/orders/{id}/request-completion

**描述：** 机构申请完成，订单状态从 `BALANCE_PAID` → `PENDING_COMPLETION`

**路径参数：** `id` — 订单 ID

**请求参数：** 无

**响应示例：**

```json
{
  "code": 200,
  "message": "已申请完成，等待用户确认",
  "data": {
    "id": "uuid",
    "orderNo": "JOY20260722110000005",
    "status": "PENDING_COMPLETION",
    "completionRequestedAt": "2026-07-26T14:00:00"
  }
}
```

**错误响应：**
- 400：订单状态不允许申请完成（需全款已付）
- 404：订单不存在

---

### 9.13 POST /api/orders/{id}/confirm-completion

**描述：** 用户确认完成，订单状态从 `PENDING_COMPLETION` → `COMPLETED`，自动进入 30 天待结算期

**路径参数：** `id` — 订单 ID

**请求参数：** 无

**响应示例：**

```json
{
  "code": 200,
  "message": "已确认完成",
  "data": {
    "id": "uuid",
    "orderNo": "JOY20260722110000005",
    "status": "COMPLETED"
  }
}
```

**错误响应：**
- 400：订单状态不允许确认完成
- 404：订单不存在

---

### 9.14 GET /api/orders/{id}/settlement

**描述：** 查看本人订单的消费者安全结算摘要。此接口不会返回任何专业身份的分账比例、分账金额或钱包余额。

**路径参数：** `id` — 订单 ID

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "settlementId": 1001,
    "orderId": "uuid",
    "currency": "USD",
    "grossTotalPaid": { "minor": 128000, "currency": "USD" },
    "netSettled": { "minor": 115200, "currency": "USD" },
    "state": "PENDING",
    "settlementDueAt": "2026-08-14T14:00:00",
    "settlementCreatedAt": "2026-07-14T14:00:00",
    "releasedAt": null
  }
}
```

**错误响应：**
- 404：订单不存在或不属于当前登录用户
- 409：`SETTLEMENT_NOT_GENERATED`，订单结算记录尚未生成；客户端应展示生成中状态，不应将其视为零金额结算

金额对象始终使用 ISO-4217 三位 `currency` 和整数最小货币单位 `minor`；退款后 `grossTotalPaid` 与 `netSettled` 可能不同。

---

### 9.15 GET /api/wallets/me

**认证：** Bearer JWT，服务端仅从当前认证主体推导可见钱包范围；请求不接受 owner ID，返回的 `walletId` 只是后续账本查询的选择器，不能授予所有权。

**描述：** 返回当前用户可访问的独立专业钱包。医生、顾问与机构法定代表人可分别拥有钱包；同一登录账号的各身份余额绝不汇总。普通用户成功返回空 `wallets`，不会创建 `CUSTOMER` 钱包。

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "currency": "USD",
    "wallets": [{
      "walletId": 101,
      "ownerType": "DOCTOR",
      "ownerId": "doctor-id",
      "displayName": "医生钱包",
      "ownerName": "张医生",
      "pendingMinor": 12000,
      "availableMinor": 85000,
      "frozenMinor": 0
    }]
  }
}
```

所有余额均为 USD 整数最小货币单位；客户端使用服务端提供的 `displayName` 与 `ownerName`，不得从 ID 推断身份名称。

---

### 9.16 GET /api/wallets/me/ledger

**认证：** Bearer JWT。`walletId` 必填且必须属于当前认证主体的可见范围；无权或不存在的钱包返回 403 或 404，且不泄露任何余额或账本数据。

**查询参数：** `walletId`（整数，必填）、`page`（整数，默认 0，最小 0）、`size`（整数，默认 20，服务端限制为 1–100）。账本稳定按 `createdAt` 倒序、再按 `id` 倒序返回。

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "content": [{
      "id": 1001,
      "walletId": 101,
      "entryType": "SETTLEMENT",
      "title": "诊疗收益",
      "description": "订单 JS202608110001",
      "amountMinor": 12000,
      "pendingAfterMinor": 12000,
      "availableAfterMinor": 85000,
      "frozenAfterMinor": 0,
      "currency": "USD",
      "createdAt": "2026-08-11T10:30:00"
    }],
    "page": 0,
    "size": 20,
    "totalElements": 1,
    "totalPages": 1,
    "last": true
  }
}
```

`entryType` 当前可为 `SETTLEMENT`（创建待结算收益）、`RELEASE`（结算到期，将待入账余额转入可用余额）或 `REVERSAL`（退款冲正）。`amountMinor` 为带符号整数：收入为正，退款冲正为负；对 `RELEASE`，请结合三个 `*AfterMinor` 余额快照展示余额桶转移。`title` 和 `description` 是服务端提供的业务文案；客户端不得显示原始操作键或推导隐私归属信息。当前 Wallet 的“提现”仅是客户端提示“提现功能即将开放”，不发送 API 请求，也不代表已支持出金、收款账户、银行、受益人、KYC 或换汇。

---

### 9.15 GET /api/orders/{id}/status-logs

**描述：** 查看订单状态变更日志（审计轨迹）

**路径参数：** `id` — 订单 ID

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "uuid",
      "orderId": "uuid",
      "fromStatus": "",
      "toStatus": "PENDING_PAYMENT",
      "operatorId": "",
      "operatorType": "SYSTEM",
      "remark": "订单创建",
      "createdAt": "2026-07-09T10:30:00"
    },
    {
      "id": "uuid",
      "orderId": "uuid",
      "fromStatus": "PENDING_PAYMENT",
      "toStatus": "CONSULTATION_PAID",
      "operatorId": "uuid",
      "operatorType": "USER",
      "remark": "面诊金支付成功",
      "createdAt": "2026-07-09T10:31:00"
    }
  ]
}
```

**错误响应：**
- 404：订单不存在

---

## 9A、优惠券模块 `/api/coupons`

> 需要 Bearer Token 认证。

### 9A.1 GET /api/coupons/available

**描述：** 查询当前可用的优惠券列表（根据订单金额和项目/机构筛选适用优惠券）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderAmount | BigDecimal | 否 | 订单金额，用于筛选满足门槛的优惠券 |
| projectId | String | 否 | 项目 ID，筛选适用项目的优惠券 |
| institutionId | String | 否 | 机构 ID，筛选适用机构的优惠券 |

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 1,
      "name": "新客专享减 50 元",
      "type": "FIXED",
      "discountValue": 50.00,
      "minOrderAmount": 500.00,
      "applicableProjectIds": "",
      "applicableInstitutionIds": "",
      "totalCount": 1000,
      "claimedCount": 120,
      "validFrom": "2026-07-01T00:00:00",
      "validTo": "2026-12-31T23:59:59",
      "status": "ACTIVE"
    }
  ]
}
```

---

### 9A.2 GET /api/coupons/my

**描述：** 查询当前用户已领取的优惠券列表（可按状态筛选）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| status | String | 否 | 按状态筛选：`UNUSED` / `USED` / `EXPIRED` |

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": 10,
      "userId": "uuid",
      "couponId": 1,
      "status": "UNUSED",
      "usedOrderId": null,
      "usedAt": null,
      "expireAt": "2026-12-31T23:59:59",
      "createdAt": "2026-07-15T10:00:00",
      "coupon": {
        "id": 1,
        "name": "新客专享减 50 元",
        "type": "FIXED",
        "discountValue": 50.00,
        "minOrderAmount": 500.00
      }
    }
  ]
}
```

---

### 9A.3 GET /api/coupons/{id}/discount

**描述：** 计算指定优惠券在当前订单条件下的实际优惠金额

**路径参数：** `id` — 优惠券 ID

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| orderAmount | BigDecimal | 是 | 订单金额 |
| projectId | String | 否 | 项目 ID |
| institutionId | String | 否 | 机构 ID |

**响应示例：**

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "couponId": 1,
    "couponName": "新客专享减 50 元",
    "type": "FIXED",
    "orderAmount": 2999.00,
    "discountAmount": 50.00,
    "finalAmount": 2949.00,
    "applicable": true,
    "reason": ""
  }
}
```

**错误响应：**
- 400：订单金额未达到最低门槛、优惠券不适用于该项目/机构
- 404：优惠券不存在

---

## 十、AI 聊天模块 `/api/chat`

> 需要 Bearer Token 认证。

### 10.1 POST /api/chat/sessions

**描述：** 创建新的聊天会话

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| persona | String | 否 | AI 人设：`BESTIE`（AI闺蜜小颜，默认）/ `CONSULTANT`（AI美学咨询师娇娇）/ `CS`（平台客服小娇） |
| contextType | String | 否 | 上下文类型：`DOCTOR` / `PROJECT` / `GENERAL`（默认） |
| contextId | String | 否 | 上下文关联 ID（医生/项目 ID） |
| title | String | 否 | 会话标题 |

**响应 data（ChatSessionResponse）：**

```json
{
  "id": "uuid",
  "persona": "BESTIE",
  "contextType": "GENERAL",
  "contextId": "",
  "title": "",
  "lastMessage": "",
  "createdAt": "2026-07-24T10:30:00",
  "updatedAt": "2026-07-24T10:30:00"
}
```

---

### 10.2 GET /api/chat/sessions

**描述：** 获取当前用户的会话列表（可按 persona 筛选）

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| persona | String | 否 | 按人设类型筛选（BESTIE / CONSULTANT / CS） |

**响应 data：** ChatSessionResponse 数组

---

### 10.3 POST /api/chat/sessions/{id}/messages

**描述：** 向指定会话发送消息（自动获取 AI 回复）

**路径参数：** `id` — 会话 ID

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| content | String | 是 | 消息内容 |

**响应 data（ChatMessageResponse）：** AI 回复的消息对象

```json
{
  "id": "uuid",
  "sessionId": "uuid",
  "role": "ASSISTANT",
  "content": "AI 的回复内容...",
  "createdAt": "2026-07-24T10:30:05"
}
```

> 如果会话不存在，返回 404 错误。

---

### 10.4 GET /api/chat/sessions/{id}/messages

**描述：** 获取指定会话的历史消息

**路径参数：** `id` — 会话 ID

**响应 data：** ChatMessageResponse 数组

```json
[
  {
    "id": "uuid",
    "sessionId": "uuid",
    "role": "USER",
    "content": "你好",
    "createdAt": "2026-07-24T10:30:00"
  },
  {
    "id": "uuid",
    "sessionId": "uuid",
    "role": "ASSISTANT",
    "content": "你好呀！有什么可以帮你的吗？",
    "createdAt": "2026-07-24T10:30:05"
  }
]
```

> 如果会话不存在，返回 404 错误。

---

### 10.5 DM（私信）说明

当前 DM（私信）功能为**纯前端模拟**，无后端 API。聊天数据存储在客户端本地（DmChatViewModel），不经过服务端。

前端支持的 DM 功能包括：
- 消息总览页面显示 DM 卡片（点击用户/医生头像进入私信）
- 聊天气泡显示发送者头像（共享 ChatUserAvatar 组件）
- 气泡上方居中显示完整时间（年月日时分格式）
- 长按气泡显示上下文菜单（复制、删除可用；引用/翻译/撤回置灰）
- 消息删除后有新消息时自动恢复卡片（auto-undelete 机制）
- 消息页点击用户/医生头像跳转主页
- DmChatViewModel 采用并行加载提升性能

后续如接入后端 DM 服务，需新增 `/api/dm` 相关端点。

---

## 十ー、文件上传 `/api/upload`

> 需要 Bearer Token 认证。

### 11.1 POST /api/upload

**描述：** 上传文件

**请求类型：** `multipart/form-data`

**请求参数：**

| 参数 | 类型 | 必填 | 说明 |
|------|------|------|------|
| file | MultipartFile | 是 | 上传的文件 |
| folder | String | 否 | 存储文件夹，默认 `general`（如 avatar/banners/institutions） |
| customFileName | String | 否 | 自定义文件名（不含扩展名） |

`folder` 仅允许字母、数字、下划线和连字符（1–64 位）；`customFileName` 仅允许字母、数字、下划线和连字符（1–100 位）。当前仅允许 jpg、jpeg、png、webp、gif 图片，单文件最大 10 MB。服务端会生成安全文件名，禁止路径穿越。

**响应 data：**

```json
{
  "url": "${SERVER_BASE_URL}/images/avatar/xxx.jpg"
}
```

> 生产环境由 `UPLOAD_LOCAL_DIR` 指定实际文件目录，并由 Nginx 直接暴露 `/images/`；本地开发默认目录为 `./data/uploads`。部署配置见 `joysong-server/deploy/README.md`。

---

## 十二、管理后台 `/api/admin`

> 需要 Bearer Token + `ROLE_ADMIN` 角色。

### 12.0 POST /api/admin/login

**描述：** 管理员登录。此接口不携带 Bearer Token，只允许具有 `ADMIN` 角色的账号登录管理后台。

```json
{ "phone": "13800000000", "password": "管理员密码" }
```

成功时返回 `BaseResponse<LoginResponse>`（含 token、用户信息）；账号不存在、密码错误或账号不是管理员时统一返回 `401`，不暴露具体原因。连续失败达到同一账号 5 次/15 分钟，或同一来源地址 20 次/15 分钟时返回 `429`，响应包含 `Retry-After`。前端收到 `401` 或 `403` 时应清理本地会话并回到登录页。

### 12.1 GET /api/admin/stats

**描述：** 获取统计数据

**响应 data：**

```json
{
  "projects": 5,
  "doctors": 4,
  "institutions": 3,
  "articles": 2,
  "diaries": 2,
  "orders": 2,
  "users": 3,
  "banners": 3
}
```

---

### 12.2 项目管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/projects` | 列出所有项目（支持 `?keyword=X` 模糊搜索，返回 ProjectAdminVo 含 `doctorIds`） |
| POST | `/api/admin/projects` | 新建项目（自动生成 UUID，支持多医生关联） |
| PUT | `/api/admin/projects/{id}` | 更新项目（先删旧关联再建新关联） |
| DELETE | `/api/admin/projects/{id}` | 删除项目（逻辑删除） |

**请求体（POST/PUT）：** ProjectRequest 对象

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| name | String | 是 | 项目名称 |
| coverImage | String | 否 | 封面图 URL |
| category | String | 否 | 项目分类 |
| description | String | 否 | 项目描述 |
| rating | BigDecimal | 否 | 评分，默认 4.5 |
| reviewCount | Int | 否 | 评价数，默认 0 |
| tags | String | 否 | 标签（逗号分隔） |
| images | String | 否 | 项目图集 URL（逗号分隔） |
| referencePrice | BigDecimal | 否 | 参考均价，默认 0 |
| slogan | String | 否 | 宣传语 |
| detailContent | String | 否 | 项目详情富文本（HTML 结构化） |
| salesCount | Int | 否 | 销量，默认 0 |
| categoryTags | String | 否 | 分类标签（逗号分隔） |
| **doctorIds** | **List\<String\>** | **否** | **关联医生 ID 列表（写入 doctor_projects 表）** |
| **doctorBindings** | **List\<DoctorProjectBinding\>** | **否** | **医生-机构项目绑定关系（精确关联医生与机构项目）** |

**DoctorProjectBinding 对象：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| doctorId | String | 是 | 医生 ID |
| institutionProjectId | String | 否 | 机构项目 ID（关联到具体机构项目） |

```json
{
  "name": "玻尿酸填充",
  "coverImage": "",
  "category": "注射美容",
  "description": "进口玻尿酸，塑形自然持久。",
  "rating": 4.8,
  "reviewCount": 62,
  "tags": "玻尿酸,填充,微整",
  "referencePrice": 2999,
  "slogan": "自然塑形，持久美丽",
  "detailContent": "<html>...</html>",
  "doctorIds": ["doctor-uuid-1", "doctor-uuid-2"],
  "doctorBindings": [
    { "doctorId": "doctor-uuid-1", "institutionProjectId": "inst-proj-uuid-1" },
    { "doctorId": "doctor-uuid-2", "institutionProjectId": "" }
  ]
}
```

**GET 列表响应额外字段（ProjectAdminVo）：**

| 字段 | 类型 | 说明 |
|------|------|------|
| doctorIds | List\<String\> | 该项目关联的所有医生 ID 列表（从 doctor_projects 表查询） |
| createdAt | LocalDateTime | 创建时间（ISO 8601 格式） |
| referencePrice | BigDecimal | 参考均价（精确数字） |
| rating | BigDecimal | 评分（精确数字） |

---

### 12.3 Banner 管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/banners` | 列出所有 Banner |
| POST | `/api/admin/banners` | 新建 Banner（自动生成 UUID） |
| PUT | `/api/admin/banners/{id}` | 更新 Banner |
| DELETE | `/api/admin/banners/{id}` | 删除 Banner（逻辑删除） |

---

### 12.4 医生管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/doctors` | 列出所有医生（支持 `?keyword=X` 模糊搜索） |
| POST | `/api/admin/doctors` | 新建医生（支持绑定多家机构并指定主展示机构） |
| PUT | `/api/admin/doctors/{id}` | 更新医生 |
| DELETE | `/api/admin/doctors/{id}` | 删除医生（逻辑删除） |

**请求体（POST/PUT）：**

```json
{
  "name": "王医生",
  "title": "主任医师",
  "bio": "从事医美行业 15 年。",
  "avatar": "",
  "institutionIds": ["机构UUID-1", "机构UUID-2"],
  "primaryInstitutionId": "机构UUID-1",
  "rating": 4.9,
  "reviewCount": 86,
  "specialties": "玻尿酸,肉毒素,注射美容",
  "isVerified": true,
  "consultationCount": 0,
  "credentials": "资质说明",
  "credentialImages": "",
  "caseCount": 0,
  "certificationTags": ""
}
```

`institutionIds` 为完整出诊机构列表，`primaryInstitutionId` 必须在该列表中；不传主机构时按列表第一项处理。`institutionId` 仍接受为旧客户端兼容输入，并会转换为单个主机构绑定。响应除兼容字段 `institutionId`、`institutionName` 外，还返回 `institutions`、`primaryInstitution` 与 `institutionCount`。

> 新建/更新医生时，如果 `institutionId` 非空，`institutionName` 会自动从机构表查询填充。

---

### 12.5 机构管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/institutions` | 列出所有机构（支持 `?keyword=X` 模糊搜索） |
| GET | `/api/admin/institutions/{id}` | 机构详情 |
| POST | `/api/admin/institutions` | 新建机构（自动生成 UUID） |
| PUT | `/api/admin/institutions/{id}` | 更新机构 |
| DELETE | `/api/admin/institutions/{id}` | 删除机构（逻辑删除） |
| GET | `/api/admin/institutions/{id}/doctors` | 该机构下的医生列表 |
| GET | `/api/admin/institutions/{id}/projects` | 该机构下的项目列表 |

---

### 12.6 文章管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/articles` | 列出所有文章（支持 `?keyword=X` 模糊搜索） |
| POST | `/api/admin/articles` | 新建文章（自动生成 UUID） |
| PUT | `/api/admin/articles/{id}` | 更新文章 |
| DELETE | `/api/admin/articles/{id}` | 删除文章（逻辑删除） |

---

### 12.7 日记管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/diaries` | 列出所有日记（支持 `?keyword=X` 模糊搜索） |
| POST | `/api/admin/diaries` | 新建日记（自动生成 UUID） |
| PUT | `/api/admin/diaries/{id}` | 更新日记 |
| DELETE | `/api/admin/diaries/{id}` | 删除日记（逻辑删除） |

---

### 12.8 订单管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/orders` | 列出所有订单 |
| PUT | `/api/admin/orders/{id}/status` | 更新订单状态 |
| DELETE | `/api/admin/orders/{id}` | 删除订单（逻辑删除） |

**更新状态请求体：**

```json
{ "status": "COMPLETED" }
```

---

### 12.9 用户管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/users` | 列出所有用户（支持 `?keyword=X` 模糊搜索） |
| GET | `/api/admin/users/{id}` | 用户详情 |
| PUT | `/api/admin/users/{id}/role` | 更新用户角色 |
| PUT | `/api/admin/users/{id}/deactivate` | 禁用用户账号 |
| PUT | `/api/admin/users/{id}/reactivate` | 恢复用户账号 |
| GET | `/api/admin/users/{id}/diaries` | 该用户发布的日记 |
| GET | `/api/admin/users/{id}/orders` | 该用户的所有订单 |

**更新角色请求体：**

```json
{ "role": "ADMIN" }
```

---

### 12.10 机构项目管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/institution-projects` | 列出机构项目（支持 `?projectId=X` 或 `?institutionId=X` 过滤），返回 BaseResponse 包裹，每项含关联医生摘要 |
| POST | `/api/admin/institution-projects` | 新建机构项目，返回 BaseResponse 包裹 |
| PUT | `/api/admin/institution-projects/{id}` | 更新机构项目，返回 BaseResponse 包裹 |
| DELETE | `/api/admin/institution-projects/{id}` | 删除机构项目（逻辑删除），返回 BaseResponse 包裹 |

**请求体（POST/PUT）：** InstitutionProjectRequest 对象

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| institutionId | String | 是 | 机构 ID |
| projectId | String | 是 | 项目模板 ID |
| price | BigDecimal | 是 | 该机构实际售价 |
| originalPrice | BigDecimal? | 否 | 原价（展示折扣） |
| coverImage | String | 否 | 封面图（机构可自定义） |
| images | String | 否 | 图集（机构可自定义，逗号分隔） |
| name | String | 否 | 机构项目独立名称；为空时展示项目模板名称 |
| category | String | 否 | 机构项目独立分类；为空时展示项目模板分类 |
| description | String | 否 | 机构项目独立简介；为空时展示项目模板简介 |
| rating | BigDecimal | 否 | 机构项目独立评分，范围 0–5；为空时展示项目模板评分 |
| reviewCount | Int | 否 | 机构项目独立评价数，不能为负；为空时展示项目模板评价数 |
| tags | String | 否 | 机构项目独立标签（逗号分隔）；为空时展示项目模板标签 |
| slogan | String | 否 | 机构项目独立宣传语；为空时展示项目模板宣传语 |
| detailContent | String | 否 | 机构项目独立详情富文本；为空时展示项目模板详情 |
| salesCount | Int | 否 | 销量，默认 0 |
| isActive | Boolean | 否 | 是否上架，默认 true |
| doctorBindings | List\<DoctorProjectBinding\> | 否 | 医生-机构项目绑定关系（新建/更新时自动同步 doctor_projects 表） |

```json
{
  "institutionId": "uuid",
  "projectId": "uuid",
  "price": 2999,
  "originalPrice": 3999,
  "name": "机构专属玻尿酸填充",
  "description": "本机构定制方案",
  "tags": "玻尿酸,自然填充",
  "coverImage": "",
  "images": "url1,url2",
  "salesCount": 0,
  "isActive": true
}
```

**GET 列表响应（BaseResponse\<List\<InstitutionProjectDto\>\>）：**

```json
{
  "code": 200,
  "message": "success",
  "data": [
    {
      "id": "uuid",
      "institutionId": "uuid",
      "projectId": "uuid",
      "price": 12800,
      "originalPrice": 15000,
      "coverImage": "",
      "images": "url1,url2",
      "salesCount": 100,
      "isActive": true,
      "createdAt": "2026-01-01T00:00:00",
      "doctors": [
        { "id": "d1", "name": "张医生", "title": "主任医师", "avatar": "..." }
      ]
    }
  ]
}
```

**InstitutionProjectDto 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 机构项目 ID |
| institutionId | String | 机构 ID |
| projectId | String | 项目模板 ID |
| projectName | String | 有效项目名称（兼容字段） |
| baseProjectName | String | 原项目模板名称 |
| name/category/description/rating/reviewCount/tags/slogan/detailContent | 可空 | 原始机构项目独立详情覆盖字段；未配置时为 `null` 或空白 |
| effectiveName/effectiveCategory/effectiveDescription/effectiveRating/effectiveReviewCount/effectiveTags/effectiveSlogan/effectiveDetailContent | 非空（detailContent 可空） | 已合并的有效详情，优先机构项目配置，未配置时回退项目模板；后台预览和外部展示应使用这些字段 |
| price | BigDecimal | 机构实际售价 |
| originalPrice | BigDecimal? | 原价 |
| coverImage | String | 封面图 URL |
| images | String | 图集（逗号分隔） |
| effectiveCoverImage | String | 已合并的有效封面图 |
| effectiveImages | String | 已合并的有效图集 |
| salesCount | Int | 销量 |
| isActive | Boolean | 是否上架 |
| createdAt | LocalDateTime | 创建时间（ISO 8601 格式） |
| **doctors** | **List\<DoctorSummary\>** | **关联医生摘要列表（id/name/title/avatar）** |

**POST/PUT/DELETE 响应：** 统一返回 BaseResponse 包裹格式（`code` + `message` + `data`），其中 POST/PUT 的 data 为 InstitutionProjectEntity 对象。

---

### 12.11 举报管理

| 方法 | 路径 | 描述 |
|------|------|------|
| GET | `/api/admin/reports` | 列出举报（支持 `?status=X` 与 `?deleted=true` 过滤） |
| PUT | `/api/admin/reports/{id}/status` | 更新举报状态 |
| DELETE | `/api/admin/reports/{id}` | 软删除举报 |
| PUT | `/api/admin/reports/{id}/restore` | 恢复已删除举报 |
| DELETE | `/api/admin/reports/target/{targetType}/{targetId}` | 删除举报目标内容 |

**GET 列表请求参数：**

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|------|------|------|--------|------|
| status | String | 否 | null | 按状态过滤（pending/resolved/rejected） |
| deleted | Boolean | 否 | false | 是否显示已删除举报 |

**GET 列表响应（ReportAdminVo 数组）：**

```json
[
  {
    "id": "uuid",
    "userId": "uuid",
    "targetType": "diary",
    "targetId": "uuid",
    "reason": "Spam / Advertising",
    "description": null,
    "status": "pending",
    "createdAt": "2026-07-28T22:00:00",
    "deleted": false,
    "target_summary": {
      "id": "uuid",
      "title": "日记标题..."
    }
  }
]
```

**ReportAdminVo 字段说明：**

| 字段 | 类型 | 说明 |
|------|------|------|
| id | String | 举报 ID |
| userId | String | 举报人 ID |
| targetType | String | 目标类型 |
| targetId | String | 目标 ID |
| reason | String | 举报原因 |
| description | String? | 补充说明 |
| status | String | 举报状态（pending/resolved/rejected） |
| createdAt | LocalDateTime | 创建时间（ISO 8601） |
| deleted | Boolean | 是否已软删除 |
| target_summary | Map? | 举报目标摘要（日记/评论/评价内容摘要，可能为 null） |

**更新状态请求体：**

```json
{ "status": "resolved" }
```

---

## 十三、点赞模块 `/api/likes`

> 需要 Bearer Token 认证。

### 13.1 POST /api/likes

**描述：** 点赞（幂等，重复点赞直接返回成功）

**请求体：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| targetType | String | 是 | 点赞目标类型（如 diary） |
| targetId | String | 是 | 点赞目标 ID |

**响应 data：** `null`

> 当 targetType 为 "diary" 时，会自动同步 `diaries.like_count + 1`。

---

### 13.2 DELETE /api/likes/{targetType}/{targetId}

**描述：** 取消点赞

**路径参数：**
- `targetType` — 点赞目标类型
- `targetId` — 点赞目标 ID

**响应 data：** `null`（未找到点赞记录时返回 404）

> 当 targetType 为 "diary" 时，会自动同步 `diaries.like_count - 1`（最小为 0）。

---

### 13.3 GET /api/likes/{targetType}/{targetId}

**描述：** 检查是否已点赞 & 获取点赞总数

**路径参数：**
- `targetType` — 点赞目标类型
- `targetId` — 点赞目标 ID

**响应 data（LikeResponse）：**

```json
{
  "liked": true,
  "count": 42
}
```

---

## 十四、举报模块 `/api/reports`

### 14.1 提交举报

**POST** `/api/reports`  
认证：✅

**请求体：**
```json
{
  "targetType": "diary",
  "targetId": "uuid",
  "reason": "Spam / Advertising",
  "description": "补充说明（可选，选择"其它"时必填）"
}
```

**字段说明：**

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| targetType | String | ✅ | 目标类型：`diary` / `review` / `comment` |
| targetId | String | ✅ | 目标 ID |
| reason | String | ✅ | 举报原因（预设选项） |
| description | String | ❌ | 补充说明（选择"其它"时必填） |

**预设举报原因：**
- `Spam / Advertising` — 垃圾广告
- `Pornographic / Vulgar` — 色情低俗
- `Verbal Abuse / Harassment` — 辱骂攻击
- `False / Misleading Info` — 虚假信息
- `Politically Sensitive` — 涉政敏感
- `Copyright Infringement` — 侵权抄袭
- `Other` — 其它

**响应：**
```json
{
  "code": 200,
  "data": {
    "id": "uuid",
    "userId": "uuid",
    "targetType": "diary",
    "targetId": "uuid",
    "reason": "Spam / Advertising",
    "description": null,
    "status": "pending",
    "createdAt": "2026-07-28T22:00:00"
  }
}
```

> 同一用户对同一目标只允许提交一条举报，重复举报返回 409。

### 14.2 检查是否已举报

**GET** `/api/reports/check/{targetType}/{targetId}`  
认证：✅

**响应：**
```json
{
  "code": 200,
  "data": {
    "reported": true
  }
}
```

---

## 十五、端点汇总

| 模块 | 方法 | 端点 | 认证 |
|------|------|------|------|
| 认证 | POST | `/api/auth/login` | ❌ |
| 认证 | POST | `/api/auth/register` | ❌ |
| 认证 | POST | `/api/auth/send-code` | ❌ |
| 认证 | POST | `/api/auth/login-with-code` | ❌ |
| 认证 | POST | `/api/auth/login-with-google` | ❌ |
| 认证 | POST | `/api/auth/reset-password` | ❌ |
| 认证 | GET | `/api/auth/check-phone-registered` | ❌ |
| 用户 | GET | `/api/user/profile` | ✅ |
| 用户 | PUT | `/api/user/profile` | ✅ |
| 用户 | PUT | `/api/user/avatar` | ✅ |
| 用户 | PUT | `/api/user/password` | ✅ |
| 用户 | PUT | `/api/user/password/set` | ✅ |
| 用户 | DELETE | `/api/user/account` | ✅ |
| 首页 | GET | `/api/home/banners` | ❌ |
| 首页 | GET | `/api/home/hot-projects` | ❌ |
| 首页 | GET | `/api/home/expert-articles` | ❌ |
| 首页 | GET | `/api/home/user-diaries` | ❌ |
| 首页 | GET | `/api/home/recommended-institution-projects` | ❌ |
| 发现 | GET | `/api/discover/filter-options` | ❌ |
| 发现 | GET | `/api/discover/projects` | ❌ |
| 发现 | GET | `/api/discover/diaries` | ❌ |
| 发现 | GET | `/api/discover/doctors` | ❌ |
| 发现 | GET | `/api/discover/institutions` | ❌ |
| 发现 | GET | `/api/discover/articles` | ❌ |
| 发现 | GET | `/api/discover/projects/{id}` | ❌ |
| 发现 | GET | `/api/discover/diaries/{id}` | ❌ |
| 发现 | GET | `/api/discover/doctors/{id}` | ❌ |
| 发现 | GET | `/api/discover/institutions/{id}` | ❌ |
| 发现 | GET | `/api/discover/articles/{id}` | ❌ |
| 发现 | GET | `/api/discover/institutions/{id}/projects` | ❌ |
| 发现 | GET | `/api/discover/institutions/{institutionId}/projects/{projectId}` | ❌ |
| 发现 | GET | `/api/discover/institutions/{id}/diaries` | ❌ |
| 发现 | GET | `/api/discover/institutions/{id}/doctors` | ❌ |
| 发现 | GET | `/api/discover/institution-projects/{institutionProjectId}/doctors` | ❌ |
| 日记 | GET | `/api/diaries/my` | ✅ |
| 日记 | POST | `/api/diaries` | ✅ |
| 日记 | PUT | `/api/diaries/{id}` | ✅ |
| 日记 | DELETE | `/api/diaries/{id}` | ✅ |
| 评论 | GET | `/api/comments?diaryId=xxx` | ✅ |
| 评论 | GET | `/api/comments/replies?parentId=xxx` | ✅ |
| 评论 | POST | `/api/comments` | ✅ |
| 评论 | DELETE | `/api/comments/{id}` | ✅ |
| 收藏 | GET | `/api/favorites` | ✅ |
| 收藏 | POST | `/api/favorites` | ✅ |
| 收藏 | DELETE | `/api/favorites/{type}/{targetId}` | ✅ |
| 收藏 | GET | `/api/favorites/{type}/{targetId}` | ✅ |
| 点赞 | POST | `/api/likes` | ✅ |
| 点赞 | DELETE | `/api/likes/{targetType}/{targetId}` | ✅ |
| 点赞 | GET | `/api/likes/{targetType}/{targetId}` | ✅ |
| 订单 | POST | `/api/orders` | ✅ |
| 订单 | GET | `/api/orders` | ✅ |
| 订单 | GET | `/api/orders/{id}` | ✅ |
| 订单 | POST | `/api/orders/{id}/pay` | ✅ |
| 订单 | POST | `/api/orders/{id}/refund` | ✅ |
| 订单 | GET | `/api/orders/{id}/refund` | ✅ |
| 订单 | POST | `/api/orders/{id}/cancel-refund` | ✅ |
| 订单 | POST | `/api/orders/{id}/review` | ✅ |
| 订单 | POST | `/api/orders/{id}/pay-consultation` | ✅ |
| 订单 | POST | `/api/orders/{id}/pay-balance` | ✅ |
| 订单 | POST | `/api/orders/{id}/verify` | ✅ |
| 订单 | POST | `/api/orders/{id}/request-completion` | ✅ |
| 订单 | POST | `/api/orders/{id}/confirm-completion` | ✅ |
| 订单 | GET | `/api/orders/{id}/settlement` | ✅ |
| 订单 | GET | `/api/orders/{id}/status-logs` | ✅ |
| 优惠券 | GET | `/api/coupons/available` | ✅ |
| 优惠券 | GET | `/api/coupons/my` | ✅ |
| 优惠券 | GET | `/api/coupons/{id}/discount` | ✅ |
| 聊天 | POST | `/api/chat/sessions` | ✅ |
| 聊天 | GET | `/api/chat/sessions` | ✅ |
| 聊天 | POST | `/api/chat/sessions/{id}/messages` | ✅ |
| 聊天 | GET | `/api/chat/sessions/{id}/messages` | ✅ |
| 上传 | POST | `/api/upload` | ✅ |
| 管理 | GET | `/api/admin/stats` | ADMIN |
| 管理 | GET | `/api/admin/projects` | ADMIN |
| 管理 | POST | `/api/admin/projects` | ADMIN |
| 管理 | PUT | `/api/admin/projects/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/projects/{id}` | ADMIN |
| 管理 | GET | `/api/admin/banners` | ADMIN |
| 管理 | POST | `/api/admin/banners` | ADMIN |
| 管理 | PUT | `/api/admin/banners/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/banners/{id}` | ADMIN |
| 管理 | GET | `/api/admin/doctors` | ADMIN |
| 管理 | POST | `/api/admin/doctors` | ADMIN |
| 管理 | PUT | `/api/admin/doctors/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/doctors/{id}` | ADMIN |
| 管理 | GET | `/api/admin/institutions` | ADMIN |
| 管理 | GET | `/api/admin/institutions/{id}` | ADMIN |
| 管理 | POST | `/api/admin/institutions` | ADMIN |
| 管理 | PUT | `/api/admin/institutions/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/institutions/{id}` | ADMIN |
| 管理 | GET | `/api/admin/institutions/{id}/doctors` | ADMIN |
| 管理 | GET | `/api/admin/institutions/{id}/projects` | ADMIN |
| 管理 | GET | `/api/admin/institution-projects` | ADMIN |
| 管理 | POST | `/api/admin/institution-projects` | ADMIN |
| 管理 | PUT | `/api/admin/institution-projects/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/institution-projects/{id}` | ADMIN |
| 管理 | GET | `/api/admin/articles` | ADMIN |
| 管理 | POST | `/api/admin/articles` | ADMIN |
| 管理 | PUT | `/api/admin/articles/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/articles/{id}` | ADMIN |
| 管理 | GET | `/api/admin/diaries` | ADMIN |
| 管理 | POST | `/api/admin/diaries` | ADMIN |
| 管理 | PUT | `/api/admin/diaries/{id}` | ADMIN |
| 管理 | DELETE | `/api/admin/diaries/{id}` | ADMIN |
| 管理 | GET | `/api/admin/orders` | ADMIN |
| 管理 | PUT | `/api/admin/orders/{id}/status` | ADMIN |
| 管理 | DELETE | `/api/admin/orders/{id}` | ADMIN |
| 管理 | GET | `/api/admin/users` | ADMIN |
| 管理 | GET | `/api/admin/users/{id}` | ADMIN |
| 管理 | PUT | `/api/admin/users/{id}/role` | ADMIN |
| 管理 | PUT | `/api/admin/users/{id}/deactivate` | ADMIN |
| 管理 | PUT | `/api/admin/users/{id}/reactivate` | ADMIN |
| 管理 | GET | `/api/admin/users/{id}/diaries` | ADMIN |
| 管理 | GET | `/api/admin/users/{id}/orders` | ADMIN |
| 举报 | POST | `/api/reports` | ✅ |
| 举报 | GET | `/api/reports/check/{targetType}/{targetId}` | ✅ |
| 管理 | GET | `/api/admin/reports` | ADMIN |
| 管理 | PUT | `/api/admin/reports/{id}/status` | ADMIN |
| 管理 | DELETE | `/api/admin/reports/{id}` | ADMIN |
| 管理 | PUT | `/api/admin/reports/{id}/restore` | ADMIN |
| 管理 | DELETE | `/api/admin/reports/target/{targetType}/{targetId}` | ADMIN |
