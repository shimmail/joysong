package com.joysong.app.ui.dm

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.CreateDmConversationRequest
import com.joysong.app.data.remote.dto.DmMessageDto
import com.joysong.app.data.remote.dto.SendDmMessageRequest
import com.joysong.app.data.repository.MessageCenter
import com.joysong.app.data.repository.CustomerServiceRepository
import com.joysong.app.data.util.copyImageToCache
import com.joysong.app.data.util.saveCameraImageToCache
import com.joysong.app.domain.repository.FileRepository
import com.joysong.app.domain.repository.UserRepository
import com.joysong.app.domain.repository.TranslationRepository
import com.joysong.app.ui.translation.TranslationUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject

data class DmMessage(
    val id: String,
    val senderId: String,
    val content: String,
    val createdAt: String,
    val isMine: Boolean,
    val messageType: String = "TEXT"
)

data class DmUiState(
    val messages: List<DmMessage> = emptyList(),
    val otherUserNickname: String = "",
    val otherUserAvatar: String? = null,
    val myAvatar: String? = null,
    val myNickname: String = "",
    val isLoading: Boolean = false,
    val isSending: Boolean = false,
    val showRateLimitHint: Boolean = false,
    val errorMessage: String? = null,
    val translationStates: Map<String, TranslationUiState> = emptyMap()
)

@HiltViewModel
class DmChatViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val apiService: ApiService,
    private val messageCenter: MessageCenter,
    private val customerServiceRepository: CustomerServiceRepository,
    private val userRepository: UserRepository,
    private val fileRepository: FileRepository,
    private val translationRepository: TranslationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DmUiState())
    val uiState: StateFlow<DmUiState> = _uiState.asStateFlow()

    private var targetUserId: String = ""
    private var targetType: String = ""
    private var hasSentFirstMessage: Boolean = false
    private var cachedUserId: String = "me"
    private var conversationId: String? = null
    private var pollingJob: Job? = null

    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun toggleMessageTranslation(message: DmMessage) {
        if (message.messageType != "TEXT" || message.content.isBlank()) return
        val current = _uiState.value.translationStates[message.id]
        if (current is TranslationUiState.Loading) return
        if (current is TranslationUiState.Success) {
            updateTranslationState(
                message.id,
                current.copy(showingTranslation = !current.showingTranslation)
            )
            return
        }
        if (!translationRepository.shouldTranslate(message.content)) return
        updateTranslationState(message.id, TranslationUiState.Loading)
        viewModelScope.launch {
            translationRepository.translate(message.content, "message")
                .onSuccess { translation ->
                    updateTranslationState(
                        message.id,
                        TranslationUiState.Success(translation.translatedText)
                    )
                }
                .onFailure { error ->
                    updateTranslationState(
                        message.id,
                        TranslationUiState.Error(error.message ?: "AI翻译暂时不可用")
                    )
                }
        }
    }

    private fun updateTranslationState(messageId: String, state: TranslationUiState) {
        _uiState.value = _uiState.value.copy(
            translationStates = _uiState.value.translationStates + (messageId to state)
        )
    }

    /** Convert backend DmMessageDto to UI DmMessage */
    private fun DmMessageDto.toUiMessage(): DmMessage = DmMessage(
        id = id,
        senderId = senderId,
        content = content,
        createdAt = formatCreatedAt(createdAt),
        isMine = senderId == cachedUserId,
        messageType = messageType
    )

    private fun com.joysong.app.data.remote.dto.CsMessageDto.toUiMessage(): DmMessage = DmMessage(
        id = id,
        senderId = senderId,
        content = content,
        createdAt = formatCreatedAt(createdAt),
        isMine = senderId == cachedUserId,
        messageType = messageType
    )

    private fun formatCreatedAt(raw: String): String {
        return try {
            // Handle ISO format like "2026-07-25T14:30:00"
            val parsed = LocalDateTime.parse(raw.take(19))
            parsed.format(timeFormatter)
        } catch (_: Exception) {
            raw.take(16).replace("T", " ")
        }
    }

    fun initChat(targetId: String, type: String = "user") {
        val dmKey = if (type == "cs") "CS" else "DM_$targetId"
        val isDeleted = messageCenter.getChatDeletedAt(dmKey) != null
        if (this.targetUserId == targetId && this.targetType == type
            && (_uiState.value.messages.isNotEmpty() || isDeleted)) return
        this.targetUserId = targetId
        this.targetType = type

        _uiState.value = DmUiState(isLoading = true)

        if (type == "cs") {
            initCustomerServiceChat()
            return
        }

        // 如果是已删除的 user-to-user DM 会话，立即启用 rate limit
        if (isDeleted && type == "user") {
            hasSentFirstMessage = true  // 标记已发送过消息
            _uiState.value = _uiState.value.copy(showRateLimitHint = true)
        }

        // 独立协程加载自己的头像（不写 cachedUserId，避免竞态）
        viewModelScope.launch {
            try {
                userRepository.getUserProfile().onSuccess { user ->
                    _uiState.value = _uiState.value.copy(
                        myAvatar = user.avatar.takeIf { it.isNotBlank() },
                        myNickname = user.nickname
                    )
                }
            } catch (_: Exception) { /* ignore */ }
        }

        // 加载对方数据（在协程开头同步获取 userId，只赋值一次）
        viewModelScope.launch {
            cachedUserId = tokenManager.getUserId() ?: "me"
            try {
                when (type) {
                    "doctor" -> {
                        val response = apiService.getDoctorById(targetId)
                        val doctor = response.data?.doctor
                        _uiState.value = _uiState.value.copy(
                            otherUserNickname = doctor?.name.orEmpty(),
                            otherUserAvatar = doctor?.avatar?.takeIf { it.isNotBlank() },
                            isLoading = false
                        )
                    }
                    "institution" -> {
                        val response = apiService.getInstitutionById(targetId)
                        val inst = response.data?.institution
                        _uiState.value = _uiState.value.copy(
                            otherUserNickname = inst?.name.orEmpty(),
                            otherUserAvatar = inst?.coverImage?.takeIf { it.isNotBlank() },
                            isLoading = false
                        )
                    }
                    else -> { // "user"
                        val response = apiService.getUserPublicProfile(targetId)
                        val profile = response.data
                        _uiState.value = _uiState.value.copy(
                            otherUserNickname = profile?.nickname.orEmpty(),
                            otherUserAvatar = profile?.avatar,
                            isLoading = false
                        )
                    }
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    otherUserNickname = "",
                    isLoading = false
                )
            }

            // 创建或获取后端会话，然后加载历史消息
            try {
                val response = apiService.createDmConversation(CreateDmConversationRequest(targetId))
                response.data?.let { conv ->
                    conversationId = conv.id
                    // 标记会话已读
                    try { apiService.markDmConversationRead(conv.id) } catch (_: Exception) {}
                    loadMessages()
                    startPolling()
                }
            } catch (_: Exception) {
                // API 调用失败，保留现有本地降级逻辑（不崩溃）
            }
        }
    }

    private fun initCustomerServiceChat() {
        _uiState.value = _uiState.value.copy(otherUserNickname = "", isLoading = true)
        viewModelScope.launch {
            cachedUserId = tokenManager.getUserId() ?: "me"
            try {
                userRepository.getUserProfile().onSuccess { user ->
                    _uiState.value = _uiState.value.copy(
                        myAvatar = user.avatar.takeIf { it.isNotBlank() },
                        myNickname = user.nickname
                    )
                }
            } catch (_: Exception) { }

            customerServiceRepository.getOrCreateConversation()
                .onSuccess { conversation ->
                    conversationId = conversation.id
                    customerServiceRepository.markAsRead(conversation.id)
                    loadMessages()
                    startPolling()
                    _uiState.value = _uiState.value.copy(isLoading = false, errorMessage = null)
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        errorMessage = error.message ?: "Customer service is temporarily unavailable"
                    )
                }
        }
    }

    /** Check if the other party has sent any message in the conversation */
    private fun hasOtherPartyReplied(messages: List<DmMessage>): Boolean {
        return messages.any { it.senderId != cachedUserId }
    }

    /** Update rate limit state based on actual message state (DM user-to-user only) */
    private fun updateRateLimitState(messages: List<DmMessage>) {
        if (targetType != "user") return
        val myMessages = messages.any { it.senderId == cachedUserId }
        if (myMessages) {
            hasSentFirstMessage = true
        }
        // Rate limit active only when user has sent messages AND other party hasn't replied yet
        val shouldLimit = myMessages && !hasOtherPartyReplied(messages)
        _uiState.value = _uiState.value.copy(showRateLimitHint = shouldLimit)
    }

    /** Load messages from backend API and convert to UI format */
    fun loadMessages() {
        val convId = conversationId ?: return
        if (targetType == "cs") {
            loadCustomerServiceMessages(convId)
            return
        }
        messageCenter.setChatDeletedAt("DM_$targetUserId", "")
        viewModelScope.launch {
            try {
                val response = apiService.getDmMessages(convId, limit = 30)
                if (response.code == 403) {
                    // 会话已被后端删除，清空本地数据
                    conversationId = null
                    pollingJob?.cancel()
                    pollingJob = null
                    _uiState.value = _uiState.value.copy(
                        messages = emptyList(),
                        errorMessage = null
                    )
                    return@launch
                }
                response.data?.let { dtoList ->
                    val deletedAt = messageCenter.getChatDeletedAt("DM_$targetUserId")
                    val filtered = if (deletedAt != null) {
                        val deletedTime = try { LocalDateTime.parse(deletedAt.take(19)) } catch (_: Exception) { null }
                        if (deletedTime != null) {
                            dtoList.filter { dto ->
                                try {
                                    LocalDateTime.parse(dto.createdAt.take(19)).isAfter(deletedTime)
                                } catch (_: Exception) { true }
                            }
                        } else dtoList
                    } else dtoList
                    val hiddenIds = tokenManager.getHiddenMessageIds()
                    val uiMessages = filtered.filter { it.id !in hiddenIds }.map { it.toUiMessage() }
                    _uiState.value = _uiState.value.copy(
                        messages = uiMessages,
                        errorMessage = null
                    )
                    // 如果会话已被本地删除，对 user-to-user DM 保持 rate limit
                    if (messageCenter.getChatDeletedAt("DM_$targetUserId") != null && targetType == "user") {
                        _uiState.value = _uiState.value.copy(showRateLimitHint = true)
                    }
                    // Check if the other party has replied to lift rate limit
                    updateRateLimitState(uiMessages)
                    // 标记会话已读（处理轮询时收到新消息的场景）
                    try { apiService.markDmConversationRead(convId) } catch (_: Exception) {}
                }
            } catch (_: Exception) {
                // 加载失败不崩溃，保留现有消息
            }
        }
    }

    private fun loadCustomerServiceMessages(conversationId: String) {
        viewModelScope.launch {
            customerServiceRepository.getMessages(conversationId, limit = 30)
                .onSuccess { messages ->
                    val hiddenIds = tokenManager.getHiddenMessageIds()
                    val uiMessages = messages.filter { it.id !in hiddenIds }.map { it.toUiMessage() }
                    _uiState.value = _uiState.value.copy(messages = uiMessages, errorMessage = null)
                    customerServiceRepository.markAsRead(conversationId)
                    messageCenter.setChatDeletedAt("CS", "")
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(errorMessage = error.message)
                }
        }
    }

    fun sendMessage(text: String) {
        if (text.isBlank() || _uiState.value.showRateLimitHint) return
        sendMessageInternal(text, "TEXT")
    }

    fun sendImage(uri: Uri, context: Context) {
        if (_uiState.value.showRateLimitHint || _uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, errorMessage = null)
            try {
                val file = copyImageToCache(context, uri)
                fileRepository.uploadImage(file, "dm").onSuccess { url ->
                    sendMessageInternal(url, "IMAGE")
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(isSending = false, errorMessage = error.message ?: "图片上传失败")
                }
                file.delete()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, errorMessage = error.message ?: "图片上传失败")
            }
        }
    }

    fun sendCameraImage(bitmap: Bitmap, context: Context) {
        if (_uiState.value.showRateLimitHint || _uiState.value.isSending) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSending = true, errorMessage = null)
            try {
                val file = saveCameraImageToCache(context, bitmap)
                fileRepository.uploadImage(file, "dm").onSuccess { url ->
                    sendMessageInternal(url, "IMAGE")
                }.onFailure { error ->
                    _uiState.value = _uiState.value.copy(isSending = false, errorMessage = error.message ?: "图片上传失败")
                }
                file.delete()
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(isSending = false, errorMessage = error.message ?: "图片上传失败")
            }
        }
    }

    private fun sendMessageInternal(text: String, messageType: String) {
        if (targetType == "cs") {
            sendCustomerServiceMessage(text, messageType)
            return
        }
        val currentUserId = cachedUserId
        val convId = conversationId

        if (convId != null) {
            // 乐观更新：先用临时 ID 创建消息并立即显示
            val tempId = "temp_${UUID.randomUUID()}"
            val now = LocalDateTime.now().format(timeFormatter)
            val tempMessage = DmMessage(
                id = tempId,
                senderId = currentUserId,
                content = text,
                createdAt = now,
                isMine = true,
                messageType = messageType
            )
            val optimisticMessages = _uiState.value.messages + tempMessage
            _uiState.value = _uiState.value.copy(
                messages = optimisticMessages,
                isSending = true,
                errorMessage = null
            )

            viewModelScope.launch {
                try {
                    val response = apiService.sendDmMessage(convId, SendDmMessageRequest(text, messageType))
                    response.data?.let { dto ->
                        // API 成功：用真实消息替换临时消息
                        val realMessage = dto.toUiMessage()
                        val updatedMessages = _uiState.value.messages.map {
                            if (it.id == tempId) realMessage else it
                        }
                        _uiState.value = _uiState.value.copy(
                            messages = updatedMessages,
                            isSending = false,
                            errorMessage = null
                        )
                    } ?: run {
                        _uiState.value = _uiState.value.copy(isSending = false)
                    }
                } catch (e: Exception) {
                    // API 失败：保留临时消息，标记失败状态
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        errorMessage = e.message ?: "发送失败"
                    )
                    return@launch
                }

                // Update the message card in MessageCenter
                messageCenter.upsertDmCard(
                    userId = targetUserId,
                    nickname = _uiState.value.otherUserNickname,
                    avatarUrl = _uiState.value.otherUserAvatar,
                    lastMessage = text
                )
                messageCenter.setChatDeletedAt("DM_$targetUserId", "")

                // Sync rate limit state after successful send
                if (targetType == "user") {
                    hasSentFirstMessage = true
                    updateRateLimitState(_uiState.value.messages)
                }
            }
        } else {
            // 无会话ID（API 初始化失败），降级为本地逻辑
            val now = LocalDateTime.now().format(timeFormatter)
            val myMessage = DmMessage(
                id = "local_${System.currentTimeMillis()}",
                senderId = currentUserId,
                content = text,
                createdAt = now,
                isMine = true,
                messageType = messageType
            )
            val updatedMessages = _uiState.value.messages + myMessage
            _uiState.value = _uiState.value.copy(
                messages = updatedMessages,
                isSending = false
            )
            messageCenter.upsertDmCard(
                userId = targetUserId,
                nickname = _uiState.value.otherUserNickname,
                avatarUrl = _uiState.value.otherUserAvatar,
                lastMessage = text
            )
            messageCenter.setChatDeletedAt("DM_$targetUserId", "")
            // Sync rate limit state after send
            if (targetType == "user") {
                hasSentFirstMessage = true
                updateRateLimitState(_uiState.value.messages)
            }
        }
    }

    private fun sendCustomerServiceMessage(text: String, messageType: String) {
        val convId = conversationId ?: return
        val tempId = "temp_${UUID.randomUUID()}"
        val tempMessage = DmMessage(
            id = tempId,
            senderId = cachedUserId,
            content = text,
            createdAt = LocalDateTime.now().format(timeFormatter),
            isMine = true,
            messageType = messageType
        )
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + tempMessage,
            isSending = true,
            errorMessage = null
        )
        viewModelScope.launch {
            customerServiceRepository.sendMessage(convId, text, messageType)
                .onSuccess { dto ->
                    val realMessage = dto.toUiMessage()
                    _uiState.value = _uiState.value.copy(
                        messages = _uiState.value.messages.map { if (it.id == tempId) realMessage else it },
                        isSending = false,
                        errorMessage = null
                    )
                    messageCenter.updateAiCard("CS", text)
                    messageCenter.setChatDeletedAt("CS", "")
                }
                .onFailure { error ->
                    _uiState.value = _uiState.value.copy(
                        isSending = false,
                        errorMessage = error.message ?: "Failed to send message"
                    )
                }
        }
    }

    /** Start polling for new messages every 15 seconds */
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                loadMessages()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            // 记录到本地隐藏列表（不调用后端 API）
            tokenManager.hideMessage(messageId)
            // 从当前消息列表移除
            val current = _uiState.value.messages.toMutableList()
            current.removeAll { it.id == messageId }
            _uiState.value = _uiState.value.copy(messages = current)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    fun deleteChatHistory() {
        pollingJob?.cancel()
        pollingJob = null
        conversationId = null
        hasSentFirstMessage = true  // 保持已发送标记，防止 rate limit 被重置
        _uiState.value = _uiState.value.copy(
            messages = emptyList(),
            isSending = false,
            errorMessage = null,
            showRateLimitHint = if (targetType == "user") true else false  // user-to-user DM 保持限制
        )
        if (targetType == "cs") {
            messageCenter.markDeleted("CS")
        } else if (targetUserId.isNotEmpty()) {
            messageCenter.markDeleted("DM_$targetUserId")
        }
        targetUserId = ""
    }
}
