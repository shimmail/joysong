package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator

private val PageBg = Color(0xFFF5F5F5)
private val CardBg = Color.White
private val TitleColor = Color(0xFF1A1A1A)
private val HintColor = Color(0xFF999999)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoctorAllDiariesScreen(
    doctorId: String,
    onBackClick: () -> Unit,
    onDiaryClick: (String) -> Unit,
    viewModel: DoctorAllDiariesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(doctorId) { viewModel.load(doctorId) }

    Scaffold(
        topBar = {
            JoysongTopBar(title = stringResource(R.string.all_diaries_title), onBackClick = onBackClick)
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(message = state.message, onRetry = { viewModel.load(doctorId) })
            is DetailUiState.Success -> {
                val diaries = state.data
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh(doctorId) },
                    modifier = Modifier.fillMaxSize().padding(innerPadding).background(PageBg)
                ) {
                    if (diaries.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.no_user_diaries), fontSize = 14.sp, color = HintColor)
                        }
                    } else {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(diaries, key = { it.id }) { diary ->
                                DiaryGridCard(diary = diary, onClick = { onDiaryClick(diary.id) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DiaryGridCard(diary: Diary, onClick: () -> Unit) {
    val beforeImg = diary.beforeImages.split(",").firstOrNull { it.isNotBlank() } ?: ""
    val afterImg = diary.afterImages.split(",").firstOrNull { it.isNotBlank() } ?: ""
    val hasBeforeAfter = beforeImg.isNotBlank() || afterImg.isNotBlank()

    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(CardBg)
            .clickable { onClick() }
    ) {
        // 术前术后并列展示（标题上方）
        if (hasBeforeAfter) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (beforeImg.isNotBlank()) {
                    Column(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = beforeImg, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 8.dp)),
                            contentScale = ContentScale.Crop, onError = { }
                        )
                        Text("术前", fontSize = 10.sp, color = HintColor, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
                if (afterImg.isNotBlank()) {
                    Column(modifier = Modifier.weight(1f)) {
                        AsyncImage(
                            model = afterImg, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topEnd = 8.dp)),
                            contentScale = ContentScale.Crop, onError = { }
                        )
                        Text("术后", fontSize = 10.sp, color = HintColor, modifier = Modifier.align(Alignment.CenterHorizontally))
                    }
                }
            }
        } else if (diary.coverImage.isNotBlank()) {
            AsyncImage(
                model = diary.coverImage,
                contentDescription = diary.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop,
                onError = { }
            )
        } else {
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFEEEEEE)),
                contentAlignment = Alignment.Center
            ) {
                Text(diary.title.take(1), fontSize = 20.sp, color = HintColor)
            }
        }
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                diary.title,
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = TitleColor,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(diary.publishDate, fontSize = 11.sp, color = HintColor, maxLines = 1)
        }
    }
}
