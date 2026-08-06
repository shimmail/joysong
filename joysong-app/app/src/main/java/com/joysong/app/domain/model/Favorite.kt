package com.joysong.app.domain.model

enum class FavoriteType {
    PROJECT, INSTITUTION, DOCTOR, DIARY, ARTICLE
}

data class Favorite(
    val id: String,
    val userId: String,
    val targetType: FavoriteType,
    val targetId: String,
    val targetName: String = "",
    val targetImage: String = "",
    val createdAt: String = ""
)

data class FavoriteStatus(
    val favorited: Boolean = false,
    val count: Long = 0
)
