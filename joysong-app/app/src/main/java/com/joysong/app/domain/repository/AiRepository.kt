package com.joysong.app.domain.repository

import com.joysong.app.domain.model.ChatMessage
import kotlinx.coroutines.flow.Flow

interface AiRepository {
    suspend fun sendMessage(sessionId: String, role: String, content: String): Flow<ChatMessage>
    suspend fun getQuickQuestions(role: String): Result<List<String>>
}
