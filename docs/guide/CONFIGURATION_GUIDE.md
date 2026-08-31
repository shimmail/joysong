# JoySong 项目配置指南

本文以当前服务端、管理端和 Flutter 实现为准。生产机密只允许通过部署平台环境变量或受控密钥管理服务注入，不得提交 `.env`、数据库密码、AccessKey、JWT 密钥或模型 API Key。

## 1. 配置来源与生产边界

| 模块 | 当前配置入口 | 说明 |
|---|---|---|
| 服务端通用配置 | `joysong-server/src/main/resources/application.yml` | 包含本地默认值，不能单独作为生产配置 |
| 服务端生产覆盖 | `joysong-server/src/main/resources/application-prod.yml` | 只有激活 `prod` profile 才生效 |
| 环境变量样例 | `joysong-server/.env.example` | 仅用于核对变量名，Spring 不会自动读取 `.env` |
| 本地服务端 | `application-dev.example.yml` → `application-dev.yml` | 只用于本地调试，禁止用于生产 |
| 管理后台 | `joysong-admin/` | 本地由 Vite 代理，生产部署 `dist/` |
| Flutter | `--dart-define` | API 地址和 Google Client ID 都在构建时注入 |
| Nginx | `joysong-server/deploy/nginx/joysong-api.conf` | 当前只是 HTTP/upstream 骨架，不包含完整 TLS 配置 |

生产部署必须显式设置：

```dotenv
SPRING_PROFILES_ACTIVE=prod
```

未激活 `prod` 不一定立即报错，但会继续使用默认本机数据库地址、宽松的 Flyway 设置和开发默认值，属于禁止上线状态。环境变量必须由部署平台、服务管理器、容器编排或启动脚本导入进程；只复制 `.env.example` 不会生效。

## 2. 从空白生产环境部署

这里的“空白”指：生产数据库已预创建、Flyway 可在其中建表，并且迁移完成后的 `users` 表为 `0` 行。生产账号不得拥有创建任意数据库或管理用户的权限；生产流程禁止运行 Flyway `clean`。

### 2.1 部署前准备

1. 预创建 MySQL 数据库和仅限该 schema 的应用账号。`DB_URL` 不得包含 `createDatabaseIfNotExist=true`。当前 Flyway 与运行时共用 `DB_USERNAME`，因此该账号还必须具备现有迁移需要的 schema 级 `CREATE / ALTER / DROP / INDEX / REFERENCES / CREATE ROUTINE / ALTER ROUTINE / EXECUTE` 权限；不得授予全局权限、`CREATE USER` 或 `GRANT OPTION`。若以后拆出独立 Flyway 账号，再把运行时账号收敛为纯 DML 权限。
2. 准备 API 域名、管理后台域名和 TLS 证书。
3. 准备 Google Web OAuth Client ID。
4. 准备 OSS Bucket、最小权限 RAM AccessKey、Endpoint。
5. 准备已审核的短信签名、验证码模板和最小权限 RAM AccessKey。
6. 准备 Qwen Agent 与 Translation 使用的 API Key、模型 ID 和兼容接口地址。
7. 确认固定管理员使用一个未被普通账号占用的大陆手机号裸号，例如 `13800138000`，不得写成 `+8613800138000`。

### 2.2 生产启动硬性变量

以下值缺失或不合法会导致属性解析、数据库连接、迁移或配置校验失败：

```dotenv
# 必须显式激活生产配置
SPRING_PROFILES_ACTIVE=prod

# 数据库必须预创建；不要附加 createDatabaseIfNotExist=true
DB_URL=jdbc:mysql://db.example.internal:3306/joysong?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
DB_USERNAME=joysong_app
DB_PASSWORD=替换为数据库强密码

# 至少 32 个字符，发布后保持稳定
JWT_SECRET=替换为至少32字符的随机密钥

# Google Web OAuth Client ID；当前服务端启动校验要求非空
GOOGLE_CLIENT_ID=Web客户端ID.apps.googleusercontent.com

# 唯一固定管理员，只接受 ^1\d{10}$ 的裸 11 位号码
ADMIN_PHONE=13800138000

# 生产日记分享页根地址；必须由部署环境显式提供
APP_SHARE_BASE_URL=https://api.example.com/s/diary/

# prod 中必须启用 OSS
OSS_ENABLED=true
OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
OSS_BUCKET_NAME=实际Bucket名称
OSS_ACCESS_KEY_ID=RAM用户AccessKeyID
OSS_ACCESS_KEY_SECRET=RAM用户AccessKeySecret

# prod 中必须启用短信
SMS_ENABLED=true
SMS_ACCESS_KEY_ID=短信RAM用户AccessKeyID
SMS_ACCESS_KEY_SECRET=短信RAM用户AccessKeySecret
SMS_SIGN_NAME=已审核短信签名
SMS_TEMPLATE_CODE=已审核验证码模板Code

# 当前完整部署只允许 Qwen；Base URL 必须是下面这个 HTTPS 主机和路径
AI_AGENT_PROVIDER=qwen
AI_AGENT_API_KEY=Agent服务密钥
AI_AGENT_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
AI_AGENT_MODEL=实际回答模型ID
AI_AGENT_INTENT_MODEL=实际意图模型ID
```

`OSS_ENABLED` 和 `SMS_ENABLED` 已在 `application-prod.yml` 中设为 `true`，这里仍显式列出，便于部署平台审计。不得用更高优先级配置把它们覆盖为 `false`。

### 2.3 生产功能变量

以下值不都属于启动硬门槛，但缺失会让域名、管理端、上传或翻译在运行时不可用：

```dotenv
SERVER_BASE_URL=https://api.example.com
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
UPLOAD_PRIVATE_DIR=/var/lib/joysong/private
CORS_ALLOWED_ORIGINS=https://admin.example.com

TRANSLATION_PROVIDER=qwen
TRANSLATION_API_KEY=翻译服务密钥
TRANSLATION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1
TRANSLATION_MODEL=qwen3.7-flash

# 可选：OSS 公网或 CDN 前缀；留空时按 OSS 配置生成地址
OSS_PUBLIC_BASE_URL=https://cdn.example.com

# 可选：仅供服务端获取 Google 公钥，不是全局代理
GOOGLE_PROXY_URL=http://proxy.example.internal:8080
```

`CORS_ALLOWED_ORIGINS` 必须是明确可信的 origin，多个值用英文逗号分隔，不能包含 `*`。Translation 当前不会在启动时校验 API Key；漏配时服务仍可能启动，但第一次翻译调用会失败。

`UPLOAD_PRIVATE_DIR` 保存身份证明等私有材料，只允许 Java 进程读写，禁止通过 Nginx 或公共静态目录暴露，并必须纳入备份和恢复。它与公共的 `UPLOAD_LOCAL_DIR` 完全分离。

`APP_SHARE_BASE_URL` 用于生成日记分享链接，在 `prod` 中为启动必填项。它必须是无账号信息、查询参数和片段的绝对 HTTP(S) URL，生产应指向真实 HTTPS 分享页根路径；代码不会写死域名，也不会在生产环境回退到请求 `Host`。如果使用独立分享域名，必须同步配置 DNS、TLS、Nginx `server_name` 和 `/s/diary/` 代理。

### 2.4 仅首次创建管理员的临时密码

仅当迁移完成后 `users=0` 时临时增加：

```dotenv
ADMIN_PASSWORD=首次管理员密码
```

当前 Bootstrap 只硬性校验长度为 `12–128` 个字符；生产仍应使用包含大小写字母、数字和特殊字符的随机强密码。这个变量只负责生成首次密码哈希：

- 首次创建成功后立即从部署环境移除；
- 后续启动不要求、不使用它；
- 再次设置它不会重置现有管理员密码；
- 不要把它长期保存在生产环境变量、部署清单或截图中。

管理员后续改密走账号改密接口；改密密码要求 `12–128` 位并包含大小写字母、数字和特殊字符。

### 2.5 首次启动和无密码重启

1. 记录即将连接的数据库主机、数据库名和 active profiles，确认目标是预创建的生产库，且 `users` 表尚不存在或总行数为 `0`。
2. 注入第 2.2、2.3 节的变量，并临时注入 `ADMIN_PASSWORD`。
3. 只启动一个后端实例。等待 Flyway 完成，随后 Bootstrap 创建固定管理员。
4. 检查 `GET /actuator/health`，再用 `ADMIN_PHONE` 的裸 11 位号码和首次密码调用 `POST /api/admin/login`。
5. 记录管理员 `userId`、手机号、角色、账号状态、账号总数和密码哈希指纹；证据中不得保存密码或完整哈希。
6. 受控停止后端，彻底移除 `ADMIN_PASSWORD`，保持其余配置不变。
7. 使用同一数据库再次启动。确认同一管理员仍可用原密码登录，且 `userId`、手机号、角色、状态、昵称、账号总数和密码哈希指纹均未变化。
8. 再部署管理端、Nginx/TLS 和 Flutter 生产包。

首次创建的管理员固定为：

| 字段 | 初始值 |
|---|---|
| `phone` | `ADMIN_PHONE` 的裸 11 位号码 |
| `role` | `ADMIN` |
| `account_state` | `ACTIVE` |
| `nickname` | `系统管理员` |
| `id` | 首次创建时生成，后续启动不得改变 |
| `password_hash` | 由临时 `ADMIN_PASSWORD` 生成，后续配置不得覆盖 |
| 专业身份 | 无；不会自动成为医生、顾问或机构法人 |

## 3. 固定管理员手机号

### 3.1 首次创建前修改

只要尚未成功创建管理员且 `users=0`，直接修改 `ADMIN_PHONE` 后再首次启动即可。新值必须是裸 11 位号码，并同步使用该号码登录管理端。

### 3.2 首次创建后修改

当前产品没有新增管理员页面，也没有管理员在线改号或角色转移 API。只改 `ADMIN_PHONE` 会被识别为配置漂移并拒绝启动；固定管理员走普通换号接口也会被拒绝。

确需更换时，只能执行极少使用的停机运维迁移。若不能停止所有实例、备份数据库并原子同步数据库与部署配置，不得执行，也不得把管理员改号标记为通过。

#### 前置检查

1. 进入维护窗口，停止所有连接该数据库的后端实例。
2. 创建可恢复备份，记录旧号码、固定管理员 `userId` 和当前部署配置。
3. 确认新号码为未被占用的裸 11 位号码；为避免唯一索引或历史数据冲突，受控迁移要求新裸号和其 `+86` 等价号在所有账号状态中均无人占用。
4. 确认当前只有一个非 `ERASED` 管理员，且它正是旧号码对应的 `ACTIVE + ADMIN` 账号，密码哈希非空。

#### 受控事务

将示例值替换为实际号码和已记录的 `userId`。下面两段 SQL 是同一个事务，必须在**同一个持续的数据库连接（同一 session）**中交互执行；禁止使用“每个代码块新建连接”的控制台或任务执行器。在看到预检查询只返回当前固定管理员、且目标号码无人占用后才能执行更新：

```sql
SET @old_phone = '13800138000';
SET @new_phone = '13900139000';
SET @admin_id = '替换为固定管理员userId';

START TRANSACTION;

SELECT guard_key
FROM admin_account_guard
WHERE guard_key = 'ACTIVE_ADMIN'
FOR UPDATE;

SELECT id, phone, role, account_state, password_hash
FROM users
WHERE role = 'ADMIN' AND account_state <> 'ERASED'
FOR UPDATE;

SELECT id, phone, role, account_state
FROM users
WHERE phone IN (@new_phone, CONCAT('+86', @new_phone))
FOR UPDATE;

UPDATE users
SET phone = @new_phone,
    credentials_updated_at = NOW(),
    updated_at = NOW()
WHERE id = @admin_id
  AND phone = @old_phone
  AND role = 'ADMIN'
  AND account_state = 'ACTIVE'
  AND TRIM(password_hash) <> '';

SELECT ROW_COUNT() AS admin_rows_updated;
```

此时事务仍未提交，当前连接不得关闭或切换。人工或受控脚本必须在这个连接中读取 `admin_rows_updated`：

- 若不等于 `1`，只执行 `ROLLBACK;`，停止迁移；
- 只有等于 `1`，才在同一连接中执行下面第二段撤销会话并提交。

```sql
UPDATE refresh_tokens
SET revoked_at = COALESCE(revoked_at, NOW())
WHERE user_id = @admin_id;

COMMIT;
```

随后在后端仍停止的状态下把部署变量改为新的裸号：

```dotenv
ADMIN_PHONE=13900139000
```

不要设置 `ADMIN_PASSWORD`。启动一个实例并验证：

- `userId`、密码哈希指纹、昵称、角色和状态保持不变；
- 新号码配合原密码登录成功；
- 旧号码登录失败；
- 改号前的 access token 和 refresh token 均已失效；
- 再次无 `ADMIN_PASSWORD` 重启仍能通过校验。

失败或回滚时必须同时恢复数据库手机号与 `ADMIN_PHONE`，不能只恢复一侧。如果新号码阶段已经启动或登录，回滚事务还必须再次更新 `credentials_updated_at=NOW()` 并撤销该 `userId` 的全部 refresh token，防止新号码阶段签发的会话继续有效。此流程是运维迁移，不代表产品支持第二管理员、管理员升降权或在线换号。

## 4. 管理员启动校验矩阵

| 配置和数据状态 | 结果 |
|---|---|
| `users=0`，手机号合法，首次密码为 12–128 位 | 创建唯一固定管理员 |
| `users=0`，首次密码为空、少于 12 位或超过 128 位 | 拒绝启动，不创建管理员 |
| 任意数据库，`ADMIN_PHONE` 不是裸 11 位号码 | 配置校验拒绝启动 |
| 已有匹配的 `ACTIVE + ADMIN`，密码哈希非空，未设置 `ADMIN_PASSWORD` | 正常启动，只验证、不写入 |
| 已有匹配管理员但仍设置任意 `ADMIN_PASSWORD` | 正常启动，该变量被忽略，不重置密码 |
| 非空 `users` 缺少配置号码，或只修改了 `ADMIN_PHONE` | 拒绝启动，不迁移、不新建账号 |
| 配置号码属于普通用户、暂停/注销管理员或密码哈希为空 | 拒绝启动，不修复数据 |
| 另一个非 `ERASED` 账号占用固定裸号或其 `+86` 等价号 | 拒绝启动 |
| 存在第二个非 `ERASED` 管理员 | 拒绝启动 |
| 第 3.2 节同时受控更新同一 userId、会话与配置 | 正常启动，仍只有一个固定管理员 |

当前冲突计数只覆盖非 `ERASED` 账号；历史 `ERASED` 号码 owner 不应在文档中写成启动阻断。

## 5. 外部服务配置

### 5.1 OSS 与上传

- 生产必须配置 `OSS_ENDPOINT`、`OSS_BUCKET_NAME`、`OSS_ACCESS_KEY_ID`、`OSS_ACCESS_KEY_SECRET`。
- RAM 账号只授予目标 Bucket 的最小读写权限，不使用主账号 AccessKey。
- `OSS_PUBLIC_BASE_URL` 可选；设置 CDN 时应与 Bucket 访问策略一致。
- `UPLOAD_LOCAL_DIR` 使用持久绝对路径，并让 Java 进程可写、Nginx 可读；它用于本地存储分支或既有本地资源，不替代生产 OSS 配置。

### 5.2 短信

- 生产必须保持 `SMS_ENABLED=true`。
- 签名和模板必须先在阿里云审核通过。
- 使用专用 RAM AccessKey，并配置 `SMS_SIGN_NAME` 与 `SMS_TEMPLATE_CODE`。
- 开发环境可以关闭短信并使用 dev profile 的验证码日志；生产禁止开启验证码日志。

### 5.3 AI Agent 与 Translation

两套配置完全独立，不能复用或回退：

- Agent 读取 `AI_AGENT_*`，生产启动会校验 Provider、API Key、Base URL 和两个模型；
- Translation 读取 `TRANSLATION_*`，当前 API Key 在首次翻译调用时才检查；
- 当前 Agent 只支持 Qwen，Base URL 必须为 `https://dashscope.aliyuncs.com/compatible-mode/v1`；
- Agent 的灰度、紧急停用和入口控制由网关或发布平台承担，应用内没有环境开关。

### 5.4 Google 登录

服务端 `GOOGLE_CLIENT_ID` 使用 Google Web OAuth Client ID，并与 Flutter 的 `GOOGLE_SERVER_CLIENT_ID` 保持一致。`GOOGLE_PROXY_URL` 只影响服务端获取 Google 公钥。

## 6. 管理端、Flutter 与 Nginx

### 6.1 管理后台

```shell
cd joysong-admin
npm ci
npm run build
```

将 `dist/` 部署到 HTTPS 静态站点，并把该站点的 origin 写入 `CORS_ALLOWED_ORIGINS`。如果管理端和 API 不同域，应由反向代理或静态站点配置把 `/api` 指向 API 域名。

### 6.2 Flutter 生产构建

Flutter 不读取 `joysong-app/local.properties`。Android 生产包示例：

```shell
cd joysong-flutter
flutter build appbundle --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://api.example.com --dart-define=GOOGLE_SERVER_CLIENT_ID=Web客户端ID.apps.googleusercontent.com
```

iOS 只能在安装 Xcode 的 macOS 构建，并增加 iOS OAuth Client ID：

```shell
flutter build ipa --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://api.example.com --dart-define=GOOGLE_SERVER_CLIENT_ID=Web客户端ID.apps.googleusercontent.com --dart-define=GOOGLE_IOS_CLIENT_ID=iOS客户端ID.apps.googleusercontent.com
```

生产 `API_BASE_URL` 必须是包含协议和主机的 HTTPS URL。Android 模拟器本地开发默认使用 `http://10.0.2.2:8080`，iOS 模拟器默认使用 `http://127.0.0.1:8080`。

### 6.3 Nginx 与 TLS

[当前 Nginx 文件](../../joysong-server/deploy/nginx/joysong-api.conf)包含 `listen 80`、upstream、`/api/`、`/images/`、`/s/diary/` 和健康检查代理，是 HTTP 骨架，不包含 `listen 443 ssl`、证书路径或 80→443 跳转。

生产必须选择其一：

1. 在云负载均衡或网关终止 TLS，再把受控内网 HTTP 转发到该模板；
2. 自行补齐 Nginx 的 443 server block、证书与私钥、HTTP 跳转和安全策略。

部署后检查 `server_name`、`/images/` alias 与 `UPLOAD_LOCAL_DIR` 一致，确认 `APP_SHARE_BASE_URL` 对应域名的 `/s/diary/` 会进入 Spring，并先执行 `nginx -t`。在完整 TLS 配置生效前，不得把该模板视为可直接上线的 HTTPS 配置。

## 7. 本地开发

1. 将 `application-dev.example.yml` 复制为忽略提交的 `application-dev.yml`。
2. 在系统、IDE 或启动脚本中注入所需环境变量；`.env` 不会自动加载。
3. 使用 `SPRING_PROFILES_ACTIVE=dev` 启动服务端，仅连接本地隔离数据库。
4. 开发环境可关闭 OSS、短信，使用本地上传和验证码日志。
5. 启动管理端开发服务后，通过 Vite 代理访问本地 API。
6. 本地 `APP_SHARE_BASE_URL` 可保持为空：同一 Wi-Fi 真机把 Flutter `API_BASE_URL` 指向电脑 WLAN IP；使用本机反向隧道时把它指向隧道 HTTPS origin，分享链接会自动同源。

只有 `dev` profile 允许请求来源回退，且开发配置只信任本机代理传入的转发头。手机不能用 `127.0.0.1` 访问电脑；随机隧道域名变化后旧分享 URL 会失效。完整步骤见[日记分享功能开发文档](../DIARY_SHARE_FEATURE.md#配置项)。

## 8. 上线检查表

- active profiles 明确包含 `prod`，日志中的数据库主机和库名与预期一致。
- 生产数据库已预创建，`DB_URL` 不含自动建库参数，Flyway 校验通过。
- 首次管理员创建完成；`ADMIN_PASSWORD` 已移除，无密码变量重启和原密码登录均通过。
- 仅存在一个非 `ERASED` 管理员，且与 `ADMIN_PHONE` 的裸号完全一致。
- JWT、数据库、OSS、短信和模型密钥来自密钥管理系统，仓库和证据中没有明文。
- CORS 不含 `*`，API、管理端和 Flutter 均使用 HTTPS 域名。
- `UPLOAD_PRIVATE_DIR` 位于持久私有目录，未被 Nginx 暴露，且已纳入备份恢复。
- `APP_SHARE_BASE_URL` 是合法 HTTPS 分享页根地址，Nginx 已代理 `/s/diary/`；使用真实 token 的外网分享冒烟通过。
- 管理端构建、Flutter 生产构建、OSS 上传、短信、Google 登录、Agent 和 Translation 已分别验证。
- Nginx 或上游网关已真实启用 TLS；当前 HTTP 模板没有被误当成完整 HTTPS 配置。
- 数据库备份和回滚路径已验证，任何管理员改号都同步更新数据库、会话与 `ADMIN_PHONE`。
