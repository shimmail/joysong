# 公共图片上传性能修复与联调记录

> 日期：2026-09-03
> 分支：`codex/upload-performance-fix`
> 开发目录：`.worktrees/worktree_upload_performance_fix`
> 边界：远端 ECS 仅部署本地制品和采集联调证据，不在服务器就地修改或编译源码。

## 1. 基线与问题

联调样本中 8 次 OSS 写入横跨约 388 秒，对象总字节中约 63% 是重复内容。某样本长边为 2666，表明原有声明的 2048/85 压缩策略没有实际执行。ECS 的 CPU、内存和本机健康检查未显示资源饱和，因此第一阶段优先修正手机端数据量、串行调度、超时取消和重试重复上传。

MIUI `InsetsSource` 和 `MIUIInput` 日志属窗口/触摸信息，不是本次上传慢的故障证据。

## 2. 第一阶段改动

- 公共图片选择后立即在本地处理：长边不超过 2048、JPEG 质量 85、不保留 EXIF；上传仍在用户点击发布时开始。
- 日记图片按“普通图 → 术前图 → 术后图”处理，每组最多 2 个在途请求。条目在原位由本地文件替换为 URL，因此完成先后不改变用户选择顺序。
- 单张成功即持久保留 URL 并释放其临时文件；部分失败只保留失败或未启动项。日记正文提交失败后再试不重传已成功图片。
- multipart 直接流式读取本地处理文件，避免多份 `Uint8List` 常驻；连接、整次上传和响应读取超时分离，取消和超时会主动终止请求。
- `uploadId` 与 `Idempotency-Key` 在令牌刷新、状态查询和同一文件重试间保持不变。响应不明时先查询 `/api/upload/status/{uploadId}`，不盲目再次写 OSS。
- 服务端把 multipart 写入独立临时文件，同步校验签名、长度和 SHA-256，以可重复读取的文件源交给 OSS，并在 `finally` 清理。
- 请求接收/回传 `X-Request-ID`，并记录 multipart、校验、OSS PUT、媒体登记和总耗时；日志不记录原文件名、用户 ID、Bucket 或任何凭证。
- `POST /api/upload` 接受可选 `Idempotency-Key`：新客户端以稳定 `uploadId` 作为键，返回 `200 COMPLETE`、`202 PENDING` 或 `409 UPLOAD_ID_REUSED`；旧客户端不传时仍只依赖原有 `url` 响应。状态恢复使用 `GET /api/upload/status/{uploadId}`。
- V40 新增 `public_upload_attempts`：`(owner_user_id, client_upload_id)` 唯一，租约 180 秒，记录保留 7 天；开启调度时，定时清理由 `upload.idempotency-cleanup-delay-ms` 控制，关闭通用调度的 Demo 环境则在后续上传时执行同一索引条件的机会式清理。
- OSS SDK 默认参数为连接 5 秒、Socket 30 秒、单次请求 60 秒、错误重试 1 次和连接池 32；对应配置键为 `oss.connection-timeout-ms`、`oss.socket-timeout-ms`、`oss.request-timeout-ms`、`oss.max-error-retry`、`oss.max-connections`。临时目录由 `upload.staging-dir` 指定，留空时使用 JVM 临时目录下的 `joysong-upload-staging`。

## 3. 性能分层与验收

固定使用 256 KiB、1 MiB、5 MiB 和 9.5 MiB 图片，每份样本记录 SHA-256，分层测量：

1. ECS 直接写 OSS。
2. ECS 回环访问 `/api/upload`。
3. PC 经 Workbench 通道访问。
4. 手机经 ADB reverse + Workbench 通道访问。
5. PC 和手机分别经正式 HTTPS 入口访问。

每次保留请求 ID、原始/处理后字节数、总耗时、服务端分段耗时、HTTP 结果、uploadId 和对象数，据此区分本地处理、链路、Spring multipart 与 OSS 耗时。

验收门槛：

- 20/20 上传成功，每个 uploadId 只对应一个 OSS 对象。
- 1 张 1 MiB 不超过 8 秒；3 张 1 MiB 十轮 p95 不超过 20 秒；9 张 1 MiB 不超过 60 秒。
- 27 张极限场景无 OOM、ANR、重复对象或已成功图片重传。
- 基准文件仅使用专用测试账号和前缀；只清理本次生成的 key，不删除现有联调对象。

## 4. 部署、观察与回滚

- JAR 和 APK 都由本 worktree 的已审阅提交构建，记录 Git 提交及 SHA-256 后上传到新 release 目录，不覆盖正在运行的 JAR。
- 部署前创建 RDS 备份，先运行 Flyway 并确认 V40，再切换 `current` 软链接。随后验证 health、单张上传、并发上传、幂等重放和图片访问。
- 发布后观察 HTTP 5xx/409/202、超时、OSS 错误、总耗时 p50/p95/p99、阶段耗时、重放命中数、临时文件清理失败和 JVM 内存。
- 异常时切回上一个已验证 JAR 并重启服务。V40 是可向前保留的追加表，回滚应用时不执行 Flyway clean、逆向 SQL 或删表。

## 5. 第二阶段触发条件

只有在第一阶段后“3 × 1 MiB 的 p95 仍超过 20 秒”，或中转链路相对手机直连 OSS 额外耗时同时超过 20% 且 1 秒时，才进入 V4 预签名 PUT。预签名必须绑定固定对象键、MIME 和摘要，10 分钟过期；完成后由服务端 HEAD/摘要校验再登记，不向 App 暴露 RAM Role 临时或长期凭证。

## 6. UML 维护

对应时序源码为 `design/PUBLIC_IMAGE_UPLOAD_SEQUENCE.puml`。本任务不生成 PNG/SVG；流程变更后由维护者手动重新生成图片。
