package com.joysong.server.legal.service

import com.joysong.server.legal.dto.AdminLegalDocumentSummaryView
import com.joysong.server.legal.dto.LegalDocumentContentView
import com.joysong.server.legal.dto.LegalDocumentLocaleInput
import com.joysong.server.legal.dto.LegalDocumentReleaseSummaryView
import com.joysong.server.legal.dto.LegalDocumentReleaseView
import com.joysong.server.legal.dto.PublicLegalDocumentView
import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.dto.UpdateLegalDocumentDraftRequest
import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import org.springframework.cache.annotation.Cacheable
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

@Service
class LegalDocumentService(
    private val releaseRepository: LegalDocumentReleaseRepository,
    private val contentRepository: LegalDocumentContentRepository,
    private val sanitizer: LegalDocumentHtmlSanitizer,
    private val cacheInvalidator: LegalDocumentCacheInvalidator
) {
    fun listAdmin(): List<AdminLegalDocumentSummaryView> = LegalDocumentType.entries.map { type ->
        val releases = releaseRepository.findAllByDocumentTypeOrderByVersionDesc(type)
        AdminLegalDocumentSummaryView(
            documentType = type,
            draft = releases.firstOrNull { it.status == LegalDocumentStatus.DRAFT }?.toSummary(),
            published = releases.firstOrNull { it.status == LegalDocumentStatus.PUBLISHED }?.toSummary()
        )
    }

    fun history(type: LegalDocumentType): List<LegalDocumentReleaseSummaryView> =
        releaseRepository.findAllByDocumentTypeOrderByVersionDesc(type)
            .filter {
                it.status == LegalDocumentStatus.PUBLISHED || it.status == LegalDocumentStatus.SUPERSEDED
            }
            .map { it.toSummary() }

    @Transactional
    fun createDraft(type: LegalDocumentType, actorId: String): LegalDocumentReleaseView {
        val releases = releaseRepository.findAllByDocumentTypeForUpdate(type)
        if (releases.any { it.status == LegalDocumentStatus.DRAFT }) {
            throw LegalDocumentConflictException("该协议已有草稿")
        }
        val published = releases.firstOrNull { it.status == LegalDocumentStatus.PUBLISHED }
        val release = LegalDocumentReleaseEntity(
            id = UUID.randomUUID().toString(),
            documentType = type,
            version = (releases.maxOfOrNull { it.version } ?: 0) + 1,
            status = LegalDocumentStatus.DRAFT,
            createdBy = actorId,
            updatedBy = actorId
        )
        val copiedContents = published?.let { contentRepository.findAllByReleaseIdOrderByLocaleAsc(it.id).associateBy { content -> content.locale } }
            ?: emptyMap()
        val contents = LegalDocumentLocale.entries.map { locale ->
            copiedContents[locale]?.copy(
                id = UUID.randomUUID().toString(),
                releaseId = release.id,
                createdAt = LocalDateTime.now(),
                updatedAt = LocalDateTime.now()
            ) ?: emptyContent(release.id, locale)
        }
        try {
            releaseRepository.saveAndFlush(release)
        } catch (e: DataIntegrityViolationException) {
            throw LegalDocumentConflictException("协议草稿已被并发创建")
        } catch (e: CannotAcquireLockException) {
            throw LegalDocumentConflictException("协议草稿已被并发创建")
        }
        contentRepository.saveAll(contents)
        return release.toView(contents)
    }

    fun getRelease(id: String): LegalDocumentReleaseView {
        val release = releaseRepository.findById(id).orElseThrow { LegalDocumentNotFoundException("协议版本不存在") }
        return release.toView(contentRepository.findAllByReleaseIdOrderByLocaleAsc(id))
    }

    @Transactional
    fun updateDraft(id: String, actorId: String, request: UpdateLegalDocumentDraftRequest): LegalDocumentReleaseView {
        val release = releaseRepository.findByIdForUpdate(id) ?: throw LegalDocumentNotFoundException("协议版本不存在")
        requireDraftWithVersion(release, request.lockVersion)
        val inputs = request.contents.toLocaleInputs()
        val contentsByLocale = contentRepository.findAllByReleaseIdOrderByLocaleAsc(id).associateBy { it.locale }
        val now = LocalDateTime.now()
        val contents = LegalDocumentLocale.entries.map { locale ->
            val content = contentsByLocale[locale] ?: throw LegalDocumentConflictException("协议缺少${locale.tag}内容")
            val input = inputs.getValue(locale)
            val title = input.title.trim()
            val sanitized = sanitizer.sanitize(input.contentHtml)
            content.title = title
            content.contentHtml = sanitized.html
            content.contentSha256 = sanitizer.sha256(title, sanitized.html)
            content.updatedAt = now
            content
        }
        release.changeSummary = request.changeSummary.trim()
        release.updatedAt = now
        release.updatedBy = actorId
        contentRepository.saveAll(contents)
        releaseRepository.saveAndFlush(release)
        return release.toView(contents)
    }

    @Transactional
    fun publish(id: String, actorId: String, request: PublishLegalDocumentRequest): LegalDocumentReleaseView {
        val draft = releaseRepository.findByIdForUpdate(id) ?: throw LegalDocumentNotFoundException("协议版本不存在")
        requireDraftWithVersion(draft, request.lockVersion)
        val contents = normalizeContentsForPublication(
            contentRepository.findAllByReleaseIdOrderByLocaleAsc(id),
            LocalDateTime.now()
        )
        contentRepository.saveAllAndFlush(contents)

        val releases = releaseRepository.findAllByDocumentTypeForUpdate(draft.documentType)
        val previous = releases.firstOrNull { it.status == LegalDocumentStatus.PUBLISHED && it.id != draft.id }
        val now = LocalDateTime.now()
        previous?.apply {
            status = LegalDocumentStatus.SUPERSEDED
            updatedAt = now
            updatedBy = actorId
        }
        previous?.let(releaseRepository::saveAndFlush)
        draft.status = LegalDocumentStatus.PUBLISHED
        draft.publishedAt = now
        draft.publishedBy = actorId
        draft.updatedAt = now
        draft.updatedBy = actorId
        releaseRepository.saveAndFlush(draft)
        cacheInvalidator.evictAfterCommit(draft.documentType)
        return draft.toView(contents)
    }

    @Cacheable(cacheNames = ["legalDocuments"], key = "@legalDocumentCacheInvalidator.cacheKey(#type, #locale)")
    fun findPublished(type: LegalDocumentType, locale: LegalDocumentLocale): PublicLegalDocumentView? {
        val release = releaseRepository.findFirstByDocumentTypeAndStatus(type, LegalDocumentStatus.PUBLISHED) ?: return null
        val content = contentRepository.findAllByReleaseIdOrderByLocaleAsc(release.id)
            .firstOrNull { it.locale == locale }
            ?: return null
        return PublicLegalDocumentView.from(release, content)
    }

    private fun requireDraftWithVersion(release: LegalDocumentReleaseEntity, lockVersion: Long) {
        if (release.status != LegalDocumentStatus.DRAFT) throw LegalDocumentConflictException("已发布协议不可修改")
        if (release.lockVersion != lockVersion) throw LegalDocumentConflictException("协议版本已被更新")
    }

    private fun normalizeContentsForPublication(
        contents: List<LegalDocumentContentEntity>,
        updatedAt: LocalDateTime
    ): List<LegalDocumentContentEntity> {
        val byLocale = contents.associateBy { it.locale }
        val normalized = LegalDocumentLocale.entries.map { locale ->
            val content = byLocale[locale] ?: throw IllegalArgumentException("协议缺少${locale.tag}内容")
            val title = content.title.trim()
            val sanitized = sanitizer.sanitize(content.contentHtml)
            require(title.isNotEmpty()) { "协议${locale.tag}标题不能为空" }
            require(sanitized.visibleText.isNotEmpty()) { "协议${locale.tag}正文不能为空" }
            Triple(content, title, sanitized.html)
        }
        return normalized.map { (content, title, sanitizedHtml) ->
            content.title = title
            content.contentHtml = sanitizedHtml
            content.contentSha256 = sanitizer.sha256(title, sanitizedHtml)
            content.updatedAt = updatedAt
            content
        }
    }

    private fun Map<String, LegalDocumentLocaleInput>.toLocaleInputs(): Map<LegalDocumentLocale, LegalDocumentLocaleInput> {
        val parsed = entries.associate { (tag, input) -> LegalDocumentLocale.fromTag(tag) to input }
        require(parsed.keys == LegalDocumentLocale.entries.toSet()) { "协议必须同时包含中文和英文内容" }
        return parsed
    }

    private fun emptyContent(releaseId: String, locale: LegalDocumentLocale): LegalDocumentContentEntity =
        LegalDocumentContentEntity(
            id = UUID.randomUUID().toString(),
            releaseId = releaseId,
            locale = locale,
            contentHtml = "",
            contentSha256 = sanitizer.sha256("", "")
        )

    private fun LegalDocumentReleaseEntity.toSummary() = LegalDocumentReleaseSummaryView(
        id, documentType, version, status, changeSummary, publishedAt, updatedAt, lockVersion
    )

    private fun LegalDocumentReleaseEntity.toView(contents: List<LegalDocumentContentEntity>) = LegalDocumentReleaseView(
        id, documentType, version, status, changeSummary, publishedAt, publishedBy, createdAt, updatedAt, lockVersion,
        contents.sortedBy { it.locale.tag }.map(LegalDocumentContentView::from)
    )
}

class LegalDocumentNotFoundException(message: String) : RuntimeException(message)

class LegalDocumentConflictException(message: String) : RuntimeException(message)
