### D:\code\kotlin\joysong\joysong-app\app\build\intermediates\compile_and_runtime_not_namespaced_r_class_jar\debug\processDebugResources\R.jar: 另一个程序正在使用此文件，进程无法访问

解决方法：

```
cd d:\code\kotlin\joysong\joysong-app && .\gradlew.bat --stop 2>&1
```





### 实现google登录

去 Google Cloud Console 创建 OAuth 客户端

打开 https://console.cloud.google.com/

- 创建项目（或选择已有项目）
- 左侧菜单 → **API 和服务** → **凭据**
- 点击 **创建凭据** → **OAuth 客户端 ID**
- 应用类型选 **Web 应用**（因为服务端要验证 Token）
- 记下生成的 **客户端 ID**（格式类似 `123456789-xxxxx.apps.googleusercontent.com`）

添加 Android 客户端

（同一项目下）：

- 再创建一个 **Android 应用** 类型的 OAuth 客户端
- 包名：`com.joysong.app`
- SHA-1 指纹：用以下命令获取：

```text
  keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android
```

把 Web 客户端 ID 填入 `local.properties`

```text
google.client-id=你的真实Web客户端ID
```

同步更新服务端 `application.yml`

```yaml
google:
  client-id: 你的真实Web客户端ID
```







# 阿里云 OSS 图片上传集成

用户需先在阿里云控制台完成：

1. 注册阿里云账号并开通 OSS 服务

2. 创建 Bucket（建议名称 `joysong-images`，地域按需选择）

3. 开启 Bucket 公共读权限（或配置自定义域名 + CDN）

4. 创建 AccessKey（建议使用 RAM 子账号，仅授权 OSS 读写）

5. 新增授权 管理对象存储服务(OSS)权限 

   ![image-20260717223844438](D:\picture\Saved Pictures\image-20260717223844438.png)

6. 将以下信息填入

   ```
   application.yml
   ```

   - `oss.endpoint`: 如 `oss-cn-shanghai.aliyuncs.com`
   - `oss.access-key-id`: AccessKey ID
   - `oss.access-key-secret`: AccessKey Secret
   - `oss.bucket-name`: Bucket 名称

Endpoint信息：

1. 登录 [OSS管理控制台](https://oss.console.aliyun.com/?spm=5176.8465980.console-base_micro-browser.1.4e701450QZH9re)。
2. 在Bucket列表中，单击目标Bucket名称。
3. 在左侧导航栏，单击 **概览**。
4. 在 **访问端口** 区域，查看Bucket的Endpoint和Bucket域名。





### 前端

Tab 应该是锚点滚动而非切换视图



### Compose `mutableStateMapOf` 在新版本中已移除

**现象：** 编译报错 `Not enough information to infer type argument for 'T'`

**原因：** `mutableStateMapOf` 在较新的 Compose 版本中已被移除，编译器无法解析该函数。

**解决方案：** 使用 `mutableStateOf(mapOf<K, V>())` 替代：

```kotlin
// ❌ 旧写法（新版本不可用）
val pageHeights = remember { mutableStateMapOf<Int, Dp>() }
pageHeights[page] = someValue

// ✅ 新写法
val pageHeights = remember { mutableStateOf(mapOf<Int, Dp>()) }
pageHeights.value = pageHeights.value + (page to someValue)
```

**注意：** 使用 `Dp` 等类型时，必须显式导入 `import androidx.compose.ui.unit.Dp`，仅导入 `dp` 扩展属性是不够的。



### 图片轮播动态高度适配

**需求：** HorizontalPager 中的图片应根据实际宽高比动态调整高度，而非使用固定的横版/竖版预设高度。

**实现方案：**

```kotlin
val screenWidthDp = LocalConfiguration.current.screenWidthDp
val pageHeights = remember { mutableStateOf(mapOf<Int, Dp>()) }
val pagerHeight = pageHeights.value[pagerState.currentPage]
    ?: (screenWidthDp * 0.75f).dp  // 默认估算高度

HorizontalPager(
    modifier = Modifier.fillMaxWidth().height(pagerHeight)
) { page ->
    AsyncImage(
        modifier = Modifier
            .fillMaxWidth()
            .wrapContentHeight(align = Alignment.CenterVertically),
        contentScale = ContentScale.FillWidth,
        onSuccess = { state ->
            val iw = state.result.drawable.intrinsicWidth.toFloat()
            val ih = state.result.drawable.intrinsicHeight.toFloat()
            if (iw > 0 && ih > 0) {
                val ratio = ih / iw
                val h = (screenWidthDp * ratio).dp
                pageHeights.value = pageHeights.value + (page to h)
            }
        }
    )
}
```

**关键点：**
- 使用 `ContentScale.FillWidth` 让图片宽度填满，高度按比例缩放
- 图片 modifier 用 `wrapContentHeight` 而非 `fillMaxSize`
- 在 `onSuccess` 回调中根据图片原始尺寸计算实际显示高度
- 切换页面时 pager 高度自动适配当前图片



### 复用图片轮播组件抽取

**场景：** 多个详情页（机构详情、项目详情、机构项目详情）都需要相同的图片轮播功能。

**做法：** 抽取为共享的 `DynamicImagePager` 组件，放在 `ui/detail/DynamicImagePager.kt` 中：

```kotlin
@Composable
fun DynamicImagePager(
    images: List<String>,
    contentDescription: String,
    backgroundColor: Color = Color.White,
    modifier: Modifier = Modifier
)
```

**好处：**
- 三个详情页统一调用，避免重复代码
- 修改一处即可影响所有使用方
- 清理各页面废弃的 import 和旧的 `FullscreenImageViewer` 等冗余组件