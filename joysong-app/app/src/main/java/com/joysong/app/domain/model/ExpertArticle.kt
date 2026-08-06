package com.joysong.app.domain.model

data class ExpertArticle(
    val id: String,
    val title: String,
    val authorName: String,
    val summary: String,
    val coverImage: String = "",
    val publishDate: String = "",
    val content: String = "",
    val readCount: Int = 0,
    val doctorId: String = "",
    val isFavorited: Boolean = false
)
