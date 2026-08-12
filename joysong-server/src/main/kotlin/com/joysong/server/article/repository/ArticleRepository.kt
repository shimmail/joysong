package com.joysong.server.article.repository

import com.joysong.server.article.entity.ArticleEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface ArticleRepository : JpaRepository<ArticleEntity, String> {
    @Query("""
        SELECT a FROM ArticleEntity a
        WHERE (:doctorId IS NULL OR a.doctorId = :doctorId)
          AND (:keyword IS NULL OR LOWER(a.title) LIKE LOWER(CONCAT('%', :keyword, '%')) OR a.id = :keyword)
        ORDER BY a.publishDate DESC, a.createdAt DESC, a.id DESC
    """)
    fun findManagementArticles(
        @Param("doctorId") doctorId: String?,
        @Param("keyword") keyword: String?,
        pageable: Pageable
    ): Page<ArticleEntity>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM ArticleEntity a WHERE a.id = :id")
    fun findByIdForUpdate(@Param("id") id: String): ArticleEntity?

    fun findTop6ByOrderByPublishDateDesc(): List<ArticleEntity>
    fun findByTitleContainingOrAuthorNameContaining(title: String, authorName: String): List<ArticleEntity>

    @Query("SELECT a FROM ArticleEntity a WHERE a.title LIKE %:keyword% OR a.id = :keyword")
    fun searchArticles(@Param("keyword") keyword: String): List<ArticleEntity>
}
