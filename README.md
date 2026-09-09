# JoySong

JoySong 是一个面向跨境医美业务的中英双语应用，包含 Kotlin/Spring 后端、Flutter Android/iOS 客户端和 React 管理端。仓库采用单仓库、模块化单体和按业务领域组织的结构。

当前稳定开发基线为 `origin/master`。发布、数据库和安全边界以仓库内的权威指南为准；本 README 用于帮助团队成员快速进入项目，不替代领域文档和部署手册。

## 项目状态

- Backend：Spring Boot 3.2.2、Kotlin 1.9.22、Java 17，使用 MySQL 和 Flyway。
- Flutter：Android/iOS 共用客户端，英文应用展示名为 `JOYINGSONG`；生产标识为 `com.joysong.app`，UAT 和调试标识以平台构建配置为准。
- Admin：React 19 + TypeScript + Vite，使用 Oxlint、Vitest、`tsc -b` 和未使用代码检查。
- UAT：`v0.0.1-uat.3` 已通过 GitHub Actions 和专用 ECS Runner 完成技术部署；当前状态为“`UAT Candidate` 已部署，公网未 `Ready`”。
- 当前仍有明确限制：真实 Alipay+ 支付、iOS 签名与商店发布、公网 DNS/TLS 和部分完整业务链路需要独立验收；Google 登录不属于首发范围，现有入口属于待清理的 P0 范围泄漏。

## 仓库结构

| 路径 | 责任范围 |
| --- | --- |
| `joysong-server/` | REST API、认证授权、业务领域、数据库迁移、部署脚本 |
| `joysong-flutter/` | Android/iOS 客户端、国际化、平台资源和移动端测试 |
| `joysong-admin/` | 管理后台、运营页面、管理权限和前端测试 |
| `docs/` | 产品契约、API 说明、测试记录、实施计划和部署指南 |
| `design/` | UML/PlantUML 源码；图片由维护者按需生成 |
| `doc/` | 历史开发记录、专题说明和外部资料 |
| `.github/` | CI、发布工作流、Issue/PR 模板 |

任务开始前阅读根目录 `AGENTS.md`。各子项目存在局部规则时，以其目录下的 `AGENTS.md` 补充约束为准。

## 技术栈与版本

| 组件 | 版本或要求 |
| --- | --- |
| Git | 支持 worktree 的 Git 版本 |
| Java | 17 |
| Kotlin | 1.9.22 |
| Gradle Wrapper | 8.9 |
| Spring Boot | 3.2.2 |
| Node.js | 22（CI 使用） |
| Flutter | 生产发布 workflow 固定 3.44.8；锁文件要求 Flutter `>=3.44.0`、Dart `>=3.12.0 <4.0.0` |
| Database | MySQL 8；集成测试使用 Testcontainers |
| 本地数据库 | MySQL 8 Server 与 `mysql` CLI（运行 Backend 时需要） |
| 本地容器 | Docker Desktop 或兼容的 Docker runtime（运行集成测试时需要） |
| Android | Android SDK、模拟器或真机；真机联调需要 ADB |
| iOS | macOS、Xcode 和 CocoaPods |

## 快速开始

### 1. 获取仓库

仓库为私有仓库时，先由维护者授予 GitHub 读取权限，再从 `master` 获取当前基线：

```powershell
git clone https://github.com/shimmail/joysong.git
Set-Location joysong
git switch master
git pull --ff-only origin master
```

### 2. 创建独立工作区

不要在带有其他人未提交修改的目录中开发。推荐从远端基线创建独立 worktree；模块名和任务名使用简短的 ASCII `snake_case`：

```powershell
$moduleName = 'backend'
$taskName = 'chat_api'
$worktreePath = ".worktrees/$taskName"
New-Item -ItemType Directory -Force .worktrees | Out-Null
git fetch origin
git worktree add $worktreePath -b "feature/${moduleName}-$taskName" origin/master
Set-Location $worktreePath
git status --short --untracked-files=all
```

开始实现前保存这次状态输出；不要修改或清理无法确认归属的既有变更。

### 3. 启动 Backend

在 `joysong-server/` 中准备本机配置（建议在独立终端执行）。注意：示例文件只打开开发开关，不会替你选择隔离数据库；启动前必须显式设置本 worktree 的 JDBC URL：

```powershell
Set-Location joysong-server
if (-not (Test-Path src/main/resources/application-dev.yml)) {
  Copy-Item src/main/resources/application-dev.example.yml src/main/resources/application-dev.yml
}
$worktreeName = Split-Path -Leaf (git rev-parse --show-toplevel)
$worktreeSlug = (($worktreeName -replace '[^A-Za-z0-9]+', '_').Trim('_')).ToLowerInvariant()
if ([string]::IsNullOrWhiteSpace($worktreeSlug)) { throw 'worktree directory must contain ASCII letters or digits' }
$worktreeId = "worktree_$worktreeSlug"
$databaseName = "myapp_$worktreeId"
if ($databaseName.Length -gt 64) { throw 'derived database name exceeds the MySQL 64-character limit' }
if ([string]::IsNullOrWhiteSpace($env:DB_USERNAME) -or [string]::IsNullOrWhiteSpace($env:DB_PASSWORD)) {
  throw 'Set DB_USERNAME and DB_PASSWORD in the local process environment before starting.'
}
Write-Host "Database host: localhost:3306"
Write-Host "Database name: $databaseName"
mysql --host=localhost --user=root --password --execute="CREATE DATABASE $databaseName CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
$env:SPRING_DATASOURCE_URL = "jdbc:mysql://localhost:3306/$databaseName?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&createDatabaseIfNotExist=false"
$env:SPRING_DATASOURCE_USERNAME = $env:DB_USERNAME
$env:SPRING_DATASOURCE_PASSWORD = $env:DB_PASSWORD
$env:SPRING_PROFILES_ACTIVE = 'dev'
.\gradlew.bat bootRun
```

上面的 `root` 和密码提示只用于首次创建本机隔离数据库，不读取 `DB_PASSWORD`；执行前确认打印出的 host/name，且不要把这条命令复制到 UAT 或生产。随后应由本机数据库管理员把该库权限授予 `DB_USERNAME` 对应的应用账号；`DB_PASSWORD` 是该应用账号的密码，不是管理员密码。只做一次性本机联调时可以明确使用本机 `root` 账号，但不得沿用到共享或远程环境。

开发启动还要求至少 32 字符的 `JWT_SECRET`、有效的 `ADMIN_PHONE`、`GOOGLE_CLIENT_ID`，以及通过校验的 `AI_AGENT_PROVIDER`/`AI_AGENT_BASE_URL`。Google 登录虽不属于首发范围，但当前非 demo profile 的配置校验仍要求 `GOOGLE_CLIENT_ID`；在移除这项技术债前，不得用它重新开放 Google 登录入口。开发环境缺少 AI key 或模型名时非 AI 页面仍可运行，但 AI 调用会失败；生产环境必须提供 `AI_AGENT_API_KEY`、`AI_AGENT_MODEL` 和 `AI_AGENT_INTENT_MODEL`。

首次启动全新空库时，还需临时提供 12–128 字符的 `ADMIN_PASSWORD`，用于创建唯一固定管理员。初始化成功后移除该变量并重启验证；重复设置它不会重置管理员密码。已有数据库不得通过清空用户来重新执行初始化。

`joysong-server/.env.example` 只是一份变量名参考，Spring Boot 不会自动加载它。本地 `application-dev.yml` 已被忽略，禁止提交真实密钥；数据库必须是当前 worktree 专属的新 `myapp_worktree_*` 数据库。

默认服务地址为 `http://localhost:8080`。Android 模拟器访问宿主机时使用 `http://10.0.2.2:8080`；真机联调见 [SSH 隧道指南](docs/guide/deployment/SSH_TUNNEL.md)。

### 4. 启动 Admin

在 worktree 根目录打开另一个终端：

```powershell
Set-Location joysong-admin
npm ci
npm run dev
```

开发服务器默认运行在 `http://localhost:3000`，`/api` 和 `/images` 由 Vite 代理到本机 Backend `http://localhost:8080`。

### 5. 启动 Flutter

在 worktree 根目录打开另一个终端：

先安装依赖。Android 模拟器使用 `development` flavor：

```powershell
Set-Location joysong-flutter
flutter pub get
flutter run --flavor development `
  --dart-define=APP_ENV=development `
  --dart-define=API_BASE_URL=http://10.0.2.2:8080
```

Android 真机先建立端口反向映射：

```powershell
adb reverse tcp:8080 tcp:8080
flutter run --flavor development `
  --dart-define=APP_ENV=development `
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

项目目前只有一个 iOS `Runner` scheme，因此在 macOS 上不要传 Android 的 `--flavor`：

```shell
flutter run \
  --dart-define=APP_ENV=development \
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

UAT 和生产构建必须使用 HTTPS。签名、iOS/macOS 环境和平台资源规则见 [Flutter iOS/macOS 构建指南](docs/guide/FLUTTER_IOS_MAC_BUILD.md) 与 [发布检查清单](docs/FLUTTER_RELEASE_CHECKLIST.md)。

## 配置与安全边界

- 不提交 `.env`、`application-dev.yml`、数据库密码、JWT/AI/云服务密钥、签名文件、证书或真实用户数据。
- Backend 的配置校验会拒绝缺少必要秘密或不安全的生产支付设置；生产配置只能通过受控环境注入。
- Flutter 客户端不保存服务端秘密；短信、OSS、AI、支付以及默认关闭的历史 Stripe 兼容凭据只配置在 Backend 环境。
- 身份材料走私有上传接口，不得放入公共上传路径；日志不得输出令牌、密码、验证码、证件号码或完整私聊内容。
- 旧的 `joysong-server/openapi.yaml` 和 `openapi.json` 是不完整快照，禁止用它们生成客户端。接口以实际 DTO、迁移契约和 contract test 为准，详见 [Flutter API 契约](docs/FLUTTER_API_CONTRACT.md)。

## 数据库与 Worktree 隔离

测试和迁移绝不能连接共享开发数据库。每个 worktree 都必须从目录名派生独立标识：

```text
WORKTREE_ID       = "worktree_" + normalize(directory)
数据库            = "myapp_" + WORKTREE_ID
Docker Compose    = "myapp-" + WORKTREE_ID
SQLite            = .runtime/
```

执行迁移或数据库写操作前，先打印并核对实际 database host 和 database name。只允许删除或重置名称以 `myapp_worktree_` 开头的数据库；修改迁移后必须在新建空数据库上验证。相关测试与 UAT 约束见 [AI Agent 测试指南](docs/AI_AGENT_TESTING.md) 和 [部署配置参考](docs/guide/deployment/CONFIGURATION_REFERENCE.md)。

## 本地验证

根据改动范围运行最小相关检查，不要反复执行已经通过的命令。

### Backend

```powershell
Set-Location joysong-server
.\gradlew.bat test --tests 'com.joysong.server.order.OrderServiceTest'
```

将示例类名替换为与改动最接近的完整测试类名。

MySQL/Flyway 集成测试使用：

```powershell
.\gradlew.bat mysqlIntegrationTest --tests 'com.joysong.server.migration.BaselineMigrationIntegrationTest'
```

### Admin

```powershell
Set-Location joysong-admin
npm run lint
npm test -- --maxWorkers=1 --fileParallelism=false
npm run build
```

### Flutter

```powershell
$repoRoot = (Resolve-Path .).Path
$flutterCache = Join-Path $repoRoot '.flutter-cache'
$env:PUB_CACHE = Join-Path $flutterCache 'pub-cache'
$env:GRADLE_USER_HOME = Join-Path $flutterCache 'gradle'
$env:ANDROID_USER_HOME = Join-Path $flutterCache 'android-home'
$env:APPDATA = Join-Path $flutterCache 'appdata'
$env:LOCALAPPDATA = Join-Path $flutterCache 'localappdata'
$env:TEMP = Join-Path $flutterCache 'temp'
$env:TMP = $env:TEMP
$flutter = Join-Path $flutterCache 'sdk\flutter\bin\flutter.bat'
$dart = Join-Path $flutterCache 'sdk\flutter\bin\dart.bat'

Set-Location (Join-Path $repoRoot 'joysong-flutter')
& $dart format --output=none --set-exit-if-changed lib test
& $flutter analyze
& $flutter test
& $flutter build apk --debug --flavor development --dart-define=APP_ENV=development
```

以上设置只影响当前 PowerShell 进程，复用仓库根目录已有的 Flutter 3.44.8、Pub 和 Gradle 缓存，避免在 C 盘重复下载。PR 和 `master` push 的质量门禁会对 Flutter 相关改动自动执行相同的 analyze、test 和 Android debug build；GitHub 托管 Runner 使用远程缓存。

Windows 可完成 Android 构建；iOS 构建、签名、真机和 TestFlight 验收必须在 macOS/Xcode 环境完成。完整移动端发布检查见 [Flutter 发布检查清单](docs/FLUTTER_RELEASE_CHECKLIST.md)。

### 变更完成前

```powershell
git diff --check
git status --short --untracked-files=all
```

每个新增路径都必须能归类为源码、测试、配置、维护文档或受控生成物；缓存、日志和一次性脚本必须清理。

## 团队协作流程

1. 从 `origin/master` 创建短生命周期分支或独立 worktree；不直接在 `master` 开发。
2. 一个任务指定一个 DRI，并限定明确的模块和文件范围。
3. 跨端需求先写清验收条件、权限、错误码、请求/响应和兼容策略，再并行实现 Backend、Flutter 和 Admin。
4. 复用现有模块、服务和测试模式；不要把无关格式化、热点文件重构和功能需求混在同一变更中。
5. 运行最小相关测试，记录环境型失败和未运行的检查，不用重复重试掩盖问题。
6. 完成前检查 Git 状态、敏感文件、临时产物和文档链接；除非明确授权，不自动提交、合并、打 tag 或推送。

后端继续采用模块化单体。新代码按业务领域归属，避免新增 `chat/agent`、`order/payment`、`auth/user` 等双向依赖。Flutter 保持 `core + features/{data,domain,presentation}`；新增代码不得让一个 feature 的 presentation 直接依赖另一个 feature 的 presentation。Admin 新功能按领域拆分 API、类型、hooks、组件和页面。

## API、迁移与发布变更

- API 变化必须同时说明消费者、权限、错误码和兼容性；破坏性变化需要迁移窗口或版本策略。
- Flyway 迁移只新增版本，不修改已进入共享分支的历史脚本。
- 修改部署、配置、迁移、发布包或安全边界时，同一变更更新对应权威指南和必要的 `design/` UML 源码。
- UAT 使用不可变 tag（`vX.Y.Z-uat.N`），失败 tag 不复用；`v0.0.1-uat.3` 已完成技术部署，固定状态为“`UAT Candidate` 已部署，公网未 `Ready`”，不能当作生产验收结论。
- 生产发布必须从已验证的 UAT/主分支提交晋级，包含制品摘要、SBOM、扫描报告、备份和可审计回滚证据。

部署入口和主机契约详见 [UAT 部署指南](docs/guide/deployment/README.md)，仓库执行资产索引详见 [部署资产 README](joysong-server/deploy/README.md)。

## 文档地图

| 需求或问题 | 首选文档 |
| --- | --- |
| 首发产品范围、当前完成度和 P0 缺口 | [APP_MVP_FUNCTIONAL_SPEC.md](docs/APP_MVP_FUNCTIONAL_SPEC.md) |
| Flutter API、DTO、错误码 | [FLUTTER_API_CONTRACT.md](docs/FLUTTER_API_CONTRACT.md) |
| Flutter 架构和历史迁移背景 | [FLUTTER_DUAL_PLATFORM_PLAN.md](docs/FLUTTER_DUAL_PLATFORM_PLAN.md) |
| Android/iOS 发布 | [FLUTTER_RELEASE_CHECKLIST.md](docs/FLUTTER_RELEASE_CHECKLIST.md) |
| iOS/macOS 构建 | [FLUTTER_IOS_MAC_BUILD.md](docs/guide/FLUTTER_IOS_MAC_BUILD.md) |
| 本地真机/SSH 联调 | [SSH_TUNNEL.md](docs/guide/deployment/SSH_TUNNEL.md) |
| UAT 主机、制品和回滚 | [deployment/README.md](docs/guide/deployment/README.md) |
| 最近一次 UAT 执行与验收证据 | [uat-runner-metadata-isolation-20260906.md](docs/plan/uat-runner-metadata-isolation-20260906.md) |
| 环境变量和秘密边界 | [CONFIGURATION_REFERENCE.md](docs/guide/deployment/CONFIGURATION_REFERENCE.md) |
| 自动化测试和数据库隔离 | [AI_AGENT_TESTING.md](docs/AI_AGENT_TESTING.md) |
| 角色全链路人工验收 | [docs/test/README.md](docs/test/README.md) |
| 功能实施记录 | [docs/plan/](docs/plan/) |
| UML 源码 | [design/](design/) |

## 已知限制与排障顺序

- 当前 PR 质量门禁覆盖 Backend、Admin、Flutter、Infrastructure 和安全扫描；Flutter 相关改动会自动执行 analyze、test 和 Android development debug build。
- 后端启动失败：先检查 `SPRING_PROFILES_ACTIVE`、`DB_USERNAME`/`DB_PASSWORD`、`JWT_SECRET`、`ADMIN_PHONE`、Google/AI 必要配置、数据库 host/name 和 Flyway 状态。
- Android 无法访问本机 Backend：确认服务监听、`adb reverse` 和 `API_BASE_URL`；模拟器使用 `10.0.2.2`。
- Admin 请求失败：确认 Backend 在 8080，且 Vite 代理没有被自定义配置覆盖。
- UAT 发布失败：不要重复使用失败 tag，也不要手动修改主机 current/previous；按部署指南保留证据并处理事务状态。
- iOS 无法构建：确认 macOS/Xcode、CocoaPods、Bundle 配置和签名材料；Windows 不能替代 iOS 验收环境。
- 测试需要数据库：先确认数据库名以 `myapp_worktree_` 开头，再执行迁移或重置。

出现代码、CI、主机事实和文档不一致时，先停止发布，修正唯一权威来源和对应测试，再继续开发。
