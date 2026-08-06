package com.joysong.server.diary.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.common.apiSlice
import com.joysong.server.diary.entity.dto.PublishDiaryRequest
import com.joysong.server.diary.entity.dto.UpdateDiaryRequest
import com.joysong.server.diary.service.DiaryService
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/diaries")
class DiaryController(private val diaryService: DiaryService) {

    @GetMapping("/my")
    fun getMyDiaries(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(diaryService.getMyDiaries(userId).apiSlice(offset, limit))
    }

    @PostMapping
    fun publishDiary(@RequestBody request: PublishDiaryRequest, authentication: Authentication): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = diaryService.publishDiary(userId, request)
        return handleResult(result)
    }

    @PutMapping("/{id}")
    fun updateDiary(
        @PathVariable id: String,
        @RequestBody request: UpdateDiaryRequest,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = diaryService.updateDiary(userId, id, request)
        return handleResult(result)
    }

    @DeleteMapping("/{id}")
    fun deleteDiary(
        @PathVariable id: String,
        authentication: Authentication
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = diaryService.deleteDiary(userId, id)
        return handleResult(result)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleResult(result: Any): BaseResponse<*> {
        if (result is Map<*, *> && result.containsKey("error")) {
            val error = result["error"] as String
            val code = result["code"] as Int
            return BaseResponse.error<Any>(error, code)
        }
        return BaseResponse.success(result)
    }
}
