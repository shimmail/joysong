# iOS 开发产物与文档 GitHub 提交实施计划

**计划编号**：IOS-GH-20260908<br>
**适用仓库**：`joysong`<br>
**目标分支**：`codex/ios-runner-build-docs`<br>
**计划状态**：已完成（2026-09-08）

## 1. 目标

将当前已经验证过的 Joysong Flutter iOS Runner 工程配置和可复核的构建文档，以最小、可审查的变更提交到 GitHub。不得把本机生成物、模拟器二进制、其他模块的未提交改动或任何凭据带入提交。

本计划只覆盖“源码/工程配置 + 文档证据”的 Git 提交。不新增 SwiftUI、App Intents、App Shortcuts 或 Widget target，也不负责公开域名切换或 App Store 发布。

## 1.1 实施结果

- 工程配置提交：`f3aec861 fix(ios): configure Runner simulator build dependencies`，包含 Podfile、Runner Xcode 工程、共享 scheme 和 workspace 的 Simulator 构建依赖修复。
- 证据文档提交：`e5b28dcd docs(ios): record simulator build and debug evidence`，包含本计划和 [iOS Runner 构建证据](ios-runner-build-evidence-20260908.md)。
- 提交目标分支为 `codex/ios-runner-build-docs`；该分支与本地缓存的 `origin/codex/ios-runner-build-docs` 一致。
- 本轮未提交 Pods、`Podfile.lock`、Flutter/Xcode 生成物、模拟器产物、签名文件或仅权限位变化的文件。

本文件保留以下实施前基线、操作边界和任务文本，供后续同类提交复用；其命令不表示需要再次执行本轮提交。

## 2. 已知基线

- 实施前分支为 `master`，与当时的 `origin/master` 同步；远端为 `git@github.com:shimmail/joysong.git`。
- 当前工作树约有 1,100 项以上未提交变更，主要分布在 UI、服务端、Admin、Android、Flutter 和文档；其中大量是 `100644 -> 100755` 权限位漂移。
- 已完成一次 iOS Simulator Debug 启动验证：产物目标为 `joysong-flutter/build/ios/iphonesimulator/Runner.app`，设备为 iPhone 16 Pro / iOS 18.1。
- 当前构建使用 `APP_ENV=development` 与 `API_BASE_URL=http://127.0.0.1:8080`，仅适用于本机或 SSH 隧道联调，不得标记为 UAT、TestFlight 或生产包。
- `flutter test test/core/config/app_environment_test.dart` 已通过；`flutter analyze` 仍在 `test/features/auth/login_page_test.dart:13` 和 `test/features/auth/login_page_test.dart:14` 报 `LoginStrings` 无未命名 const 构造函数，不能宣称全量静态检查通过。

## 3. 实施边界

### 3.1 允许审查和暂存的 iOS 路径

只对下列路径执行内容 diff 审查。只有确认存在语义变更且与 Runner 构建直接相关时，才能暂存：

```text
joysong-flutter/ios/Podfile
joysong-flutter/ios/Flutter/AppFrameworkInfo.plist
joysong-flutter/ios/Runner.xcodeproj/project.pbxproj
joysong-flutter/ios/Runner.xcodeproj/project.xcworkspace/contents.xcworkspacedata
joysong-flutter/ios/Runner.xcodeproj/xcshareddata/xcschemes/Runner.xcscheme
joysong-flutter/ios/Runner.xcworkspace/contents.xcworkspacedata
```

`AppDelegate.swift`、`Info.plist`、entitlements、插件注册文件、图标和 storyboard 当前主要是权限位变化；除非 diff 显示明确的功能内容变化，否则不得暂存。`Debug.xcconfig`、`Profile.xcconfig`、`Release.xcconfig` 同样按内容 diff 决定，不得因权限位变化纳入。

### 3.2 文档交付

本计划文件 `docs/plan/ios-github-submission-20260908.md` 本身是本轮文档交付的一部分，第二个文档 commit 必须包含它。

新增一份本计划对应的构建证据文档，建议路径为 `docs/plan/ios-runner-build-evidence-20260908.md`，必须包含：

1. 实际执行的完整构建命令和 `--dart-define` 值。
2. Flutter、Xcode、CocoaPods 版本。
3. Simulator 名称、系统版本、UDID 和构建退出码。
4. `Runner.app` 产物路径、存在性检查和体积；注明它是 Simulator 专用产物。
5. 首屏截图路径或受控引用、启动结果和关键日志摘要。
6. `flutter test`、`flutter analyze`、`pod install` 的结果；对 analyze 已知失败保留第一段真实错误。
7. 当前 SSH 隧道地址只作为开发环境说明，不得写入任何私钥、token、密码或签名材料。

已有文档只有权限位变化时，不要为了凑提交而重新提交：

```text
docs/guide/FLUTTER_IOS_MAC_BUILD.md
docs/FLUTTER_RELEASE_CHECKLIST.md
docs/guide/FLUTTER_APP_BRANDING.md
docs/guide/deployment/SSH_TUNNEL.md
docs/guide/deployment/CONFIGURATION_REFERENCE.md
joysong-flutter/README.md
```

若实施 agent 修改上述文档，必须在提交说明中给出具体内容原因，并同时检查是否暴露公网 IP、隧道用户名或内部路径。

### 3.3 必须排除的路径和文件

以下内容一律不得进入 Git commit：

```text
joysong-flutter/build/**
joysong-flutter/ios/Pods/**
joysong-flutter/ios/Flutter/ephemeral/**
joysong-flutter/ios/Flutter/Generated*.xcconfig
joysong-flutter/ios/Flutter/flutter_export_environment*.sh
joysong-flutter/ios/Flutter/Flutter*.podspec
joysong-flutter/ios/**/xcuserdata/**
joysong-flutter/.flutter-plugins-dependencies*
joysong-flutter/touch-file
DerivedData/**
*.xcresult
*.p12
*.mobileprovision
*.cer
*.key
*.pem
*.jks
*.keystore
```

当前带空格后缀的生成文件（例如 `Flutter 2.podspec`、`Generated 2.xcconfig`）也必须排除。`joysong-flutter/ios/Podfile.lock` 本轮按既有边界保持排除；如需纳入，必须另行取得明确授权并单独提交。

## 4. 执行顺序

1. 记录 `git status --short --branch`、当前 HEAD 和远端；确认没有未授权的 staged 内容。
2. 建立或切换到 `codex/ios-runner-build-docs`，不重置、不清理、不覆盖现有工作树改动。
3. 对允许路径运行 `git diff --summary`、`git diff --check` 和内容审查，区分语义变化与权限位变化。
4. 新增构建证据文档；不得把截图、日志、`.app` 或 DerivedData 复制进源码树。
5. 只按显式路径暂存 iOS 工程文件和证据文档；禁止 `git add .`、`git add -A` 或按目录整体暂存。
6. 运行暂存区检查、敏感信息扫描和最小相关测试；构建失败时只记录第一条真实错误，不以静态检查替代 iOS 构建结果。
7. 拆成两个提交：iOS 工程提交、构建证据文档提交。
8. 推送新分支并创建 PR，目标为 `master`；不要直接推送 `master`。

建议命令骨架（实施 agent 需根据实际 diff 填充路径）：

```bash
git status --short --branch
git switch -c codex/ios-runner-build-docs
git diff --summary -- <explicit-allowlist>
git diff --check -- <explicit-allowlist>

# 只暂存审查后的明确文件，不使用 git add .
git add -- <reviewed-ios-files> docs/plan/ios-runner-build-evidence-20260908.md
git diff --cached --name-status
git diff --cached --summary
git diff --cached --check

git commit -m "fix(ios): configure Runner simulator build dependencies"
git add -- docs/plan/ios-github-submission-20260908.md docs/plan/ios-runner-build-evidence-20260908.md
git commit -m "docs(ios): record simulator build and debug evidence"
git push -u origin codex/ios-runner-build-docs
```

## 5. 可量化交付目标

实施 agent 必须逐项满足以下指标，并在最终报告中提供命令输出或文件路径作为证据：

| 编号 | 目标 | 验收阈值 |
| --- | --- | --- |
| Q1 | 分支隔离 | 新建并推送 1 个 `codex/ios-runner-build-docs` 分支；`master` 不产生新 commit |
| Q2 | 提交数量 | 恰好 2 个本轮新 commit：1 个 iOS 工程、1 个文档（包含本计划和构建证据） |
| Q3 | 路径范围 | 暂存区 100% 来自允许清单；Android、server、admin、UI 和无关 docs 路径数量为 0 |
| Q4 | 生成物隔离 | `Runner.app`、`build/**`、Pods、Generated 文件、xcuserdata、`.xcresult`、带空格生成副本和 `Podfile.lock` 入库数量均为 0 |
| Q5 | 权限漂移隔离 | 仅权限位变化的文件入库数量为 0；不得出现批量 `100644 -> 100755` 变更 |
| Q6 | 构建证据 | 证据文档包含 1 条完整构建命令、1 个明确 Simulator、1 个产物绝对路径、1 个退出码 `0` 和 1 段日志摘要 |
| Q7 | 运行验证 | Simulator 安装/启动成功 1 次；首屏非空；崩溃数为 0；`MissingPluginException`、文件选择器初始化错误和原生桥接异常各为 0 次 |
| Q8 | 测试记录 | 配置测试通过数为 6/6；`flutter analyze` 的失败必须记录原始文件/行号，不得标记为通过 |
| Q9 | 安全扫描 | 私钥、证书、token、密码、AccessKey、`.env` 和签名文件命中数为 0；staged diff 经过人工复核 |
| Q10 | 远端交付 | 分支推送退出码为 0，PR base=`master`，PR 描述包含变更范围、测试结果、已知阻塞和未提交文件说明 |

任何一个阈值未达到，状态必须标记为“未完成提交”，不得用“部分通过”替代。

## 6. 公开仓库风险门禁

公开 GitHub 前必须逐项确认：

- 文档中的 `121.41.230.98`、隧道用户名、Windows 路径和内部 bucket 名称是否允许公开；不允许时改为占位符。
- 不存在 SSH 私钥、Apple signing certificate、provisioning profile、Google service 配置、API key 或运行时环境文件。
- `API_BASE_URL=http://127.0.0.1:8080` 明确标为开发隧道配置；不能被误解为生产 endpoint。
- 模拟器 `.app` 不作为 App Store/TestFlight 产物；正式发布需在公开 HTTPS 域名可用后重新构建、签名和上传。

## 7. 后续域名切换提示

`API_BASE_URL` 通过 `--dart-define` 在编译期写入应用。公开域名和 TLS 就绪后，必须重新执行 UAT/Production 构建、重新签名、重新安装或上传；不能复用本轮 SSH 隧道 Debug 包。建议至少保留以下独立版本：

```text
development + 127.0.0.1:8080  -> Simulator 联调
uat + https://<uat-api-domain> -> 真机/测试人员验收
production + https://<production-api-domain> -> TestFlight/App Store
```

## 8. 可直接交给实施 agent 的任务文本

```text
请在 /Users/xuqianxun/Documents/Codex/joysong 执行计划 IOS-GH-20260908。

目标：把当前已验证的 Flutter iOS Runner 工程配置和构建证据安全提交到 GitHub。

硬性约束：
1. 只在新分支 codex/ios-runner-build-docs 工作，不重置或覆盖现有工作树。
2. 禁止 git add .、git add -A；只按计划允许清单逐文件暂存。
3. 只审查并按需提交 Podfile、AppFrameworkInfo.plist、Runner project/workspace/scheme 五类工程文件，以及 docs/plan/ios-github-submission-20260908.md 和新增的 docs/plan/ios-runner-build-evidence-20260908.md。
4. 生成物、Runner.app、Pods、Generated*.xcconfig、flutter_export_environment*.sh、Flutter*.podspec、xcuserdata、.xcresult、带空格生成副本、Podfile.lock、Android/server/admin/UI 和无关权限位变化必须为 0 个入库文件。
5. 产出恰好两个 commit，并推送分支创建 PR 到 master；不要直接推送 master。
6. 运行并记录 staged diff 检查、秘密扫描、配置测试、analyze 结果和已有 Simulator 构建/启动证据。analyze 的两处已知错误必须原样记录，不能声称全绿。
7. 最终报告用表格列出 Q1-Q10 的 pass/fail、命令、退出码、commit SHA、推送分支和未解决阻塞；任一硬性指标失败时标记“未完成提交”。
```
