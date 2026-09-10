# UAT 公网 IP HTTPS 入口

> 目标 ECS：`cn-hangzhou / i-bp19abm7697mvhl0xewu / 121.41.230.98`

## 固定边界

- App API 根地址为 `https://121.41.230.98`。
- 该地址仅用于当前 UAT，不能写入 production workflow 或描述为正式上线。
- Nginx 仅对公网提供 TCP 80/443；80 除 ACME HTTP-01 外均跳转 HTTPS。
- Backend 继续只监听 `127.0.0.1:8080`，MySQL 继续只监听 `127.0.0.1:3306`。
- 安全组和主机防火墙不得开放 8080、3306；公网 IP 入口不提供管理后台首页。
- 证书是 Let's Encrypt `shortlived` IP 证书，必须由 Certbot 5.4 或更高版本自动续期。

## 启用与验证

仓库入口为 `joysong-server/deploy/host/enable-public-ip-tls.sh`，Nginx 模板为
`joysong-server/deploy/nginx/joysong-public-ip.conf.template`。脚本执行前拒绝非回环的
8080/3306 监听，先用 staging 签发验证链路，再签发正式证书；成功后更新公开 origin、
重启 Backend，并执行续期 dry-run。

启用前必须在受控目录备份 `/etc/nginx` 与 `/etc/joysong-demo/joysong.env`。启用后从
ECS 外部验证 HTTP 跳转、HTTPS 证书 SAN、健康检查和核心 App API，同时确认公网
8080/3306 均不可达。证书续期 hook 必须先通过 `nginx -t` 再平滑 reload。

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
