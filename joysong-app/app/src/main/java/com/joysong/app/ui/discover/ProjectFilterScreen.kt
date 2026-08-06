package com.joysong.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R
import com.joysong.app.domain.model.FilterOptions
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

/**
 * 筛选页 — 左右分栏布局
 * 左侧：纵向导航（项目类别 / 项目标签 / 城市）
 * 右侧：三列网格排列的 Chip 方块，全部支持多选
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectFilterScreen(
    filterOptions: FilterOptions,
    initialCategories: Set<String>,
    initialTags: Set<String>,
    initialCities: Set<String>,
    onConfirm: (categories: Set<String>, tags: Set<String>, cities: Set<String>) -> Unit,
    onBack: () -> Unit
) {
    var selectedCategories by remember { mutableStateOf(initialCategories) }
    var selectedTags by remember { mutableStateOf(initialTags) }
    var selectedCities by remember { mutableStateOf(initialCities) }

    // 当前选中的左侧导航项：0=项目类别, 1=项目标签, 2=城市
    var selectedNavIndex by remember { mutableIntStateOf(0) }

    val navItems = listOf(
        stringResource(R.string.filter_category),
        stringResource(R.string.filter_tags),
        stringResource(R.string.filter_city)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.filter_title),
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .size(24.dp)
                            .clickable { onBack() },
                        tint = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Background
                )
            )
        },
        bottomBar = {
            FilterBottomBar(
                onReset = {
                    selectedCategories = emptySet()
                    selectedTags = emptySet()
                    selectedCities = emptySet()
                },
                onConfirm = {
                    onConfirm(selectedCategories, selectedTags, selectedCities)
                }
            )
        },
        containerColor = Background
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // ===== 左侧导航栏 (1/4 宽度) =====
            FilterLeftNav(
                items = navItems,
                selectedIndex = selectedNavIndex,
                selectedCounts = listOf(
                    selectedCategories.size,
                    selectedTags.size,
                    selectedCities.size
                ),
                onItemClick = { selectedNavIndex = it },
                modifier = Modifier.weight(1f)
            )

            // ===== 右侧内容区 (3/4 宽度) =====
            Column(
                modifier = Modifier
                    .weight(3f)
                    .fillMaxHeight()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                when (selectedNavIndex) {
                    0 -> FilterChipGrid(
                        items = filterOptions.categories,
                        selectedItems = selectedCategories,
                        onToggle = { item ->
                            selectedCategories = if (selectedCategories.contains(item)) {
                                selectedCategories - item
                            } else {
                                selectedCategories + item
                            }
                        }
                    )
                    1 -> FilterChipGrid(
                        items = filterOptions.tags,
                        selectedItems = selectedTags,
                        onToggle = { item ->
                            selectedTags = if (selectedTags.contains(item)) {
                                selectedTags - item
                            } else {
                                selectedTags + item
                            }
                        }
                    )
                    2 -> FilterChipGrid(
                        items = filterOptions.cities,
                        selectedItems = selectedCities,
                        onToggle = { item ->
                            selectedCities = if (selectedCities.contains(item)) {
                                selectedCities - item
                            } else {
                                selectedCities + item
                            }
                        }
                    )
                }
            }
        }
    }
}

/**
 * 左侧纵向导航栏，带选中高亮 + 左侧竖线指示器
 */
@Composable
private fun FilterLeftNav(
    items: List<String>,
    selectedIndex: Int,
    selectedCounts: List<Int>,
    onItemClick: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(SurfaceVariant)
    ) {
        items.forEachIndexed { index, item ->
            val isSelected = selectedIndex == index
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onItemClick(index) }
                    .background(if (isSelected) Background else Color.Transparent)
                    .padding(vertical = 16.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 左侧竖线指示器
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(20.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (isSelected) PrimaryDark else Color.Transparent)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item,
                        fontSize = 14.sp,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) PrimaryDark else TextSecondary
                    )
                    // 显示已选数量
                    val count = selectedCounts.getOrNull(index) ?: 0
                    if (count > 0) {
                        Text(
                            text = stringResource(R.string.filter_count_format, count),
                            fontSize = 11.sp,
                            color = PrimaryDark,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 右侧 Chip 网格，使用自适应列数 LazyVerticalGrid 排列，支持多选
 */
@Composable
private fun FilterChipGrid(
    items: List<String>,
    selectedItems: Set<String>,
    onToggle: (String) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 90.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(items) { item ->
            val isSelected = selectedItems.contains(item)
            FilterChipItem(
                text = item,
                isSelected = isSelected,
                onClick = { onToggle(item) }
            )
        }
    }
}

/**
 * 单个筛选方块 Chip
 * 选中：filled 样式（PrimaryDark 背景 + 白色文字）
 * 未选中：outlined 样式（边框 + 深色文字）
 */
@Composable
private fun FilterChipItem(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .then(
                if (isSelected) {
                    Modifier.background(PrimaryDark, shape)
                } else {
                    Modifier
                        .background(Background, shape)
                        .border(1.dp, SurfaceVariant, shape)
                }
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
            color = if (isSelected) Color.White else TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 底部按钮栏：左侧重置（较小较淡），右侧确定（较大醒目）
 */
@Composable
private fun FilterBottomBar(
    onReset: () -> Unit,
    onConfirm: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier
                .weight(1f)
                .height(40.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
        ) {
            Text(
                text = stringResource(R.string.filter_reset),
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        Button(
            onClick = onConfirm,
            modifier = Modifier
                .weight(2f)
                .height(50.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary)
        ) {
            Text(
                text = stringResource(R.string.filter_confirm),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
