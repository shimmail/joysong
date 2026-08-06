package com.joysong.app.ui.notification

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.MessageUnreadManager
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.repository.ChatRepository
import com.joysong.app.data.repository.CustomerServiceRepository
import com.joysong.app.data.repository.MessageCenter
import com.joysong.app.data.repository.MessageCardItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject

@HiltViewModel
class MessagesViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val customerServiceRepository: CustomerServiceRepository,
    private val messageCenter: MessageCenter,
    private val apiService: ApiService,
    private val tokenManager: TokenManager,
    private val messageUnreadManager: MessageUnreadManager,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class MessageCardData(
        val role: String,
        val name: String,
        val emoji: String,
        val preview: String,
        val date: String,
        val unreadCount: Int,
        val avatarUrl: String? = null,
        val isUnread: Boolean = false,
        val isPinned: Boolean = false
    )

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences("messages_prefs", Context.MODE_PRIVATE)

    private val _messages = MutableStateFlow<List<MessageCardData>>(emptyList())
    val messages: StateFlow<List<MessageCardData>> = _messages.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _notificationUnreadCount = MutableStateFlow(0L)
    val notificationUnreadCount: StateFlow<Long> = _notificationUnreadCount.asStateFlow()

    private var pollingJob: Job? = null
    private var cachedUserId: String? = null
    private val loadMutex = Mutex()

    private val pinnedIds = MutableStateFlow<Set<String>>(loadSetPref("pinned_ids"))
    private val unreadIds = MutableStateFlow<Set<String>>(loadSetPref("unread_ids"))

    init {
        viewModelScope.launch {
            cachedUserId = tokenManager.getUserId()
        }
        // 将本地持久化的删除集合同步到 MessageCenter（运行时单一数据源）
        messageCenter.migrateDeletedIds()
        // Initialize AI cards in MessageCenter
        messageCenter.initAiCards()
        // Observe MessageCenter flow so any update (e.g. from AiAgentViewModel) triggers re-render
        observeMessageCenter()
        // Load sessions from server (updates MessageCenter)
        loadSessions()
        // Start polling for periodic refresh
        startPolling()
    }

    private fun observeMessageCenter() {
        viewModelScope.launch {
            combine(
                messageCenter.messageCards,
                pinnedIds,
                unreadIds,
                messageCenter.chatDeletedAt
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                val cards = values[0] as List<MessageCardItem>
                val pinned = values[1] as Set<String>
                val unread = values[2] as Set<String>
                val deletedMap = values[3] as Map<String, String>
                cards to listOf(pinned, unread, deletedMap)
            }.collect { (cards, localState) ->
                val pinned = localState[0] as Set<String>
                val unread = localState[1] as Set<String>
                val deletedMap = localState[2] as Map<String, String>
                _messages.value = buildCardList(cards, pinned, unread, deletedMap.keys)
            }
        }
    }

    /** 将本地状态（置顶/未读/删除）应用到消息卡片并排序 */
    private fun buildCardList(
        cards: List<MessageCardItem>,
        pinned: Set<String>,
        unread: Set<String>,
        deletedKeys: Set<String>
    ): List<MessageCardData> {
        val currentUserId = cachedUserId
        return cards
            .filter { card ->
                // 过滤掉与自己的 DM 会话卡片
                if (card.id.startsWith("DM_") && !currentUserId.isNullOrBlank()) {
                    if (card.id == "DM_$currentUserId") return@filter false
                }
                // 统一用 chatDeletedAt 判断删除状态（AI/CS/DM 通用）
                card.id !in deletedKeys
            }
            .map { card ->
                MessageCardData(
                    role = card.id,
                    name = card.name,
                    emoji = card.emoji ?: "",
                    preview = card.preview,
                    date = card.time,
                    unreadCount = maxOf(card.unreadCount, if (card.id in unread) 1 else 0),
                    avatarUrl = card.avatarUrl,
                    isUnread = card.id in unread || card.unreadCount > 0,
                    isPinned = card.id in pinned
                )
            }
            .sortedByDescending { it.isPinned }
    }

    fun markAllNotificationsRead() {
        viewModelScope.launch {
            try {
                apiService.markAllNotificationsRead()
            } catch (_: Exception) {}
        }
    }

    fun clearAllUnread() {
        messageUnreadManager.clearUnread()
    }

    fun refresh() {
        loadSessions()
    }

    fun loadSessions() {
        viewModelScope.launch {
            if (!loadMutex.tryLock()) return@launch
            try {
                _isRefreshing.value = true

                // 进入消息页时标记所有通知已读
                try {
                    apiService.markAllNotificationsRead()
                } catch (_: Exception) {}

                // One unified medical-aesthetics Agent card, backed by the latest consultant session.
                try {
                    chatRepository.getSessions("CONSULTANT").onSuccess { sessions ->
                        sessions.maxByOrNull { it.updatedAt }?.let { session ->
                            messageCenter.updateAiCardFromSession(
                                role = "AI_AGENT",
                                lastMessage = session.lastMessage,
                                updatedAt = session.updatedAt
                            )
                        }
                        messageCenter.removeCards("BESTIE", "CONSULTANT")
                    }
                } catch (_: Exception) {}
                // CS 使用独立的 CustomerServiceRepository
                try {
                    customerServiceRepository.getConversations().onSuccess { convs ->
                        convs.firstOrNull()?.let { conv ->
                            messageCenter.updateAiCardFromSession(
                                role = "CS",
                                lastMessage = conv.lastMessage ?: "",
                                updatedAt = conv.updatedAt ?: "",
                                unreadCount = conv.unreadCount
                            )
                        }
                    }
                } catch (_: Exception) {}

                // Fetch DM conversations and update MessageCenter
                var dmUnreadTotal = 0
                try {
                    val currentUserId = tokenManager.getUserId() ?: ""
                    cachedUserId = currentUserId
                    val dmResponse = apiService.getDmConversations()
                    if (dmResponse.code == 200 && dmResponse.data != null) {
                        for (conv in dmResponse.data) {
                            val otherUserId = if (conv.userAId == currentUserId) conv.userBId else conv.userAId
                            if (otherUserId == "CS_ADMIN") continue // 跳过客服会话，已在CS卡片中展示
                            val myUnread = if (conv.userAId == currentUserId) conv.userAUnread else conv.userBUnread
                            dmUnreadTotal += myUnread
                            try {
                                val profileResp = apiService.getUserPublicProfile(otherUserId)
                                val nickname = profileResp.data?.nickname ?: "用户"
                                val avatarUrl = profileResp.data?.avatar
                                messageCenter.upsertDmCard(
                                    userId = otherUserId,
                                    nickname = nickname,
                                    avatarUrl = avatarUrl,
                                    lastMessage = conv.lastMessage ?: "",
                                    timestamp = conv.updatedAt,
                                    unreadCount = myUnread
                                )
                            } catch (_: Exception) {
                                // Profile fetch failed, still upsert with fallback name
                                messageCenter.upsertDmCard(
                                    userId = otherUserId,
                                    nickname = "用户",
                                    avatarUrl = null,
                                    lastMessage = conv.lastMessage ?: "",
                                    timestamp = conv.updatedAt,
                                    unreadCount = myUnread
                                )
                            }
                        }
                        // 清理可能残留的 CS_ADMIN DM 卡片
                        messageCenter.removeDmCardIfExists("CS_ADMIN")
                    }
                } catch (_: Exception) {}

                // Fetch notification unread count and update total
                var notificationUnread = 0L
                try {
                    val unreadResp = apiService.getUnreadNotificationCount()
                    if (unreadResp.code == 200 && unreadResp.data != null) {
                        notificationUnread = unreadResp.data
                        _notificationUnreadCount.value = notificationUnread
                    }
                } catch (_: Exception) {}

                // 汇总 DM + 通知未读计数，更新全局未读状态
                messageUnreadManager.updateUnreadCount(dmUnreadTotal + notificationUnread.toInt())

                // Read all cards from MessageCenter and apply local state
                applyLocalState()
                _isRefreshing.value = false
            } finally {
                loadMutex.unlock()
            }
        }
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(30_000) // 30秒间隔
                loadSessions()
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
    }

    private fun applyLocalState() {
        _messages.value = buildCardList(
            cards = messageCenter.messageCards.value,
            pinned = pinnedIds.value,
            unread = unreadIds.value,
            deletedKeys = messageCenter.chatDeletedAt.value.keys
        )
    }

    fun togglePin(role: String) {
        val current = pinnedIds.value.toMutableSet()
        if (role in current) {
            current.remove(role)
        } else {
            current.add(role)
        }
        pinnedIds.value = current
        saveSetPref("pinned_ids", current)
        applyLocalState()
    }

    fun markAsUnread(role: String) {
        val current = unreadIds.value.toMutableSet()
        current.add(role)
        unreadIds.value = current
        saveSetPref("unread_ids", current)
        applyLocalState()
    }

    fun clearUnread(role: String) {
        val current = unreadIds.value.toMutableSet()
        current.remove(role)
        unreadIds.value = current
        saveSetPref("unread_ids", current)
        applyLocalState()
    }

    fun deleteMessage(role: String) {
        // 统一使用 chatDeletedAt 作为删除来源（MessageCenter.markDeleted 内部调用 setChatDeletedAt）
        messageCenter.markDeleted(role)
        // Also remove from pinned/unread
        val pinned = pinnedIds.value.toMutableSet().apply { remove(role) }
        pinnedIds.value = pinned
        saveSetPref("pinned_ids", pinned)
        val unread = unreadIds.value.toMutableSet().apply { remove(role) }
        unreadIds.value = unread
        saveSetPref("unread_ids", unread)
        applyLocalState()
    }

    private fun loadSetPref(key: String): Set<String> {
        return prefs.getStringSet(key, emptySet()) ?: emptySet()
    }

    private fun saveSetPref(key: String, value: Set<String>) {
        prefs.edit().putStringSet(key, value).apply()
    }
}
