package com.joysong.app.domain.model

data class Notification(
    val id: String,
    val userId: String,
    val title: String,
    val content: String,
    val type: String = "",
    val isRead: Boolean = false,
    val targetId: String = "",
    val createdAt: String = ""
)
