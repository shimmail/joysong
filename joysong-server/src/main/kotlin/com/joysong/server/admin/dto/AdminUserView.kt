package com.joysong.server.admin.dto

import com.joysong.server.user.entity.UserEntity
import java.time.LocalDate
import java.time.LocalDateTime

data class AdminUserView(
    val id: String,
    val phone: String?,
    val email: String?,
    val nickname: String,
    val avatar: String,
    val gender: String,
    val city: String,
    val bio: String,
    val birthday: LocalDate?,
    val role: String,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
    val deletedAt: LocalDateTime?,
    val hasPassword: Boolean
)

internal fun UserEntity.toAdminUserView() = AdminUserView(
    id = id,
    phone = phone,
    email = email,
    nickname = nickname,
    avatar = avatar,
    gender = gender,
    city = city,
    bio = bio,
    birthday = birthday,
    role = role,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
    hasPassword = passwordHash.isNotEmpty()
)
