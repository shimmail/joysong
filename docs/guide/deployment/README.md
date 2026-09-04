# JoySong 阿里云部署与发布指南

> 文档状态：唯一现行部署入口（Canonical）
> 适用范围：GitHub CI/CD、阿里云 ECS、UAT、未来 Prod
> 最后核验：2026-09-04
> 配套字典：见 [CONFIGURATION_REFERENCE.md](./CONFIGURATION_REFERENCE.md)；主流程以本文为准
> 原则：本文定义流程和门禁，精确命令与配置以仓库内 workflow、脚本和模板为准。

## 1. 如何阅读本文

本文只维护一套线上部署口径。能力状态使用以下标记：

- **Current**：当前仓库或 ECS 已经具备，并有本轮核验证据。
- **Target**：本次 UAT 改造要交付的能力，完成验收前不能写成已具备。
- **Blocker**：未解除前不得进入对应环境或阶段。

事实来源按以下顺序解释：

1. GitHub Release 中的 manifest 记录某次发布事实，包括提交、环境、构建号和制品哈希。
2. `.github/workflows/`、`joysong-server/deploy/` 与应用配置定义实际执行行为。
3. `CONFIGURATION_REFERENCE.md` 定义变量语义和安全边界。
4. 本文定义环境、发布、回滚、验收和运维流程。

出现冲突时停止发布，先在同一 Pull Request 中统一代码、配置和本文，不得凭经验任选一份旧说明继续操作。

## 2. 当前结论

现有阿里云实例不重建，按“Demo 原地升级为 UAT”实施。

### 2.1 已确认基线

| 检查项 | 当前状态 | UAT 结论 |
| --- | --- | --- |
| Spring profile | `demo` | **Current** |
| 后端监听 | `127.0.0.1:8080` | **Current**，迁移目标为 UAT `8081` |
| 数据库 | 本机独立 MySQL，库名带 `myapp_worktree_` 前缀 | **Current**，仅限 UAT |
| 应用服务 | `joysong-demo.service`，独立 `joysong-demo` 用户 | **Current**，迁移目标为 `joysong-uat` |
| 版本目录 | `/opt/joysong-demo/releases/<release>` 与 `current` | **Current**，缺少可验证的 `previous` |
| 管理端/API | Nginx 监听 80 | **Current**，仅 HTTP |
| 公网域名 | `joyingsong.net`、`api.joyingsong.net` | **Blocker**：当前被阿里云 ICP 合规页拦截 |
| TLS | 未配置 443 和证书 | **Blocker** |
| 支付 | 真实支付、自动支付、对账、Stripe 和模拟支付均关闭 | **Current**；UAT 目标仅开启手动模拟支付 |
| SMS | 关闭 | **Current** |
| AI/翻译 | Qwen provider 已配置 | **Current**，仍需公网业务验收 |
| 公共图片 | ECS RAM Role 写入杭州 OSS，保留本地历史回退 | **Current**，不得在升级中降级为长期 AccessKey |
| 私有文件 | ECS 本地受限目录 | **Current**，需继续备份和禁止公网暴露 |
| CI/CD | 手工版本目录发布 | **Target**：迁移到 GitHub OIDC + Cloud Assistant |

当前环境可称为 **UAT Candidate**，不能称为 **UAT Ready** 或 **UAT Accepted**。

### 2.2 状态推进

```text
UAT Candidate（当前已部署、未准入）
  -> ICP/公网入口、HTTPS、可回滚发布、UAT APK、目标功能通过
UAT Ready（可以交给验收人员）
  -> GitHub 验收 Issue 全部通过
UAT Accepted（验收通过，可作为未来 Prod 晋级依据）
```

## 3. 环境矩阵

| 项目 | Dev | UAT | Prod |
| --- | --- | --- | --- |
| Spring profile | `dev` | `demo` | `prod` |
| Flutter `APP_ENV` | `development` | `uat` | `production` |
| API scheme | 本机 HTTP 可用 | 必须 HTTPS | 必须 HTTPS |
| Android flavor | 开发默认 | `uat` | `prod` |
| Android applicationId | 开发配置 | `com.joysong.app.uat` | `com.joysong.app` |
| Deep Link scheme | `joysong` | `joysong-uat` | `joysong` |
| 后端端口 | 8080 | 8081 | 8080 |
| systemd | 不适用 | `joysong-uat` | `joysong-prod` |
| 数据库 | 本地隔离 | `myapp_worktree_uat*` | 独立生产库 |
| 数据 | 开发数据 | 仅虚构数据 | 经批准的真实数据 |
| 支付 | 可模拟和自动 | 仅手动模拟成功、全额模拟退款 | 真实 Alipay+ 完成前 No-Go |
| SMS / Google | 按开发需要 | 关闭 | 独立审核和验收后启用 |

UAT 暂时使用：

- 管理端：`https://joyingsong.net`
- API 与公开分享：`https://api.joyingsong.net`

未来启用 Prod 前必须先把 UAT 迁移到：

- `https://uat.joyingsong.net`
- `https://api-uat.joyingsong.net`

只有新 UAT 域名和新版 APK 验收通过后，主域名才能切换给 Prod。

## 4. 目标拓扑与身份边界

```text
开发者 -> Pull Request -> GitHub quality gates
                              |
UAT tag -> Draft Release -> 私有发布 OSS -> Cloud Assistant
                                            |
                                      root dispatcher
                                            |
                                     joysong-deploy
                                            |
                            current/previous 原子切换
                                      /           \
                                  systemd         Nginx
                                     |              |
                                Spring Boot      Admin dist
                                     |
                    UAT 独立 MySQL、OSS 与持久私有文件
```

身份必须分离：

- GitHub OIDC 角色：只写发布 Bucket 指定前缀，并对指定 ECS 调用、查询 Cloud Assistant。
- 临时下载地址：由 OIDC 角色生成，短时有效，不写日志，不作为长期凭据。
- `joysong-deploy`：只管理发布目录、软链接和指定服务，不能读取应用秘密。
- `joysong-uat` / `joysong-prod`：只运行对应应用并访问对应数据目录。
- root：持有环境文件、TLS 私钥、固定 dispatcher 和 Prod 哨兵。
- 应用 OSS 身份：现有 ECS RAM Role 只处理 UAT 业务图片，不得拥有发布 Bucket 写权限。

GitHub 中不得保存数据库、JWT、AI、翻译、SMS 或应用 OSS 的运行时秘密。Android keystore 是构建秘密，按 UAT/Prod 分开保存。

## 5. 仓库部署资产

| 资产 | 责任 |
| --- | --- |
| `.github/workflows/quality-gates.yml` | PR 与可复用全量质量门禁 |
| `.github/workflows/uat-candidate.yml` | UAT tag 构建、Draft Release、上传和部署 |
| `.github/workflows/uat-promote.yml` | 验收后转为 Pre-release |
| `.github/workflows/uat-rollback.yml` | 人工选择已验证版本回滚 |
| `.github/workflows/prod-deploy.yml` | 默认禁用的 Prod 晋级流程 |
| `.github/ISSUE_TEMPLATE/uat-acceptance.yml` | UAT 业务验收证据 |
| [`joysong-server/deploy/`](../../../joysong-server/deploy/README.md) | ECS dispatcher、发布核心、systemd、Nginx、sudoers 和环境样例 |

工作流不得内联大段远程 shell。GitHub 只向 Cloud Assistant 传目标环境、release ID、manifest SHA 和短时下载地址；所有安全逻辑由 ECS 上 root-owned 固定脚本执行。

## 6. GitHub 与阿里云一次性接入

这一步只做一次，但它是发布前置条件。创建私有 OSS Bucket、RAM/OIDC 身份或其他可能计费的云资源前，必须先获得用户批准；仓库内代码和文档不会自动创建云资源。

### 6.1 GitHub 仓库保护

1. 仓库必须保持 **Private**。UAT APK、Draft 和 Pre-release 都属于受控交付物；若仓库改为 Public，工作流会拒绝候选发布，必须改用真正的私有制品渠道后才能恢复。
2. 为 `master` 启用分支保护或 Ruleset：必须走 Pull Request、禁止 force-push/删除，并把稳定检查名 `Required quality gates` 设为合并必需检查。
3. 创建名称精确为 `uat` 和 `production` 的 GitHub Environments。
4. `uat` 的部署 ref 只允许 `vX.Y.Z-uat.N` 候选 tag；按当前决策可允许发起者自审，但验收 Issue 的清单和证据不可省略。
5. `production` 工作流通过 `workflow_dispatch` 从受保护 `master` 发起，并在流程内校验/创建稳定 `vX.Y.Z` tag，因此 Environment 的部署分支只允许受保护 `master`，不要误配成 tag-only。设置独立批准人、禁止管理员绕过并禁止发起者自审。
6. 若当前 GitHub 套餐不能强制所需的 Environment 审批或部署分支/标签规则，将其记为发布阻断项，不得把人工约定描述成已强制执行。

仓库级变量只放两个 UAT 公网 origin 和 `PROD_DEPLOY_ENABLED=false`；UAT 签名秘密暂放仓库 Secrets，因为 APK 构建发生在进入 `uat` Environment 之前。阿里云参数放入对应 Environment，同名变量分别指向 UAT/Prod 的独立资源；Prod 签名秘密只放 `production` Environment。完整键名见配置字典。

### 6.2 GitHub OIDC 与 RAM Role

在阿里云 RAM 创建 GitHub OIDC 身份提供商：

- Issuer URL：`https://token.actions.githubusercontent.com`
- Client ID / Audience：`github-actions`
- 不配置长期 `ALIYUN_ACCESS_KEY_ID` / `ALIYUN_ACCESS_KEY_SECRET`

分别创建 UAT 和 Prod RAM Role，不共用角色。信任策略必须同时限制 audience 和 GitHub token 的实际 `sub`。不要从旧教程硬编码 `sub`：先用 GitHub 官方 OIDC token 预览或一次受控诊断运行确认当前仓库的真实 subject，再写入信任条件；仓库新建、转移或重命名后必须重新核对。环境型 subject 至少应绑定精确的 `uat` 或 `production` Environment。

最小业务权限如下；资源 ARN 必须替换为实际账号、地域、Bucket、前缀、实例和命令，不得把 `*` 当作长期方案：

| 身份 | 允许动作 | 资源/条件 |
| --- | --- | --- |
| UAT 发布 Role | `oss:GetBucketAcl`、`oss:GetBucketVersioning` | 仅发布 Bucket；确认 ACL 为 Private 且 Versioning 从未启用 |
| UAT 发布 Role | `oss:GetObject`、`oss:PutObject` | 仅发布 Bucket 的 `<prefix>/uat/*` |
| UAT 发布 Role | `ecs:RunCommand` | 仅当前 UAT ECS；`ecs:CommandRunAs=joysong-deploy` |
| UAT 发布 Role | `ecs:DescribeInvocationResults` | 仅目标 ECS 与本流程创建/使用的 Cloud Assistant command |
| Prod 发布 Role | 同上 OSS/ECS 动作 | 仅 `<prefix>/prod/*` 与独立 Prod ECS |
| Prod 发布 Role | `rds:DescribeDBInstanceAttribute`、`rds:CreateBackup`、`rds:DescribeBackupTasks` | 仅配置的 Prod RDS 实例 |

两个角色都不授予 OSS Delete、ECS 任意命令、RAM 管理或业务图片 Bucket 写权限。OIDC 会话最长按工作流当前的 3600 秒配置；回滚会话更短。权限变化后先执行只读/预检流程验证，不能以长期 AccessKey 作为失败回退。

### 6.3 私有发布 Bucket

发布 Bucket 与业务图片 Bucket 分离，并满足：

- ACL 为 Private，关闭静态网站和公共匿名访问；
- Versioning 必须为 `Disabled`，因为本流程依赖 `forbid-overwrite` 保证同一 key 不可覆盖；
- UAT/Prod 使用 `<prefix>/uat/` 与 `<prefix>/prod/` 独立前缀；
- 上传使用 OSS 服务端 `forbid-overwrite` 条件完成一次原子 PutObject；任何“已存在”或无法确认状态都失败，不使用存在性检查后再上传的竞态流程；
- 生命周期只清理由明确规则覆盖的临时对象，不删除 GitHub Release、服务器 `current`/`previous` 或失败审计引用所需制品。

### 6.4 ECS 与 Cloud Assistant

先确认目标 ECS 上 Cloud Assistant Agent 在线且版本支持指定运行用户。随后按[部署资产索引](../../../joysong-server/deploy/README.md)安装 root-owned dispatcher、`joysong-deploy` 用户、sudoers、systemd 和 Nginx 模板。Cloud Assistant 只能以 `joysong-deploy` 调用固定 dispatcher；不能传任意 root shell，也不能读取 `/etc/joysong/<env>/joysong.env`。

首次 UAT 接入顺序固定为：完成本节配置 -> 创建并核验 root-only 备份 -> bootstrap -> 填写并验证 UAT 环境文件 -> 捕获现有 Demo 基线 -> 解决 ICP/DNS/TLS -> 明确 Go/No-Go -> 最终切流。自动 tag 发布只有在 `BASELINE_CAPTURED` 和 `CUTOVER_COMPLETED` 两个门禁都存在且匹配时才允许执行。

## 7. Pull Request 质量门禁

`quality-gates.yml` 必须始终产生一个稳定汇总结果，不能因顶层 `paths` 跳过后让分支保护永久等待。

### 7.1 后端

- Java 17。
- 相关单元测试。
- 服务器、迁移或数据库代码变化时运行 MySQL 集成测试。
- UAT tag 运行一次完整 `test`、`mysqlIntegrationTest` 和 `bootJar`。
- 测试数据库必须从本次工作区/运行 ID 派生，名称以 `myapp_worktree_` 开头。
- Flyway 前打印实际数据库主机和数据库名，绝不连接共享开发库。

### 7.2 管理端

- Node 22。
- `npm ci`、lint、test、build。
- lint 警告保留在日志；只有非零退出阻断。

### 7.3 Flutter

- 固定一个明确 Flutter stable 版本，不允许工作流使用浮动 `stable`。
- 格式检查、analyze、test。
- Flutter 相关 PR 和 UAT tag 都在 macOS 执行 iOS `--no-codesign` 编译。
- UAT tag 构建已签名 universal APK，并校验包名、版本、Deep Link、API origin 和证书指纹。

### 7.4 文档和脚本

- 校验权威文档、相对链接和部署资产路径。
- 禁止旧部署文档名重新出现。
- workflow、profile、环境变量、迁移、Nginx、systemd、支付门禁或 flavor 变化时，必须同步更新本文或配置参考。
- workflow、shell 和 PowerShell 脚本分别执行静态检查。

## 8. 供应链门禁

UAT 候选必须生成：

- Spring Boot JAR；
- Admin 静态包；
- UAT universal APK；
- release manifest；
- SHA-256；
- SBOM；
- 安全扫描结果。

manifest 至少包含：

```json
{
  "schemaVersion": 1,
  "tag": "v1.2.3-uat.1",
  "commit": "<40位提交哈希>",
  "environment": "uat",
  "buildNumber": 123,
  "artifacts": [
    {"name": "server.jar", "sha256": "<sha256>"},
    {"name": "admin.tar.gz", "sha256": "<sha256>"},
    {"name": "android.apk", "sha256": "<sha256>"}
  ]
}
```

禁止把 `.env`、`application-dev.yml`、数据库导出、keystore、私钥、Token 或服务端密钥打入发布制品。

Critical/High 漏洞和 secret 扫描命中均阻断；当前流程不实现自动例外白名单。如确需接受风险，必须先通过单独安全评审变更门禁代码，不能在某次发布中临时跳过扫描。

## 9. UAT 候选发布

### 9.1 标签规则

标签只接受 `vX.Y.Z-uat.N`，并同时满足：

1. 指向受保护 `master` 可达的提交。
2. tag、GitHub Release 和发布 Bucket 前缀均未使用。
3. 同一提交的全栈质量门禁通过。

失败标签永不复用；修复后递增 `N`。

### 9.2 GitHub Release 生命周期

1. 候选构建完成后创建 Draft Release；仓库必须保持 Private，才能把 Draft/Pre-release 视为私有交付物。
2. 服务器部署和自动技术检查通过后创建 UAT 验收 Issue。
3. 验收人员完成清单，记录 tag、commit 和部署版本，添加 `uat-accepted` 并关闭 Issue。
4. 手工触发 Promote，将同一个 Draft 改为 Pre-release，不重建、不重新部署。

自审批允许，但 Issue 中必须保留完整结果。业务失败时标记拒绝、保存证据，并手工触发回滚。

### 9.3 并发

- 发布使用固定 `uat-deploy` 并发组。
- `cancel-in-progress` 必须为 false，活动部署不能被新 tag 中断。
- 新候选可以排队；“不存在更新的待验收候选”在 Promote 时强制检查，只有最新成功 Draft 候选允许晋级。

## 10. 现有 ECS 原地升级

### 10.1 迁移原则

- 不重装 ECS，不清空数据库，不覆盖当前版本目录。
- 先绑定并记录旧 systemd unit、MainPID、8080 listener、实际 JAR 路径及 SHA-256、环境文件、数据根、Nginx 配置和数据库身份；最终切流前逐项复核，任一漂移都在停写/同步前失败。
- 在任何服务切换前创建应用级可回滚备份；云快照如产生费用，必须先取得批准。
- 新 UAT 在独立目录完成验证后再切换入口。
- Baseline 只短暂启动新 UAT 做本机身份/健康检查，成功后立即停止并禁用；最终切流冻结旧服务前，不能让旧 Demo 与新 UAT 同时作为完整写实例运行。

### 10.2 目标目录

```text
/opt/joysong/uat/
  releases/<tag>/
    server.jar
    admin/
    manifest.json
  current -> releases/<tag>
  previous -> releases/<previous-tag>

/etc/joysong/uat/joysong.env
/var/lib/joysong/uat/uploads    # 历史公共文件兼容
/var/lib/joysong/uat/upload-staging
/var/lib/joysong/uat/private
```

Prod 使用同结构的 `/opt/joysong/prod`、`/etc/joysong/prod` 和 `/var/lib/joysong/prod`，不得共用环境文件或数据目录。

### 10.3 原子发布

1. 下载到新的 release 目录，不写 `current`。
2. 校验 manifest、commit、目标环境、哈希和磁盘空间。
3. 校验 UAT profile/数据库，或校验 Prod 双门禁。
4. 启用维护响应。
5. 将旧 `current` 记录为 `previous`，再原子切换 `current`。
6. 重启对应 systemd 服务并等待本机健康检查。
7. 校验 Nginx、Admin 首页、API、分享页和公开资源。
8. 成功后退出维护态；失败时自动切回 `previous` 并保存日志。

后端和 Admin 位于同一个 release 根目录并共享一次软链接切换，防止前后端版本错配。

维护标记位于持久 root-owned 路径 `/var/lib/joysong-maintenance/<env>`，不是会在重启后消失的 `/run` 临时文件。Nginx 在标记存在时持续返回 503，systemd `ExecStartPre` 也拒绝自动启动；只有新版本、本机健康和公网身份全部验证成功后才删除。若主机在切换中断电，恢复后保持 fail-closed，由操作者依据备份 identity 和日志决定恢复旧版或继续验证，不能自动暴露未验收的 `current`。

## 11. 数据库与演示数据

### 11.1 UAT

- 当前本机 MySQL 可继续用于 UAT，不得描述为生产架构。
- 数据库必须以 `myapp_worktree_` 开头，并使用 UAT 独立账号。
- 首次基线执行 Flyway 后，只人工执行一次 Demo `Apply`，随后执行 `Verify`。
- 日常发布只自动执行 `Verify`，不得每次启动重新 Apply。
- UAT 强制 `app.scheduling.enabled=false`；除停用定时任务外，候选启动也跳过中断上传恢复，防止验证阶段删除本地/OSS 对象或写回媒体状态。
- 只允许虚构机构、医生、顾问、项目和测试账号。
- 需要重置时创建新安全前缀数据库，执行 migrate -> Apply -> Verify 后切换；不得 truncate/drop 当前库。

### 11.2 Prod

- 使用独立 RDS/数据库、账号、备份和数据目录。
- 部署前等待 RDS 备份成功。
- 迁移遵循 Expand/Contract，禁止 Flyway clean 和自动逆向 SQL。
- 数据恢复到新 RDS，验证后切换；不得在原库直接覆盖恢复。

## 12. UAT 功能边界

### 12.1 登录

UAT App 只显示密码登录。SMS、验证码登录、注册、找回密码和 Google 登录均关闭；隐藏 UI 不能替代服务端配置门禁。

### 12.2 支付与退款

UAT 只允许：

1. 用户手工点击旅游地接服务费支付。
2. 模拟 Alipay+ 立即返回成功。
3. 用户发起全额退款申请。
4. 管理员批准后模拟渠道立即全额退款。

UAT 禁止自动支付、部分退款、真实渠道、查询刷新、Webhook、对账和失败注入。页面不增加额外 UAT 水印或模拟支付提示。

### 12.3 APK

- flavor：`uat`
- applicationId：`com.joysong.app.uat`
- Deep Link：`joysong-uat://`
- 使用独立 UAT keystore。
- 不使用 `--split-per-abi`，只发布一个 universal APK。
- 首次安装可与 Prod 包并存；后续覆盖升级必须保持同一证书且提高 versionCode。
- APK 只通过私有 GitHub Pre-release 或受控渠道交付，不在公开网盘长期暴露。

iOS 本期只有无签名编译证据，不属于交付物。

## 13. Nginx、公网入口与 TLS

目标 Nginx 必须具备：

- 未知 Host/SNI 拒绝。
- 80 仅用于 ACME 和跳转 HTTPS。
- 443 使用 TLS 1.2/1.3。
- Admin SPA fallback。
- `/api/`、SSE、`/s/diary/`、`/legal/` 精确代理。
- `/images/` 只暴露公共上传目录，绝不暴露 private。
- 新公共图片继续由 ECS RAM Role 写入现有 UAT OSS；`/images/` 只兼容历史本地 URL。
- Admin 登录和未来短信端点独立限流。
- Admin 响应增加 `X-Robots-Tag: noindex, nofollow`。
- API 维护态返回 JSON 503，页面维护态返回中英双语 HTML 503。
- 仅公开聚合健康状态或改用等价外部功能冒烟，其他 Actuator 路径拒绝。

当前域名返回阿里云 `Non-compliance ICP Filing`，因此未解除备案/接入阻断前不得标记 UAT Ready。不得用裸 IP、HTTP 或忽略证书错误的客户端替代正式验收。

首次启用 HTTPS 按下面的可执行清单操作：

1. 在阿里云确认域名备案及当前接入服务商状态已经生效，公网访问不再进入合规拦截页。
2. 将 `joyingsong.net`、`www.joyingsong.net`、`api.joyingsong.net` 的 DNS A/AAAA 记录指向当前 ECS；未来 UAT 子域名另建 `uat.joyingsong.net`、`api-uat.joyingsong.net`，不得混用当前与未来 origin 对。
3. 在 ECS 安全组及主机防火墙开放 TCP 443；保留 TCP 80 仅用于 ACME HTTP-01 和 HTTPS 跳转。
4. 申请或导入覆盖当前三个 SAN 的证书。未来两个 UAT 子域名必须也被目标证书覆盖，或使用独立证书。私钥不得进入 Git、GitHub Actions 日志或 Release。
5. 将完整证书链和私钥分别安装到 `/etc/nginx/tls/joyingsong.net.pem`、`/etc/nginx/tls/joyingsong.net.key`，属主为 root，权限分别为 `0644`、`0600`。
6. 安装全机唯一的 default-deny server 和对应 UAT Nginx 模板，执行 `nginx -t` 成功后才 reload。当前主域名模板与未来 UAT 子域名模板都在 `joysong-server/deploy/nginx/`。
7. 用 `openssl x509 -in /etc/nginx/tls/joyingsong.net.pem -noout -dates -ext subjectAltName` 核对有效期与 SAN；再从 ECS 外部使用真实 DNS/SNI 验证证书链、跳转、API 健康和 Admin 深层路由。仅在本机用 `--resolve ...:127.0.0.1` 不能证明公网可达。
8. ACME 自动续期必须有 deploy hook，把更新后的 fullchain/key 同步到上述固定路径，重新设置权限，先 `nginx -t` 再 reload；同时设置证书到期告警。
9. 在最终切流前人工记录 Go/No-Go。切流维护窗口覆盖旧服务停写、最终数据库/配置备份、持久数据 delta、健康验证和 Nginx 原子替换；任一步失败都按脚本审计结果回退或保持维护态。

## 14. 自动与人工验收

### 14.1 自动技术验收

- manifest 和全部制品 SHA-256 匹配。
- systemd 为 active，实际进程使用当前 release。
- 本机健康检查通过。
- 公网使用批准的 UAT API/Admin HTTPS origin 对，并且两个入口返回本次 `release.json`。
- API 健康、Admin 根路径和 `/orders` 深层路由通过。
- APK 签名、包名、版本、Deep Link 和 API origin 正确。
- JAR、APK、Admin 和组装制品通过既定漏洞/secret 扫描门禁。
- 部署健康失败自动恢复旧版本；公网检查失败时，仅在新旧 migration digest 相同且旧版可验证时自动恢复，否则停止新服务并保持维护态等待人工处置。

### 14.2 人工业务验收

- Android 真机全新安装与覆盖升级。
- 只显示密码登录，隐藏入口不存在。
- 不连接开发电脑也能登录和浏览虚构目录。
- AI、翻译、图片上传和公开读取正常。
- 手工模拟支付立即成功。
- 用户提交全额退款，管理员批准后完成退款。
- 中英文协议、公开分享和 Admin 深层页面正常。

所有必需项通过后才能将 Draft 改为 Pre-release。

## 15. 回滚与恢复

### 15.1 应用回滚

- 只能选择服务器上已有、manifest 和哈希验证通过的 release。
- 切换前记录当前版本、目标版本、操作者和原因。
- 迁移保持向后兼容时自动切回 `previous`；不兼容时进入维护态并人工决策。
- 回滚不会自动执行数据库逆向 SQL。

### 15.2 数据恢复

- 备份必须包含环境配置、当前/上一版制品、Nginx、uploads/private/staging 和数据库；备份清单及 SHA-256 只能证明完整性，不能代替恢复演练。
- MySQL dump 只导出库内对象和数据，禁止携带 `CREATE DATABASE` 或 `USE`，避免误覆盖原库；凭据只从 root-only MySQL client 配置读取。
- UAT 只恢复到一个新建、空白且名称以 `myapp_worktree_` 开头的数据库。先打印并人工确认 host/name，再导入、执行 Flyway validate、Demo Verify、登录和关键只读检查，最后修改环境文件并切换。
- Prod 恢复到新的 RDS/数据库，验证登录、只读数据和关键交易状态后切换；不得在原实例上直接覆盖恢复。
- 原数据库保留到观察期结束，未经单独审批不得 drop、reset 或删除。应用回滚不自动执行逆向 SQL。

首次最终切流前至少完成一次上述 UAT “恢复到新空库”的人工演练并保存耗时、目标库、备份 SHA 和验证结果；未完成时，migration digest 变化的候选不得切流。仓库脚本目前负责生成和绑定完整性备份，不宣称已经自动验证业务级恢复。

## 16. Prod 晋级硬门禁

Prod 工作流默认禁用，并要求全部满足：

1. GitHub 变量 `PROD_DEPLOY_ENABLED=true`。
2. ECS root-owned `/etc/joysong/prod/DEPLOY_ENABLED` 存在且内容有效。
3. 稳定 tag `vX.Y.Z` 与一个已接受 UAT tag 指向同一 commit。
4. 后端和 Admin 制品哈希与已验收 UAT 完全一致。
5. Prod profile、数据库、目录和密钥与 UAT 隔离。
6. 模拟支付和自动支付在配置、启动校验和部署脚本三层均为 false。
7. RDS 备份完成并验证可见。
8. UAT 已先迁移到 UAT 子域名。
9. 生产业务 OSS 已采用经批准的版本化/软删除与保留策略，并完成数据库、本地持久文件和对象存储的一致恢复演练。

真实 Alipay+ 的签名、创建、查询、回调、退款和对账未完成；legacy Stripe 仍存在可创建新 Checkout 的未收口风险。因此交易型 Prod 当前明确为 **No-Go**。

## 17. 运维与保留

- UAT 日志保留 14 天，Prod 30 天。
- 每个 ECS 环境保留最近 5 个 release，不删除 `current`、`previous` 或 `failed-public-check` 指向的版本。
- root-only 主机备份在创建前做容量预检，至少预留估算备份量的两倍和 512 MiB 余量；达到 10 个已完成且 SHA 可自校验的历史备份后，自动流程停止并打印精确目录，由 root 人工审计后按明确路径清理。部署自动化不删除回滚材料。
- CI 临时制品和私有发布 Bucket 对象保留 30 天。
- GitHub Pre-release、正式 Release 和未来 Prod 符号文件长期保留。
- Prod 目标 RPO 24 小时、RTO 4 小时、RDS 备份 30 天。
- 监控至少覆盖进程、健康、5xx、延迟、磁盘、证书到期、数据库、AI/翻译额度和发布失败。
- 密钥轮换按“新增 -> 验证 -> 停用旧值”执行，不把旧值写入日志或 Issue。

## 18. 持续维护规则

以下变化必须在同一 Pull Request 更新本文或配置参考：

- GitHub workflow、发布脚本、Cloud Assistant、OIDC 或权限边界；
- profile、环境变量、启动校验、域名、端口、目录或 systemd；
- 数据库迁移、备份、恢复或 Demo 数据策略；
- 支付、SMS、Google、OSS、AI 或翻译开关；
- Android/iOS flavor、签名、版本或发布产物；
- Nginx 路由、健康检查、维护页或日志保留策略。

本文件是唯一部署流程入口；配置字典只是字段附录，`joysong-server/deploy/README.md` 只是可执行资产索引。旧部署指南不得恢复或新增平行流程。

指南只描述稳定流程。某次发布的 commit、SHA、数据版本和扫描结果一律进入 Release manifest 与验收 Issue，避免把易过期快照固化在本文。

## 19. 官方参考

- [GitHub Actions OIDC](https://docs.github.com/en/actions/reference/security/oidc)
- [GitHub OIDC immutable subject claims](https://github.blog/changelog/2026-04-23-immutable-subject-claims-for-github-actions-oidc-tokens/)
- [Alibaba Cloud：为可信 OIDC IdP 创建 RAM Role](https://www.alibabacloud.com/help/en/ram/user-guide/create-a-ram-role-for-a-trusted-idp)
- [Alibaba Cloud ECS RunCommand](https://www.alibabacloud.com/help/en/ecs/developer-reference/api-ecs-2014-05-26-runcommand)
- [Alibaba Cloud OSS 防止对象覆盖](https://www.alibabacloud.com/help/en/oss/user-guide/prevent-file-overwrite)
- [Alibaba Cloud RDS CreateBackup](https://www.alibabacloud.com/help/en/rds/developer-reference/api-rds-2014-08-15-createbackup)
