package com.joysong.app.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ArticleRepository @Inject constructor() {

    private val _favorites = MutableStateFlow<Set<String>>(emptySet())
    val favoritesFlow: StateFlow<Set<String>> = _favorites.asStateFlow()

    /**
     * 切换文章收藏状态
     */
    fun toggleFavorite(articleId: String) {
        _favorites.value = if (_favorites.value.contains(articleId)) {
            _favorites.value - articleId
        } else {
            _favorites.value + articleId
        }
    }

    /**
     * 观察某篇文章是否已收藏
     */
    fun isFavorite(articleId: String): Flow<Boolean> {
        return _favorites.map { it.contains(articleId) }
    }

    /**
     * 同步获取某篇文章是否已收藏
     */
    fun isFavoriteSync(articleId: String): Boolean {
        return _favorites.value.contains(articleId)
    }

    /**
     * 获取所有已收藏文章 ID 的快照
     */
    fun getFavoriteIds(): Set<String> = _favorites.value
}
