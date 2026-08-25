package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.legal.dto.AdminLegalDocumentSummaryView
import com.joysong.server.legal.dto.LegalDocumentReleaseSummaryView
import com.joysong.server.legal.dto.LegalDocumentReleaseView
import com.joysong.server.legal.dto.PublishLegalDocumentRequest
import com.joysong.server.legal.dto.UpdateLegalDocumentDraftRequest
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.service.LegalDocumentService
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/legal-documents")
class AdminLegalDocumentController(
    private val legalDocumentService: LegalDocumentService
) {
    @GetMapping
    fun list(): BaseResponse<List<AdminLegalDocumentSummaryView>> = BaseResponse.success(legalDocumentService.listAdmin())

    @GetMapping("/{type}/history")
    fun history(@PathVariable type: String): BaseResponse<List<LegalDocumentReleaseSummaryView>> =
        BaseResponse.success(legalDocumentService.history(LegalDocumentType.fromSlug(type)))

    @PostMapping("/{type}/draft")
    fun createDraft(authentication: Authentication, @PathVariable type: String): BaseResponse<LegalDocumentReleaseView> =
        BaseResponse.success(legalDocumentService.createDraft(LegalDocumentType.fromSlug(type), authentication.name))

    @GetMapping("/releases/{id}")
    fun getRelease(@PathVariable id: String): BaseResponse<LegalDocumentReleaseView> =
        BaseResponse.success(legalDocumentService.getRelease(id))

    @PutMapping("/releases/{id}")
    fun updateDraft(
        authentication: Authentication,
        @PathVariable id: String,
        @Valid @RequestBody request: UpdateLegalDocumentDraftRequest
    ): BaseResponse<LegalDocumentReleaseView> =
        BaseResponse.success(legalDocumentService.updateDraft(id, authentication.name, request))

    @PostMapping("/releases/{id}/publish")
    fun publish(
        authentication: Authentication,
        @PathVariable id: String,
        @Valid @RequestBody request: PublishLegalDocumentRequest
    ): BaseResponse<LegalDocumentReleaseView> =
        BaseResponse.success(legalDocumentService.publish(id, authentication.name, request))
}
