# 上传目录、分享页与 Nginx 部署

生产环境将上传文件保存在服务器持久目录，由 Nginx 直接提供 `/images/` 静态资源，并把 API 与公开日记分享页转发到 Spring Boot。

## 环境变量

```bash
SERVER_BASE_URL=https://api.example.com
APP_SHARE_BASE_URL=https://api.example.com/s/diary/
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
```

`prod` profile 必须显式提供 `APP_SHARE_BASE_URL`。该值必须是绝对 HTTP(S) URL，不能包含账号信息、查询参数或片段；生产部署应使用真实 HTTPS 域名。代码中没有固定分享域名，也不会在生产环境回退到请求的 `Host`。

上例让分享页与 API 共用 `api.example.com`，可直接使用仓库中的 Nginx 模板。如果改用 `app.example.com` 等独立分享域名，还必须为该域名配置 DNS、TLS、`server_name`，并将 `/s/diary/` 转发到 Spring Boot；只修改环境变量不会让新域名自动可访问。

`UPLOAD_LOCAL_DIR` 必须同时满足：

- Java 进程可写入；
- Nginx 进程可读取；
- 与 `nginx/joysong-api.conf` 中 `/images/` 的 `alias` 指向同一目录。

示例权限（按实际运行用户调整）：

```bash
sudo install -d -o joysong -g www-data -m 0750 /var/lib/joysong/uploads
```

## Nginx

将 `nginx/joysong-api.conf` 安装到 Nginx 站点目录，替换域名与 TLS 配置后测试并重载。模板中的 `/api/` 覆盖公开 JSON 接口 `/api/public/diary-shares/**`，`/s/diary/` 单独转发公开 HTML 分享页：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

上传后的 URL 将是 `https://api.example.com/images/<folder>/<file>`，分享链接将是 `https://api.example.com/s/diary/<token>`。

## GitHub Actions 边界

GitHub Actions 可以构建、测试并把制品发布到一台已经准备好的云服务器，但 Actions runner 是临时执行环境，不能替代持续运行的 Spring Boot 服务、公网服务器或本地反向隧道。没有公网服务器时，可在开发电脑上运行 Cloudflare Tunnel、ngrok 等反向隧道进行临时测试；隧道停止或随机域名变化后，旧分享链接将不可用。
