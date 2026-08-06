package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator

private val PageBg = Color(0xFFF5F5F5)
private val HintColor = Color(0xFF999999)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectAllDiariesScreen(
    projectId: String,
    onBackClick: () -> Unit,
    onDiaryClick: (String) -> Unit,
    viewModel: ProjectAllDiariesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    LaunchedEffect(projectId) {
        viewModel.load(projectId)
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.tab_user_diaries),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        when (val state = uiState) {
            is DetailUiState.Loading -> LoadingIndicator()
            is DetailUiState.Error -> ErrorView(
                message = state.message,
                onRetry = { viewModel.load(projectId) }
            )
            is DetailUiState.Success -> {
                val diaries = state.data
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh(projectId) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(PageBg)
                ) {
                    if (diaries.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.no_user_diaries),
                                fontSize = 14.sp,
                                color = HintColor
                            )
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
