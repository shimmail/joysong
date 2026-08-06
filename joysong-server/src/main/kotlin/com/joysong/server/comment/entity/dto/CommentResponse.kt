package com.joysong.server.comment.entity.dto

data class CommentResponse(
    val id: String,
    val diaryId: String,
    val userId: String,
    val userName: String,
    val userAvatar: String,
    val content: String,
    val parentId: String?,
    val replyToUserId: String?,
    val replyToUserName: String?,
    val createdAt: String,
    val likeCount: Int = 0,
    val isLiked: Boolean = false
)
