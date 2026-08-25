# Joysong Flutter

Flutter 客户端工程，用于逐步替代 `joysong-app`，同时发布 Android 与 iOS。

## 对接边界

- 服务端：复用 `joysong-server` 的 REST API，唯一迁移契约见 [`../docs/FLUTTER_API_CONTRACT.md`](../docs/FLUTTER_API_CONTRACT.md)。
- 管理系统：继续使用 `joysong-admin`；Flutter 不直接连接管理系统数据库。
- 身份与权限：以服务端签发的令牌、角色与权限为唯一来源。

> `joysong-server/openapi.yaml` 和 `openapi.json` 是缺少 refresh、身份认证、专业管理与 Agent 接口的旧快照。禁止用它们生成 Flutter 客户端；在服务端提供自动生成并经 CI 校验的新规范前，以迁移契约和实际 DTO 为准。

## 当前骨架

- Android 与 iOS 共用 `com.joysong.app` 应用标识，Android 最低版本为 API 26，iOS 最低版本为 13.0。
- `lib/core` 放置环境、路由、主题和网络边界；`lib/features` 按业务功能组织页面。
- App Shell 已包含首页、发现、医美 AI、我的四个一级入口。
- `AppShell` 是唯一的账号作用域组合根，负责创建和销毁当前登录账号使用的 Agent controller。
- 网络层仅使用 Dart SDK，统一检查 HTTP 状态和 `{code,message,data}`；写操作不自动重试。
- Agent 聊天仅使用同步 REST，SSE 配置固定关闭；发送请求的 `idempotencyKey` 可选，不传的旧客户端仍然有效。
- Agent 聊天界面只加载并显示最新 20 条消息，不提供永久历史分页或“加载更早消息”入口。
- 已建立首批认证模型、真实接口映射、登录/注册/刷新/退出仓储，以及密码/验证码登录组件。
- 登录状态机已接到应用外层路由；冷启动恢复会话、退出和安全过期均可回到登录页。
- access/refresh token 作为一个加密会话原子写入 Android Keystore / iOS Keychain；认证仓储已实现 single-flight 刷新，GET 请求刷新后最多重放一次，写操作不自动重试。

当前代码是 Sprint 0 工程底座并包含 Sprint 1 的认证基础链路，不代表完整业务迁移。后续范围、验收门禁与发布风险见 [`../docs/FLUTTER_DUAL_PLATFORM_PLAN.md`](../docs/FLUTTER_DUAL_PLATFORM_PLAN.md)。

## 本地运行

```shell
flutter pub get
flutter run --dart-define=APP_ENV=development
```

开发环境默认地址：Android 模拟器为 `http://10.0.2.2:8080`，iOS 模拟器为 `http://127.0.0.1:8080`。

Android 真机推荐通过 USB 调试反向映射本机服务。连接设备后执行一次：

```shell
adb reverse tcp:8080 tcp:8080
flutter run --dart-define=APP_ENV=development --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

这样无需使用电脑当前的局域网 IP，切换 Wi-Fi 后仍可继续调试。设备重启、重新连接 USB 或撤销映射后，需要重新执行 `adb reverse`。其他环境仍可通过 `API_BASE_URL` 覆盖。

当前海外 USD card 流程固定使用 Stripe Hosted Checkout；Stripe 密钥和 webhook 配置只放在服务端环境变量中，不放入 Flutter 客户端。

生产构建必须同时指定 `APP_ENV=production` 和 HTTPS 的 `API_BASE_URL`。

Android 调试包：

```shell
flutter build apk --debug
```

## 质量检查

```shell
dart format --output=none --set-exit-if-changed lib test
flutter analyze
flutter test
```

Windows 开发机可以完成 Android 构建；iOS 构建、签名和真机验证必须在安装了 Xcode 的 macOS 环境执行。
