package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.joysong.app.R
import com.joysong.app.domain.model.Diary
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.EmptyView
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDiariesScreen(
    onBackClick: () -> Unit,
    onDiaryClick: (String) -> Unit = {},
    onCreateDiaryClick: () -> Unit = {},
    onEditDiaryClick: (String) -> Unit = {},
    viewModel: MyDiariesViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()
    val scope = rememberCoroutineScope()

    // Delete confirmation dialog state
    var diaryToDelete by remember { mutableStateOf<Diary?>(null) }

    // 每次页面可见时重新加载日记列表（发布、删除、编辑后自动刷新）
    LifecycleResumeEffect(Unit) {
        viewModel.loadDiaries()
        onPauseOrDispose { }
    }

    // Handle delete state changes
    LaunchedEffect(deleteState) {
        when (deleteState) {
            is DeleteState.Success -> {
                diaryToDelete = null
                viewModel.resetDeleteState()
            }
            is DeleteState.Error -> {
                diaryToDelete = null
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.my_diaries), onBackClick = onBackClick) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateDiaryClick,
                containerColor = PrimaryDark,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = TextOnPrimary
                )
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.error != null -> ErrorView(
                message = uiState.error ?: stringResource(R.string.error_retry),
                onRetry = { viewModel.loadDiaries() }
            )
            uiState.diaries.isEmpty() -> EmptyView(message = stringResource(R.string.my_diaries_empty))
            else -> {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshDiaries() },
                    state = pullRefreshState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Background),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(uiState.diaries) { diary ->
                            DiaryCard(
                                diary = diary,
                                onClick = { onDiaryClick(diary.id) },
                                showMenu = true,
                                onEditClick = { onEditDiaryClick(diary.id) },
                                onDeleteClick = { diaryToDelete = diary },
                                showBeforeAfter = true,
                                showProjectTag = true,
                                showStats = true
                            )
                        }
                    }
                }
            }
        }
    }

    // Delete confirmation dialog
    diaryToDelete?.let { diary ->
        AlertDialog(
            onDismissRequest = { diaryToDelete = null },
            title = { Text(text = stringResource(R.string.delete_diary)) },
            text = { Text(text = stringResource(R.string.delete_diary_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { viewModel.deleteDiary(diary.id) }
                }) {
                    val isLoading = deleteState is DeleteState.Loading
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = PrimaryDark
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.delete),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { diaryToDelete = null }) {
                    Text(text = stringResource(R.string.cancel))
                }
            }
        )
    }
}
