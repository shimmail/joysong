# AI Agent backend hardening final-fix report

- 日期：2026-08-11
- 分支：`codex/ai-agent-production-hardening`
- 范围：仅 `joysong-server`、后端部署文档与本报告；未修改 V10、Flutter，未访问公网或共享数据库。
- 数据库验证：所有集成测试均使用 Testcontainers；执行前打印 `AGENT_TEST_DB_HOST=Testcontainers` 与 `AGENT_TEST_DB_NAME=myapp_worktree_ai_agent_production_hardening`。

## Commits

| Commit | 内容 |
|---|---|
| `e5a9afc` | V11 legacy RUNNING 回填、lease HTTP 预算下限、统一 Clock、same-key stale 稳定错误码 |
| `16c8bf8` | 生产显式 model、base URL 端口与规范化、拒绝 HTTPS proxy、配置文档 |
| `88130a3` | PLANNING catalog 安全投影、provider-error 契约矩阵、disabled numeric code |
| 本报告所在提交 | 最终证据、文件索引、自审与部署关注 |

## Finding 1：V11 回填 legacy RUNNING lease

- RED：在 V10 阶段插入 `RUNNING` Turn 后迁移到 V11，新增断言发现 `lease_expires_at` 仍为 `NULL`，该行无法被 `recoverIfExpired` 回收。
- 修复：只修改尚未部署的 V11，将 legacy `RUNNING AND lease_expires_at IS NULL` 回填为 `started_at`，使其在新版本首次竞争时可立即恢复；V10 未修改。
- GREEN：`mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest.already V10*'`，V10→V11 数据断言通过；Spring 测试上下文同时在新的空 Testcontainers 数据库执行完整迁移，`BUILD SUCCESSFUL`（1m19s）。
- 文件：`V11__add_agent_turn_lease.sql`、`AgentV2MySqlIntegrationTest.kt`。
- Commit：`e5a9afc`。
- 关注：若任何环境已执行旧 checksum 的 V11，不能直接部署此版本，应改用新迁移；已确认目标数据库尚未部署本分支 V11。滚动发布前应 drain 旧节点，避免正在执行的旧请求与恢复请求重叠。

## Finding 2：lease 正值与总 HTTP 预算下限

- RED：`AgentWorkflowCoreTest` + `AiAgentPropertiesTest` 首轮共 41 tests / 9 failures，其中 `-1s`、`0s`、`81s` 和 runtime bean 的 `81s` 均未被拒绝。
- 修复：共享定义 completion `10s + 60s`、intent parser `3s + 8s`，最大串行预算为 81 秒；turn lease 最小值为 82 秒。启动 validator 与 runtime bean 双层拒绝过短、零值和负值。
- GREEN：`test --offline --tests '*AgentWorkflowCoreTest' --tests '*AiAgentPropertiesTest'`，`BUILD SUCCESSFUL`（18s）。
- 文件：`AiAgentProperties.kt`、`RestTemplateConfig.kt`、`ConfigValidator.kt`、`AiAgentPropertiesTest.kt`、`AgentWorkflowCoreTest.kt`。
- Commit：`e5a9afc`。
- 关注：未来增大 timeout 或增加新的串行 provider 调用时，必须同步更新集中预算常量和最小 lease；默认 90 秒当前保留 9 秒余量。

## Finding 3：生产 model 必须显式配置

- RED：新增真实 prod `ApplicationContextRunner` 启动断言；同一配置测试批次在旧实现缺少后续 finding 所需的 `normalizeAllowed` API 时先于断言执行发生编译 RED。旧 `application.yml` 的 `${AI_AGENT_MODEL:gpt-5.5}` 会让该启动断言在单独执行时错误启动成功。
- 修复：共享 `application.yml` 改为 `${AI_AGENT_MODEL:}`；仅 `application-dev.example.yml` 保留本地示例默认；生产启用 Agent 时 validator 要求显式 model。
- GREEN：四个配置测试类共 29 tests，`BUILD SUCCESSFUL`（14s）；真实 prod binding/startup 在缺少 model 时失败且根因包含 `AI_AGENT_MODEL`。
- 文件：`application.yml`、`application-dev.example.yml`、`AiAgentProfileStartupTest.kt`、`.env.example` 及三份部署文档。
- Commit：`16c8bf8`。
- 关注：部署 Secret/环境变量必须提供 `AI_AGENT_MODEL`；不再支持文档曾声称的 `OPENAI_MODEL` 回退。

## Finding 4：base URL 端口限制与运行时规范化

- RED：新测试引用缺失的 `OpenAiBaseUrlPolicy.normalizeAllowed`，配置批次编译 RED；它同时规定 `:443` 接受、`:444` 拒绝以及外围空白/大小写/默认端口/尾斜杠的 canonical 结果。
- 修复：只允许 exact `www.fastaitoken.com`、HTTPS、无 user-info/query/fragment、无显式端口或显式 443；成功后把 canonical URL 回写到共享 `AiAgentProperties`。两个 Chat Completions 路径使用统一且 trim 后的运行时 URL。
- GREEN：`OpenAiBaseUrlPolicyTest`、`AiAgentPropertiesTest`、`AiAgentProfileStartupTest`、`RestTemplateConfigTest` 共 29 tests，`BUILD SUCCESSFUL`（14s）。
- 文件：`OpenAiBaseUrlPolicy.kt`、`ConfigValidator.kt`、`ChatService.kt` 及对应配置测试。
- Commit：`16c8bf8`。
- 关注：当前生产白名单有意只允许 FastAIToken 443；新增供应商必须先扩展策略与测试。

## Finding 5：TurnLifecycleService 统一 injected Clock

- RED：固定 Clock 测试发现 begin/create、complete、fail/cancel、clear history 的写入时间不一致；首轮 workflow/config 批次中相关 Clock 断言失败。
- 修复：Turn、USER/ASSISTANT message、session update、complete/fail/cancel/clear 等生命周期写入全部使用 `LocalDateTime.now(clock)`。
- GREEN：`AgentWorkflowCoreTest` + `AiAgentPropertiesTest` focused run，`BUILD SUCCESSFUL`（18s）。
- 文件：`TurnLifecycleService.kt`、`AgentWorkflowCoreTest.kt`。
- Commit：`e5a9afc`。
- 关注：数据库仍使用无时区 `DATETIME`；本修复令所有该服务写入与生产 UTC Clock 一致，但历史本地时间不会改写。

## Finding 6：PLANNING catalogItems/report 安全投影

- RED：真实 MySQL 测试首先失败为 `expected <> but was <恢复期1天 subtitle-marker>`，证明完成响应仍透传自由文本。
- 修复：在 `completeTurn` 持久化边界只对 `PLANNING` 做一次统一投影，并将同一 projected command 用于 `metadata_json`、context summary 和 response/replay reconstruction。保留 type/id/name/institutionId/projectId/canChatWithHuman；清空 subtitle/summary；属性同时要求 exact label 白名单和价格/数字/认证值形态；report title/summary/warnings 改为固定中性文本并清空 comparison dimensions。Planning grounding prompt 复用同一投影。
- GREEN：
  - `mysqlIntegrationTest --offline --rerun-tasks --tests '*AgentV2MySqlIntegrationTest.planning completion*'`，`BUILD SUCCESSFUL`（1m16s）。
  - 最终 focused verification 同时运行上述持久化测试与 `AgentChatFlowIntegrationTest.unsafe planning output*`，2/2 通过，`BUILD SUCCESSFUL`（43s）。
- 集成断言覆盖：首次 response、数据库 `metadata_json`、session summary、same-key replay 均不含 description/slogan/detail 或“恢复期/无痛/零风险”等 marker，同时保留标识、名称、关联 ID 与安全结构属性。
- 文件：`PlanningCatalogProjection.kt`、`TurnLifecycleService.kt`、`ChatService.kt`、`AgentV2MySqlIntegrationTest.kt`。
- Commit：`88130a3`。
- 关注：本次没有新增历史数据迁移；新完成 Turn 及其 replay 均安全。若部署库中可能已有旧版本生成的 SUCCEEDED PLANNING metadata，应在部署前确认并选择数据迁移或读取边界二次投影。

## Finding 7：明确拒绝 HTTPS proxy

- RED：新增 validator/client factory 契约要求 `https://proxy...` 失败；配置批次先因缺失的 base URL normalization API 编译 RED。旧策略明确允许 `https`，而运行时却只能创建普通 HTTP proxy。
- 修复：只允许带 host 和显式有效端口的 `http`、`socks`；validator 和 client factory 返回固定配置错误且不回显 URL/credentials；运行时 trim proxy URL。
- GREEN：四个配置测试类共 29 tests，`BUILD SUCCESSFUL`（14s）。
- 文件：`AiAgentProperties.kt`、`ConfigValidator.kt`、`RestTemplateConfig.kt`、`AiAgentPropertiesTest.kt`、`RestTemplateConfigTest.kt`、`.env.example` 与部署文档。
- Commit：`16c8bf8`。
- 关注：这不是 HTTPS target over HTTP CONNECT 的限制，而是 proxy URL scheme 的明确契约；当前实现不声称支持 TLS-to-proxy。

## Finding 8：provider-error 等价契约与脱敏矩阵

- 初次 characterization：新增矩阵第一次有效执行即 GREEN，说明现有生产异常归并已经满足契约；本项是测试覆盖缺口，没有制造生产改动或虚构 RED。
- 覆盖：401、429、500、503、malformed JSON、`choices: []`、read timeout，共 7 cases。
- 统一断言：HTTP 503、numeric `$.code == 503`、稳定 `AI_PROVIDER_UNAVAILABLE` / `AI_PROVIDER_TIMEOUT`、非空 traceId、Turn=`FAILED` 且 errorCode 稳定、无 ASSISTANT、失败 USER 不进入可见历史；response、operation log、message metadata 不含 raw body、provider base URL、API key 或 idempotency key。
- GREEN：三个 provider 测试入口一次运行共 7 tests / 0 failures / 0 errors，`BUILD SUCCESSFUL`（1m51s）。
- 文件：`AgentChatFlowIntegrationTest.kt`（fake provider raw response fixture 与参数化契约）。
- Commit：`88130a3`。
- 关注：未来如需区分 provider 的 401/429/5xx，只能增加脱敏内部指标，不能把 raw body/URL/key 放入 API、持久化或日志。

## Minor 1：same-key stale 错误码持续稳定

- RED：连续两次相同 key/内容请求过期 RUNNING Turn，第一次返回 `IDEMPOTENCY_EXPIRED`，第二次在旧实现变成 `IDEMPOTENCY_KEY_CONFLICT`。
- 修复：只对 `FAILED + STALE_RECOVERED + same request hash` 返回 `IdempotencyExpired`；不同内容或其他 FAILED/CANCELLED 状态仍冲突。
- GREEN：`AgentWorkflowCoreTest` focused run 通过，并验证只恢复/flush 一次。
- 文件：`TurnLifecycleService.kt`、`AgentWorkflowCoreTest.kt`。
- Commit：`e5a9afc`。

## Minor 2：disabled integration numeric code

- 初次 characterization：生产 handler 已正确返回 numeric 503；新增遗漏断言第一次执行即 GREEN，无生产改动。
- GREEN：`AgentChatFlowIntegrationTest.HTTP send is rejected as AGENT_DISABLED*`，`BUILD SUCCESSFUL`（30s），同时断言 HTTP 503、`$.code == 503`、message=`AGENT_DISABLED` 且 turn 未持久化。
- 文件：`AgentChatFlowIntegrationTest.kt`。
- Commit：`88130a3`。

## 最终验证与自审

- 未运行全量测试；遵守要求只运行改动相关的 unit/config/MySQL Testcontainers 测试。
- 最终 PLANNING focused verification：2/2 通过，43 秒。
- Provider matrix：7/7 通过；disabled 单例通过。
- `git diff --check` 通过；V10 与 Flutter 无 diff。
- 独立只读审查：未发现阻断或 P1；确认新完成链路 response/metadata/context/replay 使用一致投影，provider matrix 与 disabled 断言完整。审查指出的唯一 P2 是“可能存在的历史 SUCCEEDED PLANNING metadata 未迁移”，已列为部署前数据存在性确认项。
- 已知非功能警告：Gradle 报告现有 Gradle 9 deprecation 与若干既有 Kotlin warning；本修复未新增对应生产 warning。
