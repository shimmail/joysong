package com.joysong.server.diary.repository

import com.joysong.server.diary.entity.DiaryEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DiaryRepository : JpaRepository<DiaryEntity, String> {
    @Query("SELECT d FROM DiaryEntity d WHERE d.id = :id AND d.status = 'published'")
    fun findPublishedById(@Param("id") id: String): java.util.Optional<DiaryEntity>
    fun findTop8ByStatusOrderByPublishDateDesc(status: String): List<DiaryEntity>
    fun findByTitleContainingOrTagsContaining(title: String, tags: String): List<DiaryEntity>
    fun findByAuthorName(authorName: String): List<DiaryEntity>
    fun findByUserId(userId: String): List<DiaryEntity>
    fun findByUserIdOrderByCreatedAtDesc(userId: String): List<DiaryEntity>
    fun findByDoctorId(doctorId: String): List<DiaryEntity>
    fun findByProjectId(projectId: String): List<DiaryEntity>
    fun findByInstitutionId(institutionId: String): List<DiaryEntity>
    fun findByInstitutionProjectId(institutionProjectId: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.title LIKE %:keyword% OR d.id = :keyword")
    fun searchDiaries(@Param("keyword") keyword: String): List<DiaryEntity>

    // Filtered queries: only show published diaries for public display
    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' ORDER BY d.publishDate DESC")
    fun findPublishedOrderByPublishDateDesc(): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND d.userId = :userId ORDER BY d.publishDate DESC")
    fun findPublishedByUserId(userId: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND (LOWER(d.title) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(d.tags) LIKE LOWER(CONCAT('%', :q, '%')))")
    fun findPublishedByTitleOrTags(q: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND d.doctorId = :doctorId")
    fun findPublishedByDoctorId(doctorId: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND d.projectId = :projectId")
    fun findPublishedByProjectId(projectId: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND d.institutionId = :institutionId")
    fun findPublishedByInstitutionId(institutionId: String): List<DiaryEntity>

    @Query("SELECT d FROM DiaryEntity d WHERE d.status = 'published' AND d.institutionProjectId = :institutionProjectId")
    fun findPublishedByInstitutionProjectId(institutionProjectId: String): List<DiaryEntity>

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.likeCount = d.likeCount + 1 WHERE d.id = :id")
    fun incrementLikeCount(@Param("id") id: String)

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.likeCount = CASE WHEN d.likeCount > 0 THEN d.likeCount - 1 ELSE 0 END WHERE d.id = :id")
    fun decrementLikeCount(@Param("id") id: String)

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.commentCount = d.commentCount + 1 WHERE d.id = :id")
    fun incrementCommentCount(@Param("id") id: String)

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.commentCount = CASE WHEN d.commentCount > :count THEN d.commentCount - :count ELSE 0 END WHERE d.id = :id")
    fun decrementCommentCount(@Param("id") id: String, @Param("count") count: Int)

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.favoriteCount = d.favoriteCount + 1 WHERE d.id = :id")
    fun incrementFavoriteCount(@Param("id") id: String)

    @Modifying
    @Query("UPDATE DiaryEntity d SET d.favoriteCount = CASE WHEN d.favoriteCount > 0 THEN d.favoriteCount - 1 ELSE 0 END WHERE d.id = :id")
    fun decrementFavoriteCount(@Param("id") id: String)
}
