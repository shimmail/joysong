package com.joysong.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.ui.theme.Primary
import kotlin.math.abs

/**
 * 三列滚动时间选择器：上午/下午 | 小时(01-12) | 分钟(00-59)
 *
 * 三列均为滚动选择交互：滚动停止后由 SnapFlingBehavior 自动吸附，居中项即为选中项（无需点击）。
 * 吸附完全交给 fling behavior 处理，不做任何手写滚动动画，避免程序化滚动与用户滚动互相取消
 * 而陷入死循环导致主线程 ANR。
 *
 * @param initialHour24 初始小时（24小时制，0-23）
 * @param initialMinute 初始分钟（0-59）
 * @param onTimeChanged 时间变化回调，返回24小时制的小时和分钟
 */
@Composable
fun ScrollingTimePicker(
    initialHour24: Int = 9,
    initialMinute: Int = 0,
    onTimeChanged: (hour24: Int, minute: Int) -> Unit
) {
    val itemHeight = 40.dp
    val visibleItems = 5
    val centerOffset = visibleItems / 2  // 2

    // 将24小时制转换为12小时制 + AM/PM
    val initialIsAm = initialHour24 < 12
    val initialHour12 = when {
        initialHour24 == 0 -> 12
        initialHour24 > 12 -> initialHour24 - 12
        else -> initialHour24
    }

    var isAm by remember { mutableStateOf(initialIsAm) }
    var hour12 by remember { mutableIntStateOf(initialHour12) }
    var minute by remember { mutableIntStateOf(initialMinute) }

    // 当任何值变化时回调
    LaunchedEffect(isAm, hour12, minute) {
        val hour24 = when {
            isAm && hour12 == 12 -> 0
            !isAm && hour12 == 12 -> 12
            !isAm -> hour12 + 12
            else -> hour12
        }
        onTimeChanged(hour24, minute)
    }

    val amPmData = remember { listOf("上午", "下午") }
    val hourData = remember { (1..12).map { String.format("%02d", it) } }
    val minuteData = remember { (0..59).map { String.format("%02d", it) } }

    val pickerHeight = itemHeight * visibleItems
    val centerRowTop = itemHeight * centerOffset

    // 每列固定宽度，避免列膨胀超出 Row 边界而不可见
    val columnWidth = 56.dp

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(pickerHeight)
    ) {
        // 选中行高亮背景 — 固定 offset 绝对定位，放在列之前（底层）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .offset(y = centerRowTop)
                .background(Color(0xFFD3D3D3))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(pickerHeight),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.Top
        ) {
            // 上午/下午列 — 与时间列相同的滚动选择交互
            ScrollablePickerColumn(
                data = amPmData,
                columnWidth = columnWidth,
                itemHeight = itemHeight,
                visibleItems = visibleItems,
                centerOffset = centerOffset,
                selectedIndex = if (isAm) 0 else 1,
                onSelected = { index -> isAm = index == 0 }
            )

            // 小时列 (01-12)
            ScrollablePickerColumn(
                data = hourData,
                columnWidth = columnWidth,
                itemHeight = itemHeight,
                visibleItems = visibleItems,
                centerOffset = centerOffset,
                selectedIndex = hour12 - 1,
                onSelected = { index -> hour12 = index + 1 }
            )

            // 分钟列 (00-59)
            ScrollablePickerColumn(
                data = minuteData,
                columnWidth = columnWidth,
                itemHeight = itemHeight,
                visibleItems = visibleItems,
                centerOffset = centerOffset,
                selectedIndex = minute,
                onSelected = { index -> minute = index }
            )
        }
    }
}

/**
 * 可滚动选择器列
 *
 * 列表结构：[centerOffset 个空白项] + [真实选项] + [centerOffset 个空白项]
 * 因此当列表滚动到 firstVisibleItemIndex = i 时，视口第 centerOffset+1 行（即高亮行）
 * 正好是 data[i]，据此实现"居中即选中"。
 *
 * @param selectedIndex 当前选中项在 data 中的下标（由调用方持有）
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScrollablePickerColumn(
    data: List<String>,
    columnWidth: Dp,
    itemHeight: Dp,
    visibleItems: Int,
    centerOffset: Int,
    selectedIndex: Int,
    onSelected: (Int) -> Unit
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    // 吸附由官方 SnapFlingBehavior 完成，滚动结束自动对齐到整项
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    // 距视口中心最近的项 → 换算为 data 下标（空白头部占 centerOffset 项）
    val centeredIndex by remember(data.size, centerOffset) {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            if (visible.isEmpty()) return@derivedStateOf -1
            val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
            val nearest = visible.minByOrNull {
                abs(it.offset + it.size / 2 - viewportCenter)
            } ?: return@derivedStateOf -1
            (nearest.index - centerOffset).coerceIn(0, data.lastIndex)
        }
    }

    // 滚动过程中实时同步选中项：滚动到哪一项即选中该项，无需再点击
    LaunchedEffect(centeredIndex) {
        if (centeredIndex >= 0 && centeredIndex != selectedIndex) {
            onSelected(centeredIndex)
        }
    }

    // 点击选项后滚动到居中位置；用户正在滚动时不打断（避免与用户操作抢滚动权）
    LaunchedEffect(selectedIndex) {
        if (!listState.isScrollInProgress && centeredIndex >= 0 && centeredIndex != selectedIndex) {
            listState.animateScrollToItem(selectedIndex)
        }
    }

    LazyColumn(
        state = listState,
        flingBehavior = flingBehavior,
        modifier = Modifier
            .width(columnWidth)
            .height(itemHeight * visibleItems),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 顶部空白项：使第一个真实项能停留在居中高亮行
        items(centerOffset) {
            Spacer(modifier = Modifier.height(itemHeight))
        }

        itemsIndexed(data) { index, text ->
            val isSelected = index == selectedIndex
            Box(
                modifier = Modifier
                    .height(itemHeight)
                    .fillMaxWidth()
                    .clickable { onSelected(index) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = text,
                    fontSize = if (isSelected) 18.sp else 15.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Primary else Color(0xFFCCCCCC),
                    textAlign = TextAlign.Center
                )
            }
        }

        // 底部空白项：使最后一个真实项能停留在居中高亮行
        items(centerOffset) {
            Spacer(modifier = Modifier.height(itemHeight))
        }
    }
}
