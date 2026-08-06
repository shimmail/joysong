package com.joysong.server.user.dto

import java.time.LocalDate

data class UserProfileResponse(
    val id: String,
    val nickname: String,
    val avatar: String? = null,
    val gender: String? = null,
    val bio: String? = null,
    val city: String? = null,
    val birthday: LocalDate? = null,
    val diaryCount: Int = 0,
    val followingCount: Int = 0,
    val followerCount: Int = 0
)
