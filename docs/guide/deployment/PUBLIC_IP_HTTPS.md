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
复用证书前同时验证有效期、IP SAN、私钥公钥匹配和 webroot 续期配置；不满足时以事务
唯一名称重新签发。当前 Nginx 已因私钥丢失等原因无法通过校验时，先人工恢复报错文件。
回滚使用有截止时间的健康等待；未改环境或服务的失败不会额外重启后端。
脚本与发布器共用 `/var/lib/joysong-deploy/state/deploy.lock`，禁止并发切换。

启用前必须在受控目录备份 `/etc/nginx` 与 `/etc/joysong-demo/joysong.env`。启用后从
ECS 外部验证 HTTP 跳转、HTTPS 证书 SAN、健康检查和核心 App API，同时确认公网
8080/3306 均不可达。证书续期 hook 必须先通过 `nginx -t` 再平滑 reload。

管理后台使用子路径构建，构建命令为：

```bash
cd joysong-admin
VITE_BASE_PATH=/ npm run build
VITE_BASE_PATH=/admin/ npm run build -- --outDir dist/admin
```

`dist/index.html` 是旧根入口构建，`dist/admin/index.html` 是公网子路径构建；两者使用
同一提交，全部纳入现有 manifest 和文件哈希。`dist/` 与后端打入单一发布包，主机安装到
`/var/www/joysong-demo/releases/<tag>/`，再由 `/var/www/joysong-demo/current` 与后端
`current` 成对原子切换。公网 IP Nginx 必须始终读取该 `current` 链接，不能维护独立的
Admin 手工副本。回滚时 Backend/Admin 同步恢复。不得把管理后台改为公网 IP 根路径，
也不得为此开放后端或数据库端口。

首次部署顺序是：bootstrap → 双构建 Candidate → 本机 HTTP Host 页面/深链/资源验收 →
单独启用 TLS → 外部业务验收。首次启动与迁移不等待证书签发。

已有 UAT 先发布双构建过渡版本；若 previous 仍是旧单构建，必须再发布一次兼容版本。
TLS 脚本会检查 current 和存在的 previous 的两份页面及引用资源完整后才切换站点。
原有无新标记的公网 IP 站点作为明确的 legacy 过渡状态：保留站点，验证其配置、HTTPS
可达性和哈希不变，Candidate 先按旧 HTTP Host 入口验收，不能宣称新入口已接管。
legacy 仅接受原 `public-ip-ui` 独立目录站点。已安装旧 eba4a355 的 current 站点但没有标记时，
必须在受控维护中从备份恢复原独立站点后进行上述过渡；不得伪造标记跳过兼容检查。

切换成功后原子写入 `/etc/joysong-demo/public-ip-tls.enabled`（`root:root 0600`，精确内容
为 `enabled` 加换行）。之后每次发布同时校验本机 HTTP 根入口和 HTTPS `/admin/` 页面、
深链、真实资源哈希；站点或证书异常一律阻断，不降级 HTTP。公网站点及启用标记也纳入
发布前后配置摘要检查。不得手工删除标记绕过检查。

新 Nginx 的 `/admin/` 使用 `root /var/www/joysong-demo/current`，读取 `current/admin/`；
公网根目录及根 `/assets/` 不提供旧 SPA。回滚仅切换同一对后端/后台 current，两个版本均
含 `/admin/` 构建，公网入口保持不变。两份构建和旧受控入口暂时保留，退出兼容另开任务。

## 回滚

TLS 事务的原文件备份使用 `original-` 前缀，与候选 hook、环境文件及启用标记分开保存，
防止渲染候选配置覆盖恢复依据。任何恢复不完整都会保留备份目录并返回失败。

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

## PR #16 本地修复验证记录

- 双构建 TypeScript/Vite 编译通过，根与 `/admin/` 的 HTML 资源路径分别正确。
- 部署测试套件运行 109 项：103 通过，6 项因 Windows 缺少 Linux/POSIX 能力跳过。
- 后续新增 TLS main 故障矩阵单独通过：候选校验、reload、续期、环境安装、服务重启、
  标记安装失败均恢复原文件；成功与重复执行通过。该测试另发现并修复备份同名覆盖问题。
- 双构建发布包由真实打包器生成并通过主机校验器展开，两份页面和资源字节一致。
- 真实 Chrome 验证两种 base 的登录、深链接/query/hash、刷新和会话失效跳转通过。
  使用本机静态服务及模拟 API，不代表真实 Nginx 或后端业务验收。
- ShellCheck、Bash 语法与 workflow validator 已通过；没有运行迁移或连接真实数据库。
- Docker daemon 未运行，真实 Linux Nginx/systemd/MySQL 全链路和跳过项待隔离环境验收。
  此前 GitHub infrastructure 失败日志读取返回 403，未宣称远端 CI 已修复或重新通过。
- 本记录仅代表本地修复验证；未部署、推送或合并。
