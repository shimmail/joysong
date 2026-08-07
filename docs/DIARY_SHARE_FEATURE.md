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

返回数据包含分享链接和 token。链接格式由 `app.share.base-url` 控制。

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

### 本地局域网测试

如果电脑局域网 IP 是：

```text
192.168.2.54
```

开发环境可配置：

```yaml
app:
  share:
    base-url: http://192.168.2.54:8080/s/diary/
```

这样生成的分享链接类似：

```text
http://192.168.2.54:8080/s/diary/{token}
```

手机需要与电脑处于同一 Wi-Fi，并且 Windows 防火墙允许访问后端端口 `8080`。

### 生产环境

生产环境建议使用正式域名：

```yaml
app:
  share:
    base-url: https://app.joysong.cn/s/diary/
```

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

Nginx 示例：

```nginx
location /s/diary/ {
    proxy_pass http://127.0.0.1:8080;
}

location /api/public/diary-shares/ {
    proxy_pass http://127.0.0.1:8080;
}

location /api/ {
    proxy_pass http://127.0.0.1:8080;
}
```

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
- 未登录浏览器访问分享页不返回 401。
- 网页预览最多显示三张图片。
- 点击图片可查看全部图片并循环滑动。
- 点击底部叉号后底部操作块消失。
- 点击“在 App 中打开”可唤起已安装 App。
- App 内能加载对应分享日记内容。
- 撤销分享后原链接返回失效提示。

