package com.joysong.app.ui.dm

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.res.stringResource
import com.joysong.app.R
import com.joysong.app.ui.components.ChatUserAvatar
import com.joysong.app.ui.components.ImageZoomDialog
import com.joysong.app.ui.components.MessageInputBar
import com.joysong.app.data.util.resolveImageUrl
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.translation.AiTranslationStatus
import com.joysong.app.ui.translation.TranslationUiState
import com.joysong.app.ui.translation.displayText
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DmChatScreen(
    targetId: String,
    type: String = "user",
    onBackClick: () -> Unit,
    onInstitutionClick: (String) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    viewModel: DmChatViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val otherUserNickname = uiState.otherUserNickname.ifBlank {
        when (type) {
            "cs" -> stringResource(R.string.cs_chat_title)
            "doctor" -> stringResource(R.string.doctor_placeholder)
            "institution" -> stringResource(R.string.institution_placeholder)
            else -> stringResource(R.string.dm_user_placeholder)
        }
    }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { viewModel.sendImage(it, context) }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        bitmap?.let { viewModel.sendCameraImage(it, context) }
    }

    LaunchedEffect(targetId, type) {
        viewModel.initChat(targetId, type)
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = if (type == "institution") {
                            Modifier.clickable { onInstitutionClick(targetId) }
                        } else {
                            Modifier
                        }
                    ) {
                        Text(
                            text = otherUserNickname,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = TextPrimary
                        )
                    }
                },

                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Surface
                ),
                windowInsets = WindowInsets(0, 0, 0, 0)
            )
        },
        bottomBar = {
            Column {
                // Rate limit hint bar
                if (uiState.showRateLimitHint) {
                    DmRateLimitBar()
                }
                MessageInputBar(
                    placeholder = stringResource(R.string.chat_input_hint),
                    disabledPlaceholder = stringResource(R.string.dm_waiting_reply),
                    sendContentDescription = stringResource(R.string.chat_send),
                    moreContentDescription = stringResource(R.string.chat_more_features),
                    cameraContentDescription = stringResource(R.string.chat_camera),
                    albumContentDescription = stringResource(R.string.chat_album),
                    onSend = { viewModel.sendMessage(it) },
                    onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onTakePhoto = { cameraLauncher.launch(null) },
                    isSending = uiState.isSending,
                    isEnabled = !uiState.showRateLimitHint
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(Background)
            ) {
            if (uiState.isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = Primary)
                }
            } else if (uiState.messages.isEmpty()) {
                // Empty state
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "✉️",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.dm_empty_state),
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        DmBubble(
                            message = message,
                            otherAvatar = uiState.otherUserAvatar,
                            otherNickname = otherUserNickname,
                            myAvatar = uiState.myAvatar,
                            myNickname = uiState.myNickname,
                            translationState = uiState.translationStates[message.id],
                            onToggleTranslation = { viewModel.toggleMessageTranslation(message) },
                            onOtherAvatarClick = { onUserClick(targetId) }
                        )
                    }
                    if (uiState.isSending) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                ChatUserAvatar(
                                    avatarUrl = uiState.otherUserAvatar,
                                    nickname = otherUserNickname
                                )
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Primary
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }

}
private fun formatTimestamp(createdAt: String): Pair<Boolean, String> {
    if (createdAt.isBlank()) return Pair(false, "")
    return Pair(true, createdAt)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DmBubble(
    message: DmMessage,
    otherAvatar: String?,
    otherNickname: String,
    myAvatar: String?,
    myNickname: String,
    translationState: TranslationUiState?,
    onToggleTranslation: () -> Unit,
    onOtherAvatarClick: () -> Unit = {}
) {
    val context = LocalContext.current
    val copiedMessage = stringResource(R.string.copied)
    var showMenu by remember { mutableStateOf(false) }
    var showImage by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 智能时间显示：当天内隐藏，跨天显示格式化日期
        val (showTime, formattedTime) = formatTimestamp(message.createdAt)
        if (showTime) {
            Text(
                text = formattedTime,
                fontSize = 11.sp,
                color = TextHint,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start
        ) {
            if (!message.isMine) {
                ChatUserAvatar(
                    avatarUrl = otherAvatar,
                    nickname = otherNickname,
                    modifier = Modifier
                        .size(36.dp)
                        .clickable { onOtherAvatarClick() }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            Box {
                Card(
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (message.isMine) 16.dp else 4.dp,
                        bottomEnd = if (message.isMine) 4.dp else 16.dp
                    ),
                    colors = CardDefaults.cardColors(
                        containerColor = if (message.isMine) PrimaryDark else Surface
                    ),
                    modifier = Modifier
                        .padding(
                            start = if (message.isMine) 40.dp else 0.dp,
                            end = if (message.isMine) 0.dp else 40.dp
                        )
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { showMenu = true }
                        )
                ) {
                    if (message.messageType == "IMAGE") {
                        AsyncImage(
                            model = resolveImageUrl(message.content),
                            contentDescription = stringResource(R.string.dm_chat_image),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(180.dp).clickable { showImage = true }
                        )
                    } else {
                        Text(text = translationState.displayText(message.content), fontSize = 15.sp,
                            color = if (message.isMine) TextOnPrimary else TextPrimary,
                            modifier = Modifier.padding(12.dp))
                    }
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.copy)) },
                        onClick = {
                            showMenu = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                            Toast.makeText(context, copiedMessage, Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dm_quote)) },
                        onClick = { showMenu = false },
                        enabled = false
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (translationState is TranslationUiState.Success && translationState.showingTranslation) {
                                    stringResource(R.string.show_original)
                                } else {
                                    stringResource(R.string.translate_message)
                                }
                            )
                        },
                        onClick = {
                            showMenu = false
                            onToggleTranslation()
                        },
                        enabled = message.messageType == "TEXT"
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.dm_unsend)) },
                        onClick = { showMenu = false },
                        enabled = false
                    )
                }
            }

            if (message.isMine) {
                Spacer(modifier = Modifier.width(8.dp))
                ChatUserAvatar(
                    avatarUrl = myAvatar,
                    nickname = myNickname
                )
            }
        }
        if (message.messageType == "TEXT" && translationState != null) {
            AiTranslationStatus(
                state = translationState,
                onTranslate = onToggleTranslation,
                onShowOriginal = onToggleTranslation,
                onShowTranslation = onToggleTranslation,
                modifier = Modifier
                    .align(if (message.isMine) Alignment.End else Alignment.Start)
                    .padding(start = if (message.isMine) 44.dp else 44.dp, end = if (message.isMine) 44.dp else 44.dp),
                compact = true,
                showInitialAction = false
            )
        }
    }
    if (showImage) ImageZoomDialog(listOf(resolveImageUrl(message.content)), 0) { showImage = false }
}

@Composable
private fun DmRateLimitBar() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFF3E0))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "⏳",
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.dm_rate_limit_hint),
            fontSize = 12.sp,
            color = Color(0xFFE65100)
        )
    }
}
