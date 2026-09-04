# 阿里云 UAT 与 GitHub CI/CD 实施计划（2026-09-04）

## 目标

在现有阿里云 `joysong-demo` 后端和隔离数据库基础上原地升级为可验收 UAT，建立 GitHub 构建、发布、验收、回滚和未来 Prod 晋级门禁；不重建现有实例，不重置当前数据库。

现行部署流程以 `docs/guide/deployment/README.md` 为准，变量语义以同目录 `CONFIGURATION_REFERENCE.md` 为准。本计划只记录实施阶段，不复制操作命令。

## 已确认基线

- ECS 上的 `joysong-demo.service` 使用 `demo` profile 和独立运行用户。
- 后端仅监听 `127.0.0.1:8080`，当前版本位于带提交短哈希的 release 目录。
- 数据库名带 `myapp_worktree_` 安全前缀，真实支付、自动支付、对账、Stripe 和 SMS 均关闭。
- 新公共图片已通过 ECS RAM Role 写入 UAT OSS，历史本地 `/images` 继续兼容；私有材料仍保存在本地受限目录。
- 管理端和 API 目前只有 HTTP；域名从公网访问会被阿里云 ICP 合规页拦截。
- 已有多个 release 目录，但缺少标准 `previous` 回滚指针和 GitHub 发布证据。

因此当前状态定义为 `UAT Candidate`，不是 `UAT Ready`。

## 实施阶段

1. **本地已完成**：收口后端 Demo/Prod 安全门，加入 UAT 手动模拟支付和全额模拟退款。
2. **本地已完成**：建立 Android UAT/Prod flavor、签名隔离、Deep Link 隔离和 UAT 密码登录界面。
3. **本地已完成**：建立 PR 质量门禁、UAT candidate/promote/rollback 和默认禁用的 Prod 工作流。
4. **本地已完成，待主机验证**：将 ECS 部署逻辑固化为 manifest 校验、备份审计、`current/previous` 原子切换和 migration-aware 失败处置。
5. **本地已完成**：合并旧部署文档并启用文档同步门禁。
6. **待显式授权**：配置 GitHub Environments、OIDC/RAM、私有发布 Bucket，并在现有 ECS 创建应用级回滚点，平滑迁移为 `joysong-uat` 目录与服务。
7. **待外部条件**：解决 ICP 公网阻断并配置 HTTPS 后，执行最终切流、自动技术验收和 GitHub Issue 业务验收。

在第 6、7 阶段完成前，现有 `joysong-demo.service` 保持原状。本地实现完成不等于已在阿里云生效，也不把当前环境升级为 `UAT Ready`。

## 外部阻断

- ICP 备案或阿里云接入状态未恢复前，公网域名无法进入 UAT Ready。
- 当前没有可复用 TLS 证书或 Certbot；证书只能在域名入口可用后申请和验证。
- 需要新增付费阿里云资源时，先报告规格与费用并取得批准。
- 真实 Alipay+ 未完成，Prod 保持 No-Go。

## 完成标准

- UAT 域名使用有效 HTTPS，Admin、API、分享页和公共图片可访问。
- GitHub UAT tag 产生不可覆盖的 Draft Release 和一致的 manifest/SHA。
- 现有 ECS 可通过 Cloud Assistant 原子发布并在技术失败时恢复上一版本。
- 已签名 UAT universal APK 可安装、覆盖升级并完成密码登录和业务验收。
- 验收 Issue 通过后，同一 Draft 变为 Pre-release；Prod 仍由双门禁阻止。
