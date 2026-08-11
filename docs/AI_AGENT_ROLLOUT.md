# AI Agent 灰度上线手册

本手册用于将同步 REST AI Agent 通过 FastAIToken 中转站安全上线。发布默认保持 `AI_AGENT_ENABLED=false`；只有全部门禁通过后，运维人员才可显式开启内部灰度。当前仓库不包含自动 cohort 分流、指标平台或自动熔断，这些能力需要由 API 网关、发布平台和监控平台配置。

## 1. 生产配置基线

机密值只从部署平台 Secret 注入，不写入仓库、命令行参数、日志或截图：

```dotenv
AI_AGENT_ENABLED=false
OPENAI_API_KEY=由部署平台Secret注入
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
AI_AGENT_MODEL=生产批准的确切模型ID
OPENAI_PROXY_URL=http://proxy.example.internal:8080
OPENAI_STREAM_ENABLED=false
```

启用生产配置时，`OPENAI_API_KEY`、`OPENAI_BASE_URL` 和 `AI_AGENT_MODEL` 缺失或不符合策略会 fail-fast。`OPENAI_PROXY_URL` 可选，只支持包含主机和显式端口的 `http://` 或 `socks://` URL。不要使用 `OPENAI_MODEL` 代替 `AI_AGENT_MODEL`。

## 2. 发布前门禁

1. 备份生产数据库并确认恢复流程可用。
2. 在执行 Flyway 前，使用只读账号运行 V10 preflight。密码只通过当前进程的 `DB_PASSWORD` 环境变量注入，命令和输出不得包含密码：

   ```powershell
   if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { throw 'DB_PASSWORD must be injected by the deployment secret store' }
   .\joysong-server\deploy\preflight-agent-v10.ps1 `
     -DatabaseHost '<生产MySQL主机>' `
     -DatabaseName '<生产数据库名>' `
     -DatabaseUser '<只读账号>'
   ```

   返回非零或出现 `V10 migration blocked` 时立即停止发布，不要启动 Flyway。先制定并审批遗留数据迁移方案。
3. 以 `AI_AGENT_ENABLED=false` 部署应用，让 Flyway 执行迁移；确认迁移历史成功且应用 readiness/`/actuator/health` 正常。
4. 执行发布版本的功能、并发和幂等门禁。数据库测试必须使用 fresh、隔离的 MySQL，不能连接生产或共享开发库。

## 3. Secret-safe FastAIToken canary

保持 `AI_AGENT_ENABLED=false`，由受控运维环境向 `https://www.fastaitoken.com/v1/chat/completions` 发起一次最小请求，模型使用 `AI_AGENT_MODEL`。请求应设置短超时和非流式响应；日志只记录时间、模型标识、HTTP 状态、耗时和结果分类。

canary 必须满足：

- Authorization 从 Secret 注入，不打印请求头、Key、完整请求体或完整回答；
- 成功返回 `2xx`，实际返回模型与批准的 `AI_AGENT_MODEL` 一致；
- 没有 `401`、`429`、`5xx` 或超时；
- 如配置 `OPENAI_PROXY_URL`，canary 与生产服务使用相同出口路径。

canary 失败时保持禁用，先修复凭证、模型授权、Host 白名单、代理或供应商容量问题。

## 4. 内部 cohort 灰度

1. canary 与应用 readiness 均通过后，将 `AI_AGENT_ENABLED=true` 仅应用于内部员工 cohort。
2. cohort 必须由 API 网关、功能开关平台或独立部署实例实施；当前应用没有自动用户分群能力。未命中的用户仍路由到禁用实例或得到明确的不可用响应。
3. 扩大 cohort 前至少观察一个完整业务高峰，并由产品、后端和运维共同确认指标与医学文案抽检结果。

## 5. 监控与停用阈值

运维需要在现有指标/日志平台建立看板和告警；仓库本身不声明已具备这些聚合指标：

| 指标 | 上线观察重点 |
|---|---|
| 请求成功率 | Agent REST 请求按状态与结果分类统计 |
| 供应商错误 | FastAIToken `401`、`429`、`5xx` 分开统计 |
| p95 延迟 | 端到端和供应商调用耗时 |
| stale 恢复 | stale `RUNNING` turn 恢复次数与比例 |
| 重复 turn | 相同幂等键或业务请求产生多个 turn 的计数，目标为 0 |
| 不安全文案 | 医疗诊断、保证效果、遗漏必要风险提示等人工抽检事件，目标为 0 |

出现下列任一情况应立即把所有生产实例设置为 `AI_AGENT_ENABLED=false` 并重新部署/刷新配置，不等待 cohort 扩大：

- 任何持续的凭证错误（尤其 `401`）；
- 持续的 `429`、`5xx`、超时或 p95 显著恶化；
- 发现重复 turn、幂等失效或 stale 恢复异常增长；
- 发现不安全医美文案、诊断性结论或保证效果表述。

停用后保留脱敏证据，冻结 cohort 扩大并开展复盘。恢复前重新执行 preflight 适用性确认、readiness、FastAIToken canary 和内部 cohort 门禁。

## 6. 回退边界

运行时回退的第一动作始终是 `AI_AGENT_ENABLED=false`。不要通过删除数据库表或回滚 Flyway 迁移来停用功能。

V11 是前向兼容迁移，必须保留；**不得回滚或删除 V11**。若需要回退应用版本，先确认旧版本可读取当前 schema，并保持 Agent 禁用。任何后续 schema 修正都应使用新的前向迁移完成。
