package com.joysong.server.identity.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.identity.service.IdentityApplicationService
import com.joysong.server.identity.service.PrivateIdentityFileService
import com.joysong.server.identity.service.SubmitIdentityApplicationRequest
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/identity")
class IdentityController(
    private val identityApplicationService: IdentityApplicationService,
    private val privateIdentityFileService: PrivateIdentityFileService
) {
    @GetMapping("/overview")
    fun overview(authentication: Authentication): BaseResponse<*> =
        BaseResponse.success(identityApplicationService.overview(authentication.userId()))

    @PostMapping("/applications")
    fun submit(
        authentication: Authentication,
        @RequestBody request: SubmitIdentityApplicationRequest
    ): BaseResponse<*> = BaseResponse.success(identityApplicationService.submit(authentication.userId(), request))

    @PostMapping("/files")
    fun uploadPrivateFile(
        authentication: Authentication,
        @RequestParam("purpose") purpose: String,
        @RequestParam("file") file: MultipartFile
    ): BaseResponse<*> = BaseResponse.success(
        privateIdentityFileService.upload(authentication.userId(), purpose, file)
    )

    @DeleteMapping("/files/{fileId}")
    fun deletePrivateDraft(
        authentication: Authentication,
        @PathVariable fileId: String
    ): BaseResponse<*> {
        privateIdentityFileService.deleteDraft(authentication.userId(), fileId)
        return BaseResponse.success(null)
    }

    private fun Authentication.userId(): String = principal as? String
        ?: throw IllegalArgumentException("无法识别当前用户")
}
