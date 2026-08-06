package com.joysong.app.domain.model

data class SearchHistory(
    val id: String,
    val userId: String,
    val keyword: String,
    val searchedAt: String = ""
)
