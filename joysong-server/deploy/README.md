# JoySong 部署资产索引

这里仅说明仓库内可执行部署资产；完整的阿里云准备、GitHub 配置、发布、验收和回滚步骤以 [`docs/guide/deployment/README.md`](../../docs/guide/deployment/README.md) 为准。

当前已部署的阿里云 Demo 是原地升级 UAT 的基础：旧服务以 Spring profile `demo` 监听 `127.0.0.1:8080`；只有 final cutover 才启动目标 `joysong@uat` 并监听 `127.0.0.1:8081`。UAT 沿用已核验的安全前缀 `myapp_worktree_` 数据库，未来 Prod 使用独立用户、配置、数据库和 `127.0.0.1:8080`，不得复用 UAT 数据或秘密。

## 资产

| 路径 | 用途 |
|---|---|
| `host/bootstrap-host.sh` | 在现有 ECS 上安装发布用户、目录、受限 dispatcher 和 systemd 模板 |
| `host/baseline-data-manifest.py` | 以 no-follow 双扫描检查持久数据树，并原子创建或校验 root-only baseline 清单 |
| `host/deploy-release.sh` | 校验制品与运行时配置，原子切换 `current`/`previous`；migration 兼容时健康失败自动回滚，不兼容时撤下服务并保持维护态；保留最近 5 版 |
| `host/backup-runtime-state.sh` | 每次切换前创建并校验 root-only 配置、持久目录与 UAT 数据库备份，并记录源/目标版本和精确 identity |
| `host/restore-uat-backup-to-new-db.sh` | 校验备份并仅允许 dry-run 或恢复到全新 `myapp_worktree_restore_*` 数据库，绝不覆盖/drop 现有库 |
| `host/finalize-uat-cutover.sh` | 冻结旧写入、同步最终数据增量、验证新服务并原子切换 Nginx |
| `host/validate-runtime-config.sh` | 强制 UAT/Prod profile、端口、数据库和支付安全门禁 |
| `systemd/joysong@.service` | 隔离运行 `joysong@uat` 与 `joysong@prod` |
| `nginx/joysong-uat-upstream.conf` | 全机只安装一份的 UAT upstream 与登录限流区 |
| `nginx/joysong-uat.conf` | 当前主域名 UAT 的 HTTPS、SPA、SSE、限流、维护态和历史图片兼容配置 |
| `nginx/joysong-uat-subdomains.conf` | Prod 上线前迁移到 `uat`/`api-uat` 独立子域时使用的完整 UAT 模板 |
| `nginx/joysong-api.conf`、`nginx/joysong-admin.conf` | 主域名移交 Prod 后使用的模板 |
| `nginx/joysong-default-deny.conf` | 全环境只安装一份的未知 Host/SNI 拒绝站点，避免 UAT/Prod 共存时重复 default server |
| `ci/` | GitHub Actions 使用的制品打包、阿里云 CLI、Cloud Assistant、RDS 备份和验收校验脚本 |

## ECS 一次性安装

### 从现有 Demo 并行迁移

迁移阶段必须保留现有 `joysong-demo`、端口 `8080`、旧目录、旧配置和旧数据不变。baseline 只做离线捕获与校验；final cutover 冻结旧写入并完成数据增量后，才会启动新服务在 `127.0.0.1:8081` 健康检查，健康通过后才允许切换 Nginx：

1. 分别记录现有 Server 与 Admin 制品对应的完整 40 位 Git commit，确认现有服务仍为 active。两者可以相同，但不得假定相同或用短 SHA 代替。
2. 在任何主机变更前，按现有受控运维方式为当前安全前缀 UAT 数据库创建初始恢复备份，打印并人工核对数据库 host/name；把备份保存到 ECS root-only 路径。不得在此流程中 drop、truncate 或新建共享数据库。
3. 把本目录安全传入 ECS，运行下面的 bootstrap；它不会停止或覆盖旧服务。
4. 以旧配置为依据人工填写新的 `/etc/joysong/uat/joysong.env`，但必须改为 `demo`、`127.0.0.1:8081` 并通过全部支付门禁。原地迁移阶段新旧配置和旧服务实际进程的数据库 host、port、name、username 必须完全相同，且数据库名保持安全的 `myapp_worktree_` 前缀；baseline 会在任何复制前 fail-closed 比对。
5. 用固定运行时备份脚本创建带完整性标记的数据库备份，再运行 baseline 捕获命令。baseline 会绑定旧 systemd MainPID、实际 JAR、8080 listener、环境文件、数据根和数据库身份，备份旧/新配置、数据库备份文件、Nginx、历史 uploads/private/staging 和旧制品；随后以 `--ignore-existing` 迁移三类持久数据并创建只读基线 release。整个过程不启动或启用 `joysong@uat`，不执行 Flyway/Demo `Apply`，也不 reload/restart Nginx；现有 Demo 始终是唯一在线写实例。
6. 只有 baseline 命令成功并生成 `/etc/joysong/uat/BASELINE_CAPTURED`，且 DNS/TLS 已准备好并取得明确 Go/No-Go 后，才运行 final cutover 命令。该命令会重新核对全部旧服务/数据/数据库身份，持久打开维护事务、冻结旧写入，确认旧 PID 与 8080 listener 消失，创建最终数据库和配置备份，以旧数据为权威完成 delta 同步与哈希核验，启动并核验 `joysong@uat` 的 MainPID/JAR/`127.0.0.1:8081` 健康，再原子替换并 reload Nginx。
7. final cutover 失败时脚本会恢复旧 Nginx 配置并重启旧服务；成功时生成 `/etc/joysong/uat/CUTOVER_COMPLETED`，旧 `joysong-demo:8080` 停止并禁用但完整保留。后续自动部署同时要求 baseline 与 cutover 两个门禁。UAT 验收通过且观察期结束前不得删除旧服务、旧目录或备份。

在把本目录安全传入 ECS 后，以 root 执行 bootstrap：

```bash
sudo bash host/bootstrap-host.sh <私有发布Bucket的完整主机名> <Nginx运行用户> <Nginx运行组>
```

后两个参数默认都是 `www-data`；阿里云 Linux 常见安装可能需要显式传入 `nginx nginx`。真实私有发布 Bucket 尚未配置时，只允许为 bootstrap 传入不可解析的 `.invalid` 占位主机（例如 `release-bucket-unconfigured.invalid`）；这不会让发布可用，CI/CD 必须保持 fail-closed。启用自动部署前必须把 `/etc/joysong/deploy.env` 的 `RELEASE_URL_HOST` 替换为真实私有 Bucket 主机名并完成 OIDC/权限验证。脚本不会创建 `/etc/joysong/prod/DEPLOY_ENABLED`，因此不会意外打开生产发布。

现有主机使用 Python 3.6 和不支持 `systemctl --value` 的旧 systemd；部署脚本必须保持兼容。bootstrap 只执行 `systemctl daemon-reload`，不授权在预备阶段 reload/restart Nginx。新增 Web 组成员关系及 Nginx 配置的生效统一延后到获批的 final cutover。

准备并验证新 UAT 环境文件后，使用固定脚本生成 baseline 所需的带标记备份。它只在数据库全部基础表为 InnoDB、备份前后 scheduled EVENT 数量均为 `0` 时执行；dump 故意不导出 EVENT，并拒绝包含 `CREATE DATABASE` 或 `USE`：

```bash
sudo /usr/local/lib/joysong/backup-runtime-state.sh \
  uat pre-baseline database <旧release标识> <基线release标识>
```

命令会打印精确 `BACKUP_PATH`。数据库凭据只放在 `/etc/mysql/joysong-uat-backup.cnf`（普通文件、`root:root`、`0600`），不得进入命令历史或应用环境文件。

带标记备份完成后，捕获旧部署基线：

```bash
sudo /usr/local/sbin/joysong-capture-uat-baseline \
  joysong-demo.service \
  <旧JAR绝对路径> \
  <旧Admin-dist绝对目录> \
  <旧环境文件绝对路径> \
  /var/lib/joysong-demo \
  <BACKUP_PATH>/database.sql.gz \
  <旧Server制品对应的40位Git提交> \
  <旧Admin制品对应的40位Git提交>
```

第五个参数是旧持久数据根目录，必须包含 `uploads/`；`private/` 与 `upload-staging/`（兼容旧名 `staging/`）为可选目录。可选目录存在时会备份并迁移，不存在时脚本会留下明确审计记录且保持对应新目录不变。迁移后 private 与 staging 目录/文件分别强制为 `0700/0600`，只属于 `joysong-uat`，Nginx 不能读取；历史 uploads 使用 bootstrap 记录的 Web 组只读共享。环境数据根本身由 root 持有且不可被应用改名，应用仅能写指定的三个固定子目录。

Baseline 备份的 `data/**` 必须同时生成 root-only `DATA_MANIFEST.jsonl`。清单按原始路径字节排序并用 Base64 表示路径，记录数据根、目录、普通文件的类型、mode、uid、gid，以及普通文件的 size/SHA-256；链接、特殊文件、跨设备条目和多硬链文件一律拒绝。生成清单后脚本会重新扫描并逐项比对；清单以及 service、Nginx、data-migration 审计文件全部纳入备份根 `SHA256SUMS`。

最后两个参数分别是完整 server commit 与完整 admin commit。脚本解析实际 JAR 和 Admin 路径后，会分别校验其 release 目录携带对应 commit 的 SHA8；任一不符即 fail-closed。两条来源会写入基线 manifest 的 `baselineSourceCommits` 和 root-only `BASELINE_CAPTURED`，为既有手工部署保留可审计 provenance。此双来源仅属于历史 baseline：正常 CI 构建仍要求 Server 与 Admin 来自同一个完整 `GITHUB_SHA`，manifest 保持单一 `commit`。

历史 Demo 环境文件实测含两条未加 `#` 的 ASCII 裸行。兼容仅作用于旧环境文件：每条 ignored line 必须按脚本内精确的“上一赋值键 + 裸行 SHA-256” allowlist 匹配，且各自只能出现一次；未知摘要、位置错误、重复出现或新 `/etc/joysong/uat/joysong.env` 中的任何裸行均 fail-closed。审计记录只保存 ignored line 的 count 与摘要，不输出原文；旧原文件仍完整保存为 root-only 备份并纳入 `SHA256SUMS`。

该命令不会修改旧服务或旧监听端口，不会启动/启用新服务，不会执行 Flyway、Demo `Apply` 或应用健康检查，也不会安装、reload/restart Nginx。若缺少同目录 `BACKUP_COMPLETE`/`SHA256SUMS` 绑定并校验通过的数据库备份、必需配置/`uploads` 数据来源、旧进程/JAR/listener 身份不一致或容量不足，它不会打开 `BASELINE_CAPTURED` 门禁，后续自动部署将被拒绝。

预备阶段只把新 Nginx 模板保存到活动配置目录之外，不安装或重载。完成 DNS/TLS 和 Go/No-Go 后，在最终切流维护窗口内准备全机唯一的 default-deny 与 UAT upstream/限流定义并通过 `nginx -t`，随后执行 final cutover；该命令验证新服务后才原子替换活动配置并 reload Nginx（活动配置必须是 `/etc/nginx/` 下的现有普通文件）：

```bash
sudo /usr/local/sbin/joysong-finalize-uat-cutover \
  joysong-demo.service \
  /var/lib/joysong-demo \
  <安全传入ECS的joysong-uat.conf绝对路径> \
  <当前旧站点的/etc/nginx/...conf绝对路径>
```

旧 Demo 在 baseline 捕获期间仍在线，因此初次数据副本只是一份迁移 seed，不是权威快照。最终 delta 阶段不使用 `--ignore-existing`：旧服务已停止，其数据树才是唯一权威来源，变化文件必须覆盖 seed，目标多余文件会在完整备份后由 `--delete` 清理；旧 private/staging 不存在时对应新目录明确收敛为空。同步后执行源/目标双向完整树哈希核验，通过后目标数据才成为权威。失败回退可从命令输出的 root-only cutover 备份恢复，旧数据根从未被改写。

安装后分别填写 root 管理的配置：

```text
/etc/joysong/uat/joysong.env
/etc/joysong/prod/joysong.env
```

配置完成前不要启用服务。环境文件禁止设置除 `SPRING_PROFILES_ACTIVE` 外的任何 `SPRING_*` 键，也禁止 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`、`JAVA_OPTS` 和任何 `LD_*` 键；校验器会拒绝这些可绕过 profile 或注入 JVM/native 参数的入口，systemd 还会再次清除已知 JVM/LD 注入变量。发布/切流事务使用 root-owned `/var/lib/joysong-maintenance/{uat,prod}` 持久门禁；Nginx 返回维护态，systemd 在重启后也会拒绝自动启动未完成事务中的服务，只有当前发布进程可用一次性 `/run` 授权启动并验证。GitHub Cloud Assistant 只以 `joysong-deploy` 身份调用固定 dispatcher，应用运行用户不能读取发布 Bucket 或另一环境的秘密。

需要本机 SQL dump 的环境还必须准备 `/etc/mysql/joysong-<uat|prod>-backup.cnf`（普通文件、`root:root`、`0600`）。固定备份脚本只从该 root-only MySQL client 配置读取备份账号/密码，并从应用 `DB_URL` 取得且打印实际 host/name；它不从应用环境文件读取或导出 `DB_USERNAME`/`DB_PASSWORD`。

Prod 还必须准备 `/etc/joysong/prod/rds-binding.env`（`root:root`、`0600`），且只含 `RDS_INSTANCE_ID=<rm-...>` 与 `RDS_CONNECTION_HOST=<内网RDS域名>`。发布前 CI 会从 `ALIYUN_PROD_RDS_INSTANCE_ID` 实时读取内网 endpoint，再同时比对该 root-only 绑定和应用 `DB_URL`，确保被备份实例就是应用实际使用的实例；systemd 每次启动（含 ECS 重启）都会再次校验这份绑定和 root-only `DEPLOY_ENABLED` sentinel。

## Nginx 与图片边界

管理后台使用 `joyingsong.net`，API 和公开日记分享页使用 `api.joyingsong.net`。以下命令只属于获批的最终切流或后续 Nginx 变更窗口；预备阶段禁止执行。安装或切换模板后必须先验证再重载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

当前 UAT 的新图片通过 ECS RAM Role 写入 OSS。`/images/` 本地目录和 Nginx alias 只为历史链接兼容而保留；Java 进程需要写权限，Nginx 仅需读权限，私有身份材料目录不得暴露给 Nginx。

## GitHub Actions 边界

Actions 负责构建、测试、扫描、签名、生成 manifest/SHA-256/SBOM，并通过 GitHub OIDC、私有发布 Bucket 和 Cloud Assistant 原地升级现有 ECS。每次 UAT 切换前，ECS 固定脚本都会创建 root-only 配置/数据库备份并记录源/目标 release、DB host/name、dump SHA 和精确路径；SHA/gzip 成功只证明备份完整可读，不等于应用恢复验收。在线逻辑备份要求全部基础表为 InnoDB 且 scheduled EVENT 为 `0`；SQL dump 不导出 EVENT，也不含 `CREATE DATABASE`/`USE`。恢复工具默认 dry-run，只有显式 `--execute` 才创建一个此前不存在的 `myapp_worktree_restore_*` 数据库，绝不覆盖或 drop 当前库：

```bash
sudo /usr/local/sbin/joysong-restore-uat-backup \
  /var/backups/joysong/uat/releases/<精确备份目录> \
  myapp_worktree_restore_<唯一标识> /root/.my-uat-restore.cnf
# 审核输出后，显式执行；完成后仍需应用级验收
sudo /usr/local/sbin/joysong-restore-uat-backup \
  /var/backups/joysong/uat/releases/<精确备份目录> \
  myapp_worktree_restore_<唯一标识> /root/.my-uat-restore.cnf --execute
```

脚本在备份前按数据库估算量保留双倍空间及 512 MiB 余量。每个环境达到 10 个已完成且 SHA 可自校验的备份后会 fail-closed，自动化绝不删除任何备份；root 操作者须先按精确路径人工审计和清理。损坏或未完成目录也永不自动删除。服务器运行时秘密始终留在 `/etc/joysong/`，不进入 GitHub 制品或 Release。

活动部署不会被新任务取消。同一 tag、GitHub Release、OSS 前缀和服务器 release 目录均为不可变对象；失败版本必须使用新的 tag 修复，不能覆盖重发。

生产公网身份或路由检查失败时，固定恢复命令仅在新旧 JAR 的数据库 migration digest 完全一致时验证并切回 `previous`；migration 集合变化时会停止新版本并保持维护态，要求结合切换前数据库备份执行人工恢复决策，禁止用旧二进制盲目连接已变更 schema。生产首发尚无 `previous` 时，它同样保持 Nginx 维护态、停止并禁用新服务、移除 `current`，同时以 `failed-public-check` 保留失败 release 的只读审计引用，避免首个坏版本继续在线。

若待发布 JAR 与当前版本的 migration digest 不同，自动部署会在启动前 hard-block。只有 root 操作者先对精确迁移过渡完成隔离恢复/迁移演练，并创建 `root:root`、`0600` 的 `/etc/joysong/<uat|prod>/RESTORE_DRILL_VERIFIED`，内容恰好包含 `RESTORE_DRILL_VERIFIED=true`、`ENVIRONMENT`、`FROM_MIGRATIONS_SHA256` 与 `TO_MIGRATIONS_SHA256`，才允许一次发布；成功后 sentinel 会被消费。失败事务留下持久 maintenance marker 时，后续普通部署与 preflight 都会 hard-block，必须先由 root 按备份审计记录完成显式恢复，不能把“无 current”误当首发来绕过精确 migration 过渡。未经演练不得用自动发布测试 migration。
