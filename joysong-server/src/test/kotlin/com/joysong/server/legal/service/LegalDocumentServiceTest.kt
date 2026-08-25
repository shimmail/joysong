package com.joysong.server.legal.service

import com.joysong.server.legal.dto.LegalDocumentLocaleInput
import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.dto.UpdateLegalDocumentDraftRequest
import com.joysong.server.legal.entity.LegalDocumentContentEntity
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentReleaseEntity
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.repository.LegalDocumentContentRepository
import com.joysong.server.legal.repository.LegalDocumentReleaseRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifySequence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class LegalDocumentServiceTest {
    private val releaseRepository = mockk<LegalDocumentReleaseRepository>()
    private val contentRepository = mockk<LegalDocumentContentRepository>()
    private val service = LegalDocumentService(releaseRepository, contentRepository, LegalDocumentHtmlSanitizer())

    @Test
    fun `create draft rejects a second draft for the same document type`() {
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns listOf(release())

        assertThrows<IllegalStateException> {
            service.createDraft(LegalDocumentType.USER_AGREEMENT, "admin-1")
        }
        verify(exactly = 0) { releaseRepository.save(any()) }
    }

    @Test
    fun `create draft copies both locales from the current published release`() {
        val published = release(id = "published-1", status = LegalDocumentStatus.PUBLISHED, version = 3)
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns listOf(published)
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(published.id) } returns bilingualContents(published.id)
        every { releaseRepository.save(any()) } answers { firstArg() }
        every { contentRepository.saveAll(any<List<LegalDocumentContentEntity>>()) } answers { firstArg() }

        val draft = service.createDraft(LegalDocumentType.USER_AGREEMENT, "admin-1")

        assertEquals(4, draft.version)
        assertEquals(LegalDocumentStatus.DRAFT, draft.status)
        assertEquals(listOf("English", "中文"), draft.contents.map { it.title })
        assertEquals(listOf("<p>English body</p>", "<p>中文正文</p>"), draft.contents.map { it.contentHtml })
    }

    @Test
    fun `publish rejects an empty English document`() {
        val draft = release()
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns listOf(
            content(draft.id, LegalDocumentLocale.ZH_CN, "用户协议", "<p>正文</p>"),
            content(draft.id, LegalDocumentLocale.EN_US, "", "")
        )

        assertThrows<IllegalArgumentException> {
            service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))
        }
        verify(exactly = 0) { releaseRepository.saveAll(any<List<LegalDocumentReleaseEntity>>()) }
        verify(exactly = 0) { releaseRepository.saveAndFlush(any()) }
        verify(exactly = 0) { contentRepository.saveAll(any<List<LegalDocumentContentEntity>>()) }
    }

    @Test
    fun `update draft rejects a stale lock version`() {
        val draft = release(lockVersion = 4)
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft

        assertThrows<IllegalStateException> {
            service.updateDraft(draft.id, "admin-1", draftRequest(lockVersion = 3))
        }
        verify(exactly = 0) { contentRepository.saveAll(any<List<LegalDocumentContentEntity>>()) }
    }

    @Test
    fun `update draft rejects a published release to keep publication immutable`() {
        val published = release(status = LegalDocumentStatus.PUBLISHED)
        every { releaseRepository.findByIdForUpdate(published.id) } returns published

        assertThrows<IllegalStateException> {
            service.updateDraft(published.id, "admin-1", draftRequest(published.lockVersion))
        }
        verify(exactly = 0) { releaseRepository.save(any()) }
    }

    @Test
    fun `update draft flushes the release before returning the incremented lock version`() {
        val draft = release(lockVersion = 7)
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns bilingualContents(draft.id)
        every { contentRepository.saveAll(any<List<LegalDocumentContentEntity>>()) } answers { firstArg() }
        every { releaseRepository.saveAndFlush(draft) } answers {
            draft.lockVersion = 8
            draft
        }

        val updated = service.updateDraft(draft.id, "admin-1", draftRequest(draft.lockVersion))

        assertEquals(8, updated.lockVersion)
        verifySequence {
            releaseRepository.findByIdForUpdate(draft.id)
            contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id)
            contentRepository.saveAll(any<List<LegalDocumentContentEntity>>())
            releaseRepository.saveAndFlush(draft)
        }
    }

    @Test
    fun `publish flushes superseding release before publishing draft and returns its database version`() {
        val previous = release(id = "published-1", status = LegalDocumentStatus.PUBLISHED, version = 1)
        val draft = release(id = "draft-2", version = 2, lockVersion = 7)
        every { releaseRepository.findByIdForUpdate(draft.id) } returns draft
        every { contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id) } returns bilingualContents(draft.id)
        every { releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT) } returns listOf(draft, previous)
        every { releaseRepository.saveAndFlush(previous) } answers { previous }
        every { releaseRepository.saveAndFlush(draft) } answers {
            draft.lockVersion = 8
            draft
        }

        val published = service.publish(draft.id, "admin-1", PublishLegalDocumentRequest(draft.lockVersion))

        assertEquals(LegalDocumentStatus.PUBLISHED, published.status)
        assertEquals("admin-1", published.publishedBy)
        assertEquals(LegalDocumentStatus.SUPERSEDED, previous.status)
        assertEquals(LegalDocumentStatus.PUBLISHED, draft.status)
        assertEquals(8, published.lockVersion)
        verifySequence {
            releaseRepository.findByIdForUpdate(draft.id)
            contentRepository.findAllByReleaseIdOrderByLocaleAsc(draft.id)
            releaseRepository.findAllByDocumentTypeForUpdate(LegalDocumentType.USER_AGREEMENT)
            releaseRepository.saveAndFlush(previous)
            releaseRepository.saveAndFlush(draft)
        }
    }

    private fun draftRequest(lockVersion: Long) = UpdateLegalDocumentDraftRequest(
        lockVersion = lockVersion,
        changeSummary = "Clarified wording",
        contents = mapOf(
            "zh-CN" to LegalDocumentLocaleInput("用户协议", "<p>正文</p>"),
            "en-US" to LegalDocumentLocaleInput("User agreement", "<p>Body</p>")
        )
    )

    private fun release(
        id: String = "draft-1",
        status: LegalDocumentStatus = LegalDocumentStatus.DRAFT,
        version: Int = 1,
        lockVersion: Long = 0
    ) = LegalDocumentReleaseEntity(
        id = id,
        documentType = LegalDocumentType.USER_AGREEMENT,
        version = version,
        status = status,
        createdBy = "admin-1",
        updatedBy = "admin-1",
        lockVersion = lockVersion
    )

    private fun bilingualContents(releaseId: String) = listOf(
        content(releaseId, LegalDocumentLocale.EN_US, "English", "<p>English body</p>"),
        content(releaseId, LegalDocumentLocale.ZH_CN, "中文", "<p>中文正文</p>")
    )

    private fun content(
        releaseId: String,
        locale: LegalDocumentLocale,
        title: String,
        html: String
    ) = LegalDocumentContentEntity(
        id = "$releaseId-${locale.tag}",
        releaseId = releaseId,
        locale = locale,
        title = title,
        contentHtml = html,
        contentSha256 = "a".repeat(64)
    )
}
