package com.joysong.app.domain.repository

import com.joysong.app.domain.model.Comment

interface CommentRepository {
    suspend fun getComments(diaryId: String): Result<List<Comment>>
    suspend fun getReplies(parentId: String): Result<List<Comment>>
    suspend fun addComment(diaryId: String, content: String, parentId: String? = null, replyToUserId: String? = null): Result<Comment>
    suspend fun deleteComment(id: String): Result<String>
}
