# JoySong 部署资产索引

完整流程、权限边界、主机接入和验收要求以 [`docs/guide/deployment/README.md`](../../docs/guide/deployment/README.md) 为准。本文只说明仓库中的执行资产。

## 当前 UAT 契约

- 服务固定为 `joysong-demo.service`；
- 主机固定为 Ubuntu 24.04 / x86_64 / systemd，Nginx 用户固定为 `www-data`；
- Spring profile 固定为 `demo`；
- Backend 固定监听 `127.0.0.1:8080`；
- Backend release 位于 `/opt/joysong-demo/releases/<tag>`；
- Admin release 位于 `/var/www/joysong-demo/releases/<tag>`；
- 两端各自维护成对的 `current` 和 `previous`；
- 运行时配置固定为 `/etc/joysong-demo/joysong.env`；
- 本机数据库固定为 `myapp_worktree_uat`；
- 状态、备份和入站制品位于 `/var/lib/joysong-deploy/{state,backups,incoming}`；
- bootstrap 从仓库模板安装 systemd/Nginx 并生成 root-only host-contract；tag 发布不启动 `joysong@uat`、不监听 8081、不修改或 reload Nginx；
- 当前发布不构建移动端制品。

普通与私密业务 Bucket 分别固定为：

- `joysong-demo-media-cn-hangzhou-1335549182926992`；
- `joysong-demo-private-cn-hangzhou-1335549182926992`。

部署脚本不得读取、修改或清理两个 Bucket 的 ACL、权限和对象。

## 当前执行资产

| 路径 | 用途 |
| --- | --- |
| `ci/package-release.py` | 校验 Backend/Admin 输入，生成 manifest、release identity、内部 SHA 和单一 UAT 部署包 |
| `ci/validate-workflows.py` | 校验 workflow、Action 固定 SHA、文档、发布包和部署资产契约 |
| `ci/run-actionlint.sh` | 以固定版本运行 actionlint |
| `host/bootstrap-uat-host.sh` | Ubuntu-only `preflight/apply`；安装依赖、模板、用户、`myapp_worktree_uat`、固定入口和专用 Runner |
| `host/verify-uat-release.py` | 在主机侧按固定资源上限校验部署包、manifest、SHA、JAR migration 和 Admin 内容 |
| `host/joysong-uat-deploy` | root-owned 固定入口；完成 preflight、备份、双 release 切换、健康检查和安全回滚 |
| `systemd/joysong-demo.service` | Ubuntu UAT 的唯一应用 unit 模板，安装到 `/etc/systemd/system/joysong-demo.service` |
| `nginx/joysong-public.conf` | Ubuntu UAT 的唯一站点模板，安装到 `/etc/nginx/conf.d/joysong-public.conf` |
| `tests/test_package_release.py` | 发布包、路径、成员数、展开大小、metadata 和 digest 单元测试 |
| `tests/test_release_cross_contract.py` | 打包端与主机验证端的资源上限及实际 bundle 互操作契约测试 |
| `tests/test_joysong_uat_deploy.py` | 固定入口、备份、migration、事务顺序、双链接和 fail-closed mock 测试 |
| `tests/test_bootstrap_ubuntu.py` | Ubuntu 空根、错误 OS/架构、首次安装、二次复核和漂移拒绝 fixture |

安装到 ECS 后，Runner 只可通过 sudo 调用：

```text
/usr/local/sbin/joysong-uat-deploy
```

该入口只接受 `preflight|deploy`、UAT tag、40 位小写 commit、数字 run ID 和 64 位小写 SHA-256。服务名、端口、目录、用户、Nginx 和数据库操作均不能由 CI 传入；入口根据主机事实自动选择 fresh 或 existing。

## Artifact 数据流

```text
GitHub-hosted build/package
  -> joysong-uat-<tag>.tar.gz
  -> joysong-uat-<tag>.tar.gz.sha256
  -> GitHub Actions Artifact
  -> ECS joysong-gh-runner 下载到 incoming/<run-id>
  -> sudo /usr/local/sbin/joysong-uat-deploy
  -> root 验证、备份、安装、切换、健康检查或回滚
```

Runner 不能读取 `/etc/joysong-demo/joysong.env`、`/etc/mysql/joysong-uat-backup.cnf`、private、staging、state 或 backups，不能写 release，也不持有数据库、业务 OSS、阿里云或应用秘密。

Runner 固定为官方 Linux x64 `v2.337.0`，archive SHA-256 为 `70920811a4f8ad4328818682bca5c6469c1c942fab52448868071d0063816613`；以 `--no-default-labels` 注册且只保留 `joysong-uat-deploy` 标签。Ubuntu 24.04 不使用 CentOS/GLIBC 兼容层。Listener 版本、service active 和 GitHub API online 未全部验证前不得推送 UAT tag。

## Bootstrap 接口

```text
bootstrap-uat-host.sh preflight
bootstrap-uat-host.sh apply <source-dir> <secrets-dir> <runner-archive> <runner-version> <runner-sha256>
```

`preflight` 只读验证 Ubuntu 24.04 空白主机。首次 `apply` 要求 secrets 目录必须且只含 `joysong.env`、`joysong-uat-backup.cnf`、`runner-registration-token` 三个 `root:root`、`0600` 单硬链接普通文件；token 注册后删除，第二次 apply 要求目录必须且只含剩余两份输入，只复核完全一致的安装状态。任何已有未知资源、敏感配置或模板摘要漂移均 fail closed。

## 主机事务摘要

- **fresh**：无 current/previous、服务 inactive/disabled、数据库无业务表/Flyway history。preflight 不停服务；deploy 先备份空状态，再安装 pair并允许当前 JAR 建立成功的 B33 与 V34…V40。失败且 Flyway 为空时恢复可重试空状态；Flyway 变化或未知时保持 stopped/disabled 和事务标记。
- **existing**：验证健康 current pair、MainPID/8080、成功的 B33 与 V34…V40 以及相同 migration digest；在线完成包/容量/备份校验后进入最长 180 秒停机，成对切换或在 Flyway 未变化时成对回滚 previous。

首次成功没有 previous；下一 tag 自动进入 existing。首次健康成功后还必须原子移除 `ADMIN_PASSWORD`、受控重启并再次验收。

## 历史或未来资产

以下现有文件不属于当前快速 UAT 主路径：

| 资产 | 当前状态 |
| --- | --- |
| `ci/install-aliyun-cli.sh` | 旧阿里云发布链资产，不由当前 UAT workflow 调用 |
| `ci/cloud-assistant.sh` | 旧 Cloud Assistant 入口，不由当前 UAT workflow 调用 |
| `ci/publish-and-deploy.sh` | 旧发布 OSS/远程部署入口，不由当前 UAT workflow 调用 |
| `host/joysong-release-dispatch` | 旧远程 dispatcher，不授权给 UAT Runner |
| `host/deploy-release.sh` | 旧 `/opt/joysong/<env>` 通用发布流程，不用于 demo 原地 UAT |
| `host/capture-existing-uat-baseline.sh` | 旧并行 UAT baseline 迁移资产 |
| `host/finalize-uat-cutover.sh` | 旧 `joysong@uat:8081`/Nginx 切流资产 |
| `systemd/joysong@.service` | 未来独立环境模板，不替换 `joysong-demo.service` |
| 除 Ubuntu bootstrap 固定模板外的 `nginx/*.conf` | 未来公网/TLS 或 Prod 模板，当前候选不安装或 reload |
| `ci/rds-backup.sh` | 未来 Prod/RDS 流程参考，不用于当前本机 UAT 数据库 |

相关发布 OSS、OIDC、RAM 或 Cloud Assistant 云资源若已经创建，只读盘点后留待人工清理；当前 UAT 不使用也不删除。未来 Prod 不得直接沿用仓库级 UAT Self-hosted Runner，必须另行完成隔离、审批和权限设计。

## 验证

仓库门禁至少运行：

```text
workflow YAML parse
validate-workflows.py
actionlint
bash -n
ShellCheck
Python AST
test_package_release.py
test_release_cross_contract.py
test_joysong_uat_deploy.py
test_bootstrap_ubuntu.py
git diff --check
```

还必须覆盖 tar/JAR 边界、MySQL option-file 白名单、incoming 冻结、previous pair 权限/hash 以及 Runner 对 secrets/private/staging/state/backups 的隔离。部署成功只产生“UAT Candidate 已部署，公网未 Ready”。ICP、DNS、TLS 和公网业务验收不由这些主机测试替代。
