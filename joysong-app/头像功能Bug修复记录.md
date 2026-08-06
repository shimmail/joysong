# 头像功能 Bug 修复记录

## 1. 裁剪界面遮罩形状错误（正方形 → 圆形镂空）

**现象**：圆形裁剪框四周的深色遮罩呈正方形，未与白色圆框衔接。

**原因**：最初用4个矩形拼接遮罩，无法产生圆形镂空效果。

**解决方案**：使用 Compose 的 `drawIntoCanvas` + `BlendMode.Clear` 实现真正的圆形镂空：

```kotlin
Canvas(modifier = Modifier.fillMaxSize()) {
    drawIntoCanvas { canvas ->
        val nativeCanvas = canvas.nativeCanvas
        val checkPoint = nativeCanvas.saveLayer(null, null)
        
        // 1. 全屏半透明遮罩
        drawRect(color = Color.Black.copy(alpha = 0.6f))
        
        // 2. BlendMode.Clear 挖出圆形透明区域
        drawCircle(
            color = Color.Transparent,
            radius = cropRadius,
            center = Offset(centerX, centerY),
            blendMode = BlendMode.Clear
        )
        
        nativeCanvas.restoreToCount(checkPoint)
    }
    // 3. 白色圆形边框（在 saveLayer 外绘制）
    drawCircle(color = Color.White, radius = cropRadius, center = Offset(centerX, centerY), style = Stroke(width = 2.dp.toPx()))
}
```

**关键导入**：
```kotlin
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.BlendMode
```

---

## 2. 裁剪逻辑错误（左右裁剪 → 选取原图部分）

**现象**：裁剪后的头像与原图不符，左右被裁剪。

**原因**：使用 `ContentScale.Crop` 显示图片，图片本身就被裁剪（超出容器的部分不可见），裁剪函数无法正确映射。

**解决方案**：
- 图片使用 `ContentScale.Fit` 显示完整原图
- 裁剪函数使用 `minOf()` 计算 fitScale（而非 `maxOf()`）
- 直接从原图提取屏幕中心对应的矩形区域

```kotlin
// 显示层
AsyncImage(
    contentScale = ContentScale.Fit,  // 完整显示原图
    ...
)

// 裁剪函数
val fitScale = minOf(
    containerSize.width.toFloat() / originalBitmap.width,
    containerSize.height.toFloat() / originalBitmap.height
)
val effectiveScale = fitScale * scale
val centerOrigX = (screenCenterX - imageStartX - offsetX) / effectiveScale
val centerOrigY = (screenCenterY - imageStartY - offsetY) / effectiveScale
val origCropSize = cropSizePx / effectiveScale
```

---

## 3. 头像上传后不更新（Coil 缓存问题）

**现象**：上传新头像后，页面仍显示旧头像。

**原因**：头像 URL 不变（同一用户覆盖上传），Coil 默认缓存图片，返回缓存的旧图片。

**解决方案**：加载头像时禁用缓存：

```kotlin
AsyncImage(
    model = ImageRequest.Builder(context)
        .data(avatarUrl)
        .memoryCachePolicy(CachePolicy.DISABLED)
        .diskCachePolicy(CachePolicy.DISABLED)
        .build(),
    contentScale = ContentScale.Crop,
    ...
)
```

**注意**：ProfileScreen 和 EditProfileScreen 的头像 AsyncImage 都需要设置。

---

## 4. 头像预览与实际显示不一致

**现象**：裁剪界面预览的头像效果与个人中心/编辑资料页显示不同。

**原因**：裁剪函数输出圆形 PNG（透明角），UI 层又做圆形裁剪，双重处理导致不一致。

**解决方案**：裁剪函数只输出正方形图片，圆形显示由 UI 层统一处理：

```kotlin
// 裁剪函数：输出正方形，不做圆形裁剪
canvas.drawBitmap(originalBitmap, srcRect, dstRect, paint)

// UI层：统一用 CircleShape + ContentScale.Crop 显示
Box(modifier = Modifier.size(64.dp).clip(CircleShape)) {
    AsyncImage(
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize()
    )
}
```

---

## 5. 触摸事件被遮罩拦截

**现象**：裁剪界面无法拖拽/缩放图片。

**原因**：Canvas 遮罩带 `pointerInput(Unit) {}` 且位于 AsyncImage 之后（z-order更高），拦截了触摸事件。

**解决方案**：
- 手势检测放在 Box 的 modifier 上（不受子组件 z-order 影响）
- Canvas 遮罩不设 `pointerInput`（不拦截触摸）
- z-order：AsyncImage 先（底层），Canvas 后（顶层覆盖）

```kotlin
Box(
    modifier = Modifier
        .pointerInput(Unit) { detectTransformGestures { ... } }  // 手势在Box上
) {
    AsyncImage(...)  // 底层：图片
    Canvas(modifier = Modifier.fillMaxSize()) { ... }  // 顶层：遮罩（无pointerInput）
}
```

---

## 6. OSS 上传覆盖旧头像

**需求**：同一用户新上传头像应覆盖旧文件，避免存储空间浪费。

**实现**：
- 服务端 `FileUploadService.upload()` 支持 `customFileName` 参数
- 客户端传入 `avatar_{userId}` 作为文件名
- OSS `putObject` 相同 key 会自动覆盖

```kotlin
// 客户端
val customFileName = "avatar_${user.id}"
fileRepository.uploadImage(file, "avatar", customFileName)

// 服务端
val key = "$folder/$customFileName.$extension"  // avatar/avatar_xxx.png
ossClient.putObject(bucketName, key, inputStream)
```

---

## 7. 测试阶段本地存储

**场景**：OSS 未配置好时，头像保存到本地测试。

```kotlin
// ProfileViewModel.uploadAvatar() 中
val avatarDir = File(appContext.filesDir, "avatars")
val localFile = File(avatarDir, "avatar_$userId.png")
file.copyTo(localFile, overwrite = true)
val localUri = localFile.toURI().toString()  // file:///data/user/0/.../avatar_xxx.png
```

**恢复 OSS**：取消注释 `fileRepository.uploadImage()` 调用，删除本地保存代码。

---

## 核心原则总结

| 原则 | 说明 |
|------|------|
| 显示完整原图 | 裁剪界面用 `ContentScale.Fit`，让用户看到全图后选区 |
| 裁剪输出正方形 | 裁剪函数不做圆形裁剪，UI层统一处理 |
| 禁用头像缓存 | 所有头像 AsyncImage 设置 `CachePolicy.DISABLED` |
| 遮罩用 BlendMode | 圆形镂空必须用 `saveLayer` + `BlendMode.Clear` |
| 手势在父容器 | `pointerInput` 放在 Box 上，子组件不设 pointerInput |
| 同用户覆盖上传 | 文件名用 `avatar_{userId}`，OSS 自动覆盖 |
