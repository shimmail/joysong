package com.joysong.app.ui.detail

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.SentimentDissatisfied
import androidx.compose.material.icons.outlined.SentimentNeutral
import androidx.compose.material.icons.filled.SentimentDissatisfied
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.content.Intent
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.joysong.app.R
import com.joysong.app.domain.model.Comment
import com.joysong.app.domain.model.Diary
import com.joysong.app.ui.components.ReportDialog
import com.joysong.app.ui.report.ReportViewModel
import com.joysong.app.ui.components.DefaultUserAvatar
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.buildDiaryShareText
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.StarRatingBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import androidx.compose.material3.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.translation.AiTranslationStatus
import com.joysong.app.ui.translation.TranslationUiState
import com.joysong.app.ui.translation.displayText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryDetailScreen(
    diaryId: String,
    onBackClick: () -> Unit,
    onProjectClick: (String) -> Unit = {},
    onDoctorClick: (String) -> Unit = {},
    onInstitutionClick: (String) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    viewModel: DetailViewModel = hiltViewModel(),
    reportViewModel: ReportViewModel = hiltViewModel()
) {
    val diaryState by viewModel.diary.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    // 互动状态
    val isLiked by viewModel.isLiked
    val likeCount by viewModel.likeCount
    val isFavorited by viewModel.isFavorited
    val favoriteCount by viewModel.favoriteCount
    val commentList by viewModel.comments
    val commentCount by viewModel.commentCount
    val currentUserId by viewModel.currentUserId
    val repliesMap by viewModel.repliesMap
    val translationStates by viewModel.translationStates

    // 评论输入
    var commentText by remember { mutableStateOf("") }
    var showDeleteDialog by remember { mutableStateOf<Comment?>(null) }
    // 回复状态：null 表示普通评论，非 null 表示回复某个评论/回复
    var replyTarget by remember { mutableStateOf<Comment?>(null) }
    var replyParentId by remember { mutableStateOf<String?>(null) } // 父评论 ID（顶级评论）
    var replyTrigger by remember { mutableStateOf(0) } // 每次点击回复时自增，确保每次都触发键盘
    var focusCommentTrigger by remember { mutableStateOf(0) } // 点击评论图标时触发
    // 输入框焦点
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // 展开的回复列表
    val expandedReplies = remember { mutableStateListOf<String>() }
    // 折叠的评论列表（从 ViewModel 获取持久化状态）
    val collapsedComments by viewModel.collapsedComments.collectAsState()

    LaunchedEffect(diaryId) {
        viewModel.loadDiary(diaryId)
        viewModel.checkLikeStatus(diaryId)
        viewModel.checkFavoriteStatus(diaryId)
        viewModel.loadComments(diaryId)
        viewModel.loadCollapsedComments()
    }

    // 自动展开有子回复的父评论
    LaunchedEffect(repliesMap) {
        repliesMap.keys.forEach { parentId ->
            if (!expandedReplies.contains(parentId)) {
                expandedReplies.add(parentId)
            }
        }
    }

    // 计算 LazyColumn 内容区项数（用于滚动定位）
    fun getContentItemCount(): Int {
        var count = 0
        val d = (diaryState as? DetailUiState.Success<Diary>)?.data
        if (d != null) {
            if (d.beforeImages.split(",").any { it.trim().isNotEmpty() }) count++
            if (d.afterImages.split(",").any { it.trim().isNotEmpty() }) count++
        }
        return count + 2 // +2: 日记详情内容区 + 评论头部
    }

    // 每次点击回复：先弹出键盘，后滚动页面
    LaunchedEffect(replyTrigger) {
        if (replyTrigger > 0 && replyTarget != null) {
            val target = replyTarget!!
            val parentCommentId = replyParentId ?: target.id
            val isReplyToSubComment = target.parentId != null

            // 检测键盘是否已弹出
            val imeHeight = imeInsets.getBottom(density)
            val isKeyboardVisible = imeHeight > 0

            // 如果键盘未弹出，先弹键盘再滚动
            if (!isKeyboardVisible) {
                focusManager.clearFocus()
                delay(50)
                focusRequester.requestFocus()
                delay(300) // 等待键盘完全弹出
            }

            // 滚动到目标评论
            val commentIndex = commentList.indexOfFirst { it.id == parentCommentId }
            if (commentIndex >= 0) {
                val scrollIndex = getContentItemCount() + commentIndex
                if (isReplyToSubComment) {
                    // 子评论：根据实际内容动态计算偏移，使目标子评论出现在视口顶部
                    val parentComment = commentList[commentIndex]
                    val replies = repliesMap[parentCommentId] ?: emptyList()
                    val replyIndex = replies.indexOfFirst { it.id == target.id }.coerceAtLeast(0)

                    // 估算文本行数：手机屏约 22 字/行，每行约 20dp（14sp + 行间距）
                    val lineHeightDp = 20
                    val charsPerLine = 22
                    fun textLines(text: String) = maxOf(1, (text.length + charsPerLine - 1) / charsPerLine)

                    // 父评论内容区：头像(32) + 用户名(18) + 间距(4) + 内容 + 间距(6) + 时间行(18) + 展开按钮(32) + 回复前间距(12)
                    // val parentContentDp = 32 + 18 + 4 + (textLines(parentComment.content) * lineHeightDp) + 6 + 18 + 32 + 12
                    val parentContentDp = 100 + (textLines(parentComment.content) * lineHeightDp)
                    // 每条前置回复：头像(24) + 内容 + 间距(12)
                    /*val precedingRepliesDp = (0 until replyIndex).sumOf { i ->
                        24 + (textLines(replies[i].content) * lineHeightDp) + 12
                    }*/
                    val precedingRepliesDp = (0 until replyIndex).sumOf { i ->
                        80 + (textLines(replies[i].content) * lineHeightDp)
                    }

                    val offsetDp = parentContentDp + precedingRepliesDp
                    val offsetPx = with(density) { offsetDp.dp.toPx().toInt() }
                    listState.animateScrollToItem(scrollIndex, offsetPx)
                } else {
                    // 父评论：滚动到顶部
                    listState.animateScrollToItem(scrollIndex, 0)
                }
            }

            // 如果键盘已弹出，只需确保输入框有焦点
            if (isKeyboardVisible) {
                focusRequester.requestFocus()
            }
        }
    }

    // 点击评论图标：先弹键盘，后滚动到评论区
    LaunchedEffect(focusCommentTrigger) {
        if (focusCommentTrigger > 0) {
            replyTarget = null
            replyParentId = null
            commentText = ""

            // 检测键盘是否已弹出
            val imeHeight = imeInsets.getBottom(density)
            val isKeyboardVisible = imeHeight > 0

            // 如果键盘未弹出，先弹键盘再滚动
            if (!isKeyboardVisible) {
                focusManager.clearFocus()
                delay(50)
                focusRequester.requestFocus()
                delay(300)
            }

            val commentHeaderIndex = (getContentItemCount() - 1).coerceAtLeast(0)
            listState.animateScrollToItem(commentHeaderIndex)

            // 如果键盘已弹出，只需确保输入框有焦点
            if (isKeyboardVisible) {
                focusRequester.requestFocus()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        // TopBar
        JoysongTopBar(
            title = stringResource(R.string.diary_detail),
            onBackClick = onBackClick,
            actions = {
                val currentDiary = (diaryState as? DetailUiState.Success<Diary>)?.data
                if (currentDiary != null) {
                    val context = LocalContext.current
                    // 举报按钮
                    IconButton(onClick = {
                        reportViewModel.showReport("diary", currentDiary.id)
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
                            contentDescription = stringResource(R.string.report)
                        )
                    }
                    IconButton(onClick = {
                        val shareText = buildDiaryShareText(currentDiary)
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, currentDiary.title)
                            putExtra(Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(Intent.createChooser(intent, null))
                    }) {
                        Icon(
                            imageVector = Icons.Outlined.Share,
                            contentDescription = stringResource(R.string.share_diary)
                        )
                    }
                }
            }
        )

        // 内容区 - 占据剩余空间
        when (diaryState) {
            is DetailUiState.Loading -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
            is DetailUiState.Error -> {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    ErrorView(
                        message = (diaryState as DetailUiState.Error).message,
                        onRetry = { viewModel.loadDiary(diaryId) }
                    )
                }
            }
            is DetailUiState.Success -> {
                val diary = (diaryState as DetailUiState.Success<Diary>).data
                val beforeImages = diary.beforeImages
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val afterImages = diary.afterImages
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }

                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refreshDiary(diaryId) },
                    state = pullRefreshState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Background)
                ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                ) {
                    // 术前照区域
                    if (beforeImages.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.before_photos_label) + " (${beforeImages.size})",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                items(beforeImages) { url ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(url)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = stringResource(R.string.before_photos_label),
                                        modifier = Modifier
                                            .size(width = 300.dp, height = 240.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }

                    // 术后照区域
                    if (afterImages.isNotEmpty()) {
                        item {
                            Text(
                                text = stringResource(R.string.after_photos_label) + " (${afterImages.size})",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp)
                            )
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)
                            ) {
                                items(afterImages) { url ->
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(url)
                                            .crossfade(true)
                                            .build(),
                                        contentDescription = stringResource(R.string.after_photos_label),
                                        modifier = Modifier
                                            .size(width = 300.dp, height = 240.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }
                        }
                    }

                    // 日记详情内容区（作者、标题、评分、内容、标签、关联、互动）
                    item {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Author row
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { onUserClick(diary.userId) }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(SurfaceVariant),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (diary.authorAvatar.isNotBlank()) {
                                        AsyncImage(
                                            model = ImageRequest.Builder(LocalContext.current)
                                                .data(diary.authorAvatar)
                                                .crossfade(true)
                                                .build(),
                                            contentDescription = diary.authorName,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    } else {
                                        DefaultUserAvatar(
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = diary.authorName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = diary.publishDate,
                                        fontSize = 13.sp,
                                        color = TextHint
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // Title
                            Text(
                                text = diary.title,
                                style = MaterialTheme.typography.headlineSmall,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )

                            // Star rating
                            if (diary.rating > 0) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(R.string.surgery_rating),
                                        fontSize = 14.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    StarRatingBar(
                                        rating = diary.rating,
                                        onRatingChanged = {},
                                        readOnly = true,
                                        starSize = 20.dp
                                    )
                                }
                            }

                            // Content
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = translationStates["diary:${diary.id}"].displayText(diary.content),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                            val diaryTranslationKey = "diary:${diary.id}"
                            AiTranslationStatus(
                                state = translationStates[diaryTranslationKey],
                                onTranslate = {
                                    viewModel.translateContent(
                                        key = diaryTranslationKey,
                                        text = diary.content,
                                        contentType = "diary"
                                    )
                                },
                                onShowOriginal = { viewModel.showOriginal(diaryTranslationKey) },
                                onShowTranslation = { viewModel.showTranslation(diaryTranslationKey) }
                            )

                            // Tags
                            if (diary.tags.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(12.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    diary.tags.forEach { tag ->
                                        Text(
                                            text = "#$tag",
                                            fontSize = 13.sp,
                                            color = TextSecondary,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(SurfaceVariant)
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Association info
                            Spacer(modifier = Modifier.height(16.dp))
                            if (diary.projectId.isNotBlank() || diary.doctorId.isNotBlank() || diary.institutionId.isNotBlank()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(SurfaceVariant)
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (diary.projectId.isNotBlank()) {
                                        AssociationRow(
                                            label = stringResource(R.string.related_project),
                                            value = diary.projectName,
                                            onClick = { onProjectClick(diary.projectId) }
                                        )
                                    }
                                    if (diary.doctorId.isNotBlank()) {
                                        AssociationRow(
                                            label = stringResource(R.string.related_doctor),
                                            value = diary.doctorName,
                                            onClick = { onDoctorClick(diary.doctorId) }
                                        )
                                    }
                                    if (diary.institutionId.isNotBlank()) {
                                        AssociationRow(
                                            label = stringResource(R.string.related_institution),
                                            value = diary.institutionName,
                                            onClick = { onInstitutionClick(diary.institutionId) }
                                        )
                                    }
                                }
                            }

                            // ===== 互动行：点赞 + 收藏 + 评论 =====
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                val likeScale by animateFloatAsState(
                                    targetValue = if (isLiked) 1.15f else 1f,
                                    label = "likeScale"
                                )
                                // 点赞组
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        contentDescription = stringResource(R.string.liked),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .scale(likeScale)
                                            .pointerInput(Unit) {
                                                detectTapGestures { viewModel.toggleLike(diaryId) }
                                            },
                                        tint = if (isLiked) androidx.compose.ui.graphics.Color(0xFFE53935) else TextHint
                                    )
                                    if (likeCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = likeCount.toString(),
                                            fontSize = 13.sp,
                                            color = if (isLiked) androidx.compose.ui.graphics.Color(0xFFE53935) else TextSecondary
                                        )
                                    }
                                }
                                // 收藏组
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (isFavorited) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder,
                                        contentDescription = stringResource(R.string.favorited),
                                        modifier = Modifier
                                            .size(24.dp)
                                            .pointerInput(Unit) {
                                                detectTapGestures {
                                                    val coverImage = beforeImages.firstOrNull() ?: diary.coverImage
                                                    viewModel.toggleFavorite(diaryId, diary.title, coverImage)
                                                }
                                            },
                                        tint = if (isFavorited) androidx.compose.ui.graphics.Color(0xFFFFC107) else TextHint
                                    )
                                    if (favoriteCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = favoriteCount.toString(),
                                            fontSize = 13.sp,
                                            color = if (isFavorited) androidx.compose.ui.graphics.Color(0xFFFFC107) else TextSecondary
                                        )
                                    }
                                }
                                // 评论组 - 点击可聚焦输入框
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { focusCommentTrigger++ }
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.ChatBubbleOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(24.dp),
                                        tint = TextHint
                                    )
                                    if (commentCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = commentCount.toString(),
                                            fontSize = 13.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 评论区域头部
                    item {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            HorizontalDivider(thickness = 1.dp, color = SurfaceVariant)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = stringResource(R.string.comments_section) + " ($commentCount)",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            if (commentList.isEmpty()) {
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.no_comments),
                                    fontSize = 14.sp,
                                    color = TextHint,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        }
                    }

                    // 评论列表 - 每条独立懒加载
                    items(commentList) { comment ->
                        CommentItem(
                            comment = comment,
                            replies = repliesMap[comment.id] ?: emptyList(),
                            isExpanded = expandedReplies.contains(comment.id),
                            isCollapsed = collapsedComments.contains(comment.id),
                            collapsedReplyIds = collapsedComments,
                            isOwner = comment.userId == currentUserId,
                            onUserClick = onUserClick,
                            onReplyClick = {
                                // 回复顶级评论
                                replyTarget = comment
                                replyParentId = comment.id
                                commentText = ""
                                replyTrigger++
                            },
                            onReplyToReply = { reply ->
                                // 回复回复：parentId 为顶级评论，replyToUserId 为回复作者
                                replyTarget = reply
                                replyParentId = comment.id
                                commentText = ""
                                replyTrigger++
                                // 确保父评论的回复列表展开
                                if (!expandedReplies.contains(comment.id)) {
                                    expandedReplies.add(comment.id)
                                }
                            },
                            onToggleReplies = {
                                if (expandedReplies.contains(comment.id)) {
                                    expandedReplies.remove(comment.id)
                                } else {
                                    expandedReplies.add(comment.id)
                                    viewModel.loadReplies(comment.id)
                                }
                            },
                            onToggleCollapse = {
                                viewModel.toggleCommentCollapse(comment.id)
                            },
                            onLike = { viewModel.likeComment(comment.id) },
                            onLikeReply = { reply -> viewModel.likeComment(reply.id) },
                            onCollapseReply = { replyId ->
                                viewModel.toggleCommentCollapse(replyId)
                            },
                            onDelete = { showDeleteDialog = comment },
                            onDeleteReply = { reply -> showDeleteDialog = reply },
                            onReport = { reportViewModel.showReport("comment", comment.id) },
                            onReportReply = { reply -> reportViewModel.showReport("comment", reply.id) },
                            translationState = translationStates["comment:${comment.id}"],
                            onTranslate = {
                                viewModel.translateContent("comment:${comment.id}", comment.content, "comment")
                            },
                            onShowCommentOriginal = { viewModel.showOriginal("comment:${comment.id}") },
                            onShowCommentTranslation = { viewModel.showTranslation("comment:${comment.id}") },
                            replyTranslationState = { replyId -> translationStates["comment:$replyId"] },
                            onTranslateReply = { reply ->
                                viewModel.translateContent("comment:${reply.id}", reply.content, "comment")
                            },
                            onShowReplyOriginal = { replyId ->
                                viewModel.showOriginal("comment:$replyId")
                            },
                            onShowReplyTranslation = { replyId ->
                                viewModel.showTranslation("comment:$replyId")
                            },
                            currentUserId = currentUserId
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // 底部间距
                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
                }
            }
        }

        // 底部评论输入框 - 固定在底部
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding(),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(22.dp))
                        .background(Background)
                        .border(1.dp, SurfaceVariant, RoundedCornerShape(22.dp)),
                    contentAlignment = Alignment.CenterStart
                ) {
                    // 动态提示词：回复时显示 "回复 @用户名"，否则显示 "写评论..."
                    val hintText = replyTarget?.let {
                        stringResource(R.string.reply_to, it.userName)
                    } ?: stringResource(R.string.write_comment)

                    BasicTextField(
                        value = commentText,
                        onValueChange = { commentText = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = TextPrimary),
                        modifier = Modifier
                            .focusRequester(focusRequester)
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        decorationBox = { innerTextField ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (commentText.isEmpty()) {
                                    Text(
                                        text = hintText,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = TextHint
                                    )
                                }
                                innerTextField()
                            }
                        }
                    )
                }
                // 如果是回复模式，显示取消按钮
                if (replyTarget != null) {
                    IconButton(
                        onClick = {
                            replyTarget = null
                            replyParentId = null
                            commentText = ""
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.cancel),
                            tint = TextHint,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                IconButton(
                    onClick = {
                        if (commentText.isNotBlank()) {
                            val parentId = replyParentId
                            val replyToUserId = if (parentId != null) replyTarget?.userId else null
                            viewModel.addComment(
                                diaryId = diaryId,
                                content = commentText.trim(),
                                parentId = parentId,
                                replyToUserId = replyToUserId
                            )
                            commentText = ""
                            replyTarget = null
                            replyParentId = null
                            // 如果是回复，展开父评论的回复列表
                            if (parentId != null) {
                                expandedReplies.add(parentId)
                            }
                        }
                    },
                    enabled = commentText.isNotBlank()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Send,
                        contentDescription = stringResource(R.string.send),
                        tint = if (commentText.isNotBlank()) PrimaryDark else TextHint
                    )
                }
            }
        }
    }

    // 删除确认对话框
    if (showDeleteDialog != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text(stringResource(R.string.delete_comment)) },
            text = { Text(stringResource(R.string.confirm_delete_comment)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog?.let { comment ->
                        viewModel.deleteComment(comment.id, diaryId, comment.parentId)
                    }
                    showDeleteDialog = null
                    // 删除后下拉刷新日记内容 + 评论
                    viewModel.refreshDiary(diaryId)
                    coroutineScope.launch {
                        listState.animateScrollToItem(0)
                    }
                }) {
                    Text(stringResource(R.string.delete_comment), color = PrimaryDark)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // 举报对话框
    ReportDialog(
        reportViewModel = reportViewModel,
        onDismiss = { reportViewModel.hideReport() }
    )
}

// 时间格式化：移除 T，转为 "yyyy-MM-dd HH:mm"
private fun formatCommentTime(raw: String): String {
    return raw.replace("T", " ").let {
        if (it.length > 16) it.substring(0, 16) else it
    }
}

@Composable
private fun CommentItem(
    comment: Comment,
    replies: List<Comment>,
    isExpanded: Boolean,
    isCollapsed: Boolean,
    collapsedReplyIds: Set<String>,
    isOwner: Boolean,
    onUserClick: (String) -> Unit,
    onReplyClick: () -> Unit,
    onReplyToReply: (Comment) -> Unit,
    onToggleReplies: () -> Unit,
    onToggleCollapse: () -> Unit,
    onLike: () -> Unit,
    onLikeReply: (Comment) -> Unit,
    onCollapseReply: (String) -> Unit,
    onDelete: () -> Unit,
    onDeleteReply: (Comment) -> Unit,
    onReport: () -> Unit = {},
    onReportReply: (Comment) -> Unit = {},
    translationState: TranslationUiState?,
    onTranslate: () -> Unit,
    onShowCommentOriginal: () -> Unit,
    onShowCommentTranslation: () -> Unit,
    replyTranslationState: (String) -> TranslationUiState?,
    onTranslateReply: (Comment) -> Unit,
    onShowReplyOriginal: (String) -> Unit,
    onShowReplyTranslation: (String) -> Unit,
    currentUserId: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 头像
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .clickable { onUserClick(comment.userId) }
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (comment.userAvatar.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(comment.userAvatar)
                        .crossfade(true)
                        .build(),
                    contentDescription = comment.userName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                DefaultUserAvatar(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // 内容区
        Column(modifier = Modifier.weight(1f)) {
            // 用户名
            Text(
                text = comment.userName,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.clickable { onUserClick(comment.userId) }
            )

            Spacer(modifier = Modifier.height(4.dp))

            // 长按菜单状态
            var showContextMenu by remember { mutableStateOf(false) }

            // 评论内容（支持长按）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { showContextMenu = true },
                            onTap = { onReplyClick() }
                        )
                    }
            ) {
                if (!isCollapsed) {
                    Text(
                        text = buildAnnotatedString {
                            if (comment.replyToUserId != null && comment.replyToUserName != null && comment.replyToUserName.isNotBlank()) {
                                withStyle(style = androidx.compose.ui.text.SpanStyle(color = PrimaryDark)) {
                                    append("@${comment.replyToUserName} ")
                                }
                            }
                            append(translationState.displayText(comment.content))
                        },
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.comment_collapsed),
                        fontSize = 13.sp,
                        color = TextHint
                    )
                }
            }

            if (!isCollapsed) {
                AiTranslationStatus(
                    state = translationState,
                    onTranslate = onTranslate,
                    onShowOriginal = onShowCommentOriginal,
                    onShowTranslation = onShowCommentTranslation,
                    compact = true,
                    showInitialAction = false,
                    showVisibilityAction = false
                )
            }

            // 长按弹出菜单 - 全屏遮罩 + 底部菜单栏
            if (showContextMenu) {
                Dialog(
                    onDismissRequest = { showContextMenu = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x80000000))
                            .clickable { showContextMenu = false }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    color = Color.White,
                                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                )
                                .padding(vertical = 12.dp)
                                .clickable(enabled = false) { },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // 回复（气泡图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onReplyClick() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.reply), fontSize = 12.sp, color = TextSecondary)
                        }
                        // 点赞（爱心图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onLike() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = if (comment.isLiked) PrimaryDark else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.action_like), fontSize = 12.sp, color = if (comment.isLiked) PrimaryDark else TextSecondary)
                        }
                        // 折叠（表情图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onToggleCollapse() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (isCollapsed) Icons.Filled.SentimentDissatisfied else Icons.Outlined.SentimentNeutral,
                                contentDescription = null,
                                tint = if (isCollapsed) PrimaryDark else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isCollapsed) stringResource(R.string.action_uncollapse) else stringResource(R.string.action_dislike),
                                fontSize = 12.sp,
                                color = if (isCollapsed) PrimaryDark else TextSecondary
                            )
                        }
                        // 举报（⚠ 图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onReport() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.report), fontSize = 12.sp, color = TextSecondary)
                        }
                        // 删除（垃圾桶图标，仅所有者，放在最后）
                        if (isOwner) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { showContextMenu = false; onDelete() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(stringResource(R.string.delete_comment), fontSize = 12.sp, color = Color(0xFFE53935))
                            }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 时间 + 回复 + 爱心 + 折叠
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = formatCommentTime(comment.createdAt),
                    fontSize = 12.sp,
                    color = TextHint
                )
                Text(
                    text = stringResource(R.string.reply),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onReplyClick() }
                )
                if (!isCollapsed) {
                    IconButton(
                        onClick = {
                            when (translationState) {
                                is TranslationUiState.Success -> if (translationState.showingTranslation) {
                                    onShowCommentOriginal()
                                } else {
                                    onShowCommentTranslation()
                                }
                                else -> onTranslate()
                            }
                        },
                        enabled = translationState !is TranslationUiState.Loading,
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (translationState is TranslationUiState.Loading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 1.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = stringResource(R.string.translate_message),
                                tint = if (translationState is TranslationUiState.Success && translationState.showingTranslation) PrimaryDark else TextHint,
                                modifier = Modifier.size(17.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // 爱心按钮 + 数量
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = if (comment.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = if (comment.isLiked) PrimaryDark else TextHint,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onLike() }
                    )
                    if (comment.likeCount > 0) {
                        Text(
                            text = "${comment.likeCount}",
                            fontSize = 12.sp,
                            color = if (comment.isLiked) PrimaryDark else TextHint
                        )
                    }
                }
                // 讨厌脸按钮（折叠/展开）
                Icon(
                    imageVector = if (isCollapsed) Icons.Filled.SentimentDissatisfied else Icons.Outlined.SentimentNeutral,
                    contentDescription = null,
                    tint = if (isCollapsed) PrimaryDark else TextHint,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onToggleCollapse() }
                )
            }

            // 展开/收起回复列表（折叠时不显示）
            if (!isCollapsed && (replies.isNotEmpty() || isExpanded)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isExpanded) {
                        stringResource(R.string.collapse_replies)
                    } else {
                        stringResource(R.string.view_replies, replies.size)
                    },
                    fontSize = 13.sp,
                    color = PrimaryDark,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onToggleReplies() }
                )
            }

            // 回复列表（折叠时不显示）
            if (!isCollapsed && isExpanded && replies.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                replies.forEach { reply ->
                    ReplyItem(
                        reply = reply,
                        isOwner = reply.userId == currentUserId,
                        isCollapsed = collapsedReplyIds.contains(reply.id),
                        onUserClick = { onUserClick(reply.userId) },
                        onReplyClick = { onReplyToReply(reply) },
                        onLike = { onLikeReply(reply) },
                        onToggleCollapse = { onCollapseReply(reply.id) },
                        onDelete = { onDeleteReply(reply) },
                        onReport = { onReportReply(reply) },
                        translationState = replyTranslationState(reply.id),
                        onTranslate = { onTranslateReply(reply) },
                        onShowOriginal = { onShowReplyOriginal(reply.id) },
                        onShowTranslation = { onShowReplyTranslation(reply.id) },
                        currentUserId = currentUserId
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun ReplyItem(
    reply: Comment,
    isOwner: Boolean,
    isCollapsed: Boolean,
    onUserClick: () -> Unit,
    onReplyClick: () -> Unit,
    onLike: () -> Unit,
    onToggleCollapse: () -> Unit,
    onDelete: () -> Unit,
    onReport: () -> Unit = {},
    translationState: TranslationUiState?,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
    currentUserId: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 小头像
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickable { onUserClick() }
                .background(SurfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (reply.userAvatar.isNotBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(reply.userAvatar)
                        .crossfade(true)
                        .build(),
                    contentDescription = reply.userName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                DefaultUserAvatar(
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = reply.userName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary,
                modifier = Modifier.clickable { onUserClick() }
            )
            Spacer(modifier = Modifier.height(2.dp))

            // 长按菜单状态
            var showContextMenu by remember { mutableStateOf(false) }

            // 回复内容（支持长按）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onLongPress = { showContextMenu = true },
                            onTap = { onReplyClick() }
                        )
                    }
            ) {
                if (!isCollapsed) {
                    Text(
                        text = buildAnnotatedString {
                            if (reply.replyToUserId != null && reply.replyToUserName != null && reply.replyToUserName.isNotBlank()) {
                                withStyle(style = androidx.compose.ui.text.SpanStyle(color = PrimaryDark)) {
                                    append("@${reply.replyToUserName} ")
                                }
                            }
                            append(translationState.displayText(reply.content))
                        },
                        fontSize = 13.sp,
                        color = TextPrimary
                    )
                } else {
                    Text(
                        text = stringResource(R.string.comment_collapsed),
                        fontSize = 12.sp,
                        color = TextHint
                    )
                }
            }

            if (!isCollapsed) {
                AiTranslationStatus(
                    state = translationState,
                    onTranslate = onTranslate,
                    onShowOriginal = onShowOriginal,
                    onShowTranslation = onShowTranslation,
                    compact = true,
                    showInitialAction = false,
                    showVisibilityAction = false
                )
            }

            // 长按弹出菜单 - 全屏遮罩 + 底部菜单栏
            if (showContextMenu) {
                Dialog(
                    onDismissRequest = { showContextMenu = false },
                    properties = DialogProperties(usePlatformDefaultWidth = false)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x80000000))
                            .clickable { showContextMenu = false }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .background(
                                    color = Color.White,
                                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                )
                                .padding(vertical = 12.dp)
                                .clickable(enabled = false) { },
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                        // 回复（气泡图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onReplyClick() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.ChatBubbleOutline, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.reply), fontSize = 12.sp, color = TextSecondary)
                        }
                        // 点赞（爱心图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onLike() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (reply.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                contentDescription = null,
                                tint = if (reply.isLiked) PrimaryDark else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.action_like), fontSize = 12.sp, color = if (reply.isLiked) PrimaryDark else TextSecondary)
                        }
                        // 不喜欢（表情图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onToggleCollapse() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = if (isCollapsed) Icons.Filled.SentimentDissatisfied else Icons.Outlined.SentimentNeutral,
                                contentDescription = null,
                                tint = if (isCollapsed) PrimaryDark else TextSecondary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = if (isCollapsed) stringResource(R.string.action_uncollapse) else stringResource(R.string.action_dislike),
                                fontSize = 12.sp,
                                color = if (isCollapsed) PrimaryDark else TextSecondary
                            )
                        }
                        // 举报（⚠ 图标）
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .clickable { showContextMenu = false; onReport() }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Outlined.Warning, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(stringResource(R.string.report), fontSize = 12.sp, color = TextSecondary)
                        }
                        // 删除（垃圾桶图标，仅所有者，放在最后）
                        if (isOwner) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clickable { showContextMenu = false; onDelete() }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFE53935), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(stringResource(R.string.delete_comment), fontSize = 12.sp, color = Color(0xFFE53935))
                            }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            // 时间 + 回复 + 爱心 + 折叠
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = formatCommentTime(reply.createdAt),
                    fontSize = 11.sp,
                    color = TextHint
                )
                Text(
                    text = stringResource(R.string.reply),
                    fontSize = 12.sp,
                    color = TextSecondary,
                    modifier = Modifier.clickable { onReplyClick() }
                )
                if (!isCollapsed) {
                    IconButton(
                        onClick = {
                            when (translationState) {
                                is TranslationUiState.Success -> if (translationState.showingTranslation) {
                                    onShowOriginal()
                                } else {
                                    onShowTranslation()
                                }
                                else -> onTranslate()
                            }
                        },
                        enabled = translationState !is TranslationUiState.Loading,
                        modifier = Modifier.size(28.dp)
                    ) {
                        if (translationState is TranslationUiState.Loading) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(13.dp),
                                strokeWidth = 1.5.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.Translate,
                                contentDescription = stringResource(R.string.translate_message),
                                tint = if (translationState is TranslationUiState.Success && translationState.showingTranslation) PrimaryDark else TextHint,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                // 爱心按钮 + 数量
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Icon(
                        imageVector = if (reply.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = null,
                        tint = if (reply.isLiked) PrimaryDark else TextHint,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onLike() }
                    )
                    if (reply.likeCount > 0) {
                        Text(
                            text = "${reply.likeCount}",
                            fontSize = 12.sp,
                            color = if (reply.isLiked) PrimaryDark else TextHint
                        )
                    }
                }
                // 讨厌脸按钮
                Icon(
                    imageVector = if (isCollapsed) Icons.Filled.SentimentDissatisfied else Icons.Outlined.SentimentNeutral,
                    contentDescription = null,
                    tint = if (isCollapsed) PrimaryDark else TextHint,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onToggleCollapse() }
                )
            }
        }
    }
}

@Composable
private fun AssociationRow(
    label: String,
    value: String,
    onClick: () -> Unit = {}
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.width(80.dp)
        )
        Text(
            text = value.ifBlank { "—" },
            fontSize = 13.sp,
            color = if (value.isNotBlank()) TextPrimary else TextHint,
            modifier = Modifier.weight(1f)
        )
        if (value.isNotBlank()) {
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}
