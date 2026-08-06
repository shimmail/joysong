package com.joysong.app.data.repository

import android.content.Context
import com.joysong.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class MessageCardItem(
    val id: String,
    val name: String,
    val avatarUrl: String? = null,
    val emoji: String? = null,
    val preview: String,
    val time: String,
    val unreadCount: Int = 0,
    val isPinned: Boolean = false,
    val isUnread: Boolean = false
)

@Singleton
class MessageCenter @Inject constructor(
    @ApplicationContext private val appContext: Context
) {

    private val _messageCards = MutableStateFlow<List<MessageCardItem>>(emptyList())
    val messageCards: StateFlow<List<MessageCardItem>> = _messageCards.asStateFlow()

    private val deletedAtPrefs = appContext.getSharedPreferences("chat_deleted_at", Context.MODE_PRIVATE)
    private val _chatDeletedAt = MutableStateFlow<Map<String, String>>(loadDeletedAtMap())
    val chatDeletedAt: StateFlow<Map<String, String>> = _chatDeletedAt.asStateFlow()

    /**
     * 迁移旧版 deletedIds SharedPreferences 到 chatDeletedAt。
     * 首次升级时调用，确保之前通过 deletedIds 删除的 AI/CS 卡片不会在升级后复活。
     */
    fun migrateDeletedIds() {
        val oldPrefs = appContext.getSharedPreferences("deleted_message_ids", Context.MODE_PRIVATE)
        val oldIds = oldPrefs.getStringSet("ids", emptySet()) ?: emptySet()
        if (oldIds.isNotEmpty()) {
            val now = LocalDateTime.now().toString()
            val editor = deletedAtPrefs.edit()
            val current = _chatDeletedAt.value.toMutableMap()
            for (id in oldIds) {
                if (id !in current) {
                    current[id] = now
                    editor.putString(id, now)
                }
            }
            _chatDeletedAt.value = current
            editor.apply()
        }
        // 清除旧版 SharedPreferences，防止再次迁移
        oldPrefs.edit().clear().apply()
    }

    /** 标记卡片为已删除（临时隐藏，新消息到达时自动恢复） */
    fun markDeleted(id: String) {
        setChatDeletedAt(id, LocalDateTime.now().toString())
    }

    /** 记录会话的删除时间戳（空字符串表示清除删除标记） */
    fun setChatDeletedAt(id: String, timestamp: String) {
        if (timestamp.isBlank()) {
            _chatDeletedAt.value = _chatDeletedAt.value - id
            deletedAtPrefs.edit().remove(id).apply()
        } else {
            _chatDeletedAt.value = _chatDeletedAt.value + (id to timestamp)
            deletedAtPrefs.edit().putString(id, timestamp).apply()
        }
    }

    /** 获取会话的删除时间戳，null 表示未被删除 */
    fun getChatDeletedAt(id: String): String? {
        val ts = _chatDeletedAt.value[id]
        return if (ts.isNullOrBlank()) null else ts
    }

    private fun loadDeletedAtMap(): Map<String, String> {
        return deletedAtPrefs.all.entries.mapNotNull { (k, v) ->
            if (v is String) k to v else null
        }.toMap()
    }

    /** 清除所有运行时数据（切换账号时调用） */
    fun clearAll() {
        _messageCards.value = emptyList()
        _chatDeletedAt.value = emptyMap()
        deletedAtPrefs.edit().clear().apply()
    }

    fun upsertDmCard(userId: String, nickname: String, avatarUrl: String?, lastMessage: String, unreadCount: Int = 0) {
        val id = "DM_$userId"
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd"))
        val newCard = MessageCardItem(
            id = id,
            name = nickname,
            avatarUrl = avatarUrl,
            preview = lastMessage,
            time = time,
            unreadCount = unreadCount
        )
        val current = _messageCards.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = newCard.copy(
                isPinned = current[index].isPinned,
                isUnread = current[index].isUnread
            )
        } else {
            current.add(newCard)
        }
        _messageCards.value = current
        // 有新消息活动，自动恢复已删除的 DM 卡片
        setChatDeletedAt(id, "")
    }

    /** Upsert DM card with an explicit timestamp (e.g. from backend conversation updatedAt) */
    fun upsertDmCard(userId: String, nickname: String, avatarUrl: String?, lastMessage: String, timestamp: String?, unreadCount: Int = 0) {
        val time = formatDate(timestamp)
        val id = "DM_$userId"
        val newCard = MessageCardItem(
            id = id,
            name = nickname,
            avatarUrl = avatarUrl,
            preview = lastMessage,
            time = time,
            unreadCount = unreadCount
        )
        val current = _messageCards.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current[index] = newCard.copy(
                isPinned = current[index].isPinned,
                isUnread = current[index].isUnread
            )
        } else {
            current.add(newCard)
        }
        _messageCards.value = current
        // 有新消息活动，自动恢复已删除的 DM 卡片
        setChatDeletedAt(id, "")
    }

    /** 移除指定用户的 DM 卡片（如 CS_ADMIN 泄漏到 DM 列表时清理） */
    fun removeDmCardIfExists(userId: String) {
        val cardId = "DM_$userId"
        val current = _messageCards.value.toMutableList()
        if (current.removeAll { it.id == cardId }) {
            _messageCards.value = current
        }
    }

    fun updateAiCard(role: String, lastMessage: String) {
        val time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("MM-dd"))
        val current = _messageCards.value.toMutableList()
        val index = current.indexOfFirst { it.id == role }
        if (index >= 0) {
            current[index] = current[index].copy(preview = lastMessage, time = time)
        } else {
            val (name, emoji) = getAiCardDefaults(role)
            current.add(
                MessageCardItem(
                    id = role,
                    name = name,
                    emoji = emoji,
                    preview = lastMessage,
                    time = time
                )
            )
        }
        _messageCards.value = current
        // 有新的 AI 聊天消息活动，恢复已被删除的卡片
        setChatDeletedAt(role, "")
    }

    fun updateAiCardFromSession(role: String, lastMessage: String, updatedAt: String?, unreadCount: Int = 0) {
        val time = formatDate(updatedAt)
        val current = _messageCards.value.toMutableList()
        val index = current.indexOfFirst { it.id == role }
        if (index >= 0) {
            current[index] = current[index].copy(
                preview = lastMessage.ifBlank { current[index].preview },
                time = if (time != "--") time else current[index].time,
                unreadCount = unreadCount
            )
        } else {
            val (name, emoji) = getAiCardDefaults(role)
            current.add(
                MessageCardItem(
                    id = role,
                    name = name,
                    emoji = emoji,
                    preview = lastMessage.ifBlank { getAiDefaultPreview(role) },
                    time = time,
                    unreadCount = unreadCount
                )
            )
        }
        _messageCards.value = current
        // 有新的会话消息活动，恢复已被删除的卡片
        setChatDeletedAt(role, "")
    }

    fun initAiCards() {
        val current = _messageCards.value.toMutableList()
        // The planning Agent supersedes the legacy BESTIE and CONSULTANT cards.
        var changed = current.removeAll { it.id == "BESTIE" || it.id == "CONSULTANT" }
        val aiRoles = listOf("AI_AGENT", "CS")
        for (role in aiRoles) {
            if (current.none { it.id == role }) {
                val (name, emoji) = getAiCardDefaults(role)
                current.add(
                    MessageCardItem(
                        id = role,
                        name = name,
                        emoji = emoji,
                        preview = getAiDefaultPreview(role),
                        time = "--"
                    )
                )
                changed = true
            }
        }
        if (changed) {
            _messageCards.value = current
        }
    }

    fun removeCards(vararg ids: String) {
        val idSet = ids.toSet()
        _messageCards.value = _messageCards.value.filterNot { it.id in idSet }
    }

    private fun getAiCardDefaults(role: String): Pair<String, String> = when (role) {
        "AI_AGENT" -> appContext.getString(R.string.agent_title) to "\uD83E\uDE84"
        "BESTIE" -> "AI闺蜜" to "\uD83D\uDC95"
        "CONSULTANT" -> "AI美学咨询师" to "\uD83D\uDC8E"
        "CS" -> "平台客服" to "\uD83D\uDCAC"
        else -> role to "\uD83E\uDD16"
    }

    private fun getAiDefaultPreview(role: String): String = when (role) {
        "AI_AGENT" -> appContext.getString(R.string.agent_subtitle)
        "BESTIE" -> "很高兴认识你 \uD83D\uDC4B"
        "CONSULTANT" -> "专业分析，科学变美"
        "CS" -> "平台客服为您服务"
        else -> "开始对话吧"
    }

    private fun formatDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "--"
        return try {
            val parsed = LocalDateTime.parse(dateStr.take(19))
            when {
                parsed.toLocalDate() == LocalDate.now() -> parsed.format(DateTimeFormatter.ofPattern("HH:mm"))
                parsed.year == LocalDate.now().year -> parsed.format(DateTimeFormatter.ofPattern("MM-dd"))
                else -> parsed.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
            }
        } catch (_: Exception) {
            try {
                val datePart = dateStr.take(10)
                val parts = datePart.split("-")
                if (parts.size == 3) "${parts[1]}-${parts[2]}" else "--"
            } catch (_: Exception) {
                "--"
            }
        }
    }
}
