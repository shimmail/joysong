package com.joysong.app.ui.notification

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.ui.geometry.Offset as ComposeOffset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    onBackClick: () -> Unit,
    onMessageClick: (role: String) -> Unit,
    onCsChatClick: () -> Unit,
    onDmClick: (String) -> Unit = {},
    onAvatarClick: (String) -> Unit = {},
    viewModel: MessagesViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val pullRefreshState = rememberPullToRefreshState()

    // 进入消息页时清除首页未读红点，并刷新数据
    LaunchedEffect(Unit) {
        viewModel.clearAllUnread()
        viewModel.markAllNotificationsRead()
        viewModel.refresh()
    }

    // Track which menu is open (only one at a time)
    var openMenuRole by remember { mutableStateOf<String?>(null) }

    // 长按菜单状态
    var longPressMenuState by remember { mutableStateOf<LongPressMenuState?>(null) }

    // 删除确认对话框状态
    var pendingDeleteRole by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.messages_title),
                onBackClick = onBackClick,
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        // 外层 Box 不带 padding，用于覆盖全屏（包括标题栏）
        Box(modifier = Modifier.fillMaxSize()) {
            // ===== 主内容区（带 padding） =====
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { viewModel.loadSessions() },
                state = pullRefreshState,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background)
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(
                        items = messages,
                        key = { it.role }
                    ) { item ->
                        val isMenuOpen = openMenuRole == item.role
                        val density = LocalDensity.current
                        var cardPositionInWindow by remember { mutableStateOf(ComposeOffset.Zero) }
                        var cardSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }

                        Box(
                            modifier = Modifier.onGloballyPositioned { coords ->
                                cardPositionInWindow = coords.positionInWindow()
                                cardSize = with(density) {
                                    androidx.compose.ui.geometry.Size(
                                        coords.size.width.toFloat(),
                                        coords.size.height.toFloat()
                                    )
                                }
                            }
                        ) {
                            SwipeableMessageRow(
                                item = item,
                                isMenuOpen = isMenuOpen,
                                onMenuOpen = { openMenuRole = item.role },
                                onMenuClose = { if (openMenuRole == item.role) openMenuRole = null },
                                onAvatarClick = {
                                    if (item.role.startsWith("DM_")) {
                                        onAvatarClick(item.role.removePrefix("DM_"))
                                    }
                                },
                                onClick = {
                                    if (isMenuOpen) {
                                        openMenuRole = null
                                    } else {
                                        viewModel.clearUnread(item.role)
                                        when {
                                            item.role == "CS" -> onCsChatClick()
                                            item.role.startsWith("DM_") -> onDmClick(item.role.removePrefix("DM_"))
                                            else -> onMessageClick(item.role)
                                        }
                                    }
                                },
                                onMarkUnread = {
                                    viewModel.markAsUnread(item.role)
                                    openMenuRole = null
                                },
                                onTogglePin = {
                                    viewModel.togglePin(item.role)
                                    openMenuRole = null
                                },
                                onDelete = {
                                    openMenuRole = null
                                    pendingDeleteRole = item.role
                                },
                                onLongPress = { pressPos ->
                                    longPressMenuState = LongPressMenuState(
                                        cardId = item.role,
                                        cardData = item,
                                        pressPosition = pressPos,
                                        cardTopLeft = cardPositionInWindow,
                                        cardWidth = cardSize.width,
                                        cardHeight = cardSize.height
                                    )
                                }
                            )
                        }
                    }
                }
            }
            } // 关闭主内容区 Box(padding)

            // ===== 长按菜单覆盖层（全屏，不受 padding 限制） =====
            if (longPressMenuState != null) {
                val state = longPressMenuState!!
                val density = LocalDensity.current

                // 全屏半透明遮罩 + 模糊效果
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = 0.99f }
                        .drawWithContent {
                            drawContent()
                            drawRect(
                                color = Color.Black.copy(alpha = 0.4f),
                                blendMode = BlendMode.SrcAtop
                            )
                        }
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                    if (event.changes.any { !it.pressed && it.previousPressed }) {
                                        longPressMenuState = null
                                        break
                                    }
                                }
                            }
                        }
                )

                // 菜单本体 — 模仿小红书，在长按位置向下弹出，避免遮挡手指
                val menuWidthDp = 160.dp
                val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp

                // 水平方向以手指位置为中心，并约束在屏幕范围内
                val menuOffsetX = (with(density) { state.pressPosition.x.toDp() } - menuWidthDp / 2)
                    .coerceIn(8.dp, screenWidthDp - menuWidthDp - 8.dp)

                // 垂直方向在手指下方弹出
                val menuOffsetY = with(density) { state.pressPosition.y.toDp() } + 12.dp

                Card(
                    shape = RoundedCornerShape(8.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier
                        .width(menuWidthDp)
                        .offset(x = menuOffsetX, y = menuOffsetY)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        }
                ) {
                    Column {
                        // 置顶/取消置顶
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    longPressMenuState = null
                                    viewModel.togglePin(state.cardData.role)
                                    openMenuRole = null
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.PushPin, null, tint = Color(0xFFFF9800), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (state.cardData.isPinned) "取消置顶" else "置顶聊天",
                                fontSize = 14.sp, color = TextPrimary
                            )
                        }
                        // 标为未读
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    longPressMenuState = null
                                    viewModel.markAsUnread(state.cardData.role)
                                    openMenuRole = null
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.MarkEmailUnread, null, tint = Color(0xFF2196F3), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("标为未读", fontSize = 14.sp, color = TextPrimary)
                        }
                        // 删除
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    longPressMenuState = null
                                    pendingDeleteRole = state.cardData.role
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Outlined.Delete, null, tint = Color(0xFFF44336), modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text("删除", fontSize = 14.sp, color = Color(0xFFF44336))
                        }
                    }
                }
            }

            // ===== 删除确认对话框 =====
            if (pendingDeleteRole != null) {
                AlertDialog(
                    onDismissRequest = { pendingDeleteRole = null },
                    title = { Text("确认删除") },
                    text = { Text("删除此聊天（仅在本地隐藏，不删除历史记录）") },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteMessage(pendingDeleteRole!!)
                                openMenuRole = null
                                pendingDeleteRole = null
                            }
                        ) {
                            Text("删除", color = Color(0xFFF44336))
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { pendingDeleteRole = null }
                        ) {
                            Text("取消")
                        }
                    }
                )
            }
        }
    }
}

private typealias MessageItem = MessagesViewModel.MessageCardData

private data class LongPressMenuState(
    val cardId: String,
    val cardData: MessageItem,
    val pressPosition: ComposeOffset,
    val cardTopLeft: ComposeOffset,
    val cardWidth: Float,
    val cardHeight: Float
)

@Composable
private fun SwipeableMessageRow(
    item: MessageItem,
    isMenuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onAvatarClick: () -> Unit,
    onClick: () -> Unit,
    onMarkUnread: () -> Unit,
    onTogglePin: () -> Unit,
    onDelete: () -> Unit,
    onLongPress: (ComposeOffset) -> Unit
) {
    val menuWidth = 240f
    val coroutineScope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }

    val springSpec = spring<Float>(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessMedium
    )

    // 当 isMenuOpen 变化时，动画到目标位置
    LaunchedEffect(isMenuOpen) {
        offsetX.animateTo(
            targetValue = if (isMenuOpen) -menuWidth else 0f,
            animationSpec = springSpec
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // Action buttons layer (behind the card)
        if (isMenuOpen || offsetX.value < -5f) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .height(72.dp),
                horizontalArrangement = Arrangement.End
            ) {
                ActionButton(
                    text = "标为未读",
                    color = Color(0xFF2196F3),
                    icon = Icons.Outlined.MarkEmailUnread,
                    onClick = onMarkUnread
                )
                ActionButton(
                    text = if (item.isPinned) "取消置顶" else "置顶聊天",
                    color = Color(0xFFFF9800),
                    icon = Icons.Outlined.PushPin,
                    onClick = onTogglePin
                )
                ActionButton(
                    text = "删除",
                    color = Color(0xFFF44336),
                    icon = Icons.Outlined.Delete,
                    onClick = onDelete
                )
            }
        }

        // Message card layer (on top, swipeable)
        // 使用自定义 swipeableWithLongPress 统一处理滑动/点击/长按
        MessageCardContent(
            item = item,
            onAvatarClick = onAvatarClick,
            modifier = Modifier
                .offset(x = offsetX.value.dp)
                .swipeableWithLongPress(
                    offsetX = offsetX,
                    menuWidth = menuWidth,
                    isMenuOpen = isMenuOpen,
                    onMenuOpen = onMenuOpen,
                    onMenuClose = onMenuClose,
                    onClick = onClick,
                    onLongPress = onLongPress,
                    coroutineScope = coroutineScope,
                    springSpec = springSpec
                )
        )

    }
}

@Composable
private fun ActionButton(
    text: String,
    color: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(80.dp)
            .fillMaxHeight()
            .background(color)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = Color.White,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = text,
                fontSize = 11.sp,
                color = Color.White,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MessageCardContent(
    item: MessageItem,
    onAvatarClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            // DM卡片点击头像跳转用户主页；AI/CS卡片头像不可点击
            val isDmCard = item.role.startsWith("DM_")
            val clickableModifier = if (isDmCard) {
                Modifier.then(
                    Modifier.clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onAvatarClick() }
                )
            } else {
                Modifier
            }
            if (item.avatarUrl != null && item.avatarUrl.isNotBlank()) {
                // 真实用户头像
                AsyncImage(
                    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
                        .data(item.avatarUrl)
                        .crossfade(true)
                        .build(),
                    contentDescription = "${item.name}的头像",
                    modifier = clickableModifier
                        .size(48.dp)
                        .clip(CircleShape)
                )
            } else {
                // AI角色 emoji 圆形背景
                Card(
                    modifier = clickableModifier.size(48.dp),
                    shape = CircleShape,
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF0F0F0))
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = item.emoji,
                            fontSize = 22.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // 名称 + 预览
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.isPinned) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Outlined.PushPin,
                            contentDescription = "已置顶",
                            tint = Color(0xFFFF9800),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
                Text(
                    text = item.preview,
                    fontSize = 13.sp,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 日期 + 未读角标
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = item.date,
                    fontSize = 11.sp,
                    color = TextHint
                )
                // 未读角标：微信风格数字角标 / 小红点
                if (item.unreadCount > 0) {
                    val displayText = if (item.unreadCount >= 100) "99+" else item.unreadCount.toString()
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
                            .background(Color(0xFFE53935), RoundedCornerShape(9.dp))
                            .padding(horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = displayText,
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            maxLines = 1
                        )
                    }
                } else if (item.isUnread) {
                    Box(
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .size(8.dp)
                            .background(Color(0xFFE53935), CircleShape)
                    )
                }
            }
        }
    }
}

// ===== 自定义手势 Modifier =====

/**
 * 统一处理左滑/点击/长按手势的自定义 Modifier。
 * 使用单个 pointerInput + awaitPointerEventScope 手动处理所有手势，
 * 避免 detectTapGestures 与 detectHorizontalDragGestures 之间的事件冲突。
 *
 * - 拖拽：手指水平移动超过 touchSlop 后进入拖拽模式，snapTo 实时跟手
 * - 点击：手指在 touchSlop 内抬起，触发 onClick
 * - 长按：手指在 touchSlop 内停留超过 longPressTimeout，触发 onLongPress
 */
@Composable
private fun Modifier.swipeableWithLongPress(
    offsetX: Animatable<Float, AnimationVector1D>,
    menuWidth: Float,
    isMenuOpen: Boolean,
    onMenuOpen: () -> Unit,
    onMenuClose: () -> Unit,
    onClick: () -> Unit,
    onLongPress: (ComposeOffset) -> Unit,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    springSpec: androidx.compose.animation.core.AnimationSpec<Float>
): Modifier {
    // 记录本组件在窗口中的位置，用于计算长按时手指在窗口中的坐标
    var windowPosition by remember { mutableStateOf(ComposeOffset.Zero) }
    return this
        .onGloballyPositioned { windowPosition = it.positionInWindow() }
        .pointerInput(isMenuOpen) {
            awaitPointerEventScope {
                var snapBackJob: Job? = null

                while (true) {
                    // Phase 1: 等待手指按下（Initial pass 抢先）
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    if (down.changes.any { it.pressed && !it.previousPressed }) {
                        val pointerId = down.changes.first { it.pressed && !it.previousPressed }.id
                        val startPos = down.changes.first { it.id == pointerId }.position
                        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                        val touchSlopPx = viewConfiguration.touchSlop

                        // 启动长按定时器（长按时手指未移动，以按下位置作为长按位置，向下弹出菜单避免遮挡）
                        var longPressed = false
                        val longPressJob: Job = coroutineScope.launch {
                            delay(longPressTimeout)
                            longPressed = true
                            onLongPress(windowPosition + startPos)
                        }

                        var isDragging = false

                        // Phase 2: 持续监听事件（Initial pass 抢先，阻止 LazyColumn 消费水平事件）
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == pointerId } ?: break

                            if (!change.pressed) {
                                // 手指抬起
                                longPressJob.cancel()
                                if (!isDragging && !longPressed) {
                                    onClick()
                                }
                                break
                            }

                            val dx = change.position.x - startPos.x
                            val dy = change.position.y - startPos.y

                            if (!isDragging && (abs(dx) > touchSlopPx || abs(dy) > touchSlopPx)) {
                                // 超过 touchSlop → 取消长按，判断方向
                                longPressJob.cancel()
                                if (abs(dx) > abs(dy)) {
                                    // 水平方向为主 → 进入拖拽模式
                                    isDragging = true
                                    coroutineScope.launch { offsetX.stop() }  // awaitPointerEventScope 内不能直接调用受限 suspend 函数
                                    change.consume()
                                } else {
                                    // 垂直方向为主 → 放弃处理（让 LazyColumn 滚动）
                                    break
                                }
                            }

                            // 即使还没超过 touchSlop，只要水平位移大于垂直位移，就预消费事件
                            // 防止 LazyColumn 在 touchSlop 阶段拦截水平滑动
                            if (!isDragging && !longPressed) {
                                if (abs(dx) > abs(dy) && abs(dx) > 0) {
                                    change.consume()
                                }
                            }

                            if (isDragging) {
                                change.consume()
                                val posChange = change.positionChange().x
                                val newOffset = (offsetX.value + posChange).coerceIn(-menuWidth, 0f)
                                coroutineScope.launch { offsetX.snapTo(newOffset) }  // awaitPointerEventScope 内不能直接调用受限 suspend 函数
                            }
                        }

                        // Phase 3: 拖拽结束（手指抬起），执行归位弹簧动画
                        if (isDragging) {
                            snapBackJob?.cancel()
                            snapBackJob = coroutineScope.launch {
                                if (offsetX.value < -(menuWidth / 2)) {
                                    offsetX.animateTo(-menuWidth, springSpec)
                                    onMenuOpen()
                                } else {
                                    offsetX.animateTo(0f, springSpec)
                                    onMenuClose()
                                }
                            }
                        }
                    }
                }
            }
        }
}
