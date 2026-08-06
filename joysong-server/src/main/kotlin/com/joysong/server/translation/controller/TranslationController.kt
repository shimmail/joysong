package com.joysong.server.translation.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.translation.dto.TranslateTextRequest
import com.joysong.server.translation.dto.TranslationResponse
import com.joysong.server.translation.service.TranslationService
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/translations")
class TranslationController(
    private val translationService: TranslationService
) {
    @PostMapping
    fun translate(@Valid @RequestBody request: TranslateTextRequest): BaseResponse<TranslationResponse> =
        BaseResponse.success(translationService.translate(request))
}
