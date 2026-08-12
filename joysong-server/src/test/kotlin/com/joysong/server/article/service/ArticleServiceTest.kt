package com.joysong.server.article.service

import com.joysong.server.article.dto.DoctorArticleUpsertRequest
import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.article.repository.ArticleRepository
import com.joysong.server.doctor.entity.DoctorEntity
import com.joysong.server.doctor.repository.DoctorRepository
import com.joysong.server.identity.service.ManagementActor
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.PageImpl
import org.springframework.security.access.AccessDeniedException
import java.time.LocalDate
import java.util.Optional

class ArticleServiceTest {
    private val articles = mockk<ArticleRepository>()
    private val doctors = mockk<DoctorRepository>()
    private val service = ArticleService(articles, doctors)
    private val doctor = ManagementActor("doctor-1", false, setOf("DOCTOR"), "doctor-1", emptySet(), emptySet(), setOf("doctor-1"))
    private val request = DoctorArticleUpsertRequest("  title  ", " summary ", " cover ", LocalDate.parse("2026-08-12"), "body")

    @Test
    fun `doctor list is database scoped to self`() {
        every { articles.findManagementArticles("doctor-1", null, any()) } returns PageImpl(listOf(article()))
        val result = service.listForManagement(doctor, null, 0, 20)
        assertEquals(listOf("article-1"), result.map { it.id })
        verify(exactly = 1) { articles.findManagementArticles("doctor-1", null, any()) }
    }

    @Test
    fun `create derives doctor identity and trims fields`() {
        every { doctors.findById("doctor-1") } returns Optional.of(DoctorEntity(id = "doctor-1", name = "Dr Zhang"))
        every { articles.save(any()) } answers { firstArg() }
        val result = service.createForManagement(doctor, request)
        assertEquals("doctor-1", result.doctorId)
        assertEquals("Dr Zhang", result.authorName)
        assertEquals("title", result.title)
        assertEquals(0, result.readCount)
    }

    @Test
    fun `doctor cannot update another doctors article`() {
        every { articles.findByIdForUpdate("article-1") } returns article(doctorId = "doctor-2")
        assertThrows<AccessDeniedException> { service.updateForManagement(doctor, "article-1", request) }
    }

    private fun article(doctorId: String = "doctor-1") = ArticleEntity(
        id = "article-1", title = "title", doctorId = doctorId, authorName = "doctor"
    )
}
