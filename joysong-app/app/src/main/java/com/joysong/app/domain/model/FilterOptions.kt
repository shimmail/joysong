package com.joysong.app.domain.model

data class FilterOptions(
    val categories: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val cities: List<String> = emptyList()
)
