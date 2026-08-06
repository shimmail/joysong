# Flutter Android / iOS 发布检查清单

## 自动化门禁

- `dart format --output=none --set-exit-if-changed lib test`
- `flutter analyze`
- `flutter test`
- Android：`flutter build appbundle --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://<production-host>`
- iOS（macOS/Xcode）：`flutter build ios --release --no-codesign --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://<production-host>`

生产构建必须使用 HTTPS。Android Release 没有 `android/key.properties` 时只能生成未签名产物，不允许上传商店；严禁回退到 debug 签名。

## 双端真机回归

- 冷启动、登录恢复、令牌过期和退出。
- 中文长文本、系统字体 200%、深色模式、横竖屏与键盘遮挡。
- 无网、超时、HTTP 401/403、业务码失败及恢复后的重试入口。
- 图片列表内存、上传中断、后台恢复和低存储空间。
- TalkBack/VoiceOver 标签、焦点顺序和至少 44×44 的交互热区。
- Android 覆盖安装与独立 debug 包；iOS Debug 使用 `com.joysong.app.flutterdev`，Release 使用 `com.joysong.app`。

## 隐私与安全

- 日志和崩溃报告不得包含令牌、密码、短信验证码、证件号码、认证材料或完整私聊内容。
- 身份材料只走 `/identity/files` 私有上传，不进入公共 `/upload`。
- 专业管理每次进入都刷新 `/management/context`，客户端不从角色名称推导权限。
- AI SSE 断流不自动重放 POST；订单、支付、退款和内容写操作同样不由网络层自动重试。
- 核对 iOS `PrivacyInfo.xcprivacy`、Android Data safety 与商店隐私声明；接入第三方 SDK 后必须重新填写数据收集清单。

## 当前发布阻塞

- 真实微信/支付宝预下单、回调验签、幂等和退款查询契约尚未完成，生产入口必须保持隐藏或受控测试状态。
- iOS Archive、签名、TestFlight 和 APNs 只能在具备证书的 macOS/Xcode 环境验收。
- Google 登录所需 Android SHA、iOS Client ID 与 URL Scheme 需由账号持有人提供。
- 灰度发布前配置崩溃率、登录失败率、下单失败率、SSE 中断率告警和上一稳定版本回滚方案。
