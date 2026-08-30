# Codex Development Rules

本项目为flutter+kotlin的安卓ios双端跨境（中英双语）医美app。

对复杂任务使用多 agent，未使用的agent应及时关闭。编写时应该尽可能减少冗余代码及文件，以高内聚，低耦合为原则。测试完成后及时清理临时代码和文件。

## Incremental Development

- Prefer narrow tasks with explicit boundaries: backend, Flutter, admin, docs, or infra.
- Before editing complex changes, identify the smallest touched surface and the smallest relevant tests.
- Read project context selectively. Start from known entry points and expand only when evidence requires it.
- Do not perform unrelated refactors while completing a requested change.
- Reuse existing project patterns, helpers, configuration, and test style.
- Keep changes scoped and easy to review. Avoid new abstractions unless they remove real complexity.
- For complex cross-stack work, split investigation across multiple agents when available, then let the main agent integrate and edit.
- See `docs/CODEX_INCREMENTAL_DEVELOPMENT.md` for the full workflow.

## Project Map

- Backend: `joysong-server/`
- Flutter app: `joysong-flutter/`
- Admin app: `joysong-admin/`
- Legacy or alternate app code: `joysong-app/`
- Technical docs: `docs/`
- 开发实施计划：D:\code\kotlin\joysong\docs\plan。命名规则为模块+时间，避免混淆
- 相关指南文档的位置：D:\code\kotlin\joysong\docs\guide，需要持续维护
- Additional product and API docs: `doc/`
- 给开发人员看的uml代码放在：`design/`记得随开发推进提醒我进行更新。图片由我手动生成。

## Worktree Database Isolation

- Never connect tests or migrations to a shared development database.
- Derive `WORKTREE_ID` from the current worktree directory.
- Use database name `myapp_<WORKTREE_ID>`.
- Run Docker Compose with project name `myapp-<WORKTREE_ID>`.
- Store SQLite databases under `.runtime/`.
- Before running migrations, print the resolved database host and database name.
- Never drop or reset a database unless its name begins with `myapp_worktree_`.
- After changing migrations, verify them against a newly created empty database.

## Codex Test Rules

1. 先根据改动文件选择最小相关测试。
2. 修复失败时只运行失败用例或相关测试类。
3. 相关测试通过后，最多运行一次全量测试。
4. 全量测试超过 10 分钟则停止，报告进度和最慢测试。
5. 不要反复运行同一条已通过命令。
6. 对 flaky 或环境型失败，先标注证据，不要无限重试。

## Common Test Selection

- Backend controller, service, security, repository changes: run the related Gradle test class or method first.
- Flutter page, route, provider, or widget changes: run `flutter analyze` and the closest widget/unit test when present.
- Admin frontend changes: run the nearest lint, typecheck, or test command defined by that package.
- Documentation-only changes: do not run code tests unless the docs include generated artifacts or checked examples.
- Migration changes: verify against a fresh isolated database before broader tests.



## Superpowers 工作流使用规则

### 触发条件（以下情况才启用 Superpowers）
- 用户明确提到 "Superpowers"、"using Superpowers"、"启用 Superpowers 工作流"
- 用户明确指定某个具体 skill（如 brainstorming、writing-plans）
- 多步骤功能开发、大型重构、从 0 到 1 的新模块开发

### 轻量任务（不进入 Superpowers 链路）
轻量任务定义：单文件或小范围修改、明确 bug 修复、配置调整、文案修改、小测试补充[reference:5]

轻量任务默认行为：
- 直接分析代码并实现，不走 brainstorming / writing-plans 流程
- 不创建 worktree
- 不启动 subagent-driven-development
- 只有遇到关键不确定性时才提问，且首次最多问 1 个问题

### 默认行为（未触发 Superpowers 时）
- 不主动使用 Superpowers
- 不自动调用任何 Superpowers skill
- 按普通原生 agent 模式直接响应[reference:8]

## Docs merge policy

- `docs/**` 以本地 `master` 的已提交版本为准。
- 除非用户明确要求更新文档，功能 worktree 不得新增、修改、删除 `docs/**`。
- 合并最新 master 后，若功能分支涉及 `docs/**`，统一恢复为 master：
  `git restore --source=master --staged --worktree -- docs/`
- 如果主工作区的文档尚未提交，停止合并并等待用户先提交。
- 不得使用全局 `-X theirs`，避免同时覆盖代码冲突。
