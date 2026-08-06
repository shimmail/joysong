package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.joysong.app.ui.components.ImageZoomDialog

/**
 * 可复用的动态高度图片轮播组件。
 * 根据每张图片的实际宽高比动态调整 Pager 高度，支持点击放大查看。
 *
 * @param images 图片 URL 列表
 * @param contentDescription 图片内容描述
 * @param backgroundColor 背景颜色
 * @param modifier 外部 Modifier
 */
@Composable
fun DynamicImagePager(
    images: List<String>,
    contentDescription: String,
    backgroundColor: Color = Color.White,
    modifier: Modifier = Modifier
) {
    if (images.isEmpty()) return

    var showImageZoom by remember { mutableStateOf(false) }
    var zoomImageIndex by remember { mutableIntStateOf(0) }

    val pagerState = rememberPagerState(pageCount = { images.size })
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val pageHeights = remember { mutableStateOf(mapOf<Int, Dp>()) }
    val pagerHeight = pageHeights.value[pagerState.currentPage]
        ?: (screenWidthDp * 0.75f).dp  // 默认估算高度

    Column(modifier = modifier) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(pagerHeight)
                .background(backgroundColor)
        ) { page ->
            AsyncImage(
                model = images[page],
                contentDescription = "$contentDescription ${page + 1}",
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight(align = Alignment.CenterVertically)
                    .clickable {
                        zoomImageIndex = page
                        showImageZoom = true
                    },
                contentScale = ContentScale.FillWidth,
                onSuccess = { state ->
                    val r = state.result
                    val iw = r.drawable.intrinsicWidth.toFloat()
                    val ih = r.drawable.intrinsicHeight.toFloat()
                    if (iw > 0 && ih > 0) {
                        val ratio = ih / iw
                        val h = (screenWidthDp * ratio).dp
                        pageHeights.value = pageHeights.value + (page to h)
                    }
                },
                onError = { /* fallback */ }
            )
        }

        // Dot indicator
        if (images.size > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                images.forEachIndexed { index, _ ->
                    val isCurrent = index == pagerState.currentPage
                    Box(
                        modifier = Modifier
                            .padding(horizontal = 3.dp)
                            .size(if (isCurrent) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(
                                if (isCurrent) Color(0xFF1A1A1A) else Color(0xFFD0D0D0)
                            )
                    )
                }
            }
        }
    }

    if (showImageZoom) {
        ImageZoomDialog(
            imageUrls = images,
            initialIndex = zoomImageIndex,
            onDismiss = { showImageZoom = false }
        )
    }
}
