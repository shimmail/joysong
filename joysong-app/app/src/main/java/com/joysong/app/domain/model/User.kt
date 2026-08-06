package com.joysong.app.domain.model

data class User(
    val id: String,
    val phone: String? = null,
    val email: String? = null,
    val nickname: String = "",
    val avatar: String = "",
    val city: String = "",
    val bio: String = "",
    val gender: String = "",
    val birthday: String = "",
    val hasPassword: Boolean = true,
    val token: String? = null
)
