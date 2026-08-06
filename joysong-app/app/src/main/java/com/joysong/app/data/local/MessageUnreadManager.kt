package com.joysong.app.data.local

import android.content.Context
import com.joysong.app.data.remote.ApiService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局消息未读状态管理器（Hilt 单例）。
 * 首页与消息页共享同一数据源，保证未读红点状态一致。
 * 支持总未读计数持久化，并在 Application 作用域以 15 秒间隔轮询未读数。
 */
@Singleton
class MessageUnreadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: ApiService,
    private val tokenManager: TokenManager
) {

    private val prefs = context.getSharedPreferences("message_unread", Context.MODE_PRIVATE)

    private val _totalUnreadCount = MutableStateFlow(prefs.getInt("total_unread_count", 0))
    val totalUnreadCount: StateFlow<Int> = _totalUnreadCount.asStateFlow()

    private val _hasUnread = MutableStateFlow(prefs.getInt("total_unread_count", 0) > 0)
    val hasUnread: StateFlow<Boolean> = _hasUnread.asStateFlow()

    private var pollingJob: Job? = null

    /** 启动全局未读计数轮询（Application onCreate 时调用） */
    fun startPolling(scope: CoroutineScope) {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            // 启动时立即拉取一次
            pollUnreadCount()
            while (isActive) {
                delay(15_000) // 15 秒间隔
                pollUnreadCount()
            }
        }
    }

    /** 停止轮询 */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /** 拉取通知 + DM 未读数并更新全局状态 */
    private suspend fun pollUnreadCount() {
        try {
            // 通知未读数
            val notificationUnread = try {
                val resp = apiService.getUnreadNotificationCount()
                if (resp.code == 200) resp.data?.toInt() ?: 0 else 0
            } catch (_: Exception) { 0 }

            // DM 未读数
            var dmUnread = 0
            try {
                val dmResp = apiService.getDmConversations()
                if (dmResp.code == 200 && dmResp.data != null) {
                    val currentUserId = tokenManager.getUserId() ?: ""
                    for (conv in dmResp.data) {
                        val myUnread = if (conv.userAId == currentUserId) conv.userAUnread else conv.userBUnread
                        dmUnread += myUnread
                    }
                }
            } catch (_: Exception) {}

            updateUnreadCount(notificationUnread + dmUnread)
        } catch (_: Exception) {}
    }

    /** 更新未读计数并持久化 */
    fun updateUnreadCount(count: Int) {
        val safeCount = maxOf(0, count)
        _totalUnreadCount.value = safeCount
        _hasUnread.value = safeCount > 0
        prefs.edit().putInt("total_unread_count", safeCount).apply()
    }

    /** 标记有新消息（向后兼容：仅在计数为 0 时 +1） */
    fun markUnread() {
        if (_totalUnreadCount.value == 0) {
            updateUnreadCount(1)
        }
    }

    /** 用户进入消息页后调用，清除未读状态 */
    fun clearUnread() {
        updateUnreadCount(0)
    }
}
