# 日记分享功能开发文档

本文档记录 Joysong 日记分享功能的当前实现、配置方式、测试方法和上线注意事项。

## 目标

日记分享需要满足：

- 登录用户可以为自己的日记生成分享链接。
- 分享链接可过期、可撤销。
- 未登录用户可以直接打开分享页查看公开内容。
- 网页预览只展示前三张图片。
- 点击图片后可全屏循环查看全部图片。
- 页面底部提供“在 App 中打开”按钮。
- App 已安装时跳转到 App 内分享详情；未安装时保留网页预览。

## 链路概览

```text
App 分享按钮
  -> POST /api/diaries/{id}/share
  -> 后端创建或复用分享 token
  -> 返回 /s/diary/{token} 网页分享链接
  -> 用户在浏览器打开网页分享页
  -> 点击“在 App 中打开”
  -> joysong://s/diary/{token}
  -> App 解析 token 并打开公开日记分享页
```

## 后端接口

### 创建分享链接

```http
POST /api/diaries/{id}/share
Authorization: Bearer {accessToken}
Content-Type: application/json
```

请求体可为空，也可传过期时间配置。

返回数据包含分享链接和 token。显式配置 `app.share-base-url` 时始终使用该地址；仅在 `dev` profile、该配置为空且开发回退开关启用时，才从当前 API 请求的协议与主机生成分享链接。

### 撤销分享链接

```http
DELETE /api/diaries/{id}/share
Authorization: Bearer {accessToken}
```

用于让用户撤销已创建的日记分享。

### 公开 JSON 接口

```http
GET /api/public/diary-shares/{token}
```

该接口允许未登录访问，返回公开日记数据。它用于 App 内公开分享页读取数据，也可用于调试。

### 公开网页分享页

```http
GET /s/diary/{token}
```

该接口允许未登录访问，返回 HTML 页面。它是对外分享时推荐使用的链接。

## 网页分享页行为

网页分享页由后端渲染，当前特性：

- 移动端优先布局。
- 标题、作者、发布时间、评分、点赞、评论、关联项目/医生/机构、标签和正文。
- 图片预览最多展示三张。
- 图片来源按普通图片、术前图片、术后图片顺序合并。
- 点击任意预览图进入全屏图片查看器。
- 全屏查看器支持：
  - 左右滑动切换；
  - 上一张/下一张按钮；
  - 键盘左右键；
  - 循环浏览全部图片。
- 底部悬浮操作块包含：
  - “在 App 中打开”；
  - 叉号关闭按钮。
- 点击叉号只隐藏底部操作块，不关闭网页、不返回上一页。

## App 打开链接

网页中的 App 打开链接格式：

```text
joysong://s/diary/{token}
```

Android 侧需要在 `AndroidManifest.xml` 中注册 scheme：

```xml
<data
    android:scheme="joysong"
    android:host="s"
    android:pathPrefix="/diary" />
```

Flutter 启动时读取 `defaultRouteName`，解析 `/s/diary/{token}` 或 `joysong://s/diary/{token}`，然后进入公开日记分享页。

注意：修改 Android 深链配置后，必须重新构建并安装 APK。旧 APK 不会自动支持新的 scheme。

## 配置项

分享地址遵循以下优先级：

1. 非空的 `APP_SHARE_BASE_URL` 始终优先。
2. 仅 `dev` profile 允许在该变量为空时使用当前 API 请求 origin。
3. `prod` 禁止请求来源回退；`APP_SHARE_BASE_URL` 缺失、为空或不是合法绝对 HTTP(S) URL 时，服务启动失败。

修改配置或代理地址后应重启后端并重新点击分享。已经复制出去的旧 URL 不会自动替换域名。

### 同一 Wi-Fi 真机测试

如果电脑局域网 IP 是：

```text
192.168.2.54
```

保持后端使用 `dev` profile，且不要设置 `APP_SHARE_BASE_URL`。Flutter 与分享链接共用电脑 WLAN 地址：

```powershell
flutter run --dart-define=APP_ENV=development --dart-define=API_BASE_URL=http://192.168.2.54:8080
```

创建分享的 API 请求经过该地址后，后端会返回：

```text
http://192.168.2.54:8080/s/diary/{token}
```

手机需要与电脑处于同一 Wi-Fi，并且 Windows 防火墙允许访问后端端口 `8080`。

手机上的 `127.0.0.1` 指向手机自身，不能用来访问电脑。`adb reverse` 只方便当前连接的 Android 设备访问开发机，也不能让其他人从外部打开分享链接。

### 临时公网代理或反向隧道

更换 Wi-Fi 后仍需从外部打开时，应使用能把公网 HTTPS 请求反向转发到本机 `http://127.0.0.1:8080` 的隧道，例如 Cloudflare Tunnel 或 ngrok。普通 Clash、V2Ray 等出站代理地址不是可供别人访问的分享地址。

使用步骤：

1. 后端以 `SPRING_PROFILES_ACTIVE=dev` 启动，保持 `APP_SHARE_BASE_URL` 未设置。
2. 在同一台电脑上启动反向隧道，并将它指向 `http://127.0.0.1:8080`。
3. Flutter 使用同一个公网代理 origin：

```powershell
flutter run --dart-define=APP_ENV=development --dart-define=API_BASE_URL=https://your-tunnel.example
```

4. 重新创建分享，返回地址应为 `https://your-tunnel.example/s/diary/{token}`。

开发配置只信任来自本机 loopback 的转发头。如果代理运行在 Docker 或另一台机器上，不要直接扩大可信网段；优先显式设置 `APP_SHARE_BASE_URL`，或在确认代理会清洗并重写转发头后再调整可信代理范围。

临时隧道必须持续运行。免费随机域名变化后，旧分享 URL 会失效；需要长期稳定分享时，应使用固定隧道域名或正式云部署。GitHub Actions 可以负责构建和发布，但不能充当持续运行的公网隧道或服务器。

### 生产环境

生产环境通过部署变量配置，不在代码或 YAML 中写死域名：

```dotenv
SPRING_PROFILES_ACTIVE=prod
APP_SHARE_BASE_URL=https://api.example.com/s/diary/
```

`APP_SHARE_BASE_URL` 可以使用 API 域名，也可以使用独立分享域名。使用独立域名时，必须同时配置对应 DNS、TLS、Nginx `server_name` 和 `/s/diary/` 转发。

需要确保：

- 域名已解析到公网服务器或负载均衡。
- HTTPS 证书可用。
- 网关或反向代理把 `/s/diary/**` 和 `/api/public/diary-shares/**` 转发到后端服务。
- 安全配置允许公开访问上述两个路径。

## 阿里云部署建议

推荐组合：

- 域名：阿里云域名服务。
- 备案：中国大陆服务器需要 ICP 备案。
- HTTPS：阿里云数字证书服务或 ALB/SLB 托管证书。
- 服务承载：
  - 初期：ECS + Nginx + Spring Boot。
  - 稳定后：ALB + ECS/ACK。
- 图片资源：OSS + CDN。
- 数据库：RDS MySQL。

生产 Nginx 以 [`joysong-server/deploy/nginx/joysong-api.conf`](../joysong-server/deploy/nginx/joysong-api.conf) 为准。该模板由 `/api/` 覆盖公开 JSON 接口，并通过独立的 `/s/diary/` location 转发 HTML 分享页，避免在本文复制一份容易漂移的配置。

## 常见问题

### 打开 `/api/public/diary-shares/{token}` 只有 JSON

这是正常的。该地址是公开 JSON 接口，不是网页页面。

对外分享应使用：

```text
/s/diary/{token}
```

### 提示 401 登录失效

说明访问路径仍被鉴权拦截，通常原因是：

- 使用了非公开接口；
- Spring Security 未放行 `/s/diary/**` 或 `/api/public/diary-shares/**`；
- 反向代理把路径转发错了。

### 提示 404 分享不存在或已失效

通常原因：

- token 不存在；
- token 已过期；
- 分享已撤销；
- 链接中的 `{token}` 没有被真实 token 替换。

### 分享链接仍然是 `127.0.0.1` 或旧域名

优先检查：

- 后端是否已使用 `dev` profile 重启；
- 本地 `APP_SHARE_BASE_URL` 是否仍设置为旧值，因为显式配置始终优先；
- Flutter 的 `API_BASE_URL` 是否确实是当前 WLAN 地址或隧道 HTTPS 地址；
- 本机隧道是否会写入正确的转发协议和主机；
- 修改配置后是否重新创建了分享链接。

### 点击“在 App 中打开”进入空页面

优先检查：

- 手机上是否安装了重新构建后的 APK；
- AndroidManifest 是否包含 `joysong://s/diary` 的 intent-filter；
- App 是否能解析 `joysong://s/diary/{token}`；
- token 是否有效；
- App 的 `apiRoot` 是否能被手机访问。

### PowerShell 提示找不到 flutter

说明 Flutter SDK 没有安装，或 `flutter\bin` 没有加入系统 `Path`。

可先搜索：

```powershell
Get-ChildItem -Path D:\,C:\ -Filter flutter.bat -Recurse -ErrorAction SilentlyContinue
```

找到后使用完整路径运行，例如：

```powershell
D:\flutter\bin\flutter.bat doctor
```

## 验收清单

- 登录用户点击分享按钮后生成 `/s/diary/{token}` 链接。
- 电脑浏览器可打开网页分享页。
- 同一 Wi-Fi 手机可打开局域网分享页。
- 使用本机反向隧道时，Flutter API 与返回的分享链接使用同一个 HTTPS origin。
- 生产环境未配置合法 `APP_SHARE_BASE_URL` 时启动失败，配置后生成的域名与 Nginx/TLS 一致。
- 未登录浏览器访问分享页不返回 401。
- 网页预览最多显示三张图片。
- 点击图片可查看全部图片并循环滑动。
- 点击底部叉号后底部操作块消失。
- 点击“在 App 中打开”可唤起已安装 App。
- App 内能加载对应分享日记内容。
- 撤销分享后原链接返回失效提示。

