# 本机前端通过 SSH 连接 ECS 后端

适用实例：`cn-hangzhou / i-bp19abm7697mvhl0xewu`。

## 当前配置（2026-09-06）

- ECS 公网地址：`121.41.230.98`；内网地址：`172.17.101.65`。
- 后端监听 `127.0.0.1:8080`，服务器内健康检查返回 `UP`。
- 专用账户：`joysong-tunnel`，无 sudo 权限，登录 shell 为 `/usr/sbin/nologin`。
- SSH 配置：`/etc/ssh/sshd_config.d/60-joysong-tunnel.conf`。
- 仅允许公钥认证、本地 TCP 转发，目标限定为 `127.0.0.1:8080`；禁止 shell、exec、SFTP、远程转发、Unix socket 转发及其他目标端口。
- 本机连接别名已写入 `C:\Users\shimeng\.ssh\config`：`joysong-ecs-tunnel`。
- 专用私钥保存在 `C:\Users\shimeng\.ssh\id_ed25519_joysong_tunnel`，不要上传服务器或提交仓库。
- 服务器 ED25519 公钥已通过 Workbench 核验，固定于 `C:\Users\shimeng\.ssh\known_hosts_joysong`。
- 服务器公钥指纹：`SHA256:6nE0gr2z5w6aWwxRCXgzOov5pAuVzUxqxcLkBZEQmAI`。

2026-09-06，用户确认添加入站规则后，本机已通过公钥认证建立 SSH 隧道，访问 `http://127.0.0.1:8080/actuator/health` 返回 `{"status":"UP"}`。

验证结果：

- 专用账户执行远程 `id` 命令被拒绝。
- 尝试转发到 ECS 的 `127.0.0.1:22` 返回 `administratively prohibited`。
- 尝试 `-R` 远程端口转发被拒绝。
- 上述负向验证连接均已退出，联调用的本机 `8080` 隧道保留运行。

## 网络前置条件

安全组 `sg-bp1f7hfs30n7bzpesio3` 被三台 ECS 共用。仅为本次目标实例添加入站规则：

| 字段 | 值 |
| --- | --- |
| 策略 | 允许 |
| 协议 / 目标端口 | TCP / `22/22` |
| 来源 IPv4 | `112.49.6.96/32`（本次核验的直连出口） |
| 目标 IPv4 | `172.17.101.65/32`（限制到目标 ECS） |

自动读取和添加规则此前均被 RAM 权限拒绝，返回 `Forbidden.RAM`。用户随后确认已在控制台添加规则，本机 SSH 连接与健康接口实测通过；安全组规则内容未通过 API 再次读取核对。

本机系统代理为 `127.0.0.1:7890`。通过 HTTP 代理查询到的出口不能当作直连 SSH 的来源。切换网络后，重新查询直连公网 IP，例如：

```powershell
curl.exe --noproxy '*' --connect-timeout 5 --max-time 10 -fsS https://ip.3322.net
```

不要开放后端 `8080` 或 MySQL `3306`；此链路不依赖公网业务域名、Nginx 或 HTTPS。

## 启动与验证

本机 `8080` 空闲时，在一个终端中运行并保持运行：

```powershell
ssh -F C:/Users/shimeng/.ssh/config -N -T joysong-ecs-tunnel
```

别名已配置映射 `127.0.0.1:8080 -> ECS 127.0.0.1:8080`。在另一个终端验证：

```powershell
curl.exe --noproxy '*' --max-time 10 -fsS http://127.0.0.1:8080/actuator/health
```

返回 `{"status":"UP"}` 才表示本机到后端的整条链路可用。SSH 连接成功或本地端口开始监听本身不足以证明目标转发成功。

## 连接本机前端

Admin 已将 `/api` 和 `/images` 代理至 `localhost:8080`：

```powershell
Set-Location D:\code\kotlin\joysong\joysong-admin
npm.cmd run dev -- --host 127.0.0.1 --strictPort
```

打开 [后台登录页](http://127.0.0.1:3000/login)。管理员手机号、密码见本机受限的 `.runtime/catalog-mount-20260906-183613/test-accounts.txt`。

若登录返回 `403` 且响应正文为 `Invalid CORS request`，表示 ECS 拒绝了浏览器来源。开发代理仅对来自 `http://127.0.0.1:3000` 或 `http://localhost:3000`、且与请求 Host 一致的同源 `/api` 请求移除转发的 `Origin`，使其通过 SSH 访问 ECS；其他来源不作此处理。该配置只用于本地开发，不改变 ECS 的 CORS 白名单或管理员权限。更新代理配置后，确认 Vite 已自动重启，再刷新页面登录；保持端口为 `3000`。

Android 真机连接 USB 并授权调试后：

```powershell
adb devices
adb reverse tcp:8080 tcp:8080
Set-Location D:\code\kotlin\joysong\joysong-flutter
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' run --flavor development --dart-define=APP_ENV=development --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

Android 模拟器改用 `API_BASE_URL=http://10.0.2.2:8080`，无需 `adb reverse`。UAT/生产配置要求 HTTPS，不能直接使用此 HTTP 地址。

### Android 真机无线调试（Android 11 及以上）

确认列表中对应的 `$wirelessDevice` 状态为 `device`，然后映射端口并指定该设备启动 Flutter：

无线配对、连接已完成，沿用当前 PowerShell 中的 `$wirelessDevice`。以下命令在 `PS D:\code\kotlin\joysong\joysong-flutter>` 执行，保持 SSH 隧道运行。

```powershell
adb -s $wirelessDevice reverse tcp:8080 tcp:8080
adb -s $wirelessDevice reverse --list
& 'D:\code\kotlin\joysong\.flutter-cache\sdk\flutter\bin\flutter.bat' run -d $wirelessDevice --flavor development --dart-define=APP_ENV=development --dart-define=API_BASE_URL=http://127.0.0.1:8080
```

`reverse --list` 应包含 `tcp:8080 tcp:8080`。无线连接后仍使用手机侧的 `127.0.0.1:8080`，由 adb 转发至电脑上的 SSH 隧道。多设备同时连接时，保留上述 `-s` 和 `-d`，避免选错设备。端口映射原理参见 [Android 官方本地服务访问说明](https://developer.android.com/develop/ui/views/layout/webapps/access-local-server)。

若新开了终端或 `$wirelessDevice` 为空，先执行 `adb devices -l`，再用 `$wirelessDevice = Read-Host '输入目标无线设备第一列的完整设备 ID'` 选择已连接设备。关闭无线调试、重启手机或切换网络后，IP/连接端口可能变化，映射也可能失效；重新连接后再次映射端口即可。若电脑公网出口变化，按前面的网络条件更新 SSH 安全组来源。

停止 Flutter 时按 `q` 退出，然后在同一终端清理该设备的映射并断开无线连接：

```powershell
adb -s $wirelessDevice reverse --remove tcp:8080
adb disconnect $wirelessDevice
```

这一步只断开手机调试连接；SSH 隧道可继续供 Admin 使用。2026-09-06 已沿用现有无线连接，在 2211133C 真机上验证 development APK 构建、安装和启动成功；本机后端健康接口返回 `UP`。本次未重新执行配对。

停止联调时，在隧道终端按 `Ctrl+C`；如需清理真机映射，执行 `adb reverse --remove tcp:8080`。公网支付回调、公共分享链接和域名 TLS 需要单独验证。
