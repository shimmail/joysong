# 性能优化开发文档

> 最后更新：2026-08-07
>
> 本文对应当前已落地的 Flutter、Spring Boot 与管理后台性能优化实现。

## 1. 目标与范围

本文记录 Joysong Flutter 客户端与 Spring Boot 服务端针对“首页/聊天界面卡顿、图片加载慢、消息流式输出滚动抖动”的优化。优化重点是减少首屏数据量、避免重复网络请求、降低图片解码成本，并保持现有接口兼容性。

## 2. 问题表现与根因

| 现象 | 根因 |
| --- | --- |
| 首页内容越多，打开越慢 | 首页接口使用 `findAll()` 返回全部项目、文章和日记；推荐机构项目还会全量加载后在内存排序 |
| 聊天图片首屏白屏或滚动掉帧 | `Image.network` 默认按原始图片尺寸解码，缩略图也会占用大图的解码内存 |
| 重复进入聊天后图片仍反复请求 | 本地 `/images/**` 与 OSS 对象没有统一的公共缓存策略 |
| AI 流式回复时聊天列表抖动 | 每个 token 都触发一次滚动动画，动画不断重启 |
| 通知刷新造成整个消息壳重建 | 未读数未变化时仍调用 `setState`，导致 `IndexedStack` 重建 |

## 3. 已完成的优化

### 3.1 首页数据库限量查询

`HomeService` 使用数据库层的 Top-N 查询：

- 热门项目：销量前 8 个。
- 专家文章：发布日期前 6 个。
- 精选日记：已发布且发布日期前 8 个。
- 推荐机构项目：激活状态、销量前 8 个，然后只批量读取这些记录关联的项目和机构。

相关仓储方法：

- `ProjectRepository.findTop8ByOrderBySalesCountDesc()`
- `ArticleRepository.findTop6ByOrderByPublishDateDesc()`
- `DiaryRepository.findTop8ByStatusOrderByPublishDateDesc("published")`
- `InstitutionProjectRepository.findTop8ByIsActiveTrueOrderBySalesCountDesc()`

推荐机构项目的复合索引由 `V5__add_recommended_institution_projects_index.sql` 创建；文章发布日期索引也在该迁移中创建。完整列表继续使用发现页分页接口，不受首页截断影响。

### 3.2 聊天图片解码与封面展示

聊天消息图片在 `messaging_pages.dart` 中：

- 消息缩略图设置 `cacheWidth`/`cacheHeight`，按设备 DPR 将 220x180 的显示区域转换为实际解码尺寸。
- 头像通过 `ResizeImage.resizeIfNeeded` 限制解码尺寸。
- 缩略图使用 `FilterQuality.low` 和 `gaplessPlayback`，降低采样成本并减少切换闪烁。
- 全屏预览不设置缩略尺寸，保留原图缩放清晰度。

首页、发现列表和用户/机构/项目详情的封面图使用共享 `OptimizedNetworkImage` 组件。封面上传原文件不压缩，页面通过固定容器和 `BoxFit.cover` 裁剪展示；不设置 `cacheWidth/cacheHeight`，避免改变封面源质量。头像仍可按头像容器尺寸解码。

管理后台 `ImageUpload` 会按用途提示推荐尺寸：项目、机构、文章封面为 `1200 × 800 px（3:2）`，横幅为 `1920 × 720 px（约 8:3）`。提示明确说明上传原图，页面按比例裁剪展示。

服务端缓存策略：

- 本地 `WebMvcConfig` 为 `/images/**` 添加 `Cache-Control: public, max-age=3600`。
- OSS 上传对象写入同样的 `Cache-Control` 元数据。

图片文件名为 UUID 时可进一步延长缓存时间；若业务会复用同名头像，当前 1 小时策略可避免长期显示旧图。

### 3.3 流式消息与通知重建

- AI 聊天仅在用户仍接近底部时自动滚动；滚动请求以 80ms 合并，并使用 `jumpTo` 避免每个 token 重启动画。用户上滑阅读历史时不会被拉回底部。
- AI 消息列表增加有限 `cacheExtent`，平衡预构建和内存。
- AppShell 缓存未读通知数，只有计数实际变化时才触发状态更新；切换账号或依赖时清空缓存。

## 4. 代码位置

| 模块 | 文件 |
| --- | --- |
| Flutter 聊天图片 | `joysong-flutter/lib/features/messaging/presentation/messaging_pages.dart` |
| Flutter 封面展示 | `joysong-flutter/lib/core/network/optimized_network_image.dart` |
| 管理后台封面上传 | `joysong-admin/src/components/ImageUpload.tsx`、各业务页面的 `recommendedSize` 配置 |
| Flutter AI 流式滚动 | `joysong-flutter/lib/features/agent/presentation/agent_chat_page.dart` |
| Flutter 通知状态 | `joysong-flutter/lib/features/shell/presentation/app_shell.dart` |
| 首页查询 | `joysong-server/src/main/kotlin/com/joysong/server/home/service/HomeService.kt` |
| 图片缓存头 | `joysong-server/src/main/kotlin/com/joysong/server/config/WebMvcConfig.kt`、`common/service/FileUploadService.kt` |
| 数据库索引 | `joysong-server/src/main/resources/db/migration/V5__add_recommended_institution_projects_index.sql` |

## 5. 部署与兼容性要求

1. 服务端发布时必须执行 Flyway 迁移，确认 `V5` 成功后再切流量。
2. 生产环境若由 Nginx/CDN 托管 `/images/`，应同步配置至少 1 小时的 `Cache-Control`，并确保不会覆盖 OSS 的缓存头。
3. 图片 URL 变更后客户端会按新 URL 建立缓存；同 URL 覆盖文件时最长可能保留 1 小时旧缓存。
4. 首页“查看全部”必须跳转发现页，不应复用首页 Top-N 接口作为完整列表。

## 6. 验证清单

### 静态检查

```powershell
cd joysong-flutter
flutter analyze lib/features/messaging/presentation/messaging_pages.dart
dart format --set-exit-if-changed lib/features/messaging/presentation/messaging_pages.dart

cd ..\joysong-admin
npx tsc -b --pretty false

cd ..\joysong-server
./gradlew test --tests com.joysong.server.home.service.HomeServiceTest
```

### 手工验证

- 首次打开聊天：缩略图应先按 220x180 区域显示，不应因原图尺寸造成明显掉帧。
- 返回聊天列表再进入同一会话：图片应命中客户端/HTTP缓存，不重复出现长时间白屏。
- 上滑查看历史消息并接收新消息：列表位置保持不变。
- AI 流式输出期间：底部滚动平滑，不能每个 token 触发一次动画。
- 上传项目、机构或文章封面：上传请求中的文件字节应与原文件一致；列表和详情页按容器比例裁剪，不拉伸布局。
- 检查图片响应头包含 `Cache-Control`，并确认 OSS 对象元数据一致。

## 7. 后续基线与观测

建议在真机和生产近似环境记录：聊天首屏接口耗时、首张图片可见耗时、图片缓存命中率、Flutter 帧耗时（目标 95 分位小于 16ms）和首页响应体大小。若图片仍慢，下一步优先增加服务端缩略图/CDN，而不是继续提高客户端预加载范围。

## 8. 更新记录

| 日期 | 更新内容 |
| --- | --- |
| 2026-08-07 | 首页、发现页及用户/机构/项目详情封面统一使用 `OptimizedNetworkImage`；保留上传原图，由页面容器通过 `BoxFit.cover` 裁剪。 |
| 2026-08-07 | 管理后台 `ImageUpload` 增加推荐尺寸提示，并覆盖项目、机构、文章、横幅和个人项目封面入口。 |
| 2026-08-07 | 本地图片与 OSS 上传对象统一设置公共缓存头，降低重复加载耗时。 |
