package com.joysong.server.admin.controller

import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.article.service.ArticleService
import com.joysong.server.common.BaseResponse
import com.joysong.server.doctor.service.DoctorService
import com.joysong.server.identity.service.ManagementAccessService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
class AdminArticleController(
    private val articleService: ArticleService,
    private val doctorService: DoctorService,
    private val managementAccessService: ManagementAccessService
) {

    @GetMapping("/articles")
    fun listArticles(authentication: Authentication, @RequestParam(required = false) keyword: String?): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val list = if (actor.isAdmin && !keyword.isNullOrBlank()) {
            articleService.searchArticles(keyword.trim())
        } else {
            articleService.findAll()
        }
        val visible = if (actor.isAdmin) list else list.filter {
            managementAccessService.canManageArticleDoctor(actor, it.doctorId)
        }
        val searched = keyword?.trim()?.takeIf(String::isNotEmpty)?.let { value ->
            visible.filter { it.id == value || it.title.contains(value, ignoreCase = true) }
        } ?: visible
        return BaseResponse.success(searched)
    }

    @PostMapping("/articles")
    fun createArticle(authentication: Authentication, @RequestBody entity: ArticleEntity): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        if (actor.isAdmin) {
            return BaseResponse.success(articleService.save(entity.copy(id = UUID.randomUUID().toString())))
        }
        val doctorId = entity.doctorId.trim().also { require(it.isNotEmpty()) { "请选择文章关联医生" } }
        managementAccessService.requireArticleDoctor(actor, doctorId)
        val doctor = doctorService.findById(doctorId) ?: throw IllegalArgumentException("医生不存在")
        return BaseResponse.success(articleService.save(entity.copy(
            id = UUID.randomUUID().toString(),
            doctorId = doctorId,
            authorName = doctor.name,
            readCount = 0
        )))
    }

    @PutMapping("/articles/{id}")
    fun updateArticle(authentication: Authentication, @PathVariable id: String, @RequestBody entity: ArticleEntity): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val existing = articleService.findById(id)
            ?: return BaseResponse.error<Any>("文章不存在")
        if (!actor.isAdmin) managementAccessService.requireArticleDoctor(actor, existing.doctorId)
        return BaseResponse.success(articleService.save(entity.copy(
            id = id,
            doctorId = if (actor.isAdmin) entity.doctorId else existing.doctorId,
            authorName = if (actor.isAdmin) entity.authorName else existing.authorName,
            readCount = if (actor.isAdmin) entity.readCount else existing.readCount,
            createdAt = existing.createdAt,
            deletedAt = existing.deletedAt
        )))
    }

    @DeleteMapping("/articles/{id}")
    fun deleteArticle(authentication: Authentication, @PathVariable id: String): BaseResponse<*> {
        val actor = managementAccessService.actor(authentication)
        val existing = articleService.findById(id)
            ?: return BaseResponse.error<Any>("文章不存在", 404)
        if (!actor.isAdmin) managementAccessService.requireArticleDoctor(actor, existing.doctorId)
        articleService.deleteById(id)
        return BaseResponse.success(null)
    }
}
