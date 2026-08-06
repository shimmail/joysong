package com.joysong.app.domain.model

data class Comment(
    val id: String = "",
    val diaryId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userAvatar: String = "",
    val content: String = "",
    val parentId: String? = null,
    val replyToUserId: String? = null,
    val replyToUserName: String? = null,
    val createdAt: String = "",
    val likeCount: Int = 0,
    val isLiked: Boolean = false
)
