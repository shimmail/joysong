package com.joysong.app.data.repository

import com.joysong.app.domain.model.ChatMessage
import com.joysong.app.domain.repository.AiRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AiRepositoryImpl @Inject constructor() : AiRepository {

    override suspend fun sendMessage(
        sessionId: String,
        role: String,
        content: String
    ): Flow<ChatMessage> = flow {
        // 旧 AI 页面仅保留离线兜底；新客户端统一使用 /api/chat 与 /api/agent。
        delay(800)
        emit(mockReply(sessionId, role, content))
    }

    override suspend fun getQuickQuestions(role: String): Result<List<String>> {
        return Result.success(defaultQuickQuestions(role))
    }

    private fun mockReply(sessionId: String, role: String, userText: String): ChatMessage {
        val reply = when (role.uppercase()) {
            "BESTIE" -> "懂你！${userText} 这种心情我太熟悉了，慢慢来，你本来就很美 ✨"
            else -> "关于「${userText}」，建议先从皮肤检测和面诊开始，我可以帮你推荐合适的机构和项目。"
        }
        return ChatMessage(
            id = UUID.randomUUID().toString(),
            sessionId = sessionId,
            content = reply,
            isUser = false,
            role = role
        )
    }

    private fun defaultQuickQuestions(role: String): List<String> = when (role.uppercase()) {
        "BESTIE" -> listOf("我想做鼻子但有点害怕", "最近皮肤状态好差", "术后恢复要注意什么")
        "CS" -> listOf("我的订单有问题", "如何申请退款？", "咨询预约相关", "投诉与建议")
        else -> listOf("帮我推荐热门项目", "北京有哪些靠谱机构", "眼部年轻化多少钱")
    }
}
