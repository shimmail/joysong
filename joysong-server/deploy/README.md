# JoySong 部署资产索引

这里仅说明仓库内可执行部署资产；完整的阿里云准备、GitHub 配置、发布、验收和回滚步骤以 [`docs/guide/deployment/README.md`](../../docs/guide/deployment/README.md) 为准。

当前已部署的阿里云后端按 UAT 原地升级：Spring profile 为 `demo`，监听 `127.0.0.1:8081`，使用独立 `myapp_worktree_` 数据库。未来 Prod 使用独立用户、配置、数据库和 `127.0.0.1:8080`，不得复用 UAT 数据或秘密。

## 资产

| 路径 | 用途 |
|---|---|
| `host/bootstrap-host.sh` | 在现有 ECS 上安装发布用户、目录、受限 dispatcher 和 systemd 模板 |
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

迁移阶段必须保留现有 `joysong-demo`、端口 `8080`、旧目录、旧配置和旧数据不变。baseline 只做离线制品校验；final cutover 冻结旧写入并完成数据增量后，才会启动新服务在 `127.0.0.1:8081` 健康检查，健康通过后才允许切换 Nginx：

1. 记录现有部署对应的 40 位 Git commit，确认现有服务仍为 active。
2. 为当前安全前缀 UAT 数据库创建一致性备份，打印并人工核对数据库 host/name；把备份保存到 ECS root-only 路径。不得在此流程中 drop、truncate 或新建共享数据库。
3. 把本目录安全传入 ECS，运行下面的 bootstrap；它不会停止或覆盖旧服务。
4. 以旧配置为依据人工填写新的 `/etc/joysong/uat/joysong.env`，但必须改为 `demo`、`127.0.0.1:8081` 并通过全部支付门禁。原地迁移阶段新旧配置和旧服务实际进程的数据库 host、port、name、username 必须完全相同，且数据库名保持安全的 `myapp_worktree_` 前缀；baseline 会在任何复制前 fail-closed 比对。
5. 运行 baseline 捕获命令。它会绑定旧 systemd MainPID、实际 JAR、8080 listener、环境文件、数据根和数据库身份，备份旧/新配置、数据库备份文件、Nginx、历史 uploads/private/staging 和旧制品；随后以 `--ignore-existing` 迁移三类持久数据并创建只读基线 release。`joysong@uat` 保持停止且禁用，避免两个调度器或启动任务接触同一数据库。
6. 只有 baseline 命令成功并生成 `/etc/joysong/uat/BASELINE_CAPTURED`，且 DNS/TLS 已准备好后，才运行 final cutover 命令。该命令会重新核对全部旧服务/数据/数据库身份，持久打开维护事务、禁用并停止旧服务，确认旧 PID 与 8080 listener 消失，创建最终数据库和配置备份，以旧数据为权威完成 delta 同步与哈希核验，启用并核验 `joysong@uat` 的 MainPID/JAR/健康，再原子替换 Nginx 配置。
7. final cutover 失败时脚本会恢复旧 Nginx 配置并重启旧服务；成功时生成 `/etc/joysong/uat/CUTOVER_COMPLETED`，旧 `joysong-demo:8080` 停止并禁用但完整保留。后续自动部署同时要求 baseline 与 cutover 两个门禁。UAT 验收通过且观察期结束前不得删除旧服务、旧目录或备份。

数据库备份命令需使用 root-only MySQL client 配置文件，避免密码进入命令历史。示意如下，实际 host/name 必须先打印确认且数据库名必须以 `myapp_worktree_` 开头：

```bash
printf 'Database host: %s\nDatabase name: %s\n' '<当前host>' '<myapp_worktree_...>'
mysqldump --defaults-extra-file=/root/.my-uat.cnf --single-transaction \
  --routines --triggers '<myapp_worktree_...>' | gzip > /root/uat-before-cicd.sql.gz
gzip -t /root/uat-before-cicd.sql.gz
```

在把本目录安全传入 ECS 后，以 root 执行 bootstrap：

```bash
sudo bash host/bootstrap-host.sh <私有发布Bucket的完整主机名> <Nginx运行用户> <Nginx运行组>
```

后两个参数默认都是 `www-data`；阿里云 Linux 常见安装可能需要显式传入 `nginx nginx`。脚本不会创建 `/etc/joysong/prod/DEPLOY_ENABLED`，因此不会意外打开生产发布。安装后需在验证配置无误的前提下重启一次 Nginx，使新增的只读 release 组生效。

准备新 UAT 环境文件后，捕获旧部署基线：

```bash
sudo /usr/local/sbin/joysong-capture-uat-baseline \
  joysong-demo.service \
  <旧JAR绝对路径> \
  <旧Admin-dist绝对目录> \
  <旧环境文件绝对路径> \
  /var/lib/joysong-demo \
  /root/uat-before-cicd.sql.gz \
  <旧制品对应的40位Git提交>
```

第五个参数是旧持久数据根目录，必须包含 `uploads/`；`private/` 与 `upload-staging/`（兼容旧名 `staging/`）为可选目录。可选目录存在时会备份并迁移，不存在时脚本会留下明确审计记录且保持对应新目录不变。迁移后 private 与 staging 目录/文件分别强制为 `0700/0600`，只属于 `joysong-uat`，Nginx 不能读取；历史 uploads 使用 bootstrap 记录的 Web 组只读共享。环境数据根本身由 root 持有且不可被应用改名，应用仅能写指定的三个固定子目录。

该命令不会修改旧服务或旧监听端口，也不会启动新服务或自动切换 Nginx。若缺少经校验的数据库备份、必需配置/`uploads` 数据来源、旧进程/JAR/listener 身份不一致或容量不足，它不会打开 `BASELINE_CAPTURED` 门禁，后续自动部署将被拒绝。

先把 `nginx/joysong-default-deny.conf` 安装为独立且全机唯一的 default server，并把 `nginx/joysong-uat-upstream.conf` 安装为全机唯一的 UAT upstream/限流定义，再通过 `nginx -t`。随后准备好仓库中的新 UAT Nginx 配置，执行一次最终切流（活动配置必须是 `/etc/nginx/` 下的现有普通文件）：

```bash
sudo /usr/local/sbin/joysong-finalize-uat-cutover \
  joysong-demo.service \
  /var/lib/joysong-demo \
  <安全传入ECS的joysong-uat.conf绝对路径> \
  <当前旧站点的/etc/nginx/...conf绝对路径>
```

最终 delta 阶段不使用 `--ignore-existing`：旧服务已停止，其数据树是唯一权威来源，变化文件必须覆盖初次基线副本，目标多余文件会在完整备份后由 `--delete` 清理；旧 private/staging 不存在时对应新目录明确收敛为空。同步后执行双向完整树哈希核验。失败回退可从命令输出的 root-only cutover 备份恢复，旧数据根从未被改写。

安装后分别填写 root 管理的配置：

```text
/etc/joysong/uat/joysong.env
/etc/joysong/prod/joysong.env
```

配置完成前不要启用服务。环境文件禁止设置除 `SPRING_PROFILES_ACTIVE` 外的任何 `SPRING_*` 键，也禁止 `JAVA_TOOL_OPTIONS`、`JDK_JAVA_OPTIONS`、`_JAVA_OPTIONS`、`JAVA_OPTS` 和任何 `LD_*` 键；校验器会拒绝这些可绕过 profile 或注入 JVM/native 参数的入口，systemd 还会再次清除已知 JVM/LD 注入变量。发布/切流事务使用 root-owned `/var/lib/joysong-maintenance/{uat,prod}` 持久门禁；Nginx 返回维护态，systemd 在重启后也会拒绝自动启动未完成事务中的服务，只有当前发布进程可用一次性 `/run` 授权启动并验证。GitHub Cloud Assistant 只以 `joysong-deploy` 身份调用固定 dispatcher，应用运行用户不能读取发布 Bucket 或另一环境的秘密。

需要本机 SQL dump 的环境还必须准备 `/etc/mysql/joysong-<uat|prod>-backup.cnf`（普通文件、`root:root`、`0600`）。固定备份脚本只从该 root-only MySQL client 配置读取备份账号/密码，并从应用 `DB_URL` 取得且打印实际 host/name；它不从应用环境文件读取或导出 `DB_USERNAME`/`DB_PASSWORD`。

Prod 还必须准备 `/etc/joysong/prod/rds-binding.env`（`root:root`、`0600`），且只含 `RDS_INSTANCE_ID=<rm-...>` 与 `RDS_CONNECTION_HOST=<内网RDS域名>`。发布前 CI 会从 `ALIYUN_PROD_RDS_INSTANCE_ID` 实时读取内网 endpoint，再同时比对该 root-only 绑定和应用 `DB_URL`，确保被备份实例就是应用实际使用的实例；systemd 每次启动（含 ECS 重启）都会再次校验这份绑定和 root-only `DEPLOY_ENABLED` sentinel。

## Nginx 与图片边界

管理后台使用 `joyingsong.net`，API 和公开日记分享页使用 `api.joyingsong.net`。安装或切换模板后必须先验证再重载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

当前 UAT 的新图片通过 ECS RAM Role 写入 OSS。`/images/` 本地目录和 Nginx alias 只为历史链接兼容而保留；Java 进程需要写权限，Nginx 仅需读权限，私有身份材料目录不得暴露给 Nginx。

## GitHub Actions 边界

Actions 负责构建、测试、扫描、签名、生成 manifest/SHA-256/SBOM，并通过 GitHub OIDC、私有发布 Bucket 和 Cloud Assistant 原地升级现有 ECS。每次 UAT 切换前，ECS 固定脚本都会创建 root-only 配置/数据库备份并记录源/目标 release、DB host/name、dump SHA 和精确路径；SHA/gzip 成功只证明备份完整可读，不等于应用恢复验收。SQL dump 不含 `CREATE DATABASE`/`USE`。恢复工具默认 dry-run，只有显式 `--execute` 才创建一个此前不存在的 `myapp_worktree_restore_*` 数据库，绝不覆盖或 drop 当前库：

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
