# Flutter 前端迁移到 Mac 并构建 iOS

核查日期：2026-09-06。目标是使用 macOS 构建 iPhone/iPad 应用；工程为 `joysong-flutter`。本指南不涉及 macOS 桌面版或旧 `joysong-app` 的移植。

当前已在 Windows 完成源码与配置核查，尚未在 Mac 上执行 Xcode、CocoaPods、模拟器或签名构建。下面的成功标准需要在目标 Mac 上实际验证。

## 1. 确认项目基线

| 项目 | 当前实际配置 |
| --- | --- |
| Flutter | 本机缓存和生产 CI 均为 `3.44.8`，优先保持一致 |
| Dart | 本机 SDK 为 `3.12.2`；锁文件要求 `>=3.12.0 <4.0.0` |
| Flutter 锁文件下限 | `>=3.44.0`，不能只按 pubspec.yaml 的 Dart 3.3 下限安装旧 SDK |
| iOS 最低版本 | Podfile、Xcode 工程和 AppFrameworkInfo.plist 配置为 `13.0`；仍需实际安装 Pods 验证插件要求 |
| Xcode scheme | 只有 `Runner`，不传 Android 的 `--flavor development/uat/prod` |
| Debug Bundle ID | `com.joysong.app.flutterdev` |
| Profile Bundle ID | `com.joysong.app.flutterprofile` |
| Release Bundle ID | `com.joysong.app` |
| 默认 iOS API 地址 | `http://127.0.0.1:8080` |
| iOS 原生依赖 | 已有 CocoaPods Podfile，目前未跟踪 Podfile.lock |

项目 README 的“共用 com.joysong.app”只适用于正式包，调试包以 Xcode 配置为准。

## 2. 将完整源码放到 Mac

当前 Windows 工作区存在未提交修改和未跟踪文件，直接 clone 远程仓库不会包含这些内容。

推荐先审查并将需要迁移的修改提交到自己的分支，再推送；只提交实际需要的源码和文档，避免一条 `git add .` 收入无关文件。随后在 Mac 终端执行，替换两个占位值：

```bash
mkdir -p "$HOME/developer"
cd "$HOME/developer"
git clone --branch "实际迁移分支" "实际仓库地址" joysong
cd joysong
git status --short
git log -1 --oneline
```

若暂时不提交，可通过移动硬盘等方式复制 `joysong-flutter` 的当前源码到 `~/developer/joysong/joysong-flutter`，保留 `lib`、`test`、`ios`、`android`、`pubspec.yaml`、`pubspec.lock`、配置文件及实际使用的资源。开启 Finder 的隐藏文件显示（Command + Shift + .），避免漏掉配置文件。

不要搬运 Windows Flutter SDK、`.dart_tool`、`build`、`.flutter-plugins*`、`ios/Pods`、`ios/.symlinks`、`ios/Flutter/ephemeral`、`ios/Flutter/Generated.xcconfig`、`ios/Flutter/flutter_export_environment.sh` 或 `android/local.properties`。这些文件包含本机路径或平台产物，应在 Mac 重建。签名私钥和后端秘密配置不属于源码包。

## 3. 在 Mac 安装 Xcode

1. 在 Mac 的 App Store 安装 Xcode，首次打开并完成组件安装；macOS 必须满足所选 Xcode 的要求。
2. 打开 Terminal，选择完整 Xcode 工具链并完成初始化：

```bash
sudo xcode-select -s /Applications/Xcode.app/Contents/Developer
sudo xcodebuild -runFirstLaunch
sudo xcodebuild -license
xcodebuild -downloadPlatform iOS
xcodebuild -version
xcode-select -p
```

阅读并接受 Xcode 许可。最后一条应指向 `/Applications/Xcode.app/Contents/Developer`，只安装 Command Line Tools 不足以构建 iOS。如果 Xcode 使用其他安装路径，替换命令中的路径。

这些步骤依据 [Flutter iOS 环境指南](https://docs.flutter.dev/platform-integration/ios/setup)。

## 4. 安装相同版本 Flutter 和 CocoaPods

先执行 `uname -m`：Apple Silicon 通常为 `arm64`，Intel 为 `x86_64`。从 [Flutter SDK archive](https://docs.flutter.dev/install/archive) 下载 macOS 对应架构的 **3.44.8**，解压到 `~/development/flutter`。不要把 Windows SDK 复制到 Mac，也不要在首次迁移时直接升级依赖。

在 `~/.zprofile` 中添加下面一行；已存在则无需重复添加：

```bash
export PATH="$HOME/development/flutter/bin:$PATH"
```

重新打开终端，执行：

```bash
flutter --version
dart --version
flutter precache --ios
```

安装 [Homebrew](https://brew.sh/)：使用官网提供的安装方式，并执行安装结束时显示的 Next steps，将 brew 加入 PATH。已有 Homebrew 可直接执行：

```bash
brew install cocoapods
pod --version
```

安装命令见 [Homebrew CocoaPods formula](https://formulae.brew.sh/formula/cocoapods)。

本工程已使用 Podfile，首次迁移保持 CocoaPods 路径。Flutter 3.44.8 默认开启 Swift Package Manager，可在这台 Mac 上关闭后再继续：

```bash
flutter config --no-enable-swift-package-manager
flutter doctor -v
```

这是当前用户的 Flutter 全局设置，会影响其他 Flutter 项目；需要恢复时使用 `flutter config --enable-swift-package-manager`。`flutter doctor -v` 中 Flutter、Xcode、CocoaPods 应正常；只构建 iOS 时，Android 工具链缺失不阻塞本步骤。

## 5. 在 Mac 重建依赖和本机配置

以下命令均在 Flutter 工程目录执行：

```bash
cd "$HOME/developer/joysong/joysong-flutter"
flutter clean
flutter pub get
cd ios
pod install
cd ..
```

不要使用 `flutter pub upgrade` 或常规性删除 `pubspec.lock`。当前仓库未跟踪 Podfile.lock，首次成功安装会生成它；审查后纳入版本控制，使后续 Mac 构建复用原生依赖版本。以后使用 `pod install` 保留锁定版本。

检查 `ios/Flutter/Generated.xcconfig` 中的 `FLUTTER_ROOT` 和 `FLUTTER_APPLICATION_PATH` 已是 Mac 路径。它必须由 Mac 的 Flutter 生成，不应保留 `D:\...`。不要用 `flutter create .` 覆盖已有 iOS 工程，其中包含自定义文件选择器。

如果 `pod install` 明确报告 spec 仓库过旧，再运行一次 `pod install --repo-update`。遇到 deployment target 要求更高时，按报错插件要求同步调整 Podfile、Xcode Project/Target 的各构建配置及 AppFrameworkInfo.plist，不能仅强行降低 Pods 的目标版本。

## 6. 选择后端连接方式

| 场景 | API_BASE_URL |
| --- | --- |
| iOS 模拟器，后端或 SSH 隧道运行在这台 Mac | `http://127.0.0.1:8080` |
| 模拟器或真机连接当前 UAT 服务 | `https://121.41.230.98` |
| 真机连接开发电脑 | 电脑可达地址；额外核查监听地址、防火墙、iOS 本地网络权限和 ATS |

传入服务器根地址即可，不追加 `/api`，Dart 网络层会解析到 `/api/`。UAT/production 强制 HTTPS。

**iPhone 上的 127.0.0.1 指向手机自身。USB 连接不会像 Android 的 adb reverse 一样把 Mac 的 8080 自动映射到 iPhone。** 首次建议先用模拟器；真机优先连接有效的 HTTPS 测试服务。

如果沿用当前 ECS SSH 联调，参考 [SSH 隧道指南](deployment/SSH_TUNNEL.md)。Windows 上的 SSH 别名不会随 Git 自动迁移。把已授权专用密钥和已核验的 known_hosts 文件通过可信方式放入 Mac 的 `~/.ssh/`，设置目录权限 `700`、私钥权限 `600`，在 Mac 重建连接配置。按现有指南中的专用文件名，可在终端保持运行：

```bash
ssh -N -T \
  -i "$HOME/.ssh/id_ed25519_joysong_tunnel" \
  -o IdentitiesOnly=yes \
  -o StrictHostKeyChecking=yes \
  -o UserKnownHostsFile="$HOME/.ssh/known_hosts_joysong" \
  -o ExitOnForwardFailure=yes \
  -L 127.0.0.1:8080:127.0.0.1:8080 \
  joysong-tunnel@121.41.230.98
```

上述服务器信息来自仓库指南，本次未重新验证远端状态；Mac 出口 IP 改变时需按该指南核对安全组。不要关闭主机公钥校验。在另一个 Mac 终端验证：

```bash
curl --noproxy '*' --max-time 10 -fsS http://127.0.0.1:8080/actuator/health
```

返回 `{"status":"UP"}` 后，模拟器才具备已验证的后端连接路径。只做编译不需要运行后端；本流程不运行数据库迁移或服务端数据库测试。

## 7. 完成最小检查和模拟器构建

先执行环境配置相关测试与静态检查：

```bash
flutter analyze
flutter test test/core/config/app_environment_test.dart
```

如失败，记录并处理对应问题，只重跑失败用例或相关测试类；不要反复运行已通过命令。本次操作指南本身不构成这些测试已通过的证据。

启动模拟器：

```bash
open -a Simulator
flutter devices
```

如果没有设备，在 Xcode 的 Window → Devices and Simulators → Simulators 添加已安装系统对应的 iPhone，启动后再读取设备 ID。执行无真机签名要求的模拟器编译：

```bash
flutter build ios --simulator --debug \
  --dart-define=APP_ENV=development \
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

成功标准：退出码为 0，生成 `build/ios/iphonesimulator/Runner.app`。然后替换实际设备 ID 运行：

```bash
flutter run -d "实际模拟器ID" \
  --dart-define=APP_ENV=development \
  --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

CLI 不传 `--flavor`。修改 APP_ENV 或 API_BASE_URL 后，停止应用并重新运行，不能只热重载。

## 8. 配置真机签名并运行

```bash
open ios/Runner.xcworkspace
```

1. 打开的是 `.xcworkspace`，不是 `.xcodeproj`，以加载 Pods。
2. Xcode → Settings → Accounts 添加 Apple 账号。
3. 左侧 Runner → TARGETS → Runner → Signing & Capabilities，启用 Automatically manage signing，并选择自己的 Team。
4. 分别核对 Debug、Profile、Release 的 Bundle ID 和 Team。Debug 为 `com.joysong.app.flutterdev`，Release 为 `com.joysong.app`；APP_ENV 不会改变 iOS Bundle ID。若现有 ID 不属于自己的团队，调试时使用团队可签名的唯一 ID。当前暂不使用 Google 登录，无需配置 Google OAuth。
5. 用 USB 连接 iPhone，解锁并选择“信任此电脑”。在需要的 iOS 版本上开启 设置 → 隐私与安全性 → 开发者模式，并按提示重启。
6. 在 Xcode 设备窗口等待配对和支持组件准备完毕，执行 `flutter devices` 取得设备 ID。
7. 使用当前 UAT 公网 IP HTTPS 地址运行：

```bash
flutter run -d "实际iPhone设备ID" \
  --dart-define=APP_ENV=uat \
  --dart-define=API_BASE_URL=https://121.41.230.98
```

当前 UAT 模式使用密码登录，适合先验证基础链路。个人账号可用于受限制的真机开发测试；TestFlight/App Store 分发需要对应开发者计划资格。设备设置依据 [Flutter 真机配置指南](https://docs.flutter.dev/platform-integration/ios/setup)。

仅验证 iOS Release 编译且暂不签名时，在 Mac 上执行：

```bash
flutter build ios --release --no-codesign \
  --dart-define=APP_ENV=uat \
  --dart-define=API_BASE_URL=https://121.41.230.98
```

该命令生成的是 UAT 配置，不是生产版本。需要安装到真机或上传 TestFlight 时，仍需在 Xcode 中配置有效签名并按下节生成 IPA。

## 9. 验证平台功能，再构建 IPA

至少验收：启动、中英切换、密码登录、重启后恢复会话、退出、首页接口、图片选择、PDF/文件选择、上传、分享及外部链接。原生 Keychain 和文件选择器需要真实设备验证，Dart 单元测试不能代替它们。

当前阶段的功能范围与平台验收项如下：

- **Google 登录**：用户确认当前暂不使用，暂不配置 OAuth client 或回调 URL scheme，不传入 Google 相关 dart-define，也不纳入本次迁移验收。当前通过密码登录验证认证链路；以后启用 Google 登录时再补齐配置及回跳测试。
- **应用深链**：Dart 识别 `joysong` 和 UAT 的 `joysong-uat`，但 Info.plist 未注册这些 URL scheme。涉及浏览器回跳时要补齐原生注册并验证冷启动和运行中回跳。
- **App 图标与名称**：正式图标已开发完成，已配置 Assets.xcassets/AppIcon 和中英文名称资源，默认中文「娇颜颂」、英文 `JoySong`。Mac 构建后核查图标预览及真机桌面显示即可，资源维护方式见 [App 名称与图标](FLUTTER_APP_BRANDING.md)。
- **UIScene 生命周期**：当前 AppDelegate 在启动回调中注册插件、读取 window 并建立文件选择通道。新 Flutter 使用 UIScene，但自定义 AppDelegate 的迁移需要人工处理；先记录 Mac 构建提示。如果进行迁移，必须同时迁移 engine 回调、通道注册和选择器展示窗口，不能只添加 Scene Manifest。迁移后重测文件选择，参见 [Flutter UIScene 迁移说明](https://docs.flutter.dev/release/breaking-changes/uiscenedelegate)。这属于待验证风险，当前没有实际构建失败证据。
- **本地网络**：现有 plist 包含 `NSAllowsLocalNetworking`，但没有 `NSLocalNetworkUsageDescription`。若采用真机访问局域网地址，需要按实际系统、URL 和网络权限报错补齐说明及精确 ATS 配置；不要默认所有 HTTP 地址都被放行。
- **相机**：目前代码只从图库选择，已有双语相册权限说明；将来启用相机时再增加相机权限说明。

正式签名和当前范围内的业务验证完成后，在 App Store Connect 建立与 Release Bundle ID 匹配的应用记录，再构建：

```bash
flutter build ipa --release \
  --build-name=0.1.0 \
  --build-number=1 \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://实际生产域名
```

当前构建无需 Google 登录参数。每次上传新构建必须使用未使用过的 build number。UAT 的 Release 包同样使用 `com.joysong.app`，尚不能与生产 Release 包通过不同 Bundle ID 并存。

预期产物位于 `build/ios/archive/`（xcarchive）和 `build/ios/ipa/`（ipa）。仅 `flutter build ios --release --no-codesign` 通过只证明无签名编译，不产生可直接安装/分发的已签名 IPA。分发操作参考 [Flutter iOS 发布指南](https://docs.flutter.dev/deployment/ios)，上传 TestFlight/App Store 属于后续发布步骤。

## 10. 出错时保留最小诊断信息

记录 Mac 的 `sw_vers`、`uname -m`、`flutter --version`、`flutter doctor -v`、`xcodebuild -version`、`pod --version`，以及实际运行命令和第一段错误。构建失败时只给该命令加 `-v` 定位，不要循环清缓存或升级全部依赖。

| 现象 | 优先检查 |
| --- | --- |
| 找不到 development scheme | 删除 Android 的 `--flavor` 参数 |
| Generated.xcconfig 包含 D 盘路径 | 在 Mac 重新 clean、pub get、pod install |
| Flutter/插件 module not found | CocoaPods 安装是否成功，是否打开 xcworkspace |
| No profiles / requires a development team | 当前构建配置的 Bundle ID、Team、设备注册和签名权限 |
| SDK 约束不满足 | 是否用了 Flutter 3.44.8，锁文件是否被改动 |
| 模拟器启动但接口连接失败 | Mac 的后端/SSH 隧道及健康检查，与编译失败分开定位 |
| 真机 localhost 连接失败 | API 指向了手机本机；改用真机可达的服务地址 |
| 文件选择器无响应 | 保留 AppDelegate 自定义通道，核查 Flutter 自动迁移后的生命周期与通道注册 |

成功后记录源码 commit（或复制快照）、SDK/Xcode/Pod 版本、设备系统、构建命令和产物路径。不要清理仍用于诊断的日志；临时诊断文件在问题解决后及时移除。
