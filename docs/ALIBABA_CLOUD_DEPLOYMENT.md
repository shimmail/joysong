# JoySong 阿里云部署文档

本文档用于将 JoySong 全项目部署到阿里云生产环境，覆盖后端、Flutter、静态资源、分享链接和基础运维配置。

## 1. 目标架构

推荐采用以下基线架构：

- `api.joysong.cn`：Spring Boot 后端 API
- `www.joysong.cn`：Flutter H5/前台落地页，承载日记分享页
- `admin.joysong.cn`：管理后台
- `OSS`：图片、附件、公开媒体资源
- `RDS MySQL`：业务数据库
- `ECS`：后端服务与反向代理
- `SSL 证书 + DNS`：统一 HTTPS 与域名解析

如果后续需要更强抗压能力，再逐步接入 `SLB`、`CDN` 和 `WAF`。

## 2. 阿里云资源清单

上线前建议准备：

1. 阿里云域名 1 个
2. 阿里云 DNS 解析
3. ECS 1 台
4. ApsaraDB RDS for MySQL 1 套
5. OSS Bucket 1 个
6. SSL 证书 1 张
7. RAM 子账号与最小权限策略

中国内地公网访问场景通常还需要 `ICP 备案`。

## 3. 域名规划

建议把用途拆开，避免后期混乱：

| 域名 | 用途 |
|---|---|
| `api.joysong.cn` | 后端接口 |
| `www.joysong.cn` | Flutter 前台与分享页 |
| `admin.joysong.cn` | 管理后台 |

分享链接建议走：

`https://www.joysong.cn/api/public/diary-shares/{token}`

后端当前支持通过 `app.share-base-url` 配置分享基址。

## 4. 后端部署

### 4.1 运行位置

后端部署在 ECS，建议使用：

- Java 17
- Nginx 反向代理
- Systemd 或容器服务做守护

### 4.2 运行依赖

后端生产环境至少需要：

```dotenv
DB_URL=jdbc:mysql://<rds-host>:3306/joysong?useUnicode=true&characterEncoding=utf8&useSSL=true&serverTimezone=Asia/Shanghai
DB_USERNAME=<rds-user>
DB_PASSWORD=<db-password>
JWT_SECRET=<random-strong-secret>
SERVER_BASE_URL=https://api.joysong.cn
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
CORS_ALLOWED_ORIGINS=https://www.joysong.cn,https://admin.joysong.cn
app.share-base-url=https://www.joysong.cn/api/public/diary-shares/
```

如果你计划把图片放 OSS，也还需要：

```dotenv
OSS_ENABLED=true
OSS_ENDPOINT=oss-cn-hangzhou.aliyuncs.com
OSS_BUCKET_NAME=<bucket-name>
OSS_PUBLIC_BASE_URL=https://cdn.joysong.cn
OSS_ACCESS_KEY_ID=<ram-ak>
OSS_ACCESS_KEY_SECRET=<ram-sk>
```

### 4.3 启动顺序

1. 先建 RDS 数据库。
2. 再部署后端。
3. 确认 Flyway 迁移通过。
4. 最后接入域名和 HTTPS。

### 4.4 日记分享接口

当前分享流程是：

1. Flutter 调用 `POST /api/diaries/{id}/share`
2. 后端生成可撤销、可过期的 token
3. 后端返回完整分享链接
4. Flutter 直接分享该链接

因此分享页只要对公网可访问，手机点开就能稳定访问。

## 5. 数据库部署

建议使用 `RDS for MySQL`，不要再用本机 MySQL 作为生产库。

上线建议：

1. 创建独立数据库实例
2. 创建专用业务账号
3. 关闭高权限账号直连
4. 开启备份和慢查询日志
5. 上线前先导入测试数据或空库迁移

后端启动时会执行 Flyway 迁移，生产库应先备份再升级。

## 6. OSS 与静态资源

图片和公开资源建议放 OSS，不要长期依赖 ECS 本地磁盘。

推荐配置：

- Bucket 开启公网自定义域名
- 绑定 HTTPS 证书
- 资源前缀统一，例如 `images/`

如果暂时还在本地上传模式，需要保证：

- `UPLOAD_LOCAL_DIR` 可写
- Nginx 能读取该目录
- `/images/` 路由正确指向该目录

## 7. Flutter 发布

Flutter 生产构建必须指向正式 HTTPS 域名：

```bash
flutter build appbundle --release --dart-define=APP_ENV=production --dart-define=API_BASE_URL=https://api.joysong.cn
```

如果是 H5 或 Web 作为分享落地页，也要保证前端页面使用同一正式域名体系。

## 8. HTTPS 与反向代理

推荐用 Nginx 统一处理：

- 80 跳转 443
- `/api/` 转发到 Spring Boot
- `/images/` 转发到上传目录或 OSS 反代
- `/api/public/diary-shares/` 转到公开分享内容

生产环境不要裸露 Spring Boot 端口到公网。

## 9. 部署顺序

推荐按这个顺序执行：

1. 注册域名
2. 完成备案
3. 申请 SSL 证书
4. 创建 RDS
5. 配置 OSS
6. 部署后端
7. 部署前台/分享页
8. 配置 DNS
9. 验证分享链接和移动端访问

## 10. 上线验收

至少验证以下内容：

- `https://api.joysong.cn/actuator/health` 正常
- `POST /api/diaries/{id}/share` 正常返回链接
- 手机浏览器可打开 `https://www.joysong.cn/api/public/diary-shares/{token}`
- 图片可正常加载
- 登录、发布、评论、分享撤销流程正常

## 11. 后续建议

- 给分享页单独做缓存策略
- 生产环境引入 WAF
- 给 OSS 和 API 统一做 CDN 加速
- 将敏感配置接入阿里云密钥托管或企业级密钥服务
