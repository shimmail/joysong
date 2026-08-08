# Joysong 云服务器部署指南（开发中项目）

> 支付相关的测试、Stripe 配置获取和正式上线变量请以 [`支付开发与云服务器部署指南.md`](./支付开发与云服务器部署指南.md) 为准。

> 本文是持续维护的部署基线，不代表当前版本已经可以正式上线。当前推荐先部署测试/预发布环境，正式环境必须完成文末的上线阻断项。

## 1. 推荐拓扑

```text
用户浏览器 / Flutter / Android
              |
       HTTPS:443 (Nginx)
          /          \
  /api、/images       管理端静态文件
          |
 Spring Boot :8080
          |
       MySQL 8
```

推荐一台 Ubuntu 22.04/24.04 云服务器，至少 2 vCPU、4 GB RAM、40 GB 系统盘；生产文件和数据库应使用独立云盘或云数据库。只开放 `22`（限制来源）、`80`、`443`，不要开放 `8080`、`3306`。

## 2. 当前阶段的部署边界

当前项目仍在开发中，建议使用独立测试域名，例如：

- API：`api-dev.example.com`
- 管理端：`admin-dev.example.com`
- 分享页：`app-dev.example.com`

测试环境可以使用测试短信、测试支付和受控 AI 账号，但禁止使用真实用户数据。管理员公共认证链路尚有安全阻断项，不能把测试环境直接当成生产环境。

## 3. 云服务器初始化

```bash
sudo apt update && sudo apt upgrade -y
sudo apt install -y git nginx mysql-client certbot python3-certbot-nginx unzip curl
sudo useradd --system --home /opt/joysong --shell /usr/sbin/nologin joysong || true
sudo mkdir -p /opt/joysong /var/lib/joysong/uploads /var/backups/joysong
sudo chown -R joysong:www-data /opt/joysong /var/lib/joysong/uploads
sudo chmod 0750 /var/lib/joysong/uploads
```

安装 Java 17、Node.js 20、Flutter（仅在服务器构建 Flutter 时需要）。更推荐在 CI 构建管理端和后端制品，再上传制品到服务器；不要在生产机保留源码中的密钥文件。

## 4. 需要修改或新增的项目文件

### 4.1 服务端

涉及文件：

- `joysong-server/src/main/resources/application.yml`
- `joysong-server/src/main/resources/application-prod.yml`
- `joysong-server/.env.example`
- `joysong-server/deploy/nginx/joysong-api.conf`
- `joysong-server/deploy/README.md`

生产 profile 必须包含：

```yaml
spring:
  config:
    activate:
      on-profile: prod
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}

openai:
  base-url: ${OPENAI_BASE_URL}
```

不要把真实值写入 `application-prod.yml` 或 Git。当前仓库已取消 AI 第三方默认兜底；生产环境必须显式设置 `OPENAI_BASE_URL`，并使用经过审计的 HTTPS 地址。当前代码白名单只允许 `api.openai.com`，若部署使用 Azure OpenAI 或企业代理，需要先修改白名单策略并增加测试。

### 4.2 管理端

涉及文件：

- `joysong-admin/vite.config.ts`
- `joysong-admin/src/api.ts`
- `joysong-admin/.env.production`（不提交）

开发环境的 Vite proxy 只适用于本机。生产构建时 API 请求使用同源 `/api`，由 Nginx 转发到 `127.0.0.1:8080`。如果管理端和 API 使用不同域名，必须增加明确的生产 `baseURL`、CORS 和 Cookie/Token 策略，不要复用开发 proxy。

构建：

```bash
cd /opt/joysong/joysong-admin
npm ci
npm run build
sudo rsync -a --delete dist/ /var/www/joysong-admin/
```

构建产物目前包含约 799 KB 的富文本 chunk；正式上线前应继续拆包，避免首次加载管理端时下载全部编辑器代码。

### 4.3 Flutter 客户端

涉及文件：

- `joysong-flutter/lib/core/config/app_environment.dart`
- `joysong-flutter/android/app/build.gradle.kts`
- `joysong-flutter/android/key.properties`（不提交）
- iOS `Runner` 的 Release signing、Bundle Identifier 和 URL Scheme

生产构建必须使用 HTTPS：

```bash
cd /opt/joysong/joysong-flutter
flutter pub get
flutter build apk --release \
  --dart-define=APP_ENV=production \
  --dart-define=API_BASE_URL=https://api.example.com
```

Android 发布机需要创建 `android/key.properties` 和正式 keystore。禁止把 `key.properties`、keystore、Google client secret 提交到仓库。iOS Release 构建必须在 macOS/Xcode 上完成，并配置分发证书、Provisioning Profile、Associated Domains（如启用分享链接）。

### 4.4 原生 Android 客户端（过渡维护）

涉及文件：

- `joysong-app/app/build.gradle.kts`
- `joysong-app/local.properties`（不提交）
- `joysong-app/app/src/main/AndroidManifest.xml`

生产 `local.properties` 至少包含：

```properties
server.url=https://api.example.com/
google.client-id=...
```

Release 不得使用 `http://10.0.2.2:8080/`，也不得开启全局明文流量。当前项目同时维护 Flutter 和原生 Android；正式发布前必须明确 Flutter 为主客户端，或记录两套客户端的同步发布责任。

## 5. 服务端环境变量

在服务器保存为 `/etc/joysong/joysong.env`，权限设为 `chmod 600`，运行用户设为 `joysong`：

```dotenv
SPRING_PROFILES_ACTIVE=prod
DB_URL=jdbc:mysql://db-host:3306/joysong?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
DB_USERNAME=joysong_app
DB_PASSWORD=<strong-random-password>
JWT_SECRET=<at-least-32-random-characters>
ADMIN_PHONE=<admin-phone>
ADMIN_PASSWORD=<12-128-character-strong-password>
GOOGLE_CLIENT_ID=<oauth-client-id>

SERVER_BASE_URL=https://api.example.com
APP_SHARE_BASE_URL=https://app.example.com/s/diary/
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads

OSS_ENABLED=true
OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
OSS_BUCKET_NAME=<private-or-controlled-bucket>
OSS_PUBLIC_BASE_URL=https://cdn.example.com
OSS_ACCESS_KEY_ID=<access-key>
OSS_ACCESS_KEY_SECRET=<secret>

SMS_ENABLED=true
SMS_ACCESS_KEY_ID=<access-key>
SMS_ACCESS_KEY_SECRET=<secret>
SMS_SIGN_NAME=<signature>
SMS_TEMPLATE_CODE=<template>

OPENAI_API_KEY=<key>
OPENAI_BASE_URL=https://api.openai.com/v1
AI_AGENT_MODEL=<approved-model>
QWEN_API_KEY=<key-if-used>
QWEN_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
QWEN_MODEL=qwen3.7-flash

STRIPE_SECRET_KEY=sk_live_<from Stripe Dashboard Live mode>
STRIPE_WEBHOOK_SECRET=whsec_<from the production Webhook Endpoint>
STRIPE_SUCCESS_URL=https://app.example.com/payment/success?session_id={CHECKOUT_SESSION_ID}
STRIPE_CANCEL_URL=https://app.example.com/payment/cancel
STRIPE_API_BASE=https://api.stripe.com
STRIPE_API_VERSION=
STRIPE_WEBHOOK_TOLERANCE_SECONDS=300
STRIPE_PRODUCT_NAME=Joysong medical service
PAYMENT_RECONCILIATION_ENABLED=true
PAYMENT_RECONCILIATION_DELAY_MS=60000
PAYMENT_RECONCILIATION_STALE_SECONDS=120
```

### 5.1 完整可选变量索引

以下变量有默认值，但部署时应明确确认是否需要覆盖：

| 变量 | 用途 |
|---|---|
| `CORS_ALLOWED_ORIGINS` | 跨域来源白名单；生产只填实际 HTTPS 域名 |
| `GOOGLE_PROXY_URL` | 仅用于 Google ID Token 公钥校验的受控代理；不要配置为通用出网代理 |
| `OPENAI_MODEL` | 旧版模型变量，优先使用 `AI_AGENT_MODEL` |
| `OPENAI_STREAM_ENABLED` | AI 流式响应开关 |
| `OPENAI_INTENT_PARSER_ENABLED` | AI 意图解析开关 |
| `OPENAI_FAST_REASONING_EFFORT` / `OPENAI_COMPLEX_REASONING_EFFORT` | 推理预算；确认供应商支持后再配置 |
| `TRANSLATION_PROVIDER` / `TRANSLATION_FALLBACK_PROVIDER` | 翻译主/备用供应商 |
| `TRANSLATION_MODEL` | OpenAI 翻译模型 |
| `QWEN_MT_BASE_URL` | 旧版通义兼容地址变量 |
| `STRIPE_API_BASE` / `STRIPE_API_VERSION` | Stripe API 地址和版本 |
| `STRIPE_SUCCESS_URL` / `STRIPE_CANCEL_URL` | 支付回跳页面 |
| `STRIPE_PRODUCT_NAME` | 支付商品名 |
| `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` | Stripe 密钥和 webhook 签名 |
| `STRIPE_WEBHOOK_TOLERANCE_SECONDS` | webhook 时间容忍窗口 |
| `PAYMENT_RECONCILIATION_DELAY_MS` / `PAYMENT_RECONCILIATION_STALE_SECONDS` | 订单对账周期和过期阈值 |
| `SEED_DEMO_ENABLED` / `DEMO_USER_PASSWORD` | 仅开发环境演示数据；生产必须关闭 |

不要把可选变量的默认值复制到生产密钥文件；生产 profile 的 fail-closed 校验优先于开发默认值。

Stripe 的环境由服务端 API key 决定：测试环境使用 `sk_test_...`，正式环境使用 `sk_live_...`。两种环境都必须提供与当前 Stripe webhook endpoint 匹配的 `STRIPE_WEBHOOK_SECRET`；不要在测试环境配置 `sk_live_...`。

## 6. 数据库准备与迁移

```sql
CREATE DATABASE joysong CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'joysong_app'@'%' IDENTIFIED BY '<strong-password>';
GRANT SELECT, INSERT, UPDATE, DELETE, EXECUTE ON joysong.* TO 'joysong_app'@'%';
FLUSH PRIVILEGES;
```

生产 profile 使用 Flyway，`ddl-auto=validate`，因此应用启动前必须确认迁移脚本已提交并可重复执行。不要手动修改生产表结构绕过 Flyway。首次部署后检查 `flyway_schema_history`，并记录数据库版本。

## 7. systemd 运行后端

构建 Spring Boot jar：

```bash
cd /opt/joysong/joysong-server
./gradlew bootJar
sudo install -o joysong -g joysong -m 0750 build/libs/*.jar /opt/joysong/joysong-server.jar
```

创建 `/etc/systemd/system/joysong.service`：

```ini
[Unit]
Description=Joysong Spring Boot API
After=network-online.target
Wants=network-online.target

[Service]
User=joysong
Group=joysong
WorkingDirectory=/opt/joysong
EnvironmentFile=/etc/joysong/joysong.env
ExecStart=/usr/bin/java -Xms512m -Xmx1536m -jar /opt/joysong/joysong-server.jar
Restart=on-failure
RestartSec=5
NoNewPrivileges=true
PrivateTmp=true
ReadWritePaths=/var/lib/joysong/uploads

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now joysong
sudo journalctl -u joysong -f
```

## 8. Nginx 与 HTTPS

复制 `joysong-server/deploy/nginx/joysong-api.conf` 到 `/etc/nginx/sites-available/joysong-api.conf`，修改：

- `server_name api.example.com`
- `/images/` 的 `alias` 为 `/var/lib/joysong/uploads/`
- `client_max_body_size` 与服务端上传限制保持一致
- 增加管理端静态站点和 SPA fallback

```bash
sudo ln -s /etc/nginx/sites-available/joysong-api.conf /etc/nginx/sites-enabled/joysong-api.conf
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d api.example.com -d admin.example.com -d app.example.com
```

证书签发后确认 80 自动跳转 443，API、图片和分享页均不能混用 HTTP。

## 9. 发布、回滚和备份

发布顺序：

1. 备份数据库和 `/var/lib/joysong/uploads`。
2. 构建并上传新 jar、管理端 `dist` 和移动端制品。
3. 先执行 `systemctl restart joysong`，确认健康检查和 Flyway 成功。
4. 再切换 Nginx 静态文件目录；保留上一版本目录用于回滚。
5. 执行登录、上传、图片访问、分享页和支付 webhook 冒烟测试。

最低备份命令：

```bash
mysqldump --single-transaction --routines --triggers joysong | gzip > /var/backups/joysong/joysong-$(date +%F-%H%M).sql.gz
tar -czf /var/backups/joysong/uploads-$(date +%F-%H%M).tar.gz /var/lib/joysong/uploads
```

至少保留 7 天备份，并将备份复制到另一可用区或对象存储。回滚必须同时回滚 jar、管理端静态文件和数据库迁移兼容性；不要只替换 jar。

## 10. 当前正式上线阻断项

- 公共“忘记密码”仍需禁止管理员账号。
- 普通密码登录、公共注册/注销恢复路径仍需保证永不签发管理员 JWT。
- 管理员认证错误必须统一为 401/通用错误，不能暴露角色信息。
- Flutter 真实启动链路的 401 refresh handler 尚需修复并补集成测试。
- 验证码和手机号换绑状态需迁移 Redis，才能支持多实例。
- 原生 Android Release 禁止明文 HTTP；当前 Flutter 与原生 Android 的主客户端策略需确定。
- 生产域名、短信、OSS、支付、AI 数据处理协议和隐私合规尚未完成确认。

## 11. 上线验收清单

- [ ] 服务器只开放 22/80/443，数据库不暴露公网
- [ ] `/etc/joysong/joysong.env` 权限为 600，仓库无真实密钥
- [ ] `SPRING_PROFILES_ACTIVE=prod`，生产配置校验通过
- [ ] Flyway 迁移成功且 `ddl-auto=validate`
- [ ] API、管理端、图片、分享页全部 HTTPS
- [ ] 管理端生产构建通过，API 同源代理正常
- [ ] Flutter/Android Release 使用正式 HTTPS API 和正式签名
- [ ] 登录、刷新、退出、上传、分享、短信、OSS、支付 webhook 冒烟通过
- [ ] 数据库、上传目录和配置均有恢复演练记录
- [ ] 监控磁盘、内存、JVM、5xx、登录失败、短信和支付失败率
- [ ] 所有“正式上线阻断项”已关闭并由另一人复核
