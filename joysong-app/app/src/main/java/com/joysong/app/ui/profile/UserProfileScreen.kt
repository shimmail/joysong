package com.joysong.app.ui.profile

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cake
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.ReportRequestDto
import com.joysong.app.data.remote.dto.UserProfileResponseDto
import com.joysong.app.domain.model.Diary
import com.joysong.app.ui.components.DiaryCard
import com.joysong.app.ui.components.DiaryCardStyle
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── UI State ──────────────────────────────────────────────────────────────────

sealed class UserProfileUiState {
    data object Loading : UserProfileUiState()
    data class Success(
        val profile: UserProfileResponseDto,
        val diaries: List<Diary>
    ) : UserProfileUiState()
    data class Error(val message: String) : UserProfileUiState()
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class UserProfileViewModel @Inject constructor(
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<UserProfileUiState>(UserProfileUiState.Loading)
    val uiState: StateFlow<UserProfileUiState> = _uiState

    private val _selectedTab = MutableStateFlow(0)
    val selectedTab: StateFlow<Int> = _selectedTab

    private val _isOwnProfile = MutableStateFlow(false)
    val isOwnProfile: StateFlow<Boolean> = _isOwnProfile

    private val _hasReported = MutableStateFlow(false)
    val hasReported: StateFlow<Boolean> = _hasReported

    private val _isSubmittingReport = MutableStateFlow(false)
    val isSubmittingReport: StateFlow<Boolean> = _isSubmittingReport

    fun loadProfile(userId: String) {
        viewModelScope.launch {
            // 判断是否为自己的主页
            val currentUserId = tokenManager.getUserId()
            _isOwnProfile.value = userId == currentUserId

            _uiState.value = UserProfileUiState.Loading
            try {
                val profileResponse = apiService.getUserPublicProfile(userId)
                if (profileResponse.code == 200 && profileResponse.data != null) {
                    val profile = profileResponse.data
                    // Load user's published diaries via dedicated endpoint
                    val diariesResponse = apiService.getUserDiariesPublic(userId)
                    val userDiaries = if (diariesResponse.code == 200 && diariesResponse.data != null) {
                        diariesResponse.data.map { it.toDomain() }
                    } else emptyList()
                    _uiState.value = UserProfileUiState.Success(profile, userDiaries)

                    // 检查是否已举报该用户
                    try {
                        val checkResponse = apiService.checkReported("user", userId)
                        if (checkResponse.code == 200 && checkResponse.data != null) {
                            _hasReported.value = checkResponse.data.reported
                        }
                    } catch (_: Exception) {}
                } else {
                    _uiState.value = UserProfileUiState.Error(profileResponse.message)
                }
            } catch (e: Exception) {
                _uiState.value = UserProfileUiState.Error(e.message ?: "加载失败")
            }
        }
    }

    fun submitReport(userId: String, reason: String, description: String?) {
        viewModelScope.launch {
            _isSubmittingReport.value = true
            try {
                val response = apiService.submitReport(
                    ReportRequestDto(
                        targetType = "user",
                        targetId = userId,
                        reason = reason,
                        description = description
                    )
                )
                if (response.code == 200) {
                    _hasReported.value = true
                }
                _isSubmittingReport.value = false
            } catch (e: Exception) {
                _isSubmittingReport.value = false
            }
        }
    }

    fun selectTab(index: Int) {
        _selectedTab.value = index
    }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileScreen(
    userId: String,
    onBackClick: () -> Unit,
    onDiaryClick: (String) -> Unit = {},
    onMessageClick: (String) -> Unit = {},
    viewModel: UserProfileViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()
    val isOwnProfile by viewModel.isOwnProfile.collectAsState()
    val hasReported by viewModel.hasReported.collectAsState()
    val isSubmittingReport by viewModel.isSubmittingReport.collectAsState()
    val context = LocalContext.current

    var showMenu by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var selectedReasonIndex by remember { mutableIntStateOf(-1) }
    var reportDescription by remember { mutableStateOf("") }

    LaunchedEffect(userId) {
        viewModel.loadProfile(userId)
    }

    val reportReasons = listOf("骚扰/辱骂", "虚假信息", "诈骗嫌疑", "其他")

    // 举报对话框
    if (showReportDialog) {
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
            title = { Text("举报该用户", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("请选择举报原因：", fontSize = 14.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.height(8.dp))
                    reportReasons.forEachIndexed { index, reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReasonIndex = index }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedReasonIndex == index,
                                onClick = { selectedReasonIndex = index },
                                colors = RadioButtonDefaults.colors(selectedColor = Primary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = reason, fontSize = 14.sp, color = TextPrimary)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = reportDescription,
                        onValueChange = { reportDescription = it },
                        label = { Text("补充描述（可选）") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (selectedReasonIndex >= 0) {
                            viewModel.submitReport(
                                userId = userId,
                                reason = reportReasons[selectedReasonIndex],
                                description = reportDescription.ifBlank { null }
                            )
                            showReportDialog = false
                            selectedReasonIndex = -1
                            reportDescription = ""
                            Toast.makeText(context, "举报已提交", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "请选择举报原因", Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = !isSubmittingReport
                ) {
                    Text("提交", color = if (selectedReasonIndex >= 0) Primary else TextHint)
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) {
                    Text("取消")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = "个人主页",
                onBackClick = onBackClick,
                actions = {
                    if (!isOwnProfile) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    imageVector = Icons.Outlined.MoreVert,
                                    contentDescription = "更多操作"
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(
                                                imageVector = Icons.Outlined.Flag,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp),
                                                tint = if (hasReported) TextHint else TextPrimary
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = if (hasReported) "已举报" else "举报该用户",
                                                color = if (hasReported) TextHint else TextPrimary
                                            )
                                        }
                                    },
                                    onClick = {
                                        showMenu = false
                                        if (!hasReported) {
                                            showReportDialog = true
                                        }
                                    },
                                    enabled = !hasReported
                                )
                            }
                        }
                    }
                }
            )
        },
        containerColor = Background
    ) { padding ->
        when (val state = uiState) {
            is UserProfileUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            is UserProfileUiState.Error -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorView(
                        message = state.message,
                        onRetry = { viewModel.loadProfile(userId) }
                    )
                }
            }
            is UserProfileUiState.Success -> {
                UserProfileContent(
                    profile = state.profile,
                    diaries = state.diaries,
                    selectedTab = selectedTab,
                    isOwnProfile = isOwnProfile,
                    onTabSelected = { viewModel.selectTab(it) },
                    onDiaryClick = onDiaryClick,
                    onMessageClick = onMessageClick,
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
}

@Composable
private fun UserProfileContent(
    profile: UserProfileResponseDto,
    diaries: List<Diary>,
    selectedTab: Int,
    isOwnProfile: Boolean,
    onTabSelected: (Int) -> Unit,
    onDiaryClick: (String) -> Unit,
    onMessageClick: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        // User info section
        item {
            UserInfoSection(profile)
        }

        // Stats section
        item {
            UserStatsSection(profile)
        }

        // Follow & Message buttons (only for other users' profiles)
        if (!isOwnProfile) {
            item {
                ProfileActionButtons(
                    onFollowClick = {
                        Toast.makeText(context, "功能开发中", Toast.LENGTH_SHORT).show()
                    },
                    onMessageClick = {
                        onMessageClick(profile.id)
                    }
                )
            }
        }

        // Tab bar
        item {
            ProfileTabBar(
                selectedTab = selectedTab,
                onTabSelected = onTabSelected
            )
        }

        // Tab content
        if (selectedTab == 0) {
            // Diaries tab
            if (diaries.isEmpty()) {
                item {
                    EmptyPlaceholder("暂无日记")
                }
            } else {
                items(diaries, key = { it.id }) { diary ->
                    DiaryCard(
                        diary = diary,
                        style = DiaryCardStyle.VERTICAL,
                        onClick = { onDiaryClick(diary.id) },
                        showMenu = false,
                        showShare = false,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }
            }
        } else {
            // Favorites tab - placeholder
            item {
                EmptyPlaceholder("暂无收藏")
            }
        }
    }
}

@Composable
private fun UserInfoSection(profile: UserProfileResponseDto) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (!profile.avatar.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(profile.avatar)
                        .crossfade(true)
                        .build(),
                    contentDescription = profile.nickname,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(
                    text = profile.nickname.firstOrNull()?.toString() ?: "?",
                    fontSize = 32.sp,
                    color = TextSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Nickname
        Text(
            text = profile.nickname,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        // Gender / City / Birthday row
        val hasGender = !profile.gender.isNullOrBlank()
        val hasCity = !profile.city.isNullOrBlank()
        val hasBirthday = !profile.birthday.isNullOrBlank()
        if (hasGender || hasCity || hasBirthday) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (hasGender) {
                    Icon(
                        imageVector = if (profile.gender == "女") Icons.Filled.Female else Icons.Filled.Male,
                        contentDescription = null,
                        tint = if (profile.gender == "女") Primary else PrimaryDark,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = profile.gender ?: "",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                if (hasGender && hasCity) {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (hasCity) {
                    Icon(
                        imageVector = Icons.Filled.LocationOn,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = profile.city ?: "",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                if (hasCity && hasBirthday) {
                    Spacer(modifier = Modifier.width(12.dp))
                }
                if (hasBirthday) {
                    Icon(
                        imageVector = Icons.Filled.Cake,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = profile.birthday ?: "",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }
        }

        // Bio
        if (!profile.bio.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = profile.bio ?: "",
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

@Composable
private fun UserStatsSection(profile: UserProfileResponseDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatItem(count = profile.diaryCount, label = "日记")
        StatItem(count = profile.followingCount, label = "关注")
        StatItem(count = profile.followerCount, label = "粉丝")
    }
}

@Composable
private fun StatItem(count: Int, label: String) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Text(
            text = "$count",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary
        )
    }
}

@Composable
private fun ProfileActionButtons(
    onFollowClick: () -> Unit,
    onMessageClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 关注按钮
        Button(
            onClick = onFollowClick,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Primary,
                contentColor = TextOnPrimary
            ),
            shape = RoundedCornerShape(22.dp)
        ) {
            Text(
                text = "关注",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // 发私信按钮
        Button(
            onClick = onMessageClick,
            modifier = Modifier
                .weight(1f)
                .height(44.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White
            ),
            shape = RoundedCornerShape(22.dp)
        ) {
            Icon(
                imageVector = Icons.Outlined.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "发私信",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun ProfileTabBar(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {
    TabRow(
        selectedTabIndex = selectedTab,
        containerColor = Surface,
        contentColor = Primary,
        indicator = { tabPositions ->
            TabRowDefaults.SecondaryIndicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                color = Primary
            )
        }
    ) {
        Tab(
            selected = selectedTab == 0,
            onClick = { onTabSelected(0) },
            text = {
                Text(
                    text = "Ta的日记",
                    color = if (selectedTab == 0) Primary else TextSecondary,
                    fontWeight = if (selectedTab == 0) FontWeight.Medium else FontWeight.Normal
                )
            }
        )
        Tab(
            selected = selectedTab == 1,
            onClick = { onTabSelected(1) },
            text = {
                Text(
                    text = "Ta的收藏",
                    color = if (selectedTab == 1) Primary else TextSecondary,
                    fontWeight = if (selectedTab == 1) FontWeight.Medium else FontWeight.Normal
                )
            }
        )
    }
}

@Composable
private fun EmptyPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            fontSize = 14.sp,
            color = TextHint
        )
    }
}
