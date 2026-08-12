package com.joysong.server.article.service

import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.article.dto.DoctorArticleUpsertRequest
import com.joysong.server.article.dto.DoctorArticleView
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import com.joysong.server.common.OffsetPageRequest
import org.springframework.security.access.AccessDeniedException
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class ArticleService(
    private val articleRepository: ArticleRepository,
    private val doctorRepository: DoctorRepository
) {

    fun listForManagement(actor: ManagementActor, keyword: String?, offset: Int, limit: Int): List<DoctorArticleView> {
        require(offset >= 0) { "offset 不能小于 0" }
        require(limit in 1..100) { "limit 必须在 1-100 之间" }
        val doctorId = requireDoctorActor(actor)
        return articleRepository.findManagementArticles(
            doctorId, keyword?.trim()?.takeIf(String::isNotEmpty), OffsetPageRequest(offset.toLong(), limit)
        ).content.map(DoctorArticleView::from)
    }

    @Transactional
    @Caching(evict = [CacheEvict(cacheNames = ["articles"], allEntries = true), CacheEvict(cacheNames = ["home"], allEntries = true)])
    fun createForManagement(actor: ManagementActor, request: DoctorArticleUpsertRequest): DoctorArticleView {
        val doctorId = requireDoctorActor(actor)
        val doctor = doctorRepository.findById(doctorId).orElseThrow { AccessDeniedException("医生档案不存在") }
        val entity = request.toEntity(UUID.randomUUID().toString(), doctorId, doctor.name)
        return DoctorArticleView.from(articleRepository.save(entity))
    }

    @Transactional
    @Caching(evict = [CacheEvict(cacheNames = ["articles"], allEntries = true), CacheEvict(cacheNames = ["home"], allEntries = true)])
    fun updateForManagement(actor: ManagementActor, id: String, request: DoctorArticleUpsertRequest): DoctorArticleView {
        requireDoctorActor(actor)
        val existing = articleRepository.findByIdForUpdate(id) ?: throw ArticleNotFoundException()
        requireOwner(actor, existing)
        val updated = existing.copy(
            title = request.title.trim(), summary = request.summary.trim(), coverImage = request.coverImage.trim(),
            publishDate = request.publishDate, content = request.content, updatedAt = LocalDateTime.now()
        )
        return DoctorArticleView.from(articleRepository.save(updated))
    }

    @Transactional
    @Caching(evict = [CacheEvict(cacheNames = ["articles"], allEntries = true), CacheEvict(cacheNames = ["home"], allEntries = true)])
    fun deleteForManagement(actor: ManagementActor, id: String) {
        requireDoctorActor(actor)
        val existing = articleRepository.findByIdForUpdate(id) ?: throw ArticleNotFoundException()
        requireOwner(actor, existing)
        articleRepository.delete(existing)
    }

    private fun requireDoctorActor(actor: ManagementActor): String {
        if (actor.isAdmin || "DOCTOR" !in actor.activeRoles) throw AccessDeniedException("仅限在职医生")
        return actor.doctorId ?: throw AccessDeniedException("仅限在职医生")
    }

    private fun requireOwner(actor: ManagementActor, article: ArticleEntity) {
        if (article.doctorId != actor.doctorId) throw AccessDeniedException("无权管理该文章")
    }

    private fun DoctorArticleUpsertRequest.toEntity(id: String, doctorId: String, author: String) = ArticleEntity(
        id = id, title = title.trim(), authorName = author, summary = summary.trim(), coverImage = coverImage.trim(),
        publishDate = publishDate, content = content, doctorId = doctorId
    )

    @Cacheable(cacheNames = ["articles"], key = "'all'")
    fun findAll(): List<ArticleEntity> = articleRepository.findAll()

    @Cacheable(cacheNames = ["articles"], key = "#id")
    fun findById(id: String): ArticleEntity? = articleRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["articles"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun save(entity: ArticleEntity): ArticleEntity = articleRepository.save(entity)

    @Caching(evict = [
        CacheEvict(cacheNames = ["articles"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun deleteById(id: String) = articleRepository.deleteById(id)

    fun count(): Long = articleRepository.count()

    fun findByTitleContainingOrAuthorNameContaining(title: String, authorName: String): List<ArticleEntity> =
        articleRepository.findByTitleContainingOrAuthorNameContaining(title, authorName)

    @Cacheable(cacheNames = ["articles"], key = "'search:' + #keyword")
    fun searchArticles(keyword: String): List<ArticleEntity> = articleRepository.searchArticles(keyword)
}

class ArticleNotFoundException : RuntimeException("文章不存在")
