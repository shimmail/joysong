package com.joysong.server.legal.dto

import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentStatus
import com.joysong.server.legal.entity.LegalDocumentType
import jakarta.validation.Valid
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import java.time.LocalDateTime

data class LegalDocumentLocaleInput(
    @field:Size(max = 200) val title: String,
    @field:Size(max = 200_000) val contentHtml: String
)

data class UpdateLegalDocumentDraftRequest(
    @field:PositiveOrZero val lockVersion: Long,
    @field:Size(max = 1000) val changeSummary: String,
    @field:Size(min = 2, max = 2) val contents: Map<String, @Valid LegalDocumentLocaleInput>
)

data class PublishLegalDocumentRequest(@field:PositiveOrZero val lockVersion: Long)

data class LegalDocumentReleaseSummaryView(
    val id: String,
    val documentType: LegalDocumentType,
    val version: Int,
    val status: LegalDocumentStatus,
    val changeSummary: String,
    val publishedAt: LocalDateTime?,
    val updatedAt: LocalDateTime,
    val lockVersion: Long
)

data class AdminLegalDocumentSummaryView(
    val documentType: LegalDocumentType,
    val draft: LegalDocumentReleaseSummaryView?,
    val published: LegalDocumentReleaseSummaryView?
)

data class LegalDocumentContentView(
    val locale: LegalDocumentLocale,
    val title: String,
    val contentHtml: String
)

data class LegalDocumentReleaseView(
    val id: String,
    val documentType: LegalDocumentType,
    val version: Int,
    val status: LegalDocumentStatus,
    val changeSummary: String,
    val publishedAt: LocalDateTime?,
    val publishedBy: String?,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime,
    val lockVersion: Long,
    val contents: List<LegalDocumentContentView>
)

data class PublicLegalDocumentView(
    val documentType: LegalDocumentType,
    val version: Int,
    val publishedAt: LocalDateTime,
    val contents: List<LegalDocumentContentView>
)
