package com.joysong.server.diary.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.entity.dto.CreateDiaryShareRequest
import com.joysong.server.diary.service.DiaryShareService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
class DiaryShareController(private val service: DiaryShareService) {
    @PostMapping("/api/diaries/{id}/share")
    fun create(@PathVariable id: String, @RequestBody(required = false) request: CreateDiaryShareRequest?, authentication: Authentication): BaseResponse<*> = result(service.create(authentication.principal as String, id, request ?: CreateDiaryShareRequest()))

    @DeleteMapping("/api/diaries/{id}/share")
    fun revoke(@PathVariable id: String, authentication: Authentication): BaseResponse<*> = result(service.revoke(authentication.principal as String, id))

    @GetMapping("/api/public/diary-shares/{token}")
    fun get(@PathVariable token: String): BaseResponse<*> {
        val response = service.get(token)
        return if (response != null) {
            BaseResponse.success<Any>(response)
        } else {
            BaseResponse.error<Any>("日记分享不存在或已失效", 404)
        }
    }

    private fun result(value: Any): BaseResponse<*> =
        if (value is Map<*, *> && value["error"] != null) {
            BaseResponse.error<Any>(value["error"].toString(), value["code"] as Int)
        } else {
            BaseResponse.success<Any>(value)
        }
}
