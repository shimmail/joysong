package com.joysong.app.ui.profile

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.PrimaryDark
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 头像裁剪屏幕
 * 用户可以看到完整原图，通过拖拽移动和双指缩放选择裁剪区域
 */
@Composable
fun AvatarCropScreen(
    imageUri: Uri,
    onCropComplete: (File) -> Unit,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 变换状态
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    // 容器尺寸
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    // 裁剪区域大小（圆形）
    val cropSizeDp = 280.dp
    val cropSizePx = with(LocalDensity.current) { cropSizeDp.toPx() }

    var isProcessing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.crop_avatar),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            // 图片区域
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { containerSize = it }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offsetX += pan.x
                            offsetY += pan.y
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                // 可变换的图片（底层，使用Fit显示完整原图）
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(imageUri)
                        .allowHardware(false)
                        .build(),
                    contentDescription = "裁剪图片",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offsetX,
                            translationY = offsetY
                        )
                )

                // 圆形镂空遮罩 + 白色圆框（顶层，无pointerInput不拦截触摸）
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height
                    val centerX = canvasWidth / 2
                    val centerY = canvasHeight / 2
                    val cropRadius = cropSizePx / 2
                    val overlayColor = Color.Black.copy(alpha = 0.6f)

                    // 使用离屏合成实现圆形镂空
                    drawIntoCanvas { canvas ->
                        val nativeCanvas = canvas.nativeCanvas
                        val checkPoint = nativeCanvas.saveLayer(null, null)

                        // 1. 绘制全屏半透明遮罩
                        drawRect(color = overlayColor)

                        // 2. 用 Clear 模式挖出圆形透明区域
                        drawCircle(
                            color = Color.Transparent,
                            radius = cropRadius,
                            center = Offset(centerX, centerY),
                            blendMode = BlendMode.Clear
                        )

                        nativeCanvas.restoreToCount(checkPoint)
                    }

                    // 3. 绘制白色圆形边框
                    drawCircle(
                        color = Color.White,
                        radius = cropRadius,
                        center = Offset(centerX, centerY),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }

            // 底部操作栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 取消按钮
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.2f))
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "取消",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // 提示文字
                Text(
                    text = stringResource(R.string.crop_hint),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 14.sp
                )

                // 确认按钮
                IconButton(
                    onClick = {
                        if (!isProcessing) {
                            isProcessing = true
                            scope.launch {
                                val croppedFile = cropAvatarFromScreen(
                                    context = context,
                                    uri = imageUri,
                                    scale = scale,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    containerSize = containerSize
                                )
                                isProcessing = false
                                if (croppedFile != null) {
                                    onCropComplete(croppedFile)
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(PrimaryDark),
                    enabled = !isProcessing
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "确认",
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

/**
 * 根据当前变换参数裁剪头像并保存为临时文件
 * 使用 ContentScale.Fit 显示完整原图，用户在原图上选择裁剪区域
 * 输出正方形图片（UI层负责圆形裁剪显示）
 */
private suspend fun cropAvatarFromScreen(
    context: android.content.Context,
    uri: Uri,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    containerSize: IntSize
): File? = withContext(Dispatchers.IO) {
    try {
        // 加载原始图片为 Bitmap
        val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        if (originalBitmap == null) return@withContext null
        if (containerSize.width == 0 || containerSize.height == 0) return@withContext null

        // ContentScale.Fit：图片按比例缩放以完全显示在容器内
        val fitScale = minOf(
            containerSize.width.toFloat() / originalBitmap.width,
            containerSize.height.toFloat() / originalBitmap.height
        )

        // 图片在屏幕上的实际显示尺寸（Fit 模式下完全可见）
        val displayedWidth = originalBitmap.width * fitScale
        val displayedHeight = originalBitmap.height * fitScale

        // 图片在容器中的起始位置（居中）
        val imageStartX = (containerSize.width - displayedWidth) / 2f
        val imageStartY = (containerSize.height - displayedHeight) / 2f

        // 屏幕中心点（裁剪圆中心）
        val screenCenterX = containerSize.width / 2f
        val screenCenterY = containerSize.height / 2f

        // 计算屏幕中心对应到原始图片的坐标
        // 屏幕坐标 = imageStart + originalCoord * fitScale * scale + offset
        // 所以 originalCoord = (screenCoord - imageStart - offset) / (fitScale * scale)
        val effectiveScale = fitScale * scale
        val centerOrigX = (screenCenterX - imageStartX - offsetX) / effectiveScale
        val centerOrigY = (screenCenterY - imageStartY - offsetY) / effectiveScale

        // 裁剪圆在原始图片上对应的像素范围
        val density = context.resources.displayMetrics.density
        val cropSizePx = 280 * density  // 280dp 转像素
        val origCropSize = cropSizePx / effectiveScale  // 圆形区域在原始图片上的边长

        // 计算源矩形（从原始图片中裁剪的区域）
        val srcLeft = (centerOrigX - origCropSize / 2).toInt().coerceIn(0, originalBitmap.width)
        val srcTop = (centerOrigY - origCropSize / 2).toInt().coerceIn(0, originalBitmap.height)
        val srcRight = (centerOrigX + origCropSize / 2).toInt().coerceIn(srcLeft, originalBitmap.width)
        val srcBottom = (centerOrigY + origCropSize / 2).toInt().coerceIn(srcTop, originalBitmap.height)

        // 确保源矩形有效
        if (srcRight <= srcLeft || srcBottom <= srcTop) return@withContext null

        // 输出头像尺寸（正方形）
        val outputSize = 500
        val cropBitmap = Bitmap.createBitmap(outputSize, outputSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(cropBitmap)

        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
        }

        // 将源区域绘制到输出正方形中
        val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight, srcBottom)
        val dstRect = android.graphics.RectF(0f, 0f, outputSize.toFloat(), outputSize.toFloat())
        canvas.drawBitmap(originalBitmap, srcRect, dstRect, paint)

        // 保存到临时文件
        val outputFile = File.createTempFile("avatar_cropped_", ".png", context.cacheDir)
        FileOutputStream(outputFile).use { fos ->
            cropBitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
        }

        originalBitmap.recycle()
        cropBitmap.recycle()

        outputFile
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}
