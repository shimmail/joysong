# UAT Ubuntu 24.04 全链路交付计划（2026-09-05）

## 1. 目标与边界

本计划是本轮 UAT 交付的唯一基线。目标不是“本地完成后等待换盘”，而是在同一轮工作中依次完成唯一实现收敛、本地门禁、PR 合并与安全清理、ECS 初始化、专用 Runner 注册、首次 UAT tag 发布和证据验收。

唯一发布路径固定为：

```text
GitHub-hosted 构建与门禁
  -> GitHub Actions Artifact
  -> ECS 仓库专用 Self-hosted Runner
  -> /usr/local/sbin/joysong-uat-deploy
  -> joysong-demo.service 与 Nginx 本机验收
```

本轮不构建 Flutter，不修改安全组、DNS、TLS、OSS ACL/对象或 ECS 实例角色，不连接共享开发数据库，不 drop/reset 数据库。任何范围外变更必须停止并单独取得授权。

## 2. 已验证主机基线

2026-09-05 已通过 Workbench 只读核验目标实例：

| 项目 | 已验证值 |
| --- | --- |
| ECS | `cn-hangzhou` / `i-bp19abm7697mvhl0xewu` |
| OS | Ubuntu 24.04.4 LTS，x86_64 |
| init | systemd 255，正常运行 |
| 资源 | 2 vCPU，约 3.4 GiB 内存；根盘 40 GB，约 36 GB 可用 |
| 网络监听 | 仅 SSH |
| JoySong 状态 | 用户、服务、Runner、部署目录和 MySQL 凭据文件均不存在 |

因此目标必须按全新空白 UAT 主机初始化。远端写操作前必须重新执行只读 `preflight` 并逐项复核以上事实；发现任何新增用户、服务、监听、目标路径或无法解释的状态时立即停止，不覆盖、不删除。

主实现位于 `D:\code\kotlin\joysong\.worktrees\uat-fast-release`、分支 `codex/uat-fast-release`。`uat-final-cutover` 仅作为只读对照，不是代码来源。

## 3. 旧方案逐文件迁移/替代清单

对 `uat-final-cutover` 未提交内容逐文件只读比较后的处置如下。只有适用于“GitHub-hosted 构建、ECS 本机部署”的安全约束进入新实现；禁止把旧脚本直接复制到新 worktree。

| 旧方案文件 | 结论 | 新方案归宿 |
| --- | --- | --- |
| `.github/ISSUE_TEMPLATE/uat-acceptance.yml` | 迁移不可变 release 与主机证据字段；替代 Promote 前置语义 | 当前 fresh/existing 验收表 |
| `.github/workflows/prod-deploy.yml` | 不迁移；Prod 不在本轮范围 | 保留主线已有 Prod 资产，UAT 不调用 |
| `.github/workflows/quality-gates.yml` | 迁移精确 SHA、hosted 门禁原则 | 当前 quality gate |
| `.github/workflows/uat-candidate.yml` | 迁移 release identity/证据；替代 OIDC、发布 OSS、Cloud Assistant 调度 | 当前 hosted build + ECS Runner workflow |
| `.github/workflows/uat-promote.yml` | 退役；不保留第二条 Promote 状态机 | 删除；Draft + Acceptance Issue 保留候选状态 |
| `.github/workflows/uat-rollback.yml` | 退役；不保留远程 rollback workflow | 删除；固定主机事务负责安全自动回滚 |
| `design/ALIYUN_CICD_DEPLOYMENT.puml` | 保留完整链路表达，替换全部旧云调度节点 | 当前 Ubuntu fresh/existing UML |
| `docs/guide/deployment/CONFIGURATION_REFERENCE.md` | 保留秘密、数据库、服务边界；替换 CentOS/既有主机值 | 当前配置字典 |
| `docs/guide/deployment/README.md` | 保留备份、身份、成对回滚原则；替换旧拓扑与接管步骤 | 当前 canonical 指南 |
| `docs/plan/aliyun-github-cicd-20260904.md` | 不迁移为现行计划 | 由本文完整替代 |
| `joysong-server/deploy/README.md` | 资产索引按实际调用链重写 | 当前执行资产索引 |
| `ci/cloud-assistant.sh` | 不迁移；无 Cloud Assistant 调用方 | hosted build/publish + ECS Runner |
| `ci/package-release.py` | 迁移 manifest、release identity、摘要和归档边界思想 | 当前打包器与交叉契约测试 |
| `ci/publish-and-deploy.sh` | 不迁移；发布 OSS、OIDC 和远程调用被替代 | 当前 UAT workflow |
| `ci/validate-workflows.py` | 迁移执行资产存在性、Action 固定 SHA 等有效门禁 | 当前 validator |
| `ci/verify-uat-acceptance.sh` | 不迁移独立 Promote 验证入口；保留必要证据项 | workflow publish job + Acceptance Issue |
| `ci/__pycache__/package-release.cpython-313.pyc` | 临时产物，不迁移 | 清理 |
| `ci/__pycache__/validate-workflows.cpython-313.pyc` | 临时产物，不迁移 | 清理 |
| `cloud-assistant/uat-inplace-command.json` | 不迁移；固定命令与 Cloud Assistant 已退出主路径 | 无替代远程命令 |
| `host/backup-runtime-state.sh` | 不作为独立入口迁移；保留完整备份、摘要和完成标记语义 | 固定入口内部 root-only 备份事务 |
| `host/baseline-data-manifest.py` | 不迁移；空白主机无需 legacy baseline | fresh 主机/数据库 identity |
| `host/bootstrap-host.sh` | 不迁移 CentOS/既有主机接管逻辑；保留 fail-closed、root-owned 安装思想 | Ubuntu-only `bootstrap-uat-host.sh` |
| `host/capture-existing-uat-baseline.sh` | 不迁移；空白主机无既有 baseline | bootstrap preflight |
| `host/cloud-command-bridge.py` | 不迁移；远程 payload bridge 已无调用方 | 固定部署入口 argv |
| `host/deploy-inplace-uat.sh` | 不迁移脚本；保留在线预检、备份、180 秒预算和 migration 变化即停服原则 | `joysong-uat-deploy` fresh/existing 状态机 |
| `host/deploy-release.sh` | 不迁移通用 `/opt/joysong/<env>` 发布器 | UAT 固定目录与入口 |
| `host/finalize-uat-cutover.sh` | 不迁移；无 8081 切流 | fresh 首次发布提交 |
| `host/joysong-release-dispatch` | 不迁移；remote dispatcher 已无调用方 | `/usr/local/sbin/joysong-uat-deploy` |
| `host/restore-uat-backup-to-new-db.sh` | 不纳入自动发布；保留“只恢复到新建安全前缀库”的人工原则 | 当前故障恢复边界 |
| `host/test-deploy-inplace-uat.sh` | 不迁移旧脚本测试 | 当前 bootstrap/deploy Python tests |
| `host/validate-runtime-config.sh` | 不直接迁移；收敛必要环境、地址、数据库和秘密门禁 | host-contract、preflight 与测试 |

完成收敛的判据是：旧 worktree 不再含任何当前 Ubuntu 路径必需但尚未进入新实现的行为；其剩余差异全部在上表中被明确替代、退役或判定为范围外。

## 4. 实施要求

### 4.1 Ubuntu bootstrap

`bootstrap-uat-host.sh` 只公开两个接口：

```text
bootstrap-uat-host.sh preflight
bootstrap-uat-host.sh apply <source-dir> <secrets-dir> <runner-archive> <runner-version> <runner-sha256>
```

- `preflight` 只读校验 root、Ubuntu 24.04、x86_64、systemd、磁盘、端口、用户、服务和固定目标路径为空。
- `apply` 安装/验证 OpenJDK 17、Nginx、MySQL 8、Python 3、curl、unzip、sudo、rsync 和 iproute2；创建 `joysong-demo`、`joysong-gh-runner` 及固定目录和最小权限。
- 首次 `<secrets-dir>` 必须且只含三个 `root:root`、`0600` 单硬链接普通文件：`joysong.env`、`joysong-uat-backup.cnf`、`runner-registration-token`。注册后 token 必须被删除；二次一致性复核要求目录必须且只含剩余两份持久输入。秘密不得出现在参数、日志、Git、Artifact 或 Runner 环境。
- 从 `joysong-server/deploy/systemd/joysong-demo.service` 和 `joysong-server/deploy/nginx/joysong-public.conf` 安装唯一 systemd/Nginx 模板，并生成 `/etc/joysong-demo/host-contract`（`root:root`、`0600`，只含两个模板 SHA）；服务用户/组固定为 `joysong-demo`，Nginx 用户固定为 `www-data`，Backend 固定为 `127.0.0.1:8080`。
- 数据库固定为本机 `myapp_worktree_uat`，创建最小权限应用与备份账号；执行前打印数据库 host/name，不输出凭据。
- Runner 固定为官方 Linux x64 `v2.337.0`，SHA-256 `70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613`，使用 `--no-default-labels` 注册且仅保留 `joysong-uat-deploy` 标签。
- 第二次 `apply` 只允许复核完全一致的已安装状态；敏感配置漂移、已有未知资源、Runner 身份或版本不一致均 fail closed。

### 4.2 fresh/existing 部署状态机

外部接口保持：

```text
joysong-uat-deploy preflight TAG COMMIT RUN_ID SHA256
joysong-uat-deploy deploy TAG COMMIT RUN_ID SHA256
```

- `fresh`：无 Backend/Admin current/previous，服务 inactive/disabled，`myapp_worktree_uat` 无业务表和 Flyway history。preflight 全程不停服务；deploy 先备份空库身份、配置和持久目录，再安装 pair，启动后只接受成功的 `B33 + V34…V40`。
- fresh 启动失败且 Flyway 未变化时恢复可重试空状态并保留失败证据；Flyway 已变化或无法确认时保持服务 stopped/disabled 和事务标记，不连接旧 JAR、不删除数据库。
- `existing`：已有健康 current pair 和成功的 `B33 + V34…V40`。继续执行不可变 release、完整备份、最长 180 秒停机和“Flyway 未变化才自动回滚 previous pair”。
- 首次成功后下一 tag 自动进入 existing；`ADMIN_PASSWORD` 仅允许首次空库初始化，首次健康成功后原子移除、受控重启并再次验收。

### 4.3 安全与测试门禁

- 外层/Admin tar 流式限制成员数和展开量；JAR 在 `ZipFile` 前验证 EOCD/central directory 并拒绝 ZIP64。
- MySQL option-file 只允许备份必需键；拒绝改变导出语义的选项。
- incoming 使用 dirfd/openat/O_NOFOLLOW 冻结；previous pair 回滚前递归验证 root ownership、不可组/他写和内容 hash。
- Runner 不可读取 secrets、private、staging、state、backups，不能获得通用 root shell。
- 本地数据库测试只使用 `myapp_worktree_uat_fast_release`；迁移前打印 host/name，并在全新空库验证。
- 按一次性顺序执行静态检查、`test_bootstrap_ubuntu.py` 等部署相关单测、Backend 相关测试与 `bootJar`、Admin lint/Vitest/build、真实 JAR + Admin dist 的 package/Artifact/verifier E2E，最后最多一次全量测试；超过 10 分钟停止并报告。
- 清理测试临时物，检查 executable bit、`git diff --check`、秘密扫描和最终 diff。

## 5. 提交、合并与本地清理

1. 对主工作区非 UAT 改动先做秘密扫描，再创建仅本地 `codex/wip-pre-uat-cleanup-20260905` 分支和 WIP 提交；记录 commit 并验证可恢复，绝不推送。
2. 在 `codex/uat-fast-release` 创建少量高内聚提交并推送，创建以 `master` 为 base 的 PR，使用 merge commit。
3. 精确验证 PR head 与 merge SHA 的 `Required quality gates` 成功；使用执行关口提供的短期 GitHub 授权经 REST API 完成 PR、合并、Runner token 与状态查询。
4. fetch 并验证祖先关系后，依次安全移除已合并/替代的 `uat-fast-release`、`uat-final-cutover` 和 `uat-cicd-deployment` worktree/分支；只使用 `git worktree remove` 与 `git branch -d`。
5. 保留 `codex/aliyun-demo-readiness`、本地 WIP 分支和 detached worktree；禁止 force-remove、`branch -D`、通配符删除和 `reset --hard`。
6. 将本地 `master` fast-forward 到最新 `origin/master`，输出最终 status、branch、worktree 和 WIP 清单。

## 6. 远端发布与验收

本地实现准入通过后，重新只读核验 ECS，再上传与 merge SHA 绑定的 bootstrap bundle 和 Runner archive；任何目标路径已存在都停止。接收 root-only 秘密文件后执行 bootstrap，验证专用 Runner online、唯一标签、服务用户和 sudoers。

选择同时未出现在 Git tag、Release 和历史 workflow run 中的最小 `v0.0.1-uat.N`。GitHub-hosted Runner 构建后由 ECS Runner 执行首次部署。验收证据至少包括 PR/merge SHA、quality gate、tag/run、Draft Release、Acceptance Issue、Artifact SHA、Flyway、备份、部署 state、systemd active/enabled、8080 MainPID、Nginx 首页/深链和 Runner 权限隔离。

### 门槛一：本地实现准入

只有代码/文档收敛、全部计划内本地测试通过、精确 PR head 与 merge SHA 门禁成功、代码合入 `master` 且本地改动已安全保全/清理后，才允许初始化 ECS 和创建 UAT tag。

### 门槛二：全链路完成

只有同时满足以下条件，才可报告“本轮开发完整完成”：

- Ubuntu bootstrap 与第二次一致性复核通过；
- Runner `v2.337.0` online，只有 `joysong-uat-deploy` 自定义标签且权限隔离通过；
- 首次 tag 发布成功，fresh 状态转为健康 existing 状态；
- `ADMIN_PASSWORD` 已移除并通过重启复验；
- Draft Release、Acceptance Issue 与全部主机证据一致；
- 本地安全清理和最终 Git 清单完成。

UML 源码随实现更新；图片由用户在交付结束后手工重新生成。
