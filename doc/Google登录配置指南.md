# 娇颜颂项目配置指南

> 本文档记录项目各模块的配置流程及踩坑经验，每个问题独立成节，方便查阅和避免重复踩坑。

---

## 一、Google 登录配置 + 错误排查

### 1.1 整体架构

```
Android 客户端                          服务端
┌───────────────────┐              ┌──────────────────────┐
│ GoogleSignInClient  │──ID Token──▶│ /api/auth/login-with-google │
│ requestIdToken()    │              │                      │
│ (Web客户端ID)       │              │ RestTemplate(走代理)   │
└───────────────────┘              │   │                  │
                                   │   ▼                  │
                                   │ Google tokeninfo API │
                                   │ 验证 aud 字段        │
                                   └──────────────────────┘
```

**关键点：** Android 端和服务端共用同一个 **Web 应用类型** 的 OAuth 客户端 ID。

> **⚠️ 技术决策：** 项目从 Credential Manager API 切换为传统 `GoogleSignInClient` API，原因是 Credential Manager 在小米（MIUI/HyperOS）等国产设备上存在兼容性问题（Google 账号选择弹窗无法正常显示，直接报 `No credentials available`）。传统 API 更成熟稳定，兼容所有设备。

### 1.2 Google Cloud Console 配置

#### 创建 OAuth 客户端（需要两个）

| 客户端 | 类型 | 用途 | 关键配置 |
|--------|------|------|----------|
| 客户端 1 | **Android** | 绑定包名和 SHA-1 | 包名 `com.joysong.app`，SHA-1 通过 `gradlew signingReport` 获取 |
| 客户端 2 | **Web 应用** | 登录验证（核心） | 授权来源和重定向 URI 填 `http://localhost:8080` |

> **⚠️ 重要：** `requestIdToken()` **必须**使用 Web 应用类型的客户端 ID，不能使用 Android 类型的，否则登录失败。

#### 获取 Debug SHA-1 指纹

```bash
cd joysong-app
.\gradlew.bat signingReport
```

> **注意：** 不要用系统 keytool 命令，可能因 keystore 格式不兼容而失败。

#### OAuth 同意屏幕

- 发布状态选"测试中"时，需将测试 Google 账号添加到"测试用户"列表；或直接改为"已发布"
- 若状态为"测试中"且账号不在测试列表中，会报 `No credentials available`

#### 启用 API

进入 **API 和服务 > 库**，搜索并启用 **Identity Toolkit API**。

#### 配置生效时间

Google Cloud Console 配置修改可能需要 **5 分钟到几小时** 才能完全生效。

### 1.3 Android 客户端配置

**local.properties：**
```properties
google.client-id=<Web应用类型的客户端ID>
```

**build.gradle.kts：**
```kotlin
val googleClientId = localProperties.getProperty("google.client-id", "")
defaultConfig {
    buildConfigField("String", "GOOGLE_CLIENT_ID", "\"$googleClientId\"")
}
```

**登录代码（GoogleSignInClient）：**
```kotlin
val googleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
    .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)  // 必须是 Web 类型客户端 ID
    .requestEmail()
    .build()
val googleSignInClient = GoogleSignIn.getClient(context, googleSignInOptions)
// 通过 ActivityResultLauncher 启动登录
googleSignInLauncher.launch(googleSignInClient.signInIntent)
```

**依赖（libs.versions.toml）：**
```toml
play-services-auth = { module = "com.google.android.gms:play-services-auth", version = "21.2.0" }
```

### 1.4 服务端配置

**application.yml：**
```yaml
google:
  client-id: <Web应用类型的客户端ID>   # 必须与 Android 端一致
  proxy-url: http://127.0.0.1:7890     # 访问 Google API 的代理地址
```

**代理配置（RestTemplateConfig.kt）：**

Spring Boot 的 RestTemplate **默认不走系统代理**，必须显式配置：

```kotlin
@Configuration
class RestTemplateConfig(
    @Value("\${google.proxy-url:}") private val proxyUrl: String
) {
    @Bean
    fun restTemplate(): RestTemplate {
        val factory = SimpleClientHttpRequestFactory()
        factory.setConnectTimeout(10_000)
        factory.setReadTimeout(10_000)
        if (proxyUrl.isNotBlank()) {
            val uri = java.net.URI(proxyUrl)
            val proxy = Proxy(
                if (uri.scheme == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP,
                InetSocketAddress(uri.host, uri.port)
            )
            factory.setProxy(proxy)
        }
        return RestTemplate(factory)
    }
}
```

| 代理工具 | 地址 |
|---------|------|
| Clash | `http://127.0.0.1:7890` |
| V2Ray | `http://127.0.0.1:10809` |
| SOCKS5 | `socks5://127.0.0.1:1080` |

### 1.5 常见错误排查

#### ❌ No credentials available（Android 客户端）

| 原因 | 解决方案 |
|------|--------|
| `local.properties` 中 client-id 是占位符 | 替换为真实 Web 客户端 ID |
| `requestIdToken()` 使用了 Android 类型客户端 ID | 改用 Web 应用类型客户端 ID |
| Gradle 未重新同步 | Android Studio 中执行 Sync Project with Gradle Files |
| 应用未重新安装到手机 | 重新 Run 应用（非热重载） |
| OAuth 同意屏幕为"测试中"且账号不在测试列表 | 添加测试用户或将应用改为"已发布" |
| Google Play Services 版本过旧 | 在 Play 商店更新 |
| 配置刚创建未生效 | 等待 5 分钟到几小时后重试 |
| 小米/MIUI 设备上 Credential Manager 不兼容 | 使用传统 `GoogleSignInClient` API（本项目已采用） |

#### ❌ Connection timed out（服务端调用 Google API）

| 原因 | 解决方案 |
|------|---------|
| RestTemplate 未配置代理 | 在 `application.yml` 中配置 `google.proxy-url` |
| 代理服务未运行 | 确保代理软件（Clash/V2Ray 等）已启动 |
| 代理端口不正确 | 确认代理工具的实际端口 |

#### ❌ invalid_token: Invalid Value（服务端）

| 原因 | 解决方案 |
|------|---------|
| 通过 Apifox 手动测试传了假 Token | 此接口只能通过 Android 端真实 Google 登录获取 Token |
| Token 已过期（有效期约 1 小时） | 重新从 Android 端获取新 Token |
| audience 不匹配 | 确保两端 client-id 完全一致 |

---

## 二、真机调试网络配置

### 2.1 服务器地址配置

`build.gradle.kts` 中服务器地址**仅从 `local.properties` 读取**：

```kotlin
val serverUrl = localProperties.getProperty("server.url", "http://10.0.2.2:8080/")
```

| 场景 | 配置方式 |
|------|---------|
| **真机调试** | `server.url=http://<电脑局域网IP>:8080/` |
| **模拟器调试** | 无需配置，默认 `10.0.2.2:8080`（模拟器访问宿主机） |

### 2.2 常见网络问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 连接 `192.168.41.1` 超时 | Hyper-V 虚拟网卡 IP | 手动在 `local.properties` 指定正确 IP |
| IP 变化后连接失败 | 切换 WiFi 后电脑 IP 变化 | 重新 `ipconfig` 查看 IP 并更新 `local.properties` |
| 修改 IP 后仍连旧地址 | APK 未更新或 BuildConfig 缓存 | `clean` + 卸载旧 APK + 重新安装 |

### 2.3 排查步骤

```bash
# 1. 查看电脑当前 IP
ipconfig | findstr /i "IPv4"

# 2. 更新 local.properties（替换为实际 IP）
# server.url=http://192.168.2.54:8080/

# 3. 清理并重新编译安装
cd joysong-app
.\gradlew.bat clean installDebug
```

**验证 BuildConfig：** 编译后查看 `joysong-app\app\build\generated\source\buildConfig\debug\com\joysong\app\BuildConfig.java`，确认 `SERVER_URL` 值与 `local.properties` 一致。

### 2.4 快速检查清单

- [ ] 手机和电脑在同一 WiFi
- [ ] `local.properties` 中 `server.url` 为电脑当前局域网 IP
- [ ] 后端服务器已启动（`.\gradlew.bat bootRun`）
- [ ] 电脑防火墙允许 8080 端口
- [ ] APK 是最新编译的（`clean` 后重新编译）

---

## 三、阿里云短信验证码配置

### 3.1 整体架构

```
Android 客户端                        服务端
┌─────────────────┐                ┌──────────────────────┐
│ 输入手机号        │──POST──▶│ /api/auth/send-code  │
│                 │                │                      │
│ 输入验证码        │                │ VerificationCodeService │
│                 │──POST──▶│   │                      │
│ /api/auth/login-with-code│        │   ▼                  │
└─────────────────┘                │ AliyunSmsService     │
                                   │   │                  │
                                   │   ▼                  │
                                   │ 阿里云短信 API       │
                                   │ dysmsapi.aliyuncs.com│
                                   └──────────────────────┘
```

**关键点：** 验证码在后端生成并存储，通过阿里云短信服务发送到用户手机，客户端不接触验证码明文。

### 3.2 阿里云控制台配置

#### 开通短信服务

1. 登录 [阿里云短信控制台](https://dysms.console.aliyun.com)
2. 开通短信服务（需完成实名认证）

#### 申请短信签名

1. 进入 **国内消息 → 签名管理 → 添加签名**
2. 签名来源选择“应用”，签名名称填写实际使用的名称（如“娇颜颂”）
3. 上传应用截图等证明材料
4. 等待审核通过（通常几分钟到几小时）

#### 申请短信模板

1. 进入 **国内消息 → 模板管理 → 添加模板**
2. 模板类型选择“验证码”
3. 模板内容示例：`您的验证码为${code}，请勿泄露给他人。`
4. 等待审核通过，记录 **模板编码**（如 `SMS_337555221`）

#### 获取 AccessKey

复用已有的阿里云 AccessKey（与 OSS 共用即可），或创建专用 AccessKey。

### 3.3 服务端配置

**application.yml：**
```yaml
aliyun:
  sms:
    enabled: true                    # false=仅日志打印(开发模式)，true=真实短信发送
    access-key-id: <你的AccessKeyId>
    access-key-secret: <你的AccessKeySecret>
    sign-name: '娇颜颂'               # 必须与阿里云控制台审核通过的签名完全一致
    template-code: 'SMS_XXXXXXXX'     # 必须与阿里云控制台审核通过的模板编码完全一致
```

| 配置项 | 说明 |
|---------|------|
| `enabled` | `false` 时验证码仅打印到后端日志，适合开发调试；`true` 时通过短信发送 |
| `sign-name` | 短信签名，必须与阿里云控制台中审核通过的签名**完全一致** |
| `template-code` | 短信模板编码，必须与阿里云控制台中审核通过的模板编码**完全一致** |
| `access-key-id/secret` | 阿里云 AccessKey，需有短信发送权限 |

### 3.4 后端关键代码

**AliyunSmsService.kt** — 封装阿里云短信 API 调用：
```kotlin
val request = SendSmsRequest()
    .setPhoneNumbers(phone)
    .setSignName(signName)
    .setTemplateCode(templateCode)
    .setTemplateParam("{\"code\":\"$code\"}")
val response = client!!.sendSms(request)
```

**VerificationCodeService.kt** — 生成验证码后调用短信服务：
```kotlin
fun generate(phone: String): String {
    val code = (100000..999999).random().toString()
    store[phone] = CodeEntry(code, Instant.now().plusSeconds(300)) // 5分钟有效
    aliyunSmsService.sendVerificationCode(phone, code) // 发送短信
    return code
}
```

**AuthController.kt** — 短信启用时不在响应中返回验证码（安全）：
```kotlin
val responseData = if (aliyunSmsService.isSmsEnabled()) {
    mapOf("message" to "验证码已发送")           // 生产环境：不返回验证码
} else {
    mapOf("message" to "验证码已发送", "code" to code) // 开发环境：返回验证码便于调试
}
```

**依赖（build.gradle.kts）：**
```kotlin
implementation("com.aliyun:dysmsapi20170525:3.0.0")
implementation("com.aliyun:tea-openapi:0.3.2")
```

### 3.5 常见错误排查

#### ❌ isv.SMS_SIGNATURE_ILLEGAL（该账号下找不到对应签名）

| 原因 | 解决方案 |
|------|----------|
| 签名未创建 | 在阿里云控制台 → 签名管理 → 添加签名 |
| 签名审核中 | 等待审核通过后再使用 |
| `sign-name` 与控制台不一致 | 确保 `application.yml` 中的签名与阿里云控制台**完全一致** |

#### ❌ isv.SMS_TEMPLATE_ILLEGAL（模板不合法）

| 原因 | 解决方案 |
|------|----------|
| 模板未创建 | 在阿里云控制台 → 模板管理 → 添加模板 |
| 模板审核中 | 等待审核通过 |
| `template-code` 与控制台不一致 | 确保模板编码与阿里云控制台**完全一致** |

#### ❌ isv.MOBILE_NUMBER_ILLEGAL（手机号码格式错误）

| 原因 | 解决方案 |
|------|----------|
| 手机号格式不正确 | 确保传入纯手机号，如 `13800138000`，不带区号前缀 |

#### ❌ 短信发送成功但用户未收到

| 原因 | 解决方案 |
|------|----------|
| 手机拦截短信 | 检查手机短信拦截/垃圾短信列表 |
| 同一手机号发送频率过高 | 阿里云默认限制同一号码同模板 1 条/分钟，5 条/小时 |
| 欠费停机 | 检查阿里云账户余额 |

### 3.6 开发模式说明

当 `aliyun.sms.enabled: false` 时：
- 验证码仅打印到后端日志：`[SMS-DEV] 手机号 xxx 验证码：123456`
- API 响应中会包含 `code` 字段，方便调试
- 适合本地开发和无阿里云短信配置时的测试

当 `aliyun.sms.enabled: true` 时：
- 验证码通过真实短信发送到用户手机
- API 响应中**不包含** `code` 字段（安全）
- 后端日志仍记录发送结果

---

## 四、当前项目配置值

| 配置项 | 值 |
|--------|-----|
| Android 包名 | `com.joysong.app` |
| Debug SHA-1 | `AC:F0:C0:18:C2:66:DF:87:72:20:8C:56:EE:BE:97:63:3E:36:E9:3F` |
| Web 客户端 ID | `1074438635273-5pqevlkgfmmb910dkjm7vvhikoui5oh9.apps.googleusercontent.com` |
| 代理地址 | `http://127.0.0.1:7890`（Clash） |
| Google Cloud 项目 | `citric-pager-502807-s3` |
| 服务器地址 | `http://192.168.2.54:8080/`（从 local.properties 读取） |
| 阿里云短信签名 | `joysong` |
| 阿里云短信模板编码 | `SMS_337555221` |
| 阿里云短信状态 | `enabled: true` |
