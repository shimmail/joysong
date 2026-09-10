# UAT 公网 IP HTTPS 入口

> 目标 ECS：`cn-hangzhou / i-bp19abm7697mvhl0xewu / 121.41.230.98`
>
> 本页和相关自动化仅适用于当前 UAT，不得复用于 production。

## 固定边界

- App API 根地址为 `https://121.41.230.98`。
- UAT 管理后台入口为 `https://121.41.230.98/admin/`。
- 该地址仅用于当前 UAT，不能写入 production workflow 或描述为正式上线。
- Nginx 仅对公网提供 TCP 80/443；80 除 ACME HTTP-01 外均跳转 HTTPS。
- Backend 继续只监听 `127.0.0.1:8080`，MySQL 继续只监听 `127.0.0.1:3306`。
- 安全组和主机防火墙不得开放 8080、3306；公网 IP 入口不提供管理后台首页。
- 管理后台仅挂载在 `/admin/`，公网 IP 根路径继续返回 404。
- 证书是 Let's Encrypt `shortlived` IP 证书，必须由 Certbot 5.4 或更高版本自动续期。

## 启用与验证

仓库入口为 `joysong-server/deploy/host/enable-public-ip-tls.sh`，Nginx 模板为
`joysong-server/deploy/nginx/joysong-public-ip.conf.template`。执行前必须已安装 Certbot 5.4+
及其续期 timer，并准备真实 ACME webroot；脚本不会在事务中安装系统软件。脚本在任何业务
配置变更前检查模板、环境文件、Nginx、证书工具、续期机制、Admin current、服务和
8080/3306 回环监听。

脚本为本次涉及的站点、challenge、续期 hook、环境文件、dry-run 日志及服务/timer 原状态
建立 root-only 事务备份。challenge 和正式站点都先通过隔离的候选 Nginx 配置检查再切换。
任何后续失败都会恢复原文件和 unit 状态；首次新增的文件会移除。只有证书 SAN/有效期、
后端健康、HTTP 跳转、HTTPS 健康、续期 dry-run 与 hook、timer 状态全部通过后才提交成功。
重复执行时会复用当前站点引用且仍有效的受管 IP 证书；只有缺少可用证书时才以本次事务
唯一名称签发，避免每次执行累积新的短期证书续期任务。

启用前必须在受控目录备份 `/etc/nginx` 与 `/etc/joysong-demo/joysong.env`。启用后从
ECS 外部验证 HTTP 跳转、HTTPS 证书 SAN、健康检查和核心 App API，同时确认公网
8080/3306 均不可达。证书续期 hook 必须先通过 `nginx -t` 再平滑 reload。

管理后台使用子路径构建，构建命令为：

```bash
cd joysong-admin
VITE_BASE_PATH=/admin/ npm run build
```

`dist/` 由 UAT Candidate 工作流与同一提交的后端打入单一发布包，主机安装到
`/var/www/joysong-demo/releases/<tag>/`，再由 `/var/www/joysong-demo/current` 与后端
`current` 成对原子切换。公网 IP Nginx 必须始终读取该 `current` 链接，不能维护独立的
Admin 手工副本。回滚时 Backend/Admin 同步恢复。不得把管理后台改为公网 IP 根路径，
也不得为此开放后端或数据库端口。

现有 UAT 从根路径构建迁移到 `/admin/` 时，先用事务式 TLS 脚本安装读取 `current` 的公网
IP 站点，再发布首个 `/admin/` Candidate。模板暂时保留只读的根 `/assets/`、`favicon.svg`
和 `icons.svg` 兼容路由，使切换前的旧 `current` 仍可登录；发布器会通过公网 IP HTTPS
同时校验 Admin index、深链和真实 hashed JS/CSS。首个新 Candidate 验收完成且旧 previous
退役后，再单独删除这些兼容路由。

## 回滚

若签发、Nginx 校验、Backend 健康检查或外部验收失败，恢复启用前的 Nginx 和环境文件，
执行 `nginx -t` 后 reload Nginx，并重启 `joysong-demo.service` 验证本机健康。Certbot 安装
可以保留，但公网 IP 站点不能在失败状态下保持活动。

公网 IP 改变时，旧证书和旧 App 地址均不再适用。必须为新 IP 重新签发证书、更新运行
配置和 Flutter `API_BASE_URL`，然后重新构建并发布 App。

## 在另一台 Mac 构建 iOS UAT

拉取包含本配置的远端分支后，按照 [Flutter iOS Mac 构建指南](../FLUTTER_IOS_MAC_BUILD.md)
准备 Xcode、Flutter 和 CocoaPods。无需复制 Android APK；iOS 构建所需的公开参数为：

```bash
cd joysong-flutter
flutter pub get
flutter build ios --release --no-codesign \
  --dart-define=APP_ENV=uat \
  --dart-define=API_BASE_URL=https://121.41.230.98
```

该产物仍属于 UAT。签名证书、Provisioning Profile 和 Apple 账号凭据不进入 Git，需在目标
Mac 的 Xcode 中单独配置。
