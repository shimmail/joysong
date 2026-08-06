package com.joysong.server.comment.service

import com.joysong.server.comment.entity.CommentEntity
import com.joysong.server.comment.entity.dto.CommentResponse
import com.joysong.server.comment.entity.dto.PublishCommentRequest
import com.joysong.server.comment.repository.CommentRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.like.repository.LikeRepository
import com.joysong.server.user.repository.UserRepository
import jakarta.persistence.EntityManager
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class CommentService(
    private val commentRepository: CommentRepository,
    private val userRepository: UserRepository,
    private val likeRepository: LikeRepository,
    private val diaryRepository: DiaryRepository,
    private val entityManager: EntityManager
) {

    /**
     * 将评论实体转换为响应，同时从 users 表获取用户信息
     */
    fun toResponse(comment: CommentEntity, currentUserId: String? = null): CommentResponse {
        val user = userRepository.findById(comment.userId).orElse(null)
        val replyToUser = comment.replyToUserId?.let { userRepository.findById(it).orElse(null) }
        val isLiked = currentUserId?.let {
            likeRepository.existsByUserIdAndTargetTypeAndTargetId(it, "comment", comment.id)
        } ?: false
        return CommentResponse(
            id = comment.id,
            diaryId = comment.diaryId,
            userId = comment.userId,
            userName = user?.nickname ?: "已注销用户",
            userAvatar = user?.avatar ?: "",
            content = comment.content,
            parentId = comment.parentId,
            replyToUserId = comment.replyToUserId,
            replyToUserName = if (comment.replyToUserId != null) replyToUser?.nickname ?: "已注销用户" else null,
            createdAt = comment.createdAt.toString(),
            likeCount = comment.likeCount,
            isLiked = isLiked
        )
    }

    fun getComments(diaryId: String, currentUserId: String?): List<CommentResponse> {
        return commentRepository.findByDiaryIdAndParentIdIsNullOrderByCreatedAtDesc(diaryId)
            .map { toResponse(it, currentUserId) }
    }

    fun getReplies(parentId: String, currentUserId: String?): List<CommentResponse> {
        return commentRepository.findByParentIdOrderByCreatedAtAsc(parentId)
            .map { toResponse(it, currentUserId) }
    }

    @Caching(evict = [
        CacheEvict(cacheNames = ["comments"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["discover"], allEntries = true, beforeInvocation = false)
    ])
    @Transactional
    fun publishComment(userId: String, request: PublishCommentRequest): Any {
        userRepository.findById(userId).orElse(null)
            ?: return mapOf("error" to "用户不存在", "code" to 404)
        require(diaryRepository.existsById(request.diaryId)) { "日记不存在" }

        val parent = request.parentId?.let { parentId ->
            commentRepository.findById(parentId).orElseThrow { IllegalArgumentException("父评论不存在") }
        }
        if (parent != null) {
            require(parent.diaryId == request.diaryId) { "父评论不属于当前日记" }
            require(parent.parentId == null) { "只能回复顶级评论" }
        } else {
            require(request.replyToUserId == null) { "顶级评论不能指定被回复用户" }
        }
        request.replyToUserId?.let { replyToUserId ->
            require(userRepository.existsById(replyToUserId)) { "被回复用户不存在" }
            val threadUserIds = buildSet {
                parent?.userId?.let(::add)
                parent?.id?.let(commentRepository::findByParentIdOrderByCreatedAtAsc)
                    ?.mapTo(this) { it.userId }
            }
            require(replyToUserId in threadUserIds) { "被回复用户不在当前评论线程中" }
        }

        val comment = CommentEntity(
            id = UUID.randomUUID().toString(),
            diaryId = request.diaryId,
            userId = userId,
            content = request.content.trim(),
            parentId = request.parentId,
            replyToUserId = request.replyToUserId,
            createdAt = LocalDateTime.now()
        )
        val saved = commentRepository.save(comment)

        // 原子更新 diaries.comment_count +1
        diaryRepository.incrementCommentCount(request.diaryId)

        return toResponse(saved)
    }

    /**
     * 删除评论（仅作者本人）
     * 如果删除的是父评论，同时软删除所有子回复
     */
    @Caching(evict = [
        CacheEvict(cacheNames = ["comments"], allEntries = true, beforeInvocation = false),
        CacheEvict(cacheNames = ["discover"], allEntries = true, beforeInvocation = false)
    ])
    @Transactional
    fun deleteComment(userId: String, id: String): Any {
        val comment = commentRepository.findById(id).orElse(null)
            ?: return mapOf("error" to "评论不存在", "code" to 404)

        if (comment.userId != userId) {
            return mapOf("error" to "无权删除他人评论", "code" to 403)
        }

        // 如果是父评论，先软删除所有子回复
        var deletedCount = 1
        if (comment.parentId == null) {
            val replies = commentRepository.findByParentIdOrderByCreatedAtAsc(id)
            deletedCount += replies.size
            replies.forEach { reply ->
                commentRepository.save(reply.copy(deletedAt = LocalDateTime.now()))
            }
        }

        // 软删除当前评论
        commentRepository.save(comment.copy(deletedAt = LocalDateTime.now()))

        // 清除 JPA 持久化上下文，确保后续查询不返回已软删除的实体
        entityManager.flush()
        entityManager.clear()

        // 原子更新 diaries.comment_count
        diaryRepository.decrementCommentCount(comment.diaryId, deletedCount)

        return mapOf("message" to "删除评论成功", "deletedCount" to deletedCount)
    }
}
