# AI Agent 灰度上线手册

本手册用于将同步 REST AI Agent 安全上线。应用运行时固定启用 Agent；生产灰度和紧急停用由 API 网关或发布平台控制入口。当前仓库不包含自动 cohort 分流、指标平台或自动熔断。

## 1. 生产配置基线

机密值只从部署平台 Secret 注入，不写入仓库、命令行参数、日志或截图：

```dotenv
AI_AGENT_PROVIDER=qwen
AI_AGENT_API_KEY=由部署平台Secret注入
AI_AGENT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_AGENT_MODEL=qwen-plus
AI_AGENT_INTENT_MODEL=qwen-turbo
```

生产配置缺失必要 Agent 变量或 endpoint 不符合 Provider 策略时会 fail-fast。部署不得增加第六项 AI/翻译变量。

## 2. 发布前门禁

V10 preflight 与 Flyway 之间没有数据库锁，不能单独防止“检查后、迁移前”仍有旧实例写入的 TOCTOU 竞态。迁移文件已经发布，禁止通过修改原文件来改变 checksum；本次迁移安全依赖下面的停写窗口。

1. 备份生产数据库并确认恢复流程可用。发布全程由网关摘除 Agent 入口。
2. 进入维护窗口，先在网关摘除生产流量，再停止**全部**旧版本写实例。确认没有后台任务、旁路实例或运维脚本继续写入 Agent 表，并通过数据库连接、事务和审计记录验证 Agent 写入已经停止。
3. 保持上述停写状态，在**同一个冻结窗口**内使用只读账号运行 V10 preflight。密码只通过当前进程的 `DB_PASSWORD` 环境变量注入，命令和输出不得包含密码：

   ```powershell
   if ([string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) { throw 'DB_PASSWORD must be injected by the deployment secret store' }
   .\joysong-server\deploy\preflight-agent-v10.ps1 `
     -DatabaseHost '<生产MySQL主机>' `
     -DatabaseName '<生产数据库名>' `
     -DatabaseUser '<只读账号>'
   ```

   返回非零或出现 `V10 migration blocked` 时立即停止发布，不要启动 Flyway。保持流量摘除、旧实例停止和 Agent 禁用，先制定并审批遗留数据迁移方案。
4. preflight 通过后仍保持停写，只启动**一个**未接入生产流量的新版本实例执行 Flyway。迁移完成前不得启动其他实例或恢复任何生产流量。
5. 确认 Flyway 历史成功且该实例 readiness/`/actuator/health` 正常，再启动其余禁用 Agent 的新实例；完成发布版本的功能、并发和幂等门禁后才可恢复普通生产流量。数据库测试必须使用 fresh、隔离的 MySQL，不能连接生产或共享开发库。

任何 preflight、Flyway 或 readiness 失败都必须保持停写和入口摘除。禁止绕过 preflight、强行恢复流量、执行 Flyway `repair` 掩盖失败，或编辑已发布迁移后重试。

## 3. Secret-safe FastAIToken canary

保持生产 Agent 入口摘除，由受控运维环境向批准 endpoint 发起一次最小请求，模型使用 `AI_AGENT_MODEL`。日志只记录时间、模型标识、HTTP 状态、耗时和结果分类。

canary 必须满足：

- Authorization 从 Secret 注入，不打印请求头、Key、完整请求体或完整回答；
- 成功返回 `2xx`，实际返回模型与批准的 `AI_AGENT_MODEL` 一致；
- 没有 `401`、`429`、`5xx` 或超时；
- canary 与生产服务使用相同的固定直连出口策略。

canary 失败时保持禁用，先修复凭证、模型授权、Host 白名单、代理或供应商容量问题。

## 4. 内部 cohort 灰度

1. canary 与应用 readiness 均通过后，由网关仅向内部员工 cohort 开放 Agent 入口。
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

出现下列任一情况应立即由网关或发布平台摘除 Agent 入口，不等待 cohort 扩大：

- 任何持续的凭证错误（尤其 `401`）；
- 持续的 `429`、`5xx`、超时或 p95 显著恶化；
- 发现重复 turn、幂等失效或 stale 恢复异常增长；
- 发现不安全医美文案、诊断性结论或保证效果表述。

停用后保留脱敏证据，冻结 cohort 扩大并开展复盘。恢复前重新执行 preflight 适用性确认、readiness、FastAIToken canary 和内部 cohort 门禁。

## 6. 回退边界

运行时回退的第一动作始终是摘除 Agent 入口。不要通过删除数据库表或回滚 Flyway 迁移来停用功能。

V15 是 Agent lease 的前向兼容迁移，必须保留；**不得回滚或删除 V15**。所有已发布迁移文件都保持不可变，以免改变 Flyway checksum。若需要回退应用版本，先确认旧版本可读取当前 schema，并保持 Agent 禁用。任何后续 schema 修正都应使用新的前向迁移完成。
