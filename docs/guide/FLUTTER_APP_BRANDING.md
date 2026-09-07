# App 名称与图标

配置日期：2026-09-06。工程：`joysong-flutter`。

## 名称与语言

| 位置 | 中文 | 英文 |
| --- | --- | --- |
| 桌面应用名称、应用标题、登录品牌标题 | 娇颜颂 | JoySong |

- 应用内首次启动默认中文，设置页可切换英文，重启后恢复已保存的语言。
- 桌面名称使用原生语言资源：中文及默认资源为「娇颜颂」，英语环境显示 `JoySong`。桌面名称遵循操作系统的语言选择，应用内语言开关不会修改系统桌面名称。
- Android：`android/app/src/main/res/values/strings.xml` 为默认名称，`values-zh` 与 `values-en` 为明确的语言资源。
- iOS：`ios/Runner/Info.plist` 保留中文回退值，开发语言为 `zh-Hans`；`zh-Hans.lproj/InfoPlist.strings` 和 `en.lproj/InfoPlist.strings` 配置中英文名称，并接入 Xcode Resources。

## 图标与更新

图标为中性灰底（`#666666`）、浅色三瓣花（`#FFF9FB`），两端使用同一图形。图标不含文字，中英文版本共用。

- 唯一图形源：`android/app/src/main/res/drawable/ic_launcher_foreground.xml`。
- 背景色：`android/app/src/main/res/values/colors.xml` 的 `ic_launcher_background`。
- Android 最低版本为 API 26，直接使用自适应矢量图标；普通和圆形桌面入口共用图标，API 33 另提供 monochrome 层供主题色图标使用。
- iOS：`ios/Runner/Assets.xcassets/AppIcon.appiconset` 包含 iPhone、iPad 和 App Store 尺寸，无透明通道；Debug/Profile/Release 均使用 `AppIcon`。

修改图形或背景后，在 `joysong-flutter` 下运行（Python 需安装 Pillow）：

```powershell
python tool/generate_app_icons.py
```

生成器仅支持当前矢量图形使用的绝对 `M`、`C`、`Z` 路径指令，遇到其他指令会报错。生成资源应一并提交，日常 Flutter/Xcode 构建无需运行 Python。

## 设备验收

1. 重新构建并安装，检查桌面图标、圆形裁切和 Android 主题色图标。
2. 中文系统检查「娇颜颂」，英文系统检查 `JoySong`。
3. 首次启动检查应用内默认中文；切换英文后检查标题和登录品牌名，重启确认语言恢复。
4. macOS 上使用 Xcode 验证 iPhone/iPad 图标及 Archive。Windows 无法完成 iOS 编译与设备验收。

参考：[Android 自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)、[Apple 应用显示名称本地化](https://developer.apple.com/library/archive/qa/qa1823/)。
