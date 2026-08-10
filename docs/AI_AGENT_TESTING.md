# JoySong AI Agent 测试指南

本版本验证同步 REST Agent 工作流。SSE 已禁用，因此无需运行流式响应、流式重连或 Android 流式渲染测试。

## 隔离要求

所有测试均在当前 worktree 的 `joysong-server` 目录执行。测试或迁移前先打印解析后的数据库 host 与名称；数据库名必须为 `myapp_<WORKTREE_ID>`，Docker Compose 项目名必须为 `myapp-<WORKTREE_ID>`。不得连接共享开发数据库，也不得删除或重置名称不以 `myapp_worktree_` 开头的数据库。

```powershell
$env:GRADLE_USER_HOME='D:\code\kotlin\joysong\.tmp\gradle-user-home-codex'
cd D:\code\kotlin\joysong\.worktrees\ai-agent-architecture-refactor\joysong-server
```

## 定向 JVM 验证

```powershell
.\gradlew.bat test --offline `
  --tests '*AgentWorkflowCoreTest' `
  --tests '*AgentCatalogServiceTest' `
  --tests '*AgentProfileServiceTest' `
  --tests '*AgentSafetyServiceTest' `
  --tests 'com.joysong.server.config.OpenAiBaseUrlPolicyTest'
```

该组覆盖同步工作流、目录读取、资料、风险限制和模型 Base URL 策略。

## Fresh MySQL 集成验证

使用 `--rerun-tasks` 强制执行 fresh Testcontainers MySQL 验证：

```powershell
.\gradlew.bat mysqlIntegrationTest --offline --rerun-tasks `
  --tests '*AgentV2MySqlIntegrationTest' `
  --tests '*AgentChatFlowIntegrationTest'
```

该测试必须输出其隔离数据库 host 和名称。检查会话摘要写入、`summary_json`、最近 20 条/7 天上下文窗口、同步 HTTP 响应、可选幂等键及稳定 HTTP 状态。不得以 `agent_tool_audits` 查询或 traces API 作为验收手段。

## 全量 JVM 基线

在定向与 fresh 集成验证通过后，仅运行一次：

```powershell
.\gradlew.bat test --offline
```

允许的已知基线失败仅为四个因缺少忽略的本地配置导致的 `ProductionProfileTest` 失败。任何其他失败均阻塞本次交付，须记录失败类、测试数与证据，不得通过重试掩盖。

## 变更范围检查

```powershell
git diff --check
git status --short
git diff --name-only 01a50a5...HEAD
```

本任务只应影响 Agent/chat 计划路径与三份相关文档，不应引入迁移或无关业务模块变更。
