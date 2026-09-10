# JoySong UAT 部署指南

> 文档状态：唯一现行部署入口（Canonical）
>
> 适用范围：已换为 Ubuntu 24.04 的空白阿里云 ECS 初始化与 UAT Candidate 发布
>
> 最后核验：2026-09-10
>
> 配置字典：[CONFIGURATION_REFERENCE.md](./CONFIGURATION_REFERENCE.md)
>
> App 公网 IP HTTPS 入口：[PUBLIC_IP_HTTPS.md](./PUBLIC_IP_HTTPS.md)

## 1. 当前结论

当前 UAT 使用“快速收敛”方案：

```text
GitHub-hosted Runner
  -> 测试、构建、SBOM、扫描和发布包
  -> GitHub Actions Artifact
  -> ECS 仓库专用 Self-hosted Runner
  -> /usr/local/sbin/joysong-uat-deploy
  -> joysong-demo.service 健康检查与自动回滚
```

目标 ECS 已完成系统盘切换并经 2026-09-06 Workbench 只读复核：Ubuntu 24.04.4 LTS、x86_64、systemd 255，2 vCPU、约 3.4 GiB 内存，40 GB 根盘约 35 GB 可用；TCP 监听为 SSH 与 `systemd-resolved` 的 `127.0.0.53/54:53` 本机 DNS，JoySong 用户、服务、Runner、部署目录及 MySQL 凭据文件均不存在。它必须按 **fresh host** 初始化，任何新增或无法解释的状态都会阻断写操作。秘密尚未准备，目前没有远端初始化或首次发布证据；此快照不替代执行前复核。

初始化后的固定运行边界为：

| 项目 | 固定值 |
| --- | --- |
| ECS | `cn-hangzhou` / `i-bp19abm7697mvhl0xewu` |
| ECS 公网 IP | `121.41.230.98`（仅资产识别，不代表公网 Ready） |
| OS | Ubuntu 24.04.4 LTS / x86_64 / systemd 255 |
| systemd | `joysong-demo.service` |
| systemd 用户/组 | `joysong-demo:joysong-demo` |
| Nginx 用户/组 | `www-data:www-data` |
| Spring profile | `demo` |
| Backend | `127.0.0.1:8080` |
| 环境文件 | `/etc/joysong-demo/joysong.env` |
| Backend 根目录 | `/opt/joysong-demo` |
| Admin 根目录 | `/var/www/joysong-demo` |
| Nginx | bootstrap 从仓库模板安装；发布只验证、不修改 |
| 数据库 | 本机 MySQL 8 / `myapp_worktree_uat` |
| Flyway history | fresh 时为空；首次成功后为 B33 与 V34 至 V40 |
| 普通业务 Bucket | `joysong-demo-media-cn-hangzhou-1335549182926992` |
| 私密业务 Bucket | `joysong-demo-private-cn-hangzhou-1335549182926992` |

不得创建第二个 UAT 服务、监听 8081、在 tag 发布中修改活动 Nginx 配置、重建既有数据库、改变两个业务 Bucket，或清空上传和私密文件。

2026-09-10 已为当前 UAT 增加受信任的公网 IP HTTPS 技术入口：

> `https://121.41.230.98`

Nginx 对外提供 80/443，后端与 MySQL 仍保持回环监听。该入口用于 UAT App
联调，不代表生产上线，也不替代尚未完成的人工业务验收。配置、续期和回滚说明见
[PUBLIC_IP_HTTPS.md](./PUBLIC_IP_HTTPS.md)。

UAT Candidate 发布成功后的固定状态仍是：

> UAT Candidate 已部署；公网 HTTPS 技术入口可用，业务验收未完成

它只证明内部制品、主机事务和本机健康检查通过。ICP、DNS、TLS 和公网业务验收是后续独立门禁。

## 2. 当前主路径与保留资产

当前 UAT 主路径不使用：

- UAT 发布 OSS Bucket；
- 阿里云 OIDC Provider 或发布 RAM Role；
- Cloud Assistant 固定命令或远程 dispatcher；
- `joysong@uat:8081` 和 Nginx 双服务切流；
- Android、iOS、APK 或签名材料。

若上述云资源已由早期方案创建，只做只读盘点，不用于发布，也不在本流程中删除。旧 `uat-final-cutover` 的 OIDC、发布 OSS、Cloud Assistant、远程 dispatcher、8081 切流、Promote 和远程 Rollback 执行代码均不迁移；其中制品身份、完整备份、成对回滚和 fail-closed 原则已收敛到当前实现。

仓库内保留的通用 `joysong@.service`、Prod workflow 或旧远程发布脚本同样不代表当前 UAT 行为。执行事实以本指南、Ubuntu bootstrap、UAT tag workflow 和固定主机入口为准。

## 3. 身份与权限边界

### 3.1 GitHub-hosted Runner

GitHub-hosted Runner 负责所有不需要主机访问的工作：

- Backend 测试和构建；
- Admin lint、typecheck 和构建；
- workflow、Shell 与 Python 校验；
- manifest、release identity、SHA-256、SBOM 和扫描报告；
- 组装 `joysong-uat-<tag>.tar.gz` 并上传本次 run 的 Artifact；
- 部署成功后创建或更新私有 Draft Release 和验收 Issue。

它不能读取 ECS 运行时秘密，也不持有数据库、业务 OSS 或阿里云凭据。

### 3.2 ECS 专用 Runner

仓库级 Self-hosted Runner 固定为：

| 项目 | 值 |
| --- | --- |
| 用户 | `joysong-gh-runner` |
| 安装目录 | `/opt/joysong-actions-runner` |
| work 目录 | `/var/lib/joysong-actions-runner` |
| 自定义标签 | `joysong-uat-deploy` |
| 版本 | 官方 Linux x64 `v2.337.0` |
| archive SHA-256 | `70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613` |

安全约束：

- Runner 以非 root 用户运行，禁止交互登录；
- 使用 `--no-default-labels` 注册，不加入 `joysong-demo`、`www-data`、Docker、sudo 等应用或特权组；
- 使用 `--disableupdate` 固定已批准版本；版本到期或必需安全更新时停止发布并单独更新基线，不让 Runner 静默升级；
- 不得读取 `/etc/joysong-demo/joysong.env`；
- 不保存数据库、OSS、阿里云、JWT、AI、翻译或其他应用秘密；
- Runner 服务通过 `IPAddressDeny=100.100.100.200/32` 禁止访问 ECS 元数据；每次启动前在相同 cgroup 内执行 root-owned 探针，独立应用用户服务在负向检查前后均须成功访问 IMDSv2 token 和角色列表。只检查可达性，不获取或输出角色凭据；单独超时不算隔离成功。注册前预验证、二次初始化复核及每次启动均失败即停止；
- 不配置通用 `linux`、`x64` 调度标签，deploy job 只指定 `joysong-uat-deploy`；
- PR workflow 永不调度到该 Runner；
- Runner 只能写自己的 work 目录和受控 `incoming` 区域；
- deploy job 只授予 `contents: read` 和读取本次 Artifact 所需权限；
- Release 和 Issue 写操作必须回到 GitHub-hosted job。

仓库级 Runner 仍有固有风险：能够修改默认分支 workflow 的攻击者，可能尝试把任务调度到 UAT 主机。因此 Runner 的影响面必须被固定 root 入口、最小 sudoers、目录权限和无秘密设计共同限制。Prod 不得直接沿用该仓库级 Runner 模型，必须另行完成更强的环境隔离、审批和发布身份设计。

Ubuntu 24.04 不安装任何 CentOS/GLIBC 兼容层。接入证据必须同时包含 `Runner.Listener --version == 2.337.0`、Runner service active 和 GitHub Runner API `online`；未全部通过前不得推送 UAT tag。ECS deploy job 仍固定为纯 shell、零 `uses:`，以缩小第三方 Action 在主机上的执行面。

官方 Runner 固定归档包含 Node 20/24 的六个工具符号链接。校验器仅放行其精确路径与目标，并要求目标为包内普通文件、无重复路径和链接子路径；其他链接仍拒绝。此例外不适用于应用发布包。

官方归档提供 `bin/runsvc.sh`；bootstrap 按官方 service template 将其复制为根目录 `runsvc.sh`，设置 Runner 属主及 `0755`，继续使用项目的隔离服务单元。重复验收检查源文件与副本一致、权限和属主不漂移。初始化中断且未写入 host contract 时，不直接重跑 fresh 初始化；必须核实准确断点和已有状态后续行，不能删除或重建数据库以满足 fresh 检查。

MySQL 与 mysqldump 共享备份连接配置，但 mysqldump 不支持 `[client]` 中的 `database` 选项。发布入口在 root-only staging 中派生临时配置，只排除此项，并继续通过固定位置参数指定 UAT 数据库；成功后立即移除，失败由既有 staging 清理处理。原受控配置保持不变，密码不进入命令参数或环境变量。

`/usr/local/lib/joysong-deploy/check-runner-metadata.py` 由 bootstrap 生成并以 `root:root 0644` 安装。仅固定 `ExecStartPre=+` 探针以 root 运行，用于向 PID 1 请求独立应用正向检查；Runner 本体仍为 `joysong-gh-runner`。二次初始化精确验证探针内容、加载的拒绝规则、空允许规则、无 drop-in、无需 daemon reload 以及启动探针成功记录。不能通过普通 `runuser` 检查代替同一 systemd cgroup 内的检查。

### 3.3 root 部署入口

Runner 只能通过 sudo 调用：

```text
/usr/local/sbin/joysong-uat-deploy
```

sudoers 只允许这一固定程序，并启用 `NOSETENV` 和固定安全 `PATH`。不得授予通用 `systemctl`、`bash`、`sh`、`cp`、`ln`、编辑 sudoers或任意 root shell。

入口只接受固定 action、`vX.Y.Z-uat.N` tag、40 位小写 commit、数字 run ID 和 64 位小写 SHA-256。服务名、端口、目录、用户和命令都由 root-owned 脚本内部固定推导；CI 不能覆盖。

## 4. 主机目录契约

```text
/opt/joysong-demo/
  releases/<tag>/joysong-server.jar
  current  -> releases/<tag>
  previous -> releases/<previous-tag>

/var/www/joysong-demo/
  releases/<tag>/
  current  -> releases/<tag>
  previous -> releases/<previous-tag>

/var/lib/joysong-deploy/
  state/                       # 锁、事务和 previous pair identity
  backups/<timestamp>-<tag>/   # 配置、数据库、运行身份和持久数据备份
  incoming/<run-id>/           # Runner 下载的待验证 Artifact

/etc/joysong-demo/joysong.env               # root:joysong-demo 0640
/etc/mysql/joysong-uat-backup.cnf           # root:root 0600
/etc/joysong-demo/host-contract             # root:root 0600，仅两个模板 SHA
/etc/systemd/system/joysong-demo.service    # 从仓库固定模板安装
/etc/nginx/conf.d/joysong-public.conf       # 从仓库固定模板安装
```

`current` 和 `previous` 必须始终按 Backend/Admin 成对解释。不能把一个新 Backend 与旧 Admin 视为成功状态，也不能让两个 `previous` 指向不同版本。

Runner 不能写 release、state 或 backups。它把本次 Artifact 写入 `incoming/<run-id>`；root 入口按 run ID 推导路径，拒绝链接、特殊文件、越界路径、重复 tag 和已有 release 覆盖，然后复制到 root 控制的 staging 区域验证和安装。

fresh 初始化前上述路径必须不存在。首次发布成功后，release 清理绝不能触及数据库、业务 OSS、uploads、private、staging、TLS、Nginx 配置或 host-contract。

## 5. GitHub Actions

### 5.1 PR 和 master

PR 与 `master` push 只运行 GitHub-hosted Runner。门禁至少包括：

- Backend 最小相关测试和构建；
- Admin lint、typecheck 和 build；
- workflow YAML parse 和仓库 validator；
- actionlint；
- 所有部署 Shell 的 `bash -n` 与 ShellCheck；
- Python AST 和发布包/主机部署单元测试；
- SBOM 与基础安全扫描。

所有第三方 Action 必须固定到完整 commit SHA；版本标签只能作为旁注，不能作为实际 `uses` 引用。

稳定汇总 job 名称固定为 `Required quality gates`。目标分支明确为 `master`，不得因为 path filter 让分支保护永久等待。

### 5.2 UAT tag

触发 tag：

```text
v*.*.*-uat.*
```

workflow 必须依次：

1. 拒绝 rerun，并确认仓库为 Private。
2. 验证 tag 精确匹配 `vX.Y.Z-uat.N`。
3. 验证 tag commit 可从 `origin/master` 到达。
4. 验证同一 commit 的 `Required quality gates` 和 `master` push run 均成功。
5. 在 GitHub-hosted Runner 构建 Backend/Admin，生成证据并上传 Artifact。
6. 在 ECS 专用 Runner 下载本次 Artifact并核对外层 SHA-256。
7. 调用固定 root 部署入口；Self-hosted job 不进行 Release 或 Issue 写操作。
8. 返回 GitHub-hosted Runner，创建或更新私有 Draft Release。
9. 创建不含移动端项目的 UAT 验收 Issue，状态写为“UAT Candidate 已部署，公网未 Ready”。

publish job 不 checkout 仓库，必须通过 `GH_REPO: ${{ github.repository }}` 显式绑定所有 `gh release/issue/label` 操作的目标。

部署并发组固定为 `uat-deployment`，`cancel-in-progress: false`。失败 tag 永不复用；修复后必须递增 UAT 序号。

## 6. 发布包契约

单次构建产生：

- `joysong-server.jar`；
- Admin archive；
- `manifest.json`；
- `release.json`；
- `SHA256SUMS`；
- SBOM；
- 扫描报告；
- 单一部署包 `joysong-uat-<tag>.tar.gz`；
- 外层摘要 `joysong-uat-<tag>.tar.gz.sha256`。

manifest 至少绑定 `schemaVersion`、tag、完整 commit、run/build ID、JAR、Admin、内部文件哈希和 database migration digest。所有文件来自同一次 checkout。

打包端和主机端都必须限制压缩大小、展开大小、成员数、路径长度以及 metadata/单文件大小。禁止绝对路径、`..`、反斜杠逃逸、重复规范路径、symlink、hardlink、设备文件、FIFO 和特殊文件。主机必须重新核对 manifest、release identity、`SHA256SUMS`、JAR migration digest 和 Admin archive/展开树一致性。

发布包不得包含 `.env`、数据库导出、运行时秘密、keystore、私钥、Token 或应用 OSS 凭据。

## 7. Ubuntu 主机初始化

写操作前运行：

```text
bootstrap-uat-host.sh preflight
```

从 Windows 生成受控源码归档时使用 `git -c core.autocrlf=false archive`，确保文件字节与批准提交的 LF blob 一致。Ubuntu 解包后，源目录及祖先必须 root-owned 且不可被组或其他用户写入；Git 归档的目录可能带 `0775`，须先收紧权限再运行 bootstrap，不能通过降低路径校验绕过。

该操作只能读取并确认 Ubuntu 24.04、x86_64、systemd、资源、监听以及全部固定目标尚不存在。TCP 仅允许 SSH，以及通过服务进程身份核验的 `systemd-resolved` 本机 DNS；不允许其他进程、外网 DNS 或任意业务监听。失败后不得用删除、覆盖或放宽检查的方式继续。

先按[首次秘密交付准备](./CONFIGURATION_REFERENCE.md#14-首次秘密交付准备)确认运行时配置；只交付受控路径，不在对话粘贴值。准备受控源目录、官方 Linux x64 Runner archive，以及首次执行时必须且只含下列三个 `root:root`、`0600` 单硬链接普通文件的 secrets 目录：

```text
joysong.env
joysong-uat-backup.cnf
runner-registration-token
```

随后执行：

```text
bootstrap-uat-host.sh apply <source-dir> <secrets-dir> <runner-archive> \
  2.337.0 70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613
```

`apply` 从 `joysong-server/deploy/systemd/joysong-demo.service` 与 `joysong-server/deploy/nginx/joysong-public.conf` 安装固定模板，并安装依赖、用户、目录、host-contract、MySQL 数据库/账号、部署入口、Runner service 和最小 sudoers。先验证 Runner 版本，再由 root 通过受控 PTY 响应隐藏 token 提示；不使用 token argv、环境变量或普通 stdin 管道，不输出注册终端内容，超时即失败。进入安装事务后 token 在成功或失败退出时清理；失败保留状态，不能自动重试覆盖。安装后验证 `www-data`、`myapp_worktree_uat`、Runner 版本/唯一标签/API online 及权限隔离，再于首次发布前以必须且只含 `joysong.env` 与 `joysong-uat-backup.cnf` 的同一目录执行第二次完全一致的 `apply` 复核。发布后不把 bootstrap 当配置修复入口。不得通过云 AccessKey、应用秘密或扩大 sudo 权限绕过失败。

## 8. 部署事务

固定入口根据主机事实自动判定 `fresh` 或 `existing`，CI 不得传入或覆盖状态。

### 8.1 fresh

fresh preflight 要求 Backend/Admin current 与 previous 均不存在，服务 inactive/disabled，8080 无监听，`myapp_worktree_uat` 无业务表和 Flyway history。它只读校验 Artifact、配置、模板/host-contract 摘要、容量和端口，全程不得启动或停止服务。

fresh 同时要求有效 `ADMIN_PHONE` 与 12–128 字符的首次 `ADMIN_PASSWORD`，缺失或重复配置在加载主机/数据库状态前拒绝，避免先迁移再因管理员初始化失败停服。bootstrap 在安装前执行相同输入要求；这不是对所有业务运行时选项的完整校验。

fresh deploy 先备份空库 identity、环境配置和必要持久目录并校验摘要，再安装不可变 Backend/Admin pair、写事务标记并启动 `joysong-demo.service`。健康成功只接受数据库已形成成功的 `B33 + V34…V40`；随后切换 Admin current、验证本机 `/` 与 `/orders`、enable 服务并提交 release state。首次部署没有 previous pair。

fresh 失败且 Flyway 仍为空时，恢复为可重试空状态、保持服务 inactive/disabled，并保留失败证据；Flyway 已变化或无法确认时，保持服务 stopped/disabled 和事务标记，不连接不存在的旧 JAR、不删除数据库。

### 8.2 existing

existing preflight 要求健康 current pair、服务 active/enabled、MainPID 独占 `127.0.0.1:8080`，以及成功的 `B33 + V34…V40`。candidate migration digest 必须与 current JAR 相同。容量、制品、环境、模板、previous pair 权限/hash 和备份检查全部在服务在线时完成。

existing deploy 写 root-only 事务标记后进入最长 180 秒停机预算，原子切换 Backend、验证服务/MainPID/JAR/8080/health/Flyway，再切换并验证 Admin，最后记录完整 previous pair。Flyway 未变化才允许自动恢复旧 pair；Flyway 变化或未知时保持停服和事务标记。Admin 失败必须成对恢复，不能只恢复一端。

首次健康成功后，下一 tag 必须自动进入 existing 路径。首次成功还必须从安装环境文件及受控 bootstrap 输入中分别原子移除 `ADMIN_PASSWORD`，保留 owner/mode、不输出值；受控重启并再次完成相同健康检查。existing preflight 拒绝仍存在该键的配置（即使为空），但保留并验证 `ADMIN_PHONE`。首次配置备份仍按 root-only 边界保管，不因去密而改写备份证据。

监听验收解析 `ss` 的本地地址字段，接受 `127.0.0.1:8080` 及其 IPv4-mapped IPv6 等价表示，并要求只有一条 LISTEN、所有拥有者 PID 均为应用 MainPID。`0.0.0.0`、`::`、`::1`、其他回环地址和额外监听仍拒绝。若首次迁移已成功但启动验收失败，保留数据库和事务标记，核对原候选与备份后受控恢复；不能删除数据库或直接重跑 fresh 流程。

## 9. Migration 与恢复门禁

fresh 仅允许从空库运行当前 JAR 内固定的 `B33 + V34…V40`。existing 只接受 migration digest 完全相同的候选；digest 不同不得通过 tag 部署试错。

人工数据恢复只允许写入此前不存在的 `myapp_worktree_restore_*` 数据库，执行前打印 host/name，并复核备份摘要。自动化不得 drop/reset `myapp_worktree_uat`、执行 Flyway clean、运行逆向 SQL或覆盖恢复现有数据库。

## 10. 回滚

fresh 首次部署没有 previous pair，因此不声称可回滚到应用旧版本；只能按 8.1 的空状态/停服策略处理。进入 existing 后，自动回滚和人工回滚都只能使用与当前版本绑定的完整 previous pair，并要求：

- Backend/Admin previous 均存在，并通过已记录的路径、哈希和 pair identity 校验；标准 tag release 另校验 manifest；
- 两端 previous 属于同一个已验证的 tag/commit/run pair，不能仅凭目录 basename 判断是否成对；
- previous 与 current migration digest 完全相同；
- 回滚前重新创建并验证备份；
- 切换后重新验证服务、JAR、8080、Admin 和 Flyway 快照。

失败 tag 不复用，失败 release 和备份保留供审计。任何 migration 不兼容或数据库状态不明的回滚都保持停服并转人工恢复。

## 11. 公网 Ready 门禁

本次任务不 Promote 为公网 UAT Ready。以下项目后续必须单独完成：

- ICP 备案和阿里云接入阻断解除；
- DNS 指向和外部可达性；
- TLS 证书链、SAN、续期和告警；
- 从 ECS 外部验证 Admin/API、登录、分享和业务流程；
- 验收 Issue 全部通过。

不得使用裸 IP、HTTP 或忽略证书错误来替代公网验收，也不得把本机 Host 检查描述为公网证据。

## 12. 每次发布留存证据

每次候选至少记录：

- PR URL、merge SHA；
- 精确 merge SHA 的 `master` push 门禁 URL；
- UAT tag、Actions run URL；
- Draft Release URL、Acceptance Issue URL；
- Artifact SHA-256；
- Ubuntu 24.04.4/x86_64/systemd 255 主机 identity、bootstrap host-contract 摘要；
- ECS Runner `2.337.0`、唯一标签及 API online 状态；
- `joysong-demo.service` active/enabled、MainPID 与 8080 identity；
- 实际运行 JAR 与 tag/commit；
- Backend/Admin current；fresh 明确记录 previous 不存在，existing 记录 previous pair；
- Flyway history 为成功的 B33 与 V34…V40；existing 同时记录停机前后快照未变化；
- 数据库/配置备份路径和 SHA 校验；
- 实际停机时间；
- `ADMIN_PASSWORD` 已移除并完成重启复验；
- tag 部署期间 Nginx 配置摘要未变化，两个业务 Bucket 未改变；
- 固定状态“UAT Candidate 已部署，公网未 Ready”。

## 13. 维护规则

修改 workflow、发布包、部署脚本、systemd/Nginx 契约、路径、migration、备份或 Runner 权限时，必须在同一 PR 更新本文、配置字典和相关测试。出现代码、主机事实与本文冲突时停止发布，先收敛为一套契约。

代码/文档收敛、本地构建测试、PR head 与 merge SHA 门禁、合并及本地安全清理共同构成“本地实现准入”；只有随后 bootstrap/幂等复核、Runner online、首次 tag、fresh 部署、管理员秘密移除重启和全部证据均通过，才构成“全链路完成”。
