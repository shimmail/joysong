package com.joysong.server.article.controller

import com.joysong.server.article.dto.DoctorArticleUpsertRequest
import com.joysong.server.article.service.ArticleService
import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.ManagementAccessService
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/management/doctor-articles")
class DoctorArticleManagementController(
    private val articleService: ArticleService,
    private val managementAccessService: ManagementAccessService
) {
    @GetMapping
    fun list(
        authentication: Authentication,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "20") limit: Int
    ) = BaseResponse.success(articleService.listForManagement(managementAccessService.actor(authentication), keyword, offset, limit))

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(authentication: Authentication, @Valid @RequestBody request: DoctorArticleUpsertRequest) =
        BaseResponse.success(articleService.createForManagement(managementAccessService.actor(authentication), request))

    @PutMapping("/{id}")
    fun update(authentication: Authentication, @PathVariable id: String, @Valid @RequestBody request: DoctorArticleUpsertRequest) =
        BaseResponse.success(articleService.updateForManagement(managementAccessService.actor(authentication), id, request))

    @DeleteMapping("/{id}")
    fun delete(authentication: Authentication, @PathVariable id: String): BaseResponse<Any?> {
        articleService.deleteForManagement(managementAccessService.actor(authentication), id)
        return BaseResponse.success<Any?>(null)
    }
}
