package com.joysong.server.comment.repository

import com.joysong.server.comment.entity.CommentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CommentRepository : JpaRepository<CommentEntity, String> {
    // 查询日记的所有评论（包含回复）
    fun findByDiaryIdOrderByCreatedAtDesc(diaryId: String): List<CommentEntity>

    // 查询日记的顶级评论（不包含回复）
    fun findByDiaryIdAndParentIdIsNullOrderByCreatedAtDesc(diaryId: String): List<CommentEntity>

    // 查询某评论的所有回复
    fun findByParentIdOrderByCreatedAtAsc(parentId: String): List<CommentEntity>

    fun countByDiaryId(diaryId: String): Long

    // 统计某评论的回复数
    fun countByParentId(parentId: String): Long

    @Modifying
    @Query("UPDATE CommentEntity c SET c.likeCount = c.likeCount + 1 WHERE c.id = :id")
    fun incrementLikeCount(@Param("id") id: String)

    @Modifying
    @Query("UPDATE CommentEntity c SET c.likeCount = CASE WHEN c.likeCount > 0 THEN c.likeCount - 1 ELSE 0 END WHERE c.id = :id")
    fun decrementLikeCount(@Param("id") id: String)
}
