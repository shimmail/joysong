package com.joysong.server.article.repository

import com.joysong.server.article.entity.ArticleEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ArticleRepository : JpaRepository<ArticleEntity, String> {
    fun findByTitleContainingOrAuthorNameContaining(title: String, authorName: String): List<ArticleEntity>

    @Query("SELECT a FROM ArticleEntity a WHERE a.title LIKE %:keyword% OR a.id = :keyword")
    fun searchArticles(@Param("keyword") keyword: String): List<ArticleEntity>
}
