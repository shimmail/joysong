# JoySong 部署配置字典

> 本文是 [UAT 部署指南](./README.md) 的配置字典，不构成第二套部署流程。
>
> 最后核验：2026-09-06
>
> 这里只记录键名、语义和安全边界，不记录真实密码、Token、手机号或 AccessKey。

## 1. 配置来源

| 来源 | 用途 | 是否可含秘密 |
| --- | --- | --- |
| `application.yml` | 公共默认值和变量绑定 | 否 |
| `application-demo.yml` | Demo/UAT 强安全边界 | 否 |
| `/etc/joysong-demo/joysong.env` | 当前 UAT 运行时配置 | 是，`root:joysong-demo`、`0640` |
| `/etc/mysql/joysong-uat-backup.cnf` | root-only 备份账号 | 是，`root:root`、`0600` |
| bootstrap `<secrets-dir>` | 首次主机初始化输入 | 是，三个 `root:root`、`0600` 普通文件 |
| GitHub workflow | 非秘密发布规则和固定路径 | 否 |
| GitHub Actions Artifact | 本次 run 的发布制品 | 否，不得包含运行时配置 |
| Self-hosted Runner 本地配置 | 仓库 Runner 注册和调度 | 仅 GitHub Runner 自管凭据 |

首次 apply 要求 `<secrets-dir>` 必须且只含 `joysong.env`、`joysong-uat-backup.cnf` 和 `runner-registration-token`；三者必须是 `root:root`、`0600` 的单硬链接普通文件。进入安装事务后 token 在成功或失败退出时清理；首次发布前的二次 apply 要求目录必须且只含剩余两份输入。任何额外、缺失、链接或多硬链接成员均拒绝。禁止把 ECS 环境文件、数据库导出、应用秘密、TLS 私钥、GitHub Token 或 Runner 凭据提交到 Git、写入 Artifact、Draft Release 或 Issue。

## 2. 环境与服务 identity

| 项目 | 当前 UAT 固定值 | 约束 |
| --- | --- | --- |
| release environment | `uat` | tag 和 manifest 使用；不表示已公网 Ready |
| Spring profile | `demo` | 不创建新的 `uat` profile |
| systemd unit | `joysong-demo.service` | 不替换为 `joysong@uat` |
| systemd 用户/组 | `joysong-demo` | Runner 不加入该组 |
| Nginx 用户/组 | `www-data` | Ubuntu 24.04 固定契约；Runner 不加入该组 |
| Backend bind | `127.0.0.1:8080` | 不监听公网或 8081 |
| Backend 工作目录 | `/opt/joysong-demo/current` | 由仓库 systemd 模板决定并由 host-contract 核验 |
| 环境文件 | `/etc/joysong-demo/joysong.env` | root-owned，Runner 不可读 |
| 时区 `TZ` | 由首次 `joysong.env` 明确提供 | tag 部署不覆盖应用时区 |

部署脚本可在内部称此固定布局为 in-place UAT，但这不是新的 profile、service 或公网环境。

## 3. 服务与公网地址

| 变量 | 敏感 | 当前 UAT 约束 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 否 | 精确为 `demo` |
| `SERVER_ADDRESS` | 否 | 精确为 `127.0.0.1` |
| `SERVER_PORT` | 否 | 精确为 `8080` |
| `SERVER_BASE_URL` | 否 | 由首次 secrets 明确提供，tag 发布不改 |
| `APP_SHARE_BASE_URL` | 否 | 与 Server origin 一致，tag 发布不改 |
| `CORS_ALLOWED_ORIGINS` | 否 | 首次明确精确 origin，禁止 `*` |
| `SERVER_FORWARD_HEADERS_STRATEGY` | 否 | 与现有 Nginx 转发头一致 |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | 否 | 只信任本机反向代理 |
| `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | 否 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER` | 否 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_HOST_HEADER` | 否 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_PORT_HEADER` | 否 | 与 Nginx 一致 |
| Nginx 配置 identity | 否 | bootstrap 安装的仓库模板及 root-only host-contract；tag 发布前后摘要必须不变 |
| Admin 本机校验 | 否 | `Host: joyingsong.net` 请求 `127.0.0.1:80` 的 `/` 与 `/orders` |

bootstrap 负责首次安装 Nginx 契约；候选 tag 发布不修改 Nginx、DNS、证书或这些公网 origin。后续 TLS 窗口必须把环境变量、Nginx、DNS 和客户端配置作为一个独立变更统一验证；完成前仍是“UAT Candidate 已部署，公网未 Ready”。

## 4. 数据库与 Flyway

| 变量/文件 | 敏感 | 当前 UAT 约束 |
| --- | --- | --- |
| `DB_URL` | 部分 | JDBC MySQL URL；不得含 `createDatabaseIfNotExist` |
| `DB_USERNAME` | 是 | 只在 ECS 环境文件中 |
| `DB_PASSWORD` | 是 | 只在 ECS 环境文件中 |
| `DEMO_DATABASE_NAME` | 否 | 精确为 `myapp_worktree_uat`，并与 JDBC 选择及 `SELECT DATABASE()` 完全一致 |
| `/etc/mysql/joysong-uat-backup.cnf` | 是 | 普通非链接文件，`root:root`、`0600` |

UAT 活动数据库名必须精确为 `myapp_worktree_uat`。任何迁移、备份或恢复动作前都必须打印实际 host/name；日志不得输出账号或密码。

bootstrap 创建的应用账号只获得该库的数据库级权限且没有 `GRANT OPTION`；独立备份账号只获得该库的 `SELECT`、`SHOW VIEW`、`TRIGGER`、`LOCK TABLES`，以及 `mysqldump --routines` 在 MySQL 8 所需的全局动态 `SHOW_ROUTINE`，并在二次 apply 时精确复核这些授权。

空库系统账号白名单包含 Ubuntu 官方 MySQL 安装器创建的 `debian-sys-maint@localhost`；它不是应用/备份身份，不复用其凭据，也不允许同名远端 Host 或其他未知账号。依据：[Ubuntu Noble MySQL 8.0 安装源码](https://launchpad.net/ubuntu/+archive/primary/+sourcefiles/mysql-8.0/8.0.45-0ubuntu0.24.04.1/mysql-8.0_8.0.45-0ubuntu0.24.04.1.debian.tar.xz)。

固定 Flyway 属性：

| Spring 属性 | 值 |
| --- | --- |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.flyway.enabled` | `true` |
| `spring.flyway.validate-on-migrate` | `true` |
| `spring.flyway.baseline-on-migrate` | `false` |
| `spring.flyway.clean-disabled` | `true` |

fresh preflight 要求无业务表和 Flyway history；首次部署只接受当前 JAR 将空库迁移为成功的 B33 和 V34…V40。existing 要求当前历史精确为该集合，candidate 与 current JAR 的 migration digest 完全相同，并在备份前、停机前、启动后和最终检查时保持完整快照一致。

恢复只允许写入此前不存在的 `myapp_worktree_restore_*` 数据库。不得覆盖、drop、reset 或 clean 当前数据库。

## 5. JWT、管理员与认证

| 变量 | 敏感 | 当前 UAT 约束 |
| --- | --- | --- |
| `JWT_SECRET` | 是 | 只留在 ECS，至少 32 字符 |
| `ADMIN_PHONE` | 是 | 精确匹配 `1[0-9]{10}`；首次 secrets 提供，后续保留，不写入发布证据 |
| `ADMIN_PASSWORD` | 是 | fresh 必须为 12–128 字符；existing 必须彻底移除键，空值也拒绝 |
| `GOOGLE_CLIENT_ID` | 否 | 当前 UAT 不设置 |
| `GOOGLE_PROXY_URL` | 否 | 当前 UAT 不设置 |
| `SMS_ENABLED` | 否 | 当前 UAT 为 `false` |

existing 发布事务不得创建、修改或输出管理员凭据，也不得用部署健康检查替代登录验收。fresh 首次空库仅可用 `ADMIN_PASSWORD` 完成初始化；首次健康成功后必须分别从安装配置和受控 bootstrap 输入中原子移除该键、保留 owner/mode、受控重启并复验；已有 root-only 备份不改写。bootstrap 在安装前校验首次管理员输入，部署在加载主机/数据库状态前复核 fresh/existing 要求；长度按 Kotlin UTF-16 字符单元计算。管理员手机号轮换是独立、可审计的运维动作，不得夹带在后续 tag 发布中。

## 6. 持久文件与业务 Bucket

| 变量/路径 | 敏感 | 当前 UAT 约束 |
| --- | --- | --- |
| `UPLOAD_LOCAL_DIR` | 否 | 固定为 `/var/lib/joysong-demo/uploads` 并纳入适用备份 |
| `UPLOAD_STAGING_DIR` | 否 | 固定为 `/var/lib/joysong-demo/upload-staging`，不由 Nginx 暴露 |
| `UPLOAD_PRIVATE_DIR` | 否 | 固定为 `/var/lib/joysong-demo/private`，禁止公网暴露 |
| `UPLOAD_IDEMPOTENCY_CLEANUP_DELAY_MS` | 否 | 由首次配置明确，tag 发布不调参 |
| `PRIVATE_FILE_STORAGE_MODE` | 否 | 由首次配置明确，tag 发布不切换 |
| `OSS_ENABLED` | 否 | 由首次配置明确，tag 发布不切换 |
| `OSS_BUCKET_NAME` | 否 | `joysong-demo-media-cn-hangzhou-1335549182926992` |
| `OSS_PRIVATE_BUCKET_NAME` | 否 | `joysong-demo-private-cn-hangzhou-1335549182926992` |
| `OSS_ENDPOINT` / `OSS_REGION` | 否 | 首次配置绑定杭州，tag 发布不改 |
| `OSS_CREDENTIAL_MODE` | 否 | 首次配置明确；Runner 不继承 |
| `OSS_ECS_RAM_ROLE_NAME` | 否 | 若使用则必须与已批准实例角色一致；本流程不创建或修改角色 |
| `OSS_PUBLIC_BASE_URL` | 否 | 首次配置明确业务对象地址，本流程不改域名 |
| `OSS_CONNECTION_TIMEOUT_MS` / `OSS_SOCKET_TIMEOUT_MS` / `OSS_REQUEST_TIMEOUT_MS` | 否 | 首次配置明确，tag 发布不调参 |
| `OSS_MAX_CONNECTIONS` / `OSS_MAX_ERROR_RETRY` | 否 | 首次配置明确，tag 发布不调参 |
| `OSS_ACCESS_KEY_ID` | 是 | 不得进入 GitHub、Runner 或发布包 |
| `OSS_ACCESS_KEY_SECRET` | 是 | 不得进入 GitHub、Runner 或发布包 |

两个业务 Bucket 已存在，部署不得修改 ACL、权限、对象、生命周期或覆盖策略，不得把它们当发布制品仓库。Self-hosted Runner 不持有业务 OSS 权限。

应用 release 清理绝不能删除 uploads、private、staging 或业务 OSS 对象。必要持久目录必须在停机前形成 root-only 校验备份。

## 7. AI、翻译、SMS 与支付

| 变量 | 敏感 | 当前 UAT 约束 |
| --- | --- | --- |
| `AI_AGENT_PROVIDER` | 否 | 由首次配置明确，tag 发布不切换 |
| `AI_AGENT_API_KEY` | 是 | 只留在 ECS 环境文件 |
| `AI_AGENT_BASE_URL` / `AI_AGENT_MODEL` | 否 | 由首次配置明确，tag 发布不改 |
| `AI_AGENT_INTENT_MODEL` | 否 | 由首次配置明确，tag 发布不改 |
| `TRANSLATION_PROVIDER` | 否 | 由首次配置明确，tag 发布不切换 |
| `TRANSLATION_API_KEY` | 是 | 只留在 ECS 环境文件 |
| `TRANSLATION_BASE_URL` / `TRANSLATION_MODEL` | 否 | 由首次配置明确，tag 发布不改 |
| `SMS_ENABLED` | 否 | `false` |
| `SMS_ACCESS_KEY_ID` / `SMS_ACCESS_KEY_SECRET` | 是 | SMS 关闭时不设置；不得进入发布链路 |
| `SMS_SIGN_NAME` / `SMS_TEMPLATE_CODE` | 否 | SMS 关闭时不设置 |
| `ALIPAY_PLUS_SIMULATED_ENABLED` | 否 | 由首次配置明确，tag 发布不切换 |
| `ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED` | 否 | `false` |
| `PAYMENT_RECONCILIATION_ENABLED` | 否 | `false` |
| `STRIPE_LEGACY_ENABLED` | 否 | `false` |

这些运行时值不进入 manifest。`/actuator/health` 成功也不证明 AI、翻译、SMS 或支付业务可用；业务能力由后续 UAT 验收确认。

## 8. Demo 数据

| 变量 | 当前 UAT 约束 |
| --- | --- |
| `DEMO_DATA_ENABLED` | 常规服务固定为 `false` |
| `DEMO_DATA_ACTION` | 为空或 `VERIFY`；发布不得执行 `APPLY` |
| `DEMO_CATALOG_PATH` | 常规发布不设置临时 catalog |
| `DEMO_ACCOUNT_PASSWORD` | 常规发布不设置 |

部署流程不得重建 Demo 数据或借发布执行 seed。

## 9. 主机路径与权限

| 路径 | owner/访问边界 | 用途 |
| --- | --- | --- |
| `/opt/joysong-demo/releases/<tag>` | root 管理，应用只读 | Backend 不可变 release |
| `/opt/joysong-demo/current` | root 管理的 symlink | 当前 Backend |
| `/opt/joysong-demo/previous` | root 管理的 symlink | 上一 Backend |
| `/var/www/joysong-demo/releases/<tag>` | root 管理，Nginx 只读 | Admin 不可变 release |
| `/var/www/joysong-demo/current` | root 管理的 symlink | 当前 Admin |
| `/var/www/joysong-demo/previous` | root 管理的 symlink | 上一 Admin |
| `/var/lib/joysong-deploy/state` | root-only | 锁、事务、pair identity |
| `/var/lib/joysong-deploy/backups` | root-only | 校验后的配置/DB/数据备份 |
| `/var/lib/joysong-deploy/incoming` | Runner 仅能写受控 run 子目录 | 待验证 Artifact |
| `/var/lib/joysong-demo/uploads` | `joysong-demo:www-data`，Runner 不可写 | 公共上传持久目录 |
| `/var/lib/joysong-demo/private` | 应用可用，Runner 不可读/遍历 | 私密持久目录 |
| `/var/lib/joysong-demo/upload-staging` | 应用可用，Runner 不可读/遍历 | 上传暂存目录 |
| `/opt/joysong-actions-runner` | `joysong-gh-runner` | Runner 程序 |
| `/var/lib/joysong-actions-runner` | `joysong-gh-runner` | Runner work |
| `/usr/local/sbin/joysong-uat-deploy` | `root:root`，不可被 Runner 修改 | 唯一 sudo 部署入口 |
| `/etc/joysong-demo/host-contract` | `root:root`、`0600` | 仅 `SYSTEMD_UNIT_SHA256` 与 `NGINX_SITE_SHA256` |
| `/etc/systemd/system/joysong-demo.service` | `root:root`，由 bootstrap 安装 | 仓库 `systemd/joysong-demo.service` 的固定副本 |
| `/etc/nginx/conf.d/joysong-public.conf` | `root:root`，Nginx 读取 | 仓库 `nginx/joysong-public.conf` 的固定副本 |

部署入口必须拒绝 symlink、hardlink、特殊文件、越界路径、非预期 owner/mode、重复 tag 和非空目标 release。

## 10. Bootstrap 与 Runner 配置

### 10.1 Bootstrap 接口

```text
bootstrap-uat-host.sh preflight
bootstrap-uat-host.sh apply <source-dir> <secrets-dir> <runner-archive> <runner-version> <runner-sha256>
```

`preflight` 是严格只读的 fresh-host 检查，TCP 只允许 SSH 与经服务 PID/可执行文件身份核验的 `systemd-resolved` 本机 DNS。`apply` 固定安装 OpenJDK 17、Nginx、MySQL 8.0、Python 3、curl、unzip、sudo、rsync、iproute2，以及仓库 `systemd/joysong-demo.service` 和 `nginx/joysong-public.conf`；版本校验接受 Ubuntu 的 `mysql Ver 8.0.x` 输出，不接受 MariaDB 或 MySQL 8.4。第二次执行仅用于首次发布前的完全一致性复核，不是发布后的配置修复入口；已有未知目标、敏感配置或模板摘要漂移、Runner 不一致均 fail closed。

### 10.2 Runner 固定值

| 项目 | 固定值/约束 |
| --- | --- |
| scope | 当前 Private 仓库专用 |
| 用户 | `joysong-gh-runner`，nologin，非 root |
| 版本/平台 | 官方 Linux x64 `v2.337.0` |
| archive SHA-256 | `70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613` |
| 标签 | 以 `--no-default-labels` 注册，只保留 `joysong-uat-deploy` |
| 自动更新 | `--disableupdate`，禁止注册后静默偏离已批准版本；更新须另行校验 archive/version/hash |
| 注册 identity | `.runner` 中正整数 `agentId`、固定 `agentName`/`gitHubUrl`/`workFolder`，且 `disableUpdate=true`；二次 apply 拒绝漂移 |
| 应用组 | 不加入 `joysong-demo`、`www-data` 或其他应用组 |
| 云凭据 | 无 |
| 元数据隔离 | Runner `IPAddressDeny=100.100.100.200/32`；注册前、每次启动和二次初始化均验证，应用 IMDSv2 正向检查前后成功、Runner 同 cgroup 负向检查失败才通过 |
| 应用秘密 | 无，且不能读 `/etc/joysong-demo/joysong.env` |
| sudo | 仅 `/usr/local/sbin/joysong-uat-deploy`，`NOSETENV`、固定 `PATH` |
| 当前主机 OS | 已验证 Ubuntu 24.04.4 LTS / x86_64 / systemd 255 |
| 兼容层 | 无；禁止安装 CentOS/GLIBC side-by-side 兼容层 |
| deploy job | 永久纯 shell、零 `uses:`；不得启动内置 Node 20/24 Action runtime |
| online 门禁 | Listener `2.337.0`、service active、GitHub Runner API `online` |

Runner 注册 token 是一次性接入材料，只能位于受控 `<secrets-dir>/runner-registration-token`。注册前核验 Listener 版本；root 读取 token 并通过受控 PTY 响应隐藏提示，不通过 `--token`、Runner 环境变量或普通 stdin 管道。终端输出不转发，重复提示、超时或注册失败即终止；进入安装事务后成功或失败退出均清理 token。不得提交、写入 workflow、保存到 Issue 或长期记录在 shell history。

元数据探针固定安装在 `/usr/local/lib/joysong-deploy/check-runner-metadata.py`，由 bootstrap 内嵌模板生成，要求 `root:root 0644`。只有固定启动探针使用 `ExecStartPre=+`，不会给 Runner 开放通用 root 命令。正向检查仅读取 token 和角色名称，不请求角色凭据正文；不新增应用环境变量，不改变现有 ECS 实例角色或 Bucket 权限。

固定版本不是无限期免更新：执行前必须再次确认 GitHub 仍接受该版本；版本到期或安全更新导致不调度时停止，先更新批准基线和校验摘要，不通过开启静默更新绕过契约。维护规则见 [GitHub Self-hosted runners reference](https://docs.github.com/en/actions/reference/runners/self-hosted-runners#runner-software-updates-on-self-hosted-runners)。

## 11. GitHub Actions 配置

### 11.1 仓库与分支

- 仓库必须为 Private。
- 默认/受保护目标分支为 `master`。
- 必需检查名固定为 `Required quality gates`。
- UAT tag 格式固定为 `vX.Y.Z-uat.N`。
- 部署并发组固定为 `uat-deployment`，活动部署不取消。

### 11.2 job 权限

| job | 最小权限 |
| --- | --- |
| hosted preflight | `actions: read`、`contents: read` |
| hosted build/package | `contents: read` |
| ECS deploy | `actions: read`、`contents: read` |
| hosted publish | `actions: read`、`contents: write`、`issues: write` |

Self-hosted deploy job 不得获得 `contents: write`、`issues: write`、OIDC `id-token: write` 或云 AccessKey。
该 job 还必须保持零 `uses:`；下载本次 Artifact、校验和调用固定入口全部使用仓库中受校验的纯 shell 契约。

publish job 不 checkout，通过非秘密 `GH_REPO: ${{ github.repository }}` 固定 GitHub CLI 的仓库上下文；其 `GH_TOKEN` 仅为 GitHub 自动签发的 job token，不是 ECS 应用秘密或 Runner 注册 token。

### 11.3 当前不需要的旧配置

以下旧方案键不属于当前 UAT 主路径，不应成为 UAT tag workflow 的必需输入：

- `ALIYUN_OIDC_PROVIDER_ARN`；
- `ALIYUN_OIDC_ROLE_ARN`；
- `ALIYUN_RELEASE_BUCKET` / `ALIYUN_RELEASE_PREFIX`；
- `ALIYUN_CLOUD_ASSISTANT_COMMAND_ID`；
- UAT Android/iOS 签名 secrets。

第三方 Action 的 `uses` 必须固定到完整 commit SHA。Runner label、固定路径、tag 格式和并发组属于仓库内非秘密配置；真实 Token、凭据和环境文件不属于 GitHub 配置。

若仓库中暂时保留这些值供未来 Prod 设计或旧资源只读盘点，当前 UAT workflow 不得读取或使用它们。

## 12. 发布 identity

| 字段 | 约束 |
| --- | --- |
| `schemaVersion` | 固定支持版本 |
| `environment` | `uat` |
| `tag` | `vX.Y.Z-uat.N` |
| `commit` | 40 位小写 Git SHA |
| `runId` / `buildNumber` | 正整数，并与当前 workflow run 绑定 |
| `databaseMigrationsSha256` | 64 位小写摘要，必须与当前 JAR 相同 |
| Backend/Admin hashes | 覆盖部署的精确文件和树 |

`SHA256SUMS` 必须覆盖包内精确证据集合；外层 `.tar.gz.sha256` 绑定 Runner 下载的单一部署包。主机端必须重新计算，不能只信任 workflow 输入。

## 13. 更新规则

新增、删除或修改环境变量、Runner 权限、目录、manifest 字段或部署参数时，同一 PR 必须更新：

1. 实际 workflow、脚本或应用绑定；
2. 对应 validator 和单元测试；
3. 本配置字典；
4. 若行为变化，同时更新权威部署指南。

不得通过把真实值写进仓库来修复缺失配置。

## 14. 首次秘密交付准备

2026-09-06 两份持久文件已在仓库外受控目录准备并通过配置校验；应用使用已绑定的 `joysong-demo-oss-role`，不配置静态 OSS AccessKey。用户已授权继续部署。初始化前仍须完成 Runner 元数据隔离修复、精确 SHA 门禁和 fresh-host 复核；配置就绪不表示 ECS 已初始化或业务验收通过。

先在仓库之外准备受控目录，向执行者仅提供目录路径，不粘贴内容。Windows 本地输入用 ACL 限定本人及必要管理员访问；交付到 ECS 后目录必须为 `root:root 0700`，文件为 `root:root 0600`、普通单硬链接文件。不要把开发 `.env.example` 直接作为 UAT 配置：其中数据库名、路径和业务开关不构成本主机契约。

`joysong.env` 使用 UTF-8、逐行 `KEY=value`，不加 `export`，不重复键、不放多行值，不依赖 shell 插值；秘密建议采用不含空白、引号或反斜杠的随机 ASCII 值，以避免 systemd 和校验器转义语义差异。最少确认：

- 固定值：`SPRING_PROFILES_ACTIVE=demo`、`SERVER_ADDRESS=127.0.0.1`、`SERVER_PORT=8080`、`DEMO_DATABASE_NAME=myapp_worktree_uat`；`DB_URL=jdbc:mysql://127.0.0.1:3306/myapp_worktree_uat`，不得请求自动建库。
- 秘密：独立应用账户 `DB_USERNAME`/`DB_PASSWORD`、至少 32 字符的 `JWT_SECRET`、有效 `ADMIN_PHONE`、12–128 字符首次 `ADMIN_PASSWORD`。不使用开发或生产凭据。
- 持久目录：`UPLOAD_LOCAL_DIR=/var/lib/joysong-demo/uploads`、`UPLOAD_STAGING_DIR=/var/lib/joysong-demo/upload-staging`、`UPLOAD_PRIVATE_DIR=/var/lib/joysong-demo/private`。
- 安全开关：`SMS_ENABLED=false`、`DEMO_DATA_ENABLED=false`、`ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED=false`、`PAYMENT_RECONCILIATION_ENABLED=false`、`STRIPE_LEGACY_ENABLED=false`；不配置 Demo seed、Google 或未启用能力的秘密。
- 待明确的非秘密选项：`TZ`、公网 origin/分享地址/精确 CORS、`OSS_ENABLED`、`PRIVATE_FILE_STORAGE_MODE`、AI/翻译 endpoint/model、模拟支付开关。它们须由负责人确认，不从旧分支或旧主机恢复。若选择 OSS，先确认既有授权和凭据模式，不创建或修改实例角色/Bucket；选择本地存储或关闭 AI 也不能声称相关业务已验收。

`joysong-uat-backup.cnf` 仅含一个 `[client]` 段；允许键仅为 `host`、`port`、`protocol`、`user`、`password`、`database`。固定 `host=127.0.0.1`、`port=3306`、`protocol=TCP`、`database=myapp_worktree_uat`；user 必须不同于应用账户，password 为独立秘密，不提供其他 MySQL 选项。bootstrap 负责建库和授予最小权限，准备文件本身不应连接数据库。

两份文件和非秘密选项确认后，执行者先核验本地门禁及远端空白状态，再临时取得本仓库的一次性 registration token，形成恰好三个文件的首次输入。不要提前申请、在聊天中粘贴或用长期 GitHub PAT 代替该 token。秘密仍缺失或发现配置/主机漂移时，在此停止。
