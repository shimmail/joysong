package com.joysong.app.ui.detail

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R
import com.joysong.app.domain.model.Project
import kotlinx.coroutines.launch

// Sheet colors
private val SheetBg = Color.White
private val SheetTitleColor = Color(0xFF1A1A1A)
private val SheetHintColor = Color(0xFF999999)
private val SheetAccent = Color(0xFF1A1A1A)
private val SheetDivider = Color(0xFFEEEEEE)
private val SheetTabBg = Color(0xFFF5F5F5)
private val SheetTabSelected = Color(0xFF1A1A1A)
private val SheetDisclaimerBg = Color(0xFFFFF8E1)
private val SheetDisclaimerText = Color(0xFF8D6E00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectInfoBottomSheet(
    project: Project,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val detailContent = project.detailContent
    val sections = remember(detailContent) { parseDetailSections(detailContent) }
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val lazyListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    val disclaimerText = stringResource(R.string.risk_disclaimer)
    val mechanismLabel = stringResource(R.string.risk_mechanism)
    val suitContraLabel = stringResource(R.string.encyclopedia_suitable_contraindicated)
    val recoveryLabel = stringResource(R.string.recovery_cycle)
    val riskLabel = stringResource(R.string.tab_potential_risks)
    val allLabels = remember(mechanismLabel, suitContraLabel, recoveryLabel, riskLabel) {
        listOf(mechanismLabel, suitContraLabel, recoveryLabel, riskLabel)
    }

    // Sync selected tab when user scrolls the content
    LaunchedEffect(lazyListState) {
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .collect { index ->
                selectedTabIndex = index.coerceIn(0, 3)
            }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SheetBg,
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SheetBg)
        ) {
            // ── Top bar: Title + Close ──────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.project_info_sheet_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = SheetTitleColor
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = null,
                        tint = SheetHintColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            // ── RiskTabRow ──────────────────────────────────────────────────────
            RiskTabRow(
                tabs = allLabels,
                selectedTabIndex = selectedTabIndex,
                onTabSelected = { index ->
                    selectedTabIndex = index
                    coroutineScope.launch {
                        lazyListState.animateScrollToItem(index)
                    }
                }
            )

            // ── Content area: All sections in a scrollable list ──────────────────
            val riskSectionTitles = listOf("作用原理", "适用人群", "禁忌人群", "恢复周期", "潜在风险")

            LazyColumn(
                state = lazyListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                riskSectionTitles.forEachIndexed { index, title ->
                    item {
                        val matchedSection = sections.find { it.title.contains(title) }
                        val content = matchedSection?.content?.let {
                            it.replace(Regex("<[^>]*>"), "")
                                .replace("&nbsp;", " ")
                                .replace("&amp;", "&")
                                .replace("&lt;", "<")
                                .replace("&gt;", ">")
                                .trim()
                        }

                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = title,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SheetTitleColor
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (!content.isNullOrBlank()) {
                                Text(
                                    text = content,
                                    fontSize = 14.sp,
                                    color = Color(0xFF666666),
                                    lineHeight = 24.sp
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.no_risk_info),
                                    fontSize = 14.sp,
                                    color = SheetHintColor
                                )
                            }

                        }
                    }
                }

                // Disclaimer at bottom
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SheetDisclaimerBg)
                            .padding(12.dp)
                    ) {
                        Text(
                            text = disclaimerText,
                            fontSize = 12.sp,
                            color = SheetDisclaimerText,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

// ── RiskTabRow ────────────────────────────────────────────────────────────────

@Composable
private fun RiskTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit
) {
    ScrollableTabRow(
        selectedTabIndex = selectedTabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)),
        containerColor = SheetBg,
        contentColor = SheetAccent,
        edgePadding = 16.dp,
        divider = {},
        indicator = { tabPositions ->
            if (selectedTabIndex < tabPositions.size) {
                TabRowDefaults.Indicator(
                    modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                    color = SheetAccent,
                    height = 2.dp
                )
            }
        }
    ) {
        tabs.forEachIndexed { index, label ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabSelected(index) },
                text = {
                    Text(
                        text = label,
                        fontSize = 14.sp,
                        fontWeight = if (selectedTabIndex == index) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selectedTabIndex == index) SheetTitleColor else SheetHintColor
                    )
                }
            )
        }
    }
    // Divider below tabs
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(SheetDivider)
    )
}

