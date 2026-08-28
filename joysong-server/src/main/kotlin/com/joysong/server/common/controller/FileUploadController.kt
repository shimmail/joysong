package com.joysong.server.common.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.common.service.FileUploadService
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import org.springframework.security.core.Authentication

@RestController
@RequestMapping("/api/upload")
class FileUploadController(
    private val fileUploadService: FileUploadService
) {
    @PostMapping
    fun upload(
        authentication: Authentication,
        @RequestParam("file") file: MultipartFile,
        @RequestParam("folder", defaultValue = "general") folder: String,
        @RequestParam("customFileName", required = false) customFileName: String?
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val isAdmin = authentication.authorities.any { it.authority == "ROLE_ADMIN" }
        require(customFileName == null || isAdmin || customFileName == userId) {
            "自定义文件名只能使用当前用户 ID"
        }
        val url = fileUploadService.upload(userId, file, folder, customFileName)
        return BaseResponse.success(mapOf("url" to url))
    }
}
