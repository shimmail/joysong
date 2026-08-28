package com.joysong.server.like.service

import com.joysong.server.comment.repository.CommentRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.like.entity.LikeEntity
import com.joysong.server.like.entity.dto.LikeRequest
import com.joysong.server.like.entity.dto.LikeResponse
import com.joysong.server.like.repository.LikeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.joysong.server.user.service.AccountLifecycleGuard

@Service
class LikeService(
    private val likeRepository: LikeRepository,
    private val diaryRepository: DiaryRepository,
    private val commentRepository: CommentRepository,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {

    @Transactional
    fun addLike(userId: String, request: LikeRequest): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val targetType = normalizeAndValidateTarget(request.targetType, request.targetId)
        val targetId = request.targetId

        // 幂等：已点赞则直接返回成功
        if (likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, targetType, targetId)) {
            return "success"
        }

        val like = LikeEntity(userId = userId, targetType = targetType, targetId = targetId)
        likeRepository.save(like)

        // 同步日记点赞数
        if (targetType == "diary") {
            diaryRepository.incrementLikeCount(targetId)
        }
        // 同步评论点赞数
        if (targetType == "comment") {
            commentRepository.incrementLikeCount(targetId)
        }

        return "success"
    }

    @Transactional
    fun removeLike(userId: String, targetType: String, targetId: String): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val normalizedType = normalizeAndValidateTarget(targetType, targetId)
        val like = likeRepository.findByUserIdAndTargetTypeAndTargetId(userId, normalizedType, targetId)
            ?: return mapOf("error" to "未找到点赞记录", "code" to 404)

        likeRepository.delete(like)

        // 同步日记点赞数
        if (normalizedType == "diary") {
            diaryRepository.decrementLikeCount(targetId)
        }
        // 同步评论点赞数
        if (normalizedType == "comment") {
            commentRepository.decrementLikeCount(targetId)
        }

        return "success"
    }

    fun checkLike(userId: String, targetType: String, targetId: String): LikeResponse {
        val normalizedType = normalizeAndValidateTarget(targetType, targetId)
        val liked = likeRepository.existsByUserIdAndTargetTypeAndTargetId(userId, normalizedType, targetId)
        val count = likeRepository.countByTargetTypeAndTargetId(normalizedType, targetId)
        return LikeResponse(liked = liked, count = count)
    }

    private fun normalizeAndValidateTarget(targetType: String, targetId: String): String {
        val normalized = targetType.trim().lowercase()
        require(targetId.isNotBlank()) { "点赞目标不能为空" }
        require(normalized in setOf("diary", "comment")) { "不支持的点赞类型" }
        val exists = when (normalized) {
            "diary" -> diaryRepository.existsById(targetId)
            else -> commentRepository.existsById(targetId)
        }
        require(exists) { "点赞目标不存在" }
        return normalized
    }
}
