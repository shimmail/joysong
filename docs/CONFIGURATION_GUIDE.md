# JoySong 项目配置指南

本指南覆盖当前项目的服务端、翻译、存储、短信、登录、跨域、管理后台、Android 与 Nginx 配置。机密值只通过部署平台的环境变量或受权限控制的密钥管理服务注入，**不得提交 `.env`、AccessKey、JWT 密钥或百炼 API Key**。

## 1. 配置边界

| 模块 | 配置位置 | 用途 |
|---|---|---|
| 服务端 | `joysong-server/src/main/resources/application.yml` + 环境变量 | API、数据库、认证、第三方服务 |
| 本地服务端 | `application-dev.yml` | 仅本地调试，不能用于生产 |
| 环境变量样例 | `joysong-server/.env.example` | 复制后填写实际机密值 |
| 管理后台 | `joysong-admin/vite.config.ts` | 本地开发代理 |
| Android | `joysong-app/local.properties` | 构建时写入 API 地址与 Google Client ID |
| Nginx | `joysong-server/deploy/nginx/joysong-api.conf` | HTTPS、反向代理、图片静态服务 |

## 2. 傻瓜版：从零到可用（推荐顺序）

按下面顺序操作即可，所有 `=号右侧` 都填在服务器的环境变量或密钥管理页面，不要填进 Git 仓库。

### 第一步：准备数据库和服务端密钥

1. 创建 MySQL 数据库账号，并让它能访问 `joysong` 数据库；当前默认连接为本机 `root@localhost:3306`。
2. 在服务器的环境变量页面新增：

   ```dotenv
   DB_PASSWORD=你的数据库密码
   JWT_SECRET=一段至少32字节的随机字符串
   ADMIN_PHONE=你的管理员手机号
   ADMIN_PASSWORD=首次管理员强密码
   SERVER_BASE_URL=https://你的API域名
   UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
   CORS_ALLOWED_ORIGINS=https://你的后台域名
   ```

3. 首次启动服务端。确认能访问 `https://你的API域名/actuator/health` 后，删除 `ADMIN_PASSWORD`；管理员账号已经创建，不再需要该变量。

### 第二步：开通 Qwen 翻译

1. 打开 [阿里云百炼控制台](https://bailian.console.aliyun.com/)，开通模型服务并进入实际部署使用的业务空间。
2. 在该业务空间创建 API Key；不同地域/业务空间的 Key 和 API 地址可能不同。
3. 将控制台展示的 OpenAI 兼容 Base URL 复制出来，并新增：

   ```dotenv
   TRANSLATION_PROVIDER=qwen
   QWEN_API_KEY=刚创建的百炼APIKey
   QWEN_BASE_URL=从百炼控制台复制的兼容接口地址
   QWEN_MODEL=qwen3.7-flash
   ```

4. 可选：若希望 Qwen 故障时自动使用旧的模型中转，再设置 `TRANSLATION_FALLBACK_PROVIDER=openai` 及第五步的 OpenAI 变量；若不需要回退，设为 `none`。
5. 重启服务端，在 App 中翻译一条评论验证。百炼的 API Key 和兼容接口配置见[官方文档](https://help.aliyun.com/zh/model-studio/what-is-model-studio)。

### 第三步：配置图片存储（需要上传图片时）

1. 在 [OSS 控制台](https://oss.console.aliyun.com/) 创建 Bucket，选择接近服务器的地域。
2. 在 [RAM 控制台](https://ram.console.aliyun.com/) 创建专用 RAM 用户，为该用户只授予这个 Bucket 的读写权限；不要使用主账号 AccessKey。权限方案参考 [OSS 权限控制说明](https://help.aliyun.com/zh/oss/user-guide/permissions-and-access-control-overview)。
3. 创建该 RAM 用户的 AccessKey，并新增：

   ```dotenv
   OSS_ACCESS_KEY_ID=RAM用户AccessKeyID
   OSS_ACCESS_KEY_SECRET=RAM用户AccessKeySecret
   ```

4. 确认 `UPLOAD_LOCAL_DIR` 目录 Java 可写、Nginx 可读；图片地址应能通过 `https://你的API域名/images/` 访问。

### 第四步：配置短信（需要真实短信验证码时）

1. 在 [阿里云短信服务控制台](https://dysms.console.aliyun.com/) 开通服务，提交并等待短信签名、模板审核通过。
2. 创建具备短信发送最小权限的 RAM AccessKey，新增：

   ```dotenv
   SMS_ACCESS_KEY_ID=短信RAM用户AccessKeyID
   SMS_ACCESS_KEY_SECRET=短信RAM用户AccessKeySecret
   ```

3. 在**生产配置**中把 `aliyun.sms.enabled` 改为 `true`，并把 `sign-name`、`template-code` 换成审核通过的值。
4. 用真实手机号请求验证码；未开通或变量为空时保持关闭，不要误用于生产。

### 第五步：配置 AI Agent / OpenAI 回退（可选）

1. 从所使用的模型服务商或中转站获取 API Key、兼容 API 地址和确切模型 ID。
2. 新增：

   ```dotenv
   OPENAI_API_KEY=模型服务密钥
   OPENAI_BASE_URL=https://www.fastaitoken.com/v1
   AI_AGENT_MODEL=你的Agent模型ID
   OPENAI_PROXY_URL=http://proxy.example.internal:8080
   TRANSLATION_FALLBACK_PROVIDER=openai
   TRANSLATION_MODEL=你的翻译兜底模型ID
   ```

3. 未使用 AI Agent 或翻译回退时，可以不配置；Qwen 翻译不依赖这组变量。

### 第六步：配置 Google 登录（可选）

1. 打开 [Google Cloud Console](https://console.cloud.google.com/)，创建或选择项目，完成 OAuth 同意屏幕配置。
2. 在“凭据”中创建 **Android OAuth 客户端**：包名填 `com.joysong.app`，填写 debug 与 release 签名证书的 SHA-1。
3. 再创建 **Web OAuth 客户端**，复制其 Client ID。后端验证 ID Token 与 Android 的 `requestIdToken` 都使用这个 Web Client ID；Google 的完整 Android 配置步骤见[官方指南](https://codelabs.developers.google.com/sign-in-with-google-android)。
4. 在服务器设置 `GOOGLE_CLIENT_ID=Web客户端ID.apps.googleusercontent.com`；在 Android `local.properties` 设置相同值：

   ```properties
   google.client-id=Web客户端ID.apps.googleusercontent.com
   ```

### 第七步：配置后台、Android 与 HTTPS

1. 后台执行 `npm ci`、`npm run build`，将 `joysong-admin/dist/` 部署到 `https://你的后台域名`。
2. 将该后台域名写入 `CORS_ALLOWED_ORIGINS`，不要使用 `*`。
3. Android 的 `joysong-app/local.properties` 设置：

   ```properties
   server.url=https://你的API域名/
   google.client-id=Web客户端ID.apps.googleusercontent.com
   ```

4. 使用项目中的 [Nginx 配置模板](../joysong-server/deploy/nginx/joysong-api.conf)，替换域名、TLS 证书路径、上传目录后执行 `nginx -t`。Nginx 官方文档见 [nginx.org](https://nginx.org/en/docs/)。

## 3. 服务端必配项

生产环境至少应设置以下变量：

```dotenv
# 数据库
DB_PASSWORD=替换为高强度数据库密码

# JWT：至少 32 字节随机值，发布后保持稳定，否则所有已登录用户会失效
JWT_SECRET=替换为至少32字节的随机密钥

# 首次启动初始化管理员；成功创建后应从部署环境中移除 ADMIN_PASSWORD
ADMIN_PHONE=13800138000
ADMIN_PASSWORD=替换为强密码

# 对外 API 域名与本地上传目录
SERVER_BASE_URL=https://api.example.com
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads

# 前端管理域名。多个地址用英文逗号分隔，不要在生产使用 *
CORS_ALLOWED_ORIGINS=https://admin.example.com
```

当前默认数据库连接为 `localhost:3306/joysong`，用户名为 `root`。若生产库不在本机、名称或用户名不同，应通过受控的生产配置文件覆盖 `spring.datasource.url` 与 `spring.datasource.username`，不要修改并提交默认配置。Flyway 启动时会执行 `db/migration` 下的迁移；上线前先备份数据库。

## 4. Qwen 翻译配置

评论、回复、私信、日记正文默认使用百炼 `qwen3.7-flash`。翻译与 AI Agent 使用不同的密钥、地址和模型配置。

```dotenv
# Qwen 主翻译通道
TRANSLATION_PROVIDER=qwen
QWEN_API_KEY=百炼业务空间对应的APIKey
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_MODEL=qwen3.7-flash

# Qwen 异常时的可选回退；不需要回退时设为 none
TRANSLATION_FALLBACK_PROVIDER=openai

# OpenAI 兼容中转仅在 fallback/provider= openai 时使用
OPENAI_API_KEY=
OPENAI_BASE_URL=https://your-relay.example.com/v1
TRANSLATION_MODEL=gpt-5.5
```

支持的翻译 Provider：

| `TRANSLATION_PROVIDER` / `TRANSLATION_FALLBACK_PROVIDER` | 说明 |
|---|---|
| `qwen` | 默认值，调用 `qwen3.7-flash`；适用于四类用户内容翻译 |
| `openai` | 使用 OpenAI 兼容 Chat Completions 接口兜底 |
| `none` | 仅适用于 `TRANSLATION_FALLBACK_PROVIDER`，表示不回退 |

注意：Qwen 的 API Key 与地域/业务空间有关，应使用百炼控制台实际提供的兼容接口地址覆盖 `QWEN_BASE_URL`。服务端使用专用精简提示词，客户端不保存任何翻译密钥。

## 5. AI Agent 配置

AI Agent 独立读取以下变量，不影响 Qwen：

```dotenv
OPENAI_API_KEY=中转站或模型服务密钥
OPENAI_BASE_URL=https://www.fastaitoken.com/v1
AI_AGENT_MODEL=gpt-5.5
OPENAI_PROXY_URL=http://proxy.example.internal:8080
OPENAI_STREAM_ENABLED=false
OPENAI_INTENT_PARSER_ENABLED=true
```

生产启用 Agent 时必须显式配置 `AI_AGENT_MODEL`，服务端不会从 `OPENAI_MODEL` 或仓库默认值回退。可选的 `OPENAI_PROXY_URL` 只支持带显式端口的 `http://` 和 `socks://` URL；`https://` proxy URL 会在启动时被拒绝。

## 6. OSS 与图片上传

```dotenv
OSS_ACCESS_KEY_ID=阿里云RAM访问密钥ID
OSS_ACCESS_KEY_SECRET=阿里云RAM访问密钥Secret
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
SERVER_BASE_URL=https://api.example.com
```

当前 OSS bucket 与 endpoint 在 `application.yml` 中配置。RAM 账号应仅授予目标 Bucket 的最小读写权限。若同时启用本地上传，`UPLOAD_LOCAL_DIR` 必须可由 Java 进程写入、由 Nginx 读取，且与 Nginx 的 `/images/` `alias` 指向同一路径。

## 7. 阿里云短信

短信服务与翻译无关。默认关闭，启用前需在配置文件中将 `aliyun.sms.enabled` 设为 `true`，并设置：

```dotenv
SMS_ACCESS_KEY_ID=短信RAM访问密钥ID
SMS_ACCESS_KEY_SECRET=短信RAM访问密钥Secret
```

同时在生产配置中填入已审核的短信签名 `sign-name` 与模板 `template-code`。开发环境保持关闭，验证码仅可通过开发日志获取。

## 8. Google 登录

```dotenv
GOOGLE_CLIENT_ID=OAuthWebClientID.apps.googleusercontent.com
# 可选，仅在服务器校验 Google ID Token 需走代理时设置
GOOGLE_PROXY_URL=http://proxy.example.internal:8080
```

Android 端还必须在 `joysong-app/local.properties` 配置相同的 Client ID：

```properties
google.client-id=OAuthWebClientID.apps.googleusercontent.com
```

不要将 `GOOGLE_PROXY_URL` 用作通用代理；它仅用于服务端获取 Google 公钥验证 ID Token。

## 9. CORS、Nginx 与 HTTPS

生产环境设置明确的管理后台域名：

```dotenv
CORS_ALLOWED_ORIGINS=https://admin.example.com
```

将 `joysong-server/deploy/nginx/joysong-api.conf` 部署到 Nginx 后：

1. 将 `server_name` 改为真实 API 域名；
2. 配置 TLS 证书并将 HTTP 跳转 HTTPS；
3. 将 `/images/` 的 `alias` 改为与 `UPLOAD_LOCAL_DIR` 一致；
4. 确认后端仅监听受控地址，Nginx 再代理 `/api/` 与 `/actuator/health`；
5. 执行 `nginx -t` 后再 reload。

## 10. 管理后台

开发模式下 Vite 在 `3000` 端口运行，并把 `/api`、`/images` 代理到 `http://localhost:8080`。生产构建使用：

```bash
cd joysong-admin
npm ci
npm run build
```

将 `dist/` 交由静态站点服务器托管，并确保其域名已写入服务端 `CORS_ALLOWED_ORIGINS`。若 API 与后台不在同域，应由反向代理或前端部署配置将 `/api` 指向 API 域名。

## 11. Android 客户端

Android 的地址在构建时从 `joysong-app/local.properties` 读取：

```properties
# 必须以 / 结尾
server.url=https://api.example.com/
google.client-id=OAuthWebClientID.apps.googleusercontent.com
```

- Android 模拟器调本机服务：`http://10.0.2.2:8080/`。
- 真机调试：填开发机可访问的局域网 HTTPS 地址或经可信隧道暴露的地址。
- 发布包必须使用 HTTPS，且 API 域名、图片 URL 与 `SERVER_BASE_URL` 保持一致。

`local.properties` 是本地构建配置，不应提交。

## 12. 本地开发启动检查表

1. 创建 MySQL 数据库或允许当前 JDBC URL 自动创建；设置 `DB_PASSWORD`。
2. 使用 `SPRING_PROFILES_ACTIVE=dev` 启动服务端；仅限本地。
3. 填写 `QWEN_API_KEY` 后验证翻译接口；没有 Key 时可将 `TRANSLATION_PROVIDER=openai` 临时调试。
4. 启动管理后台开发服务，确认浏览器通过 Vite 代理访问 API。
5. Android 模拟器使用 `10.0.2.2`，真机使用可访问的开发机地址。

## 13. 上线前安全检查

- 不使用 `application-dev.yml` 的数据库密码、JWT 或管理员默认账号。
- 所有密钥来自部署平台的 Secret，仓库、日志、客户端和截图中均不出现完整密钥。
- `JWT_SECRET` 随机且稳定，管理员初始化完成后移除 `ADMIN_PASSWORD`。
- CORS 只允许已知后台域名；Nginx 配置 HTTPS。
- OSS 与短信 RAM 凭证遵循最小权限原则。
- 数据库备份完成后再执行版本迁移，并验证 `/actuator/health`、上传和 Qwen 翻译。
