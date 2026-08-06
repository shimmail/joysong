# 上传目录与 Nginx 部署

生产环境将上传文件保存在服务器持久目录，并由 Nginx 直接提供 `/images/` 静态资源。

## 环境变量

```bash
SERVER_BASE_URL=https://api.example.com
UPLOAD_LOCAL_DIR=/var/lib/joysong/uploads
```

`UPLOAD_LOCAL_DIR` 必须同时满足：

- Java 进程可写入；
- Nginx 进程可读取；
- 与 `nginx/joysong-api.conf` 中 `/images/` 的 `alias` 指向同一目录。

示例权限（按实际运行用户调整）：

```bash
sudo install -d -o joysong -g www-data -m 0750 /var/lib/joysong/uploads
```

## Nginx

将 `nginx/joysong-api.conf` 安装到 Nginx 站点目录，替换域名与 TLS 配置后测试并重载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

上传后的 URL 将是 `https://api.example.com/images/<folder>/<file>`。
