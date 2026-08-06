package com.joysong.app.domain.model

data class Banner(
    val id: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val accentColor: String = "#E8A0BF"
)

