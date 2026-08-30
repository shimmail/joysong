# 服务费退款多凭证与管理员安全查看设计

> **状态：** 设计已确认，进入实施
> **分支：** `codex/service-fee-refund-evidence`
> **worktree：** `D:\code\kotlin\joysong\.worktrees\worktree_service_fee_refund_evidence`
> **基线：** `8150f61f`
> **设计日期：** 2026-08-29

## 1. 目标

仅优化 `TRAVEL_GROUND_SERVICE_ONLY` 旅游地接服务费退款凭证能力：

- 用户可为一次退款添加最多 5 个凭证；
- 首版支持 JPG/JPEG、PNG、WebP 和 PDF；
- 单个文件不超过 10 MB；
- 凭证在退款提交前可增删，提交成功后立即冻结；
- 管理员可在退款详情中鉴权预览图片和 PDF，并可下载；
- 凭证使用私有存储，不产生公开 URL；
- 兼容现有 JSON 退款请求和历史 `evidenceUrl` 数据。

## 2. 非目标

- 不改变历史医疗退款的资格、金额、审核或渠道执行逻辑；
- 不支持 Word、Excel、压缩包、音频或视频；
- 不支持退款提交后的补件、替换或撤回凭证；
- 不实现病毒扫描、OCR、缩略图服务或对象存储私有签名 URL；
- 不改变退款金额、原路退回、重试、收入冲正或通知状态机；
- 不在本次确定法定保留期限，已绑定材料随退款审核记录保留。

## 3. 现状与问题

当前用户退款请求提交一个 `evidenceUrl` 字符串。Flutter 只允许选择一张图片，并通过公共媒体上传到 `/images/**` 或公开 OSS URL；第二次选择会覆盖第一次。后端仅按逗号拆分并限制最多 5 项，没有校验 URL 所属用户、文件内容或访问权限。管理端退款接口已返回 `evidenceUrl`，但退款页面没有渲染凭证。

这套格式不适合退款材料：`refunds.evidence_url` 当前只有 500 字符，多个完整 URL 容易溢出；逗号分隔缺少文件名、类型、大小和外键完整性；更重要的是退款凭证可能包含个人或支付信息，不应通过公开 URL 访问。

## 4. 选定方案

采用私有文件与规范化关联表方案。用户在 Flutter 页面本地维护待提交文件列表，提交退款时将业务字段和全部文件作为一次 multipart 请求发送。后端在同一事务内创建退款、保存私有文件并建立关联；任一步失败都回滚数据库并清理本次已写文件，因此不产生服务器端草稿或孤立附件。

现有 JSON 请求继续工作，`evidenceUrl` 仅用于旧客户端和历史数据兼容。新版 Flutter 不再为退款生成公共凭证 URL。

## 5. 数据模型

新增迁移 `V38__add_refund_evidence_files.sql`，创建：

```sql
CREATE TABLE refund_evidence_files (
    file_id VARCHAR(36) NOT NULL,
    refund_id VARCHAR(36) NOT NULL,
    position SMALLINT UNSIGNED NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (file_id),
    UNIQUE KEY uk_refund_evidence_position (refund_id, position),
    KEY idx_refund_evidence_refund (refund_id),
    CONSTRAINT fk_refund_evidence_file
        FOREIGN KEY (file_id) REFERENCES private_files(id),
    CONSTRAINT fk_refund_evidence_refund
        FOREIGN KEY (refund_id) REFERENCES refunds(id)
);
```

约束含义：

- 一个私有文件只能属于一条退款记录；
- `position` 保存用户提交顺序，范围由服务校验为 0 到 4；
- 文件元数据继续存放在 `private_files`；
- `private_files.purpose` 固定为 `REFUND_EVIDENCE`；
- `refunds.evidence_url` 保留，不迁移、不清空、不改变含义。

私有文件底层能力从现有认证材料实现中提取为小型共享存储服务，统一负责路径约束、大小与签名校验、SHA-256、`private_files` 写入、`user_media_assets` 登记和事务回滚文件清理。身份认证服务保留现有对外行为，只改为委托共享能力；退款领域负责订单权限、格式策略和关联关系。

## 6. API 契约

### 6.1 新版服务费退款申请

`POST /api/orders/{orderId}/refund` 继续使用原路径，新增 `multipart/form-data` 处理分支：

| Part | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `reason` | text | 是 | 非空 |
| `description` | text | 否 | 沿用现有长度约束 |
| `reasonCode` | text | 否 | 沿用现有枚举归一化 |
| `evidenceFiles` | file，可重复 | 否 | 0 到 5 个；每个不超过 10 MB |

multipart 分支只接受 `paymentFlow=TRAVEL_GROUND_SERVICE_ONLY` 的本人订单，并沿用当前 `SERVICE_ACTIVE` 或 `COMPLETED` 退款资格、唯一成功服务费支付及全额人工审核规则。

原 `application/json` 请求保持兼容：

```json
{
  "reason": "行程取消",
  "description": "无法按期出行",
  "reasonCode": "CUSTOMER_REQUEST",
  "evidenceUrl": ""
}
```

### 6.2 凭证元数据

用户退款详情和管理员退款记录增加只读字段：

```json
{
  "evidenceFiles": [
    {
      "fileId": "uuid",
      "originalName": "receipt.pdf",
      "contentType": "application/pdf",
      "sizeBytes": 245760,
      "position": 0
    }
  ]
}
```

响应不得包含 `storage_key`、服务器文件路径或可匿名访问的 URL。历史 `evidenceUrl` 继续返回，管理端将其解析为独立的“旧版凭证”。

### 6.3 管理员读取内容

`GET /api/admin/refunds/{refundId}/evidence/{fileId}/content`

- 仅 `ROLE_ADMIN` 可访问；
- 同时校验 `fileId` 确实关联到路径中的 `refundId`；
- 图片和 PDF 使用正确的 `Content-Type`；
- `Content-Disposition` 使用净化后的 UTF-8 文件名；
- 返回 `Cache-Control: no-store` 和 `X-Content-Type-Options: nosniff`；
- 文件或关联不存在返回 404，越权返回 403。

## 7. 后端事务与安全规则

1. 控制器解析 multipart，但所有业务校验由退款应用服务执行。
2. 先锁定订单并验证本人、服务费支付流和当前可退款状态。
3. 对全部文件执行数量、单文件大小、声明 MIME、扩展名和文件签名校验；任何一项失败即拒绝整笔请求。
4. 创建退款记录后，为每个文件生成不可预测 ID 和私有存储键，写入 `private_files` 与 `refund_evidence_files`。
5. 文件写入注册事务同步清理；数据库回滚时删除本次已写文件。
6. 关联创建后没有新增、更新或删除 API，保证提交后冻结。
7. 管理员读取只通过退款关联查询，不能使用任意 `private_files` ID 绕过授权。
8. 已绑定退款材料作为退款审核记录的一部分，不进入普通账户注销媒体删除队列。`UserMediaAssetService.markPending` 和账户删除私有文件清理需排除 `refund_evidence_files` 已引用的文件。

允许的内容签名：

- JPEG：`FF D8 FF`；
- PNG：标准 8 字节签名；
- WebP：`RIFF....WEBP`；
- PDF：`%PDF-`。

服务端不信任客户端文件名或 `Content-Type`。原始文件名仅作为显示元数据保存，长度限制 255 字符；响应头必须净化控制字符、路径分隔符和引号。

## 8. Flutter 用户体验

多凭证能力只出现在服务费退款页面：

- 标题显示“退款凭证（选填）”及 `当前数量/5`；
- 用户每次从系统选择器选择一个图片或 PDF，页面累加到列表；不要求原生多选；
- 列表显示图片缩略图或文件图标、文件名、类型和大小；
- 提交前允许删除任一项；达到 5 个后禁用继续添加；
- 客户端先做格式和 10 MB 校验，服务端仍为最终权威；
- 点击提交后整批 multipart 上传，期间禁用重复提交和编辑；
- 失败时显示明确错误并保留原因、说明和已选文件；
- 成功后返回订单详情，退款卡片只读显示凭证数量与文件名。

`AppFilePicker` 新增退款用途方法，复用 Android `ACTION_OPEN_DOCUMENT` 和 iOS `UIDocumentPicker` 的现有单选实现与 10 MB 内存上限，不新增存储权限，也不改为一次多选。订单仓储、远程数据源和控制器使用结构化附件模型，不再传递逗号 URL 字符串。

## 9. 管理端体验

`RefundsPage` 退款详情增加“退款凭证”区域：

- 显示凭证数量、文件名、格式和可读大小；
- 图片通过带 Bearer 凭证的 blob 请求加载并预览；
- PDF 通过鉴权 blob 在无 `opener` 的新窗口预览，并提供下载；
- object URL 在关闭或替换时及时释放；
- 加载或下载失败显示明确错误，不影响批准、拒绝和渠道重试操作；
- 历史 `evidenceUrl` 仅接受 `http`/`https` URL，单独标记为“旧版凭证”；
- 列表页只显示凭证数量，详细预览保留在详情弹窗，避免列表批量下载敏感内容。

管理端现有审批并发锁、刷新失败锁定和失败退款项重试行为保持不变。

## 10. 错误处理

- 文件超过数量、大小或类型限制：400，整笔退款不创建；
- 文件签名与声明格式不符：400，整笔退款不创建；
- 非本人订单、非服务费支付流或状态不可退款：沿用现有业务错误，不写文件；
- 文件系统或元数据写入失败：事务回滚并删除本次已写文件；
- 管理员读取不存在或不属于该退款的文件：404；
- 非管理员读取管理接口：403；
- Flutter 网络结果不确定时重新加载订单与退款状态，避免盲目重复提交；
- 管理端预览失败只影响当前文件，不改变退款审核状态。

## 11. 迁移与数据库隔离

- 迁移编号固定为 `V38`，不编辑冻结的 `B33__current_schema.sql`；
- 在 worktree 中派生 `WORKTREE_ID=worktree_service_fee_refund_evidence`；
- 数据库名使用 `myapp_worktree_worktree_service_fee_refund_evidence`；
- Docker Compose 项目名使用 `myapp-worktree_service_fee_refund_evidence`；
- 迁移前打印数据库主机和数据库名；
- 在全新空数据库验证 B33 基线后依次应用 V34 至 V38；
- 仅允许清理名称以 `myapp_worktree_` 开头的测试数据库。

## 12. 测试策略

按 TDD 和项目最小测试规则实施。

后端重点覆盖：

- 0、1、5 个合法文件成功，6 个文件拒绝；
- 四种允许格式、10 MB 边界、超限、空文件、扩展名/MIME/签名不匹配；
- 仅本人服务费订单可用 multipart，历史医疗流程不变；
- 退款、私有文件和关联在同一事务内完成；失败路径无残留文件或数据库行；
- 用户详情和管理员列表只返回安全元数据；
- 管理员可读取关联文件，普通用户、错误退款 ID 和任意私有文件 ID 均不可读取；
- 账户注销清理保留已绑定退款材料；
- 原 JSON 和历史 `evidenceUrl` 行为兼容；
- `V38` 在新空隔离数据库上成功应用并具备预期外键、索引和约束。

Flutter 重点覆盖：

- 选择、累加、删除和五个上限；
- 非服务费退款不出现新凭证组件；
- 上传中禁止重复提交，失败后保留输入；
- multipart 字段和多个文件映射；
- 退款详情凭证元数据解析及中英文文案。

管理端重点覆盖：

- 新凭证数量与元数据展示；
- 图片和 PDF 使用鉴权 blob 请求；
- 下载失败反馈与 object URL 清理；
- 历史 URL 单独展示；
- 原批准、拒绝、刷新锁和重试测试继续通过。

相关测试通过后，每个技术栈最多运行一次必要的完整检查；完整检查超过 10 分钟即停止并报告进度，不重复运行已通过命令。

## 13. 文档与交付

实施时同步维护：

- `docs/FLUTTER_API_CONTRACT.md`：multipart 退款请求、凭证元数据和管理员内容接口；
- `docs/guide/支付开发与云服务器部署指南.md`：私有目录、权限、大小限制和审核查看说明；
- `design/TRAVEL_GROUND_SERVICE_PAYMENT_WORKFLOW.puml`：用户提交多凭证、管理员安全查看及凭证冻结；
- 最终验证命令与结果摘要记录在该分支交付说明中。

UML 源码更新后提醒维护者手动重新生成图片。

## 14. 验收标准

- 服务费退款可携带 0 到 5 个符合规则的图片/PDF；
- 所有新凭证均为私有文件，匿名 URL 无法访问；
- 凭证与退款原子保存，失败路径无孤立文件；
- 提交后没有补件、替换或删除入口；
- 管理员可查看每个新凭证及历史旧版凭证；
- 非管理员不能通过管理接口读取文件；
- 历史医疗退款、旧 JSON 客户端和渠道退款状态机无回归；
- 新迁移通过全新隔离数据库验证；
- 后端、Flutter、管理端最小相关测试和静态检查通过；
- API、运维指南和 UML 源码与实现一致。
