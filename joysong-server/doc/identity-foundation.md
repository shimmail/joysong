# 多身份第一阶段契约

## 不变原则

- `users` 永远是账户与普通用户身份的来源，普通用户不需要单独申请或删除。
- 职业身份通过 `user_roles` 叠加，账号可同时拥有 `CONSULTANT`、`DOCTOR`、`INSTITUTION_LEGAL_REPRESENTATIVE`、`INSTITUTION_CUSTOMER_SERVICE`。
- 业务权限必须同时检查角色状态、机构成员关系和平台合作状态；不能只检查 JWT 内的单一 `role`。
- 撤销职业身份只撤销对应 `user_roles` / `institution_memberships`，账户仍可作为普通用户登录。

## 审核状态

`PENDING`、`APPROVED`、`REJECTED`、`REVOKED`。每次认证申请写入 `identity_applications`，审核通过后才更新 `user_roles`；不可覆盖原始申请与审核记录。

## 机构关系

`institution_memberships.member_role` 可取 `CONSULTANT`、`CUSTOMER_SERVICE`、`LEGAL_REPRESENTATIVE`。医生在不同机构的执业注册由增强后的 `doctor_institutions` 表表达，避免重复关系；机构确认后状态才是 `APPROVED`。

## 医生档案接口变更

`POST /api/admin/doctors` 的请求体新增且必须传入 `userId`：

```json
{
  "userId": "已认证用户 ID",
  "name": "展示名称",
  "title": "主治医师",
  "institutionIds": ["机构 ID"],
  "primaryInstitutionId": "机构 ID"
}
```

服务端验证用户存在、`DOCTOR` 身份已审核为 `ACTIVE` 且尚未创建医生档案，直接以 `userId` 写入 `doctors.id`。因此医生 ID 与用户 ID 相同，更新接口也不允许改变绑定账户。

## 后续接口

第二次改造添加认证申请、管理审核、当前身份切换、刷新令牌和私有认证材料上传端点。支付渠道接入前，支付回调必须以签名校验和幂等事件表为准，禁止由客户端直接把订单改为已支付。
