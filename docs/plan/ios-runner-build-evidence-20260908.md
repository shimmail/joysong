# Joysong iOS Runner 构建证据（2026-09-08）

对应计划：`IOS-GH-20260908`

本记录只描述开发环境的 iOS Simulator 验证。它不是 UAT、TestFlight 或 App Store 发布证据，也不包含签名材料、凭据、隧道地址、用户名或内部路径。

## 工具链

| 项目 | 实际值 |
| --- | --- |
| Flutter / Dart | Flutter 3.44.8（revision `058e0af2c2`）/ Dart 3.12.2 |
| Xcode | Xcode 16.1（build `16B40`） |
| CocoaPods | 1.15.2 |
| macOS | 15.0.1（24A348） |

## 构建命令和结果

Flutter wrapper 命令按计划执行，实际退出码为 `1`：

```text
flutter build ios --simulator --debug \
  --dart-define=APP_ENV=development \
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

该次 wrapper 构建在 Xcode Build Service 阶段报告 `Swift.CancellationError`，因此不标记为 Flutter wrapper 成功。

随后使用同一份 Flutter 生成配置，通过 XcodeBuildMCP 运行了等价的直接 Xcode 构建，退出码为 `0`，日志末尾为 `** BUILD SUCCEEDED **`：

```text
xcodebuild -workspace ios/Runner.xcworkspace -scheme Runner \
  -configuration Debug -sdk iphonesimulator \
  -destination id=040068EE-468B-4F6C-A6E1-9ABAF1EC0350 \
  -derivedDataPath /private/tmp/joysong-derived \
  CODE_SIGNING_ALLOWED=NO build
```

对应的 `DART_DEFINES` 为：

```text
APP_ENV=development
API_BASE_URL=http://127.0.0.1:8080
```

`127.0.0.1:8080` 只表示本机 SSH 隧道/联调配置，不是公开服务端点。直接构建日志位于本机临时文件 `/private/tmp/joysong-xcode.log`，未复制到仓库。

## Simulator、产物和启动

- Simulator：`iPhone 16 Pro` / iOS `18.1` / UDID `040068EE-468B-4F6C-A6E1-9ABAF1EC0350`。
- 安装和启动：XcodeBuildMCP `build_run_sim` 成功完成构建、安装和启动；Bundle ID 为 `com.joysong.app.flutterdev`。
- 产物：`/private/tmp/joysong-derived/Build/Products/Debug-iphonesimulator/Runner.app`，存在性检查通过，直接构建产物约 `130 MB`。该产物的 `CFBundleSupportedPlatforms` 为 `iPhoneSimulator`，仅限模拟器。
- 工作树中的 `joysong-flutter/build/ios/iphonesimulator/Runner.app` 约 `176 MB`，同样是本机生成物，不进入 Git。
- 首屏：XcodeBuildMCP UI snapshot 识别到 Home、Discover、Messages、Profile 四个 Tab 以及首页内容；截图已保存到临时路径 `/var/folders/9p/087wgm0n4vg948thbbx8x74r0000gn/T/screenshot_optimized_457b6cb2-cc1d-4d20-a172-43e0bcdf3fc6.jpg`，不进入 Git。
- 运行日志只保留本机 XcodeBuildMCP 日志引用；本次启动未观察到崩溃、`MissingPluginException`、文件选择器初始化错误或原生桥接异常。日志中有开发数据头像资源 HTTP `403` 图片加载错误和 Simulator 键盘 keyplane 警告；前者未阻止首页渲染，且未在本记录中暴露对象存储 URL。

## 测试和静态检查

| 检查 | 命令/结果 |
| --- | --- |
| CocoaPods | `LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8 LANGUAGE=en_US.UTF-8 RUBYOPT=-EUTF-8:UTF-8 pod install`，退出码 `0`；生成的 `Podfile.lock` 按计划排除 |
| 配置测试 | `flutter test test/core/config/app_environment_test.dart`，退出码 `0`，实际输出 `+5`（文件中 5 个 test 声明；计划表中的 6/6 阈值未达到） |
| Flutter analyze | 退出码 `1`；`test/features/auth/login_page_test.dart:13:18` 和 `:14:18` 均为 `LoginStrings` 没有未命名 const 构造函数 |
| Simulator 直接构建 | `xcodebuild` 退出码 `0`，见上方命令和日志 |

## 安全和提交边界

已对本轮证据范围执行敏感文件名和常见私钥、证书、token、密码、AccessKey 模式扫描，未发现命中。以下内容均未暂存：`build/**`、`Pods/**`、`Flutter/ephemeral/**`、`Generated*.xcconfig`、`flutter_export_environment*.sh`、`Flutter*.podspec`、`xcuserdata/**`、`Podfile.lock`、`.app`、`.xcresult` 以及任何签名文件。

本记录不把模拟器 `.app` 当作可发布产物；公开 HTTPS 域名和签名环境就绪后，必须按新的 UAT/Production `--dart-define` 重新构建并签名。
