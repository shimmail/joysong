package com.joysong.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage

/**
 * 全屏图片大图浏览弹窗
 * - HorizontalPager 展示多张图片
 * - 双指缩放与拖拽
 * - 底部圆点指示器 + "x/n" 页码
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageZoomDialog(
    imageUrls: List<String>,
    initialIndex: Int,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            val pagerState = rememberPagerState(
                initialPage = initialIndex.coerceIn(0, (imageUrls.size - 1).coerceAtLeast(0))
            ) { imageUrls.size }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
            ) { page ->
                // 以 page 为 key，确保切换页面时缩放状态自动重置
                ZoomableImage(
                    imageUrl = imageUrls[page],
                    pageKey = page,
                    isCurrentPage = page == pagerState.currentPage,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Top close button (渲染在 HorizontalPager 之后，确保 z-order 在最上层)
            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .zIndex(1f)
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }

            // Bottom indicator: dots + page number
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Dot indicators
                if (imageUrls.size > 1) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        imageUrls.forEachIndexed { index, _ ->
                            val isCurrent = index == pagerState.currentPage
                            Box(
                                modifier = Modifier
                                    .size(if (isCurrent) 8.dp else 6.dp)
                                    .clip(CircleShape)
                                    .background(if (isCurrent) Color.White else Color.Gray.copy(alpha = 0.5f))
                            )
                        }
                    }
                }
                // Page number
                Text(
                    text = "${pagerState.currentPage + 1}/${imageUrls.size}",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

/**
 * 支持双指缩放和拖拽的图片组件
 */
@Composable
private fun ZoomableImage(
    imageUrl: String,
    pageKey: Int,
    isCurrentPage: Boolean,
    modifier: Modifier = Modifier
) {
    var scale by remember(pageKey) { mutableStateOf(1f) }
    var offsetX by remember(pageKey) { mutableStateOf(0f) }
    var offsetY by remember(pageKey) { mutableStateOf(0f) }

    // 页面切换时重置缩放，确保新页面始终从 1x 开始
    LaunchedEffect(isCurrentPage) {
        if (isCurrentPage) {
            scale = 1f
            offsetX = 0f
            offsetY = 0f
        }
    }

    Box(
        modifier = modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var consumed = false
                    do {
                        val event = awaitPointerEvent()
                        val isMultiTouch = event.changes.size > 1
                        if (isMultiTouch) {
                            // 多指触控：处理缩放和平移
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                            if (scale > 1f) {
                                offsetX += panChange.x
                                offsetY += panChange.y
                            } else {
                                offsetX = 0f
                                offsetY = 0f
                            }
                            event.changes.forEach { it.consume() }
                            consumed = true
                        }
                        // 单指时不消费事件，让 HorizontalPager 处理滑动翻页
                    } while (event.changes.any { it.pressed })
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = imageUrl,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )
    }
}
