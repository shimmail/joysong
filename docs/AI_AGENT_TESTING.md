# JoySong AI Agent 测试指南

本版本同时验证同步 REST 与 SSE Agent 工作流。真人咨询的确定性 SSE 契约为 `started` → `completed`，不包含回答模型生成的 `delta`；流式完成、幂等重放和历史恢复都必须纳入验证。

## 隔离要求

所有后端测试均在当前 worktree 的 `joysong-server` 目录执行。测试或迁移前先打印解析后的数据库 host 与名称；数据库名必须为 `myapp_<WORKTREE_ID>`，Docker Compose 项目名必须为 `myapp-<WORKTREE_ID>`。不得连接共享开发数据库，也不得删除或重置名称不以 `myapp_worktree_` 开头的数据库。

```powershell
$repoRoot = git rev-parse --show-toplevel
Set-Location (Join-Path $repoRoot 'joysong-server')
$env:GRADLE_USER_HOME = Join-Path $env:USERPROFILE '.gradle'
```

## V10 部署预检

V10 会重建 Agent 表。正式运行 Flyway 前，使用只读预检确认 V10 是否仍待执行，以及固定清单内的旧 Agent 表是否含数据。数据库密码只能通过 `DB_PASSWORD` 传入；脚本参数和输出均不得包含密码。

```powershell
$env:DB_PASSWORD='<从密钥管理器注入>'
.\deploy\preflight-agent-v10.ps1 `
  -DatabaseHost '<mysql-host>' `
  -DatabasePort 3306 `
  -DatabaseName '<database-name>' `
  -DatabaseUser '<read-only-user>'
```

脚本在 V10 已执行，或 V10 待执行但旧 Agent 表不存在/为空时返回 `0`。若 V10 待执行且任一旧 Agent 表存在数据，则输出 `V10 migration blocked` 并返回非零；此时不得启动 Flyway，应先制定数据保留/迁移方案。测试调用必须增加 `-TestMode`，且数据库名必须以 `myapp_worktree_` 开头。

## 定向 JVM 验证

先运行真人咨询直接相关的路由、候选、工作流、上下文和 SSE 测试：

```powershell
.\gradlew.bat --offline --no-daemon test --console=plain `
  --tests '*AgentIntentRouterTest' `
  --tests '*InstitutionConsultantServiceTest' `
  --tests '*DiscoverSearchServiceTest' `
  --tests '*AgentCatalogServiceTest' `
  --tests '*ChatServiceContextRoutingTest' `
  --tests '*AgentWorkflowCoreTest' `
  --tests '*AgentStreamingServiceTest'
```

该组覆盖本地高精度和可选模型路由、安全/否定优先级、固定机构目标、咨询师资格机构 ID 的一次集合式查询，以及“点名机构 → 显式城市 → 无显式机构/城市时的合格详情上下文 → 画像城市 → 全国兜底”五级排序、固定文案和空态、零回答模型调用、元数据重建及 SSE 无 `delta`。

其余 Agent 基线可按变更范围补充：

```powershell
.\gradlew.bat --offline --no-daemon test --console=plain `
  --tests '*AgentWorkflowCoreTest' `
  --tests '*AgentCatalogServiceTest' `
  --tests '*AgentProfileServiceTest' `
  --tests '*AgentSafetyServiceTest' `
  --tests 'com.joysong.server.config.OpenAiBaseUrlPolicyTest'
```

该组覆盖通用工作流、目录读取、资料、风险限制和模型 Base URL 策略。已通过的同一命令不重复运行；修复失败时只重跑失败方法或相关测试类。

## Fresh MySQL 集成验证

使用专用 `mysqlIntegrationTest` 任务执行 fresh Testcontainers MySQL 验证；默认 `test` 任务不会发现带 MySQL integration tag 的用例：

```powershell
.\gradlew.bat --offline --no-daemon mysqlIntegrationTest --console=plain `
  --tests '*AgentMigrationPreflightTest' `
  --tests '*AgentV2MySqlIntegrationTest' `
  --tests '*AgentChatFlowIntegrationTest'
```

该测试必须先输出隔离数据库 host 和名称。除会话摘要、上下文窗口、同步 HTTP、幂等键和稳定状态外，还要验证真人咨询 REST 固定机构卡片、历史机构快照、持久化元数据不含咨询师 ID，以及 SSE `started` → `completed` 且没有 `delta`。不得以 `agent_tool_audits` 查询或 traces API 作为验收手段。Docker 不可用属于环境阻塞，记录一次证据后不要反复重试。

## Flutter 定向验证

从当前 worktree 的 `joysong-flutter` 目录运行仓库配置的 Flutter SDK。以下环境变量只作用于当前 PowerShell 进程，并把 Flutter、Pub、Gradle、Android 用户目录和临时文件统一保留在仓库根目录的 `.flutter-cache`，避免在 C 盘重复下载：

```powershell
$flutterCache = Join-Path $repoRoot '.flutter-cache'
$env:PUB_CACHE = Join-Path $flutterCache 'pub-cache'
$env:GRADLE_USER_HOME = Join-Path $flutterCache 'gradle'
$env:ANDROID_USER_HOME = Join-Path $flutterCache 'android-home'
$env:APPDATA = Join-Path $flutterCache 'appdata'
$env:LOCALAPPDATA = Join-Path $flutterCache 'localappdata'
$env:TEMP = Join-Path $flutterCache 'temp'
$env:TMP = $env:TEMP
Set-Location (Join-Path $repoRoot 'joysong-flutter')
$flutter = Join-Path $flutterCache 'sdk\flutter\bin\flutter.bat'
& $flutter test `
  test/features/agent/agent_catalog_cards_test.dart `
  test/features/agent/agent_chat_page_test.dart `
  test/features/shell/app_shell_navigation_test.dart
& $flutter test test/features/shell/institution_consultant_picker_test.dart
```

验证机构和机构项目只转发安全机构 ID，doctor/project/unknown/blank 不产生真人咨询动作；当前与历史卡片都打开现有 picker，重新加载当前 consultants，并且只有用户选择的 `BookingConsultant.id` 成为 DM `targetId`。

## 全量 JVM 基线

在定向与 fresh 集成验证通过后，仅运行一次：

```powershell
.\gradlew.bat test --offline
```

全量测试超过 10 分钟则停止并报告已完成进度和最慢测试。任何失败都须记录失败类、测试数与证据；环境或 flaky 失败没有新证据时不得通过重试掩盖。

## 变更范围检查

```powershell
git diff --check
git status --short
$baseRef = '<目标分支或已确认的基线提交>'
git diff --name-only "$baseRef...HEAD"
```

按当前任务批准的基线核对变更范围。真人咨询转接不应引入数据库迁移、全局路由、新状态管理层或无关业务模块变更。
