package com.joysong.server.admin.dto

import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.entity.AccountState
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
    val accountState: AccountState,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime?,
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
    accountState = accountState,
    createdAt = createdAt,
    updatedAt = updatedAt,
    hasPassword = passwordHash.isNotEmpty()
)
