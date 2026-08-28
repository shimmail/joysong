package com.joysong.server.favorite.service

import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.favorite.entity.FavoriteEntity
import com.joysong.server.favorite.entity.dto.AddFavoriteRequest
import com.joysong.server.favorite.entity.dto.FavoriteResponse
import com.joysong.server.favorite.repository.FavoriteRepository
import com.joysong.server.institution.repository.InstitutionRepository
import com.joysong.server.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import com.joysong.server.user.service.AccountLifecycleGuard

@Service
class FavoriteService(
    private val favoriteRepository: FavoriteRepository,
    private val diaryRepository: DiaryRepository,
    private val projectRepository: ProjectRepository,
    private val institutionRepository: InstitutionRepository,
    private val doctorRepository: DoctorRepository,
    private val articleRepository: ArticleRepository,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {

    fun getFavorites(userId: String): List<FavoriteEntity> {
        return favoriteRepository.findByUserId(userId)
    }

    @Transactional
    fun addFavorite(userId: String, request: AddFavoriteRequest): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val targetType = normalizeAndValidateTarget(request.targetType, request.targetId)
        // 幂等：已收藏则直接返回成功
        if (favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, targetType, request.targetId).isPresent) {
            return mapOf("message" to "收藏成功")
        }

        val favorite = FavoriteEntity(
            id = UUID.randomUUID().toString(),
            userId = userId,
            targetType = targetType,
            targetId = request.targetId,
            targetName = request.targetName,
            targetImage = request.targetImage,
            createdAt = LocalDateTime.now()
        )
        favoriteRepository.save(favorite)

        // 同步日记收藏数
        if (targetType == "DIARY") {
            diaryRepository.incrementFavoriteCount(request.targetId)
        }

        return mapOf("message" to "收藏成功")
    }

    @Transactional
    fun removeFavorite(userId: String, type: String, targetId: String): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val normalizedType = normalizeAndValidateTarget(type, targetId)
        val favorite = favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, normalizedType, targetId).orElse(null)
            ?: return mapOf("error" to "未找到收藏记录", "code" to 404)

        favoriteRepository.delete(favorite)

        // 同步日记收藏数
        if (normalizedType == "DIARY") {
            diaryRepository.decrementFavoriteCount(targetId)
        }

        return mapOf("message" to "取消收藏成功")
    }

    fun isFavorite(userId: String, type: String, targetId: String): FavoriteResponse {
        val normalizedType = normalizeAndValidateTarget(type, targetId)
        val exists = favoriteRepository.findByUserIdAndTargetTypeAndTargetId(userId, normalizedType, targetId).isPresent
        val count = favoriteRepository.countByTargetTypeAndTargetId(normalizedType, targetId)
        return FavoriteResponse(favorited = exists, count = count)
    }

    private fun normalizeAndValidateTarget(type: String, targetId: String): String {
        val normalized = type.trim().uppercase()
        require(targetId.isNotBlank()) { "收藏目标不能为空" }
        val exists = when (normalized) {
            "DIARY" -> diaryRepository.existsById(targetId)
            "PROJECT" -> projectRepository.existsById(targetId)
            "INSTITUTION" -> institutionRepository.existsById(targetId)
            "DOCTOR" -> doctorRepository.existsById(targetId)
            "ARTICLE" -> articleRepository.existsById(targetId)
            else -> throw IllegalArgumentException("不支持的收藏类型")
        }
        require(exists) { "收藏目标不存在" }
        return normalized
    }
}
