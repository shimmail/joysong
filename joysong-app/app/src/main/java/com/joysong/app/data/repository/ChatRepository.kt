package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiException
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.ChatMessageDto
import com.joysong.app.data.remote.dto.ChatSessionDto
import com.joysong.app.data.remote.dto.ChatTurnDto
import com.joysong.app.data.remote.dto.CreateChatSessionRequest
import com.joysong.app.data.remote.dto.SendChatMessageRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChatRepository @Inject constructor(
    private val apiService: ApiService
) {
    suspend fun createSession(
        persona: String,
        contextType: String = "GENERAL",
        contextId: String = "",
        title: String = ""
    ): Result<ChatSessionDto> {
        return try {
            val response = apiService.createChatSession(
                CreateChatSessionRequest(
                    persona = persona,
                    contextType = contextType,
                    contextId = contextId,
                    title = title
                )
            )
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getSessions(persona: String? = null): Result<List<ChatSessionDto>> {
        return try {
            val response = apiService.getChatSessions(persona)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(sessionId: String, content: String): Result<ChatTurnDto> {
        return try {
            val response = apiService.sendChatMessage(sessionId, SendChatMessageRequest(content))
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getMessages(sessionId: String): Result<List<ChatMessageDto>> {
        return try {
            val response = apiService.getChatMessages(sessionId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data)
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteMessage(messageId: String): Result<String> {
        return try {
            val response = apiService.deleteChatMessage(messageId)
            if (response.code == 200) {
                Result.success("ok")
            } else {
                Result.failure(ApiException(response.code, response.message))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun clearMessages(sessionId: String): Result<String> = try {
        val response = apiService.clearChatMessages(sessionId)
        if (response.code == 200) Result.success("ok")
        else Result.failure(ApiException(response.code, response.message))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun deleteSession(sessionId: String): Result<String> = try {
        val response = apiService.deleteChatSession(sessionId)
        if (response.code == 200) Result.success("ok")
        else Result.failure(ApiException(response.code, response.message))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun clearSessions(persona: String = "CONSULTANT"): Result<String> = try {
        val response = apiService.clearChatSessions(persona)
        if (response.code == 200) Result.success("ok")
        else Result.failure(ApiException(response.code, response.message))
    } catch (e: Exception) {
        Result.failure(e)
    }
}
