# JoySong 部署配置字典（主指南附录）

> 文档状态：[部署与发布主指南](./README.md)的字段附录，不构成第二套部署流程
> 适用环境：Dev、UAT、Prod
> 最后核验：2026-09-04
> 安全要求：本文只记录变量名、语义和示例格式，绝不记录真实密码、Token、手机号或 AccessKey。

## 1. 配置来源与优先级

服务端配置按 Spring Boot 标准优先级加载。线上环境以 ECS root-owned 环境文件为运行时来源，仓库文件只提供键名、默认值和安全约束。

| 来源 | 用途 | 是否可含秘密 |
| --- | --- | --- |
| `application.yml` | 公共默认值和变量绑定 | 否 |
| `application-dev.yml` | 本地开发默认值 | 只允许明确的本地假值 |
| `application-demo.yml` | UAT/Demo 强安全边界 | 否 |
| `application-prod.yml` | Prod 强安全边界 | 否 |
| ECS `/etc/joysong/<env>/joysong.env` | 线上运行时配置 | 是，root-owned `0640` |
| GitHub Repository Variables | UAT 公网 origin 与全局 Prod 开关 | 否 |
| GitHub Repository Secrets | UAT Android 签名材料 | 仅构建秘密 |
| GitHub Environment Variables | 每个环境独立的阿里云资源标识 | 否 |
| GitHub `production` Environment Secrets | Prod Android 签名材料 | 仅构建秘密 |
| Flutter `--dart-define` | 客户端公开构建值 | 否 |

禁止将 ECS 环境文件、数据库导出、keystore、私钥或真实凭据提交到 Git。开发机和 CI 临时生成的签名文件必须被忽略并在构建后删除。

## 2. 环境标识

| 变量/接口 | Dev | UAT | Prod | 说明 |
| --- | --- | --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` | `demo` | `prod` | UAT 继续复用安全 `demo` profile |
| Flutter `APP_ENV` | `development` | `uat` | `production` | `staging` 仅作为 `uat` 兼容别名 |
| Android flavor | 开发默认 | `uat` | `prod` | Native flavor 与 Dart 环境必须同时正确 |
| `TZ` | `Asia/Shanghai` | `Asia/Shanghai` | `Asia/Shanghai` | 主机、JVM 和数据库时区保持一致 |

`demo` 不得与 `dev` 或 `prod` 同时激活。Prod 部署脚本只接受精确的 `prod`，不得用字符串包含或默认回退判断环境。

## 3. 服务与公网地址

| 变量 | 敏感 | UAT | Prod | 约束 |
| --- | --- | --- | --- | --- |
| `SERVER_ADDRESS` | 否 | 必填 | 必填 | 固定 `127.0.0.1`，禁止直接监听公网 |
| `SERVER_PORT` | 否 | `8081` | `8080` | 两环境不得共用端口 |
| `SERVER_BASE_URL` | 否 | 必填 | 必填 | 绝对 HTTPS URL，无路径凭据、查询或片段 |
| `APP_SHARE_BASE_URL` | 否 | 必填 | 必填 | 指向 `/s/diary/` 根路径的绝对 HTTPS URL |
| `CORS_ALLOWED_ORIGINS` | 否 | 必填 | 必填 | 逗号分隔的精确 HTTPS origin，禁止 `*` |
| `SERVER_FORWARD_HEADERS_STRATEGY` | 否 | `native` | `native` | 信任受控 Nginx 转发头 |
| `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` | 否 | 必填 | 必填 | 只匹配本机反向代理 |
| `SERVER_TOMCAT_REMOTEIP_REMOTE_IP_HEADER` | 否 | `X-Forwarded-For` | 同左 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_PROTOCOL_HEADER` | 否 | `X-Forwarded-Proto` | 同左 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_HOST_HEADER` | 否 | `X-Forwarded-Host` | 同左 | 与 Nginx 一致 |
| `SERVER_TOMCAT_REMOTEIP_PORT_HEADER` | 否 | `X-Forwarded-Port` | 同左 | 与 Nginx 一致 |

当前 UAT 从 HTTP 迁移到 HTTPS 时，`SERVER_BASE_URL`、`APP_SHARE_BASE_URL`、`CORS_ALLOWED_ORIGINS`、Nginx、DNS、证书和 Flutter `API_BASE_URL` 必须作为同一变更发布。

## 4. 数据库与 Flyway

| 变量 | 敏感 | UAT | Prod | 约束 |
| --- | --- | --- | --- | --- |
| `DB_URL` | 部分 | 必填 | 必填 | JDBC MySQL URL；不得含 `createDatabaseIfNotExist` |
| `DB_USERNAME` | 是 | 必填 | 必填 | UAT/Prod 独立账号 |
| `DB_PASSWORD` | 是 | 必填 | 必填 | 只在 ECS 环境文件和受控测试会话中出现 |
| `DEMO_DATABASE_NAME` | 否 | 必填 | 禁止 | 必须与 JDBC 和 `SELECT DATABASE()` 一致 |

UAT 数据库名必须以 `myapp_worktree_` 开头。迁移或 Demo 数据操作前必须输出数据库主机、数据库名和环境，并由操作者确认。

线上固定 Flyway 约束：

| Spring 属性 | UAT / Prod |
| --- | --- |
| `spring.jpa.hibernate.ddl-auto` | `validate` |
| `spring.flyway.enabled` | `true` |
| `spring.flyway.validate-on-migrate` | `true` |
| `spring.flyway.baseline-on-migrate` | `false` |
| `spring.flyway.clean-disabled` | `true` |

任何迁移变化都必须在新建的空白隔离数据库上验证。UAT 重建使用新安全前缀数据库；Prod 恢复到新 RDS，均不得原地 clean/reset。

## 5. JWT、管理员与认证

| 变量 | 敏感 | UAT | Prod | 约束 |
| --- | --- | --- | --- | --- |
| `JWT_SECRET` | 是 | 必填 | 必填 | 至少 32 字符，环境隔离并支持轮换 |
| `ADMIN_PHONE` | 是 | 必填 | 必填 | 裸 11 位中国大陆号码，不含 `+86` |
| `ADMIN_PASSWORD` | 是 | 仅首次空库 | 仅首次空库 | 12–128 字符；创建后立即移除 |
| `GOOGLE_CLIENT_ID` | 否 | 不设置 | 启用 Google 时必填 | UAT 关闭 Google 登录 |
| `GOOGLE_PROXY_URL` | 否 | 不设置 | 可选 | 仅服务端受控出站代理 |
| `SMS_ENABLED` | 否 | `false` | 验收后决定 | UAT 禁止短信 |

UAT App 只显示密码登录。验证码、注册、找回密码和 Google 登录即使有底层代码，也不得由 UAT UI 暴露；服务端 SMS 和 Google 配置同时保持关闭。

<a id="admin-bootstrap"></a>

### 5.1 首次管理员初始化

仅当 Flyway 完成且 `users=0` 时临时提供 `ADMIN_PASSWORD`：

1. 记录实际数据库主机、数据库名和 active profile。
2. 确认 `ADMIN_PHONE` 是裸 11 位号码，临时密码长度为 12–128 字符。
3. 只启动一个后端实例，等待管理员创建。
4. 使用 `ADMIN_PHONE` 和首次密码验证 Admin 登录。
5. 记录管理员 userId、角色、状态、账号总数和密码哈希指纹；不得记录密码或完整哈希。
6. 停止应用，从环境文件彻底移除 `ADMIN_PASSWORD`。
7. 使用同一数据库重启，确认管理员信息和密码哈希未变化，原密码仍可登录。

首次管理员固定为 `ACTIVE + ADMIN`，昵称为“系统管理员”，不自动获得医生、顾问或机构法人身份。再次设置 `ADMIN_PASSWORD` 不会重置已存在管理员的密码。

| 配置和数据状态 | 结果 |
| --- | --- |
| `users=0` 且手机号、首次密码合法 | 创建唯一固定管理员 |
| `users=0` 但首次密码缺失或长度不合法 | 拒绝启动 |
| `ADMIN_PHONE` 不是裸 11 位号码 | 拒绝启动 |
| 已有匹配 `ACTIVE + ADMIN` 且密码哈希非空 | 只验证，不写入 |
| 非空用户表缺少配置号码或只改了 `ADMIN_PHONE` | 拒绝启动 |
| 配置号码属于普通、暂停、注销或无密码账号 | 拒绝启动 |
| 固定号码或 `+86` 等价号被其他非 ERASED 账号占用 | 拒绝启动 |
| 存在第二个非 ERASED 管理员 | 拒绝启动 |

<a id="admin-phone-rotation"></a>

### 5.2 已创建管理员的停机改号

产品目前没有在线管理员改号或角色转移接口。确需更换时必须进入维护窗口、停止所有连接目标库的实例并创建可恢复备份。

新裸号及其 `+86` 等价号必须在所有非 ERASED 账号中无人占用。先记录固定管理员 userId，再在同一个持续数据库 session 中执行：

```sql
SET @old_phone = '替换为旧裸号';
SET @new_phone = '替换为新裸号';
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

`admin_rows_updated` 不等于 `1` 时只执行 `ROLLBACK;`。等于 `1` 时，在同一 session 撤销全部刷新令牌并提交：

```sql
UPDATE refresh_tokens
SET revoked_at = COALESCE(revoked_at, NOW())
WHERE user_id = @admin_id;

COMMIT;
```

随后在应用仍停止时更新 `ADMIN_PHONE`，不得设置 `ADMIN_PASSWORD`。启动一个实例并验证：

- userId、密码哈希指纹、昵称、角色和状态保持不变；
- 新号码加原密码登录成功，旧号码失败；
- 改号前 access token 和 refresh token 失效；
- 再次无 `ADMIN_PASSWORD` 重启仍通过校验。

失败回滚必须同时恢复数据库号码与 `ADMIN_PHONE`。若新号码阶段已启动或登录，还要再次更新 `credentials_updated_at` 并撤销全部 refresh token。

## 6. 公共与私有文件

| 变量 | 敏感 | UAT | Prod | 约束 |
| --- | --- | --- | --- | --- |
| `UPLOAD_LOCAL_DIR` | 否 | 必填 | 按存储方案 | 公共图片本地目录 |
| `UPLOAD_STAGING_DIR` | 否 | 必填 | 必填 | 暂存目录，不得由 Nginx 暴露 |
| `UPLOAD_PRIVATE_DIR` | 否 | 必填 | 必填 | 身份/退款等私有文件，禁止公网暴露 |
| `UPLOAD_IDEMPOTENCY_CLEANUP_DELAY_MS` | 否 | 可选 | 可选 | 上传幂等占位清理延时 |
| `PRIVATE_FILE_STORAGE_MODE` | 否 | `local` | `local` 或经批准的 `oss` | 私有存储模式 |

当前 UAT 已启用 OSS：新公共图片通过 ECS RAM Role 写入 UAT Bucket，`/images/` 只兼容历史 `UPLOAD_LOCAL_DIR` URL。`UPLOAD_PRIVATE_DIR` 和 staging 目录必须使用独立权限并纳入备份，绝不配置 Nginx alias。

OSS 配置：

| 变量 | 敏感 | 约束 |
| --- | --- | --- |
| `OSS_ENABLED` | 否 | 当前 UAT 为 `true`；其他环境按能力显式设置 |
| `OSS_ENDPOINT` | 否 | 必须是带 `https://` 的合法 Endpoint |
| `OSS_REGION` | 否 | 必须与 Bucket 地域一致 |
| `OSS_CREDENTIAL_MODE` | 否 | 当前 UAT 固定 `ecs-ram-role`，兼容模式才用 `static` |
| `OSS_ECS_RAM_ROLE_NAME` | 否 | `ecs-ram-role` 时必填 |
| `OSS_ACCESS_KEY_ID` | 是 | 仅 `static` 模式；不得进入客户端 |
| `OSS_ACCESS_KEY_SECRET` | 是 | 仅 `static` 模式；不得进入客户端 |
| `OSS_BUCKET_NAME` | 否 | 公共业务图片 Bucket |
| `OSS_PRIVATE_BUCKET_NAME` | 否 | 私有 OSS 模式使用，不得走公共 CDN |
| `OSS_PUBLIC_BASE_URL` | 否 | 公共图片 HTTPS 根地址 |
| `OSS_CONNECTION_TIMEOUT_MS` | 否 | 默认 5000 |
| `OSS_SOCKET_TIMEOUT_MS` | 否 | 默认 30000 |
| `OSS_REQUEST_TIMEOUT_MS` | 否 | 默认 60000 |
| `OSS_MAX_ERROR_RETRY` | 否 | 默认 1 |
| `OSS_MAX_CONNECTIONS` | 否 | 默认 32 |

发布 Bucket 与业务图片 Bucket 是两个权限域。GitHub 发布身份不得写业务 Bucket，应用运行身份不得写发布 Bucket。

## 7. AI 与翻译

| 变量 | 敏感 | UAT | Prod |
| --- | --- | --- | --- |
| `AI_AGENT_PROVIDER` | 否 | `qwen` | 经验收 provider |
| `AI_AGENT_API_KEY` | 是 | 必填 | 必填 |
| `AI_AGENT_BASE_URL` | 否 | HTTPS | HTTPS |
| `AI_AGENT_MODEL` | 否 | 必填 | 必填 |
| `AI_AGENT_INTENT_MODEL` | 否 | 必填 | 必填 |
| `TRANSLATION_PROVIDER` | 否 | `qwen` | 经验收 provider |
| `TRANSLATION_API_KEY` | 是 | 必填 | 必填 |
| `TRANSLATION_BASE_URL` | 否 | HTTPS | HTTPS |
| `TRANSLATION_MODEL` | 否 | 必填 | 必填 |

健康检查不代表 AI 或翻译真实可用。UAT 验收必须分别发起一次公网调用，并配置额度、错误率和费用告警。

## 8. SMS

| 变量 | 敏感 | UAT | Prod |
| --- | --- | --- | --- |
| `SMS_ENABLED` | 否 | `false` | 审核和验收后才可为 `true` |
| `SMS_ACCESS_KEY_ID` | 是 | 不设置 | 启用时必填 |
| `SMS_ACCESS_KEY_SECRET` | 是 | 不设置 | 启用时必填 |
| `SMS_SIGN_NAME` | 否 | 不设置 | 启用时必填 |
| `SMS_TEMPLATE_CODE` | 否 | 不设置 | 启用时必填 |

短信身份必须与 OSS、GitHub 发布身份隔离。虚构 Demo 号码不得用于真实发送。

## 9. 支付安全门

| 变量 | Dev | UAT | Prod |
| --- | --- | --- | --- |
| `ALIPAY_PLUS_SIMULATED_ENABLED` | 可为 true | `true` | 强制 `false` |
| `ALIPAY_PLUS_AUTO_PAY_ON_ORDER_CREATE_ENABLED` | 可为 true | 强制 `false` | 强制 `false` |
| `PAYMENT_RECONCILIATION_ENABLED` | 按开发需要 | 强制 `false` | 真实渠道完成后再决定 |
| `STRIPE_LEGACY_ENABLED` | 默认 false | 强制 `false` | 当前强制 `false` |

UAT 模拟能力仅允许手工创建支付尝试和管理员批准后的全额退款。自动支付、部分退款、真实支付、主动查询、Webhook、对账和失败注入均不属于 UAT。

Prod 采用三层门禁：

1. 模拟网关 profile 永不包含 `prod`。
2. Prod 启动校验发现模拟或自动支付为 true 时拒绝启动。
3. ECS 部署 dispatcher 再校验 profile 和两个开关。

legacy Stripe 当前仍存在开启后创建新 Checkout 的代码风险。本期不修改该代码，因此 `STRIPE_LEGACY_ENABLED` 保持 false，并阻断交易型 Prod。

## 10. Demo 数据

| 变量 | UAT | 说明 |
| --- | --- | --- |
| `DEMO_DATA_ENABLED` | 常规服务固定 `false` | 防止启动时重复执行 |
| `DEMO_DATA_ACTION` | 临时 `APPLY` 或 `VERIFY` | 首次人工 Apply，发布只 Verify |
| `DEMO_CATALOG_PATH` | 临时绝对路径 | 指向本次批准 catalog |
| `DEMO_ACCOUNT_PASSWORD` | 临时秘密 | 只在 Apply/验收说明生成会话中使用 |

Apply/Verify 前必须再次通过数据库安全门。文档不固定 catalog 数量、版本或 SHA；这些事实写入 Release manifest 和验收 Issue。

## 11. Flutter 构建参数

| 参数 | UAT | Prod | 是否可进 APK |
| --- | --- | --- | --- |
| `APP_ENV` | `uat` | `production` | 是 |
| `API_BASE_URL` | UAT HTTPS API | Prod HTTPS API | 是 |
| `GOOGLE_SERVER_CLIENT_ID` | 不传 | 启用 Google 时传公开 Web Client ID | 是 |
| `GOOGLE_IOS_CLIENT_ID` | 不传 | iOS 启用时传公开 Client ID | 是 |

UAT Android 固定：

- flavor `uat`；
- applicationId `com.joysong.app.uat`；
- Deep Link `joysong-uat`；
- UAT 专用 keystore；
- universal APK，不拆 ABI。

Prod 对应 flavor `prod`、applicationId `com.joysong.app`、Deep Link `joysong` 和独立 Prod keystore。

APK 绝不能包含数据库、JWT、OSS/SMS AccessKey、AI/翻译 Key、GitHub Token 或 TLS 私钥。

## 12. GitHub 配置

GitHub Environment 名称必须精确为 `uat` 和 `production`。同名 Environment Variable 会覆盖仓库级同名值，因此阿里云变量不要在 Repository Variables 中再配置一份“默认值”。

### 12.1 Repository Variables

| 变量 | 值/用途 |
| --- | --- |
| `UAT_API_BASE_URL` | 当前 `https://api.joyingsong.net`；迁移后 `https://api-uat.joyingsong.net` |
| `UAT_ADMIN_BASE_URL` | 当前 `https://joyingsong.net`；迁移后 `https://uat.joyingsong.net` |
| `PROD_DEPLOY_ENABLED` | 默认且当前必须为 `false`；Prod 第一层门禁 |

两个 UAT URL 必须作为批准的一组同时变更。仅允许上述精确 HTTPS origin：不带凭据、自定义端口、路径、查询或片段，不能把当前 API 与未来 Admin 混成一组。

### 12.2 Environment Variables

以下键分别配置在 `uat` 和 `production` Environment；键名相同，值必须指向各自独立资源：

| 变量 | 格式/用途 |
| --- | --- |
| `ALIYUN_REGION` | ECS、OSS 和命令所在地域，例如 `cn-hangzhou` |
| `ALIYUN_OIDC_PROVIDER_ARN` | `acs:ram::<account-id>:oidc-provider/<provider-name>` |
| `ALIYUN_OIDC_ROLE_ARN` | 对应环境的最小权限 RAM Role，UAT/Prod 不得相同 |
| `ALIYUN_ECS_INSTANCE_ID` | 对应环境的唯一 ECS 实例 ID |
| `ALIYUN_RELEASE_BUCKET` | Private、业务图片之外的发布 Bucket 名 |
| `ALIYUN_RELEASE_PREFIX` | JoySong 发布根前缀；工作流再附加 `/uat` 或 `/prod` |

仅在 `production` Environment 增加：

| 变量 | 格式/用途 |
| --- | --- |
| `ALIYUN_PROD_RDS_INSTANCE_ID` | 与 `/etc/joysong/prod/rds-binding.env` 和应用 `DB_URL` 同时校验的 Prod RDS 实例 ID |

所有值都不得含凭据。OIDC provider 固定使用 issuer `https://token.actions.githubusercontent.com` 和 audience/client ID `github-actions`。Role 信任策略中的 `sub` 必须来自当前仓库 OIDC token 的实际值并绑定精确 Environment；仓库创建、转移或重命名后重新核对，不要照抄一个可能已过期的 subject 字符串。

### 12.3 Repository Secrets（仅 UAT Android）

| Secret | 用途 |
| --- | --- |
| `ANDROID_UAT_KEYSTORE_BASE64` | UAT keystore 的 base64 |
| `ANDROID_UAT_STORE_PASSWORD` | UAT store 密码 |
| `ANDROID_UAT_KEY_ALIAS` | UAT alias |
| `ANDROID_UAT_KEY_PASSWORD` | UAT key 密码 |

UAT APK 构建 job 不进入 `uat` Environment，因此这四项当前必须放 Repository Secrets。仓库访问权限应限制到最小；不得复用 Prod 证书或密码。

### 12.4 `production` Environment Secrets

| Secret | 用途 |
| --- | --- |
| `ANDROID_PROD_KEYSTORE_BASE64` | Prod keystore 的 base64 |
| `ANDROID_PROD_STORE_PASSWORD` | Prod store 密码 |
| `ANDROID_PROD_KEY_ALIAS` | Prod alias |
| `ANDROID_PROD_KEY_PASSWORD` | Prod key 密码 |

Prod 构建 job 明确进入 `production` Environment，必须等审批通过后才能读取这些秘密。阿里云部署不得配置长期 `ALIYUN_ACCESS_KEY_ID` / `ALIYUN_ACCESS_KEY_SECRET`；OIDC 失败时停止发布，不能降级到仓库长期密钥。

## 13. Prod 部署开关

Prod 必须同时满足：

```text
GitHub variable: PROD_DEPLOY_ENABLED=true
ECS sentinel: /etc/joysong/prod/DEPLOY_ENABLED
```

哨兵由 root 创建，普通部署用户无写权限。缺少任一条件、稳定 tag 不是已验收 UAT 的同一 commit、RDS 备份未完成或支付阻断项未解除时，Prod 工作流必须在切换版本前失败。

## 14. 配置变更维护规则

新增、删除或修改任一环境变量时，同一 Pull Request 必须：

1. 更新实际 `application*.yml` 或 Flutter 配置绑定。
2. 更新相关启动/部署校验和测试。
3. 更新本配置参考中的用途、敏感性和环境要求。
4. 若改变发布或验收行为，同时更新 `README.md`。

不得通过把真实值写进示例文件来“修复”缺失配置。
