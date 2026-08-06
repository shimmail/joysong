package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.PublishCommentRequest
import com.joysong.app.domain.model.Comment
import com.joysong.app.domain.repository.CommentRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CommentRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : CommentRepository {

    override suspend fun getComments(diaryId: String): Result<List<Comment>> {
        return try {
            val response = apiService.getComments(diaryId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getReplies(parentId: String): Result<List<Comment>> {
        return try {
            val response = apiService.getCommentReplies(parentId)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.map { it.toDomain() })
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addComment(diaryId: String, content: String, parentId: String?, replyToUserId: String?): Result<Comment> {
        return try {
            val response = apiService.addComment(PublishCommentRequest(diaryId, content, parentId, replyToUserId))
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.toDomain())
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteComment(id: String): Result<String> {
        return try {
            val response = apiService.deleteComment(id)
            if (response.code == 200) {
                Result.success(response.data ?: "deleted")
            } else Result.failure(Exception(response.message))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
