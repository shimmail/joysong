package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.entity.DiaryEntity
import com.joysong.server.diary.service.DiaryService
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/admin")
class AdminDiaryController(
    private val diaryService: DiaryService
) {

    @GetMapping("/diaries")
    fun listDiaries(@RequestParam(required = false) keyword: String?): BaseResponse<*> {
        return BaseResponse.success(diaryService.adminListDiaries(keyword))
    }

    @PostMapping("/diaries")
    fun createDiary(@RequestBody entity: DiaryEntity): BaseResponse<*> =
        BaseResponse.success(diaryService.adminSave(entity.copy(id = UUID.randomUUID().toString())))

    @PutMapping("/diaries/{id}")
    fun updateDiary(@PathVariable id: String, @RequestBody entity: DiaryEntity): BaseResponse<*> {
        val existing = diaryService.adminFindById(id)
            ?: return BaseResponse.error<Any>("日记不存在")
        return BaseResponse.success(diaryService.adminSave(entity.copy(id = id, deletedAt = existing.deletedAt)))
    }

    @DeleteMapping("/diaries/{id}")
    fun deleteDiary(@PathVariable id: String): BaseResponse<*> {
        diaryService.adminDeleteById(id)
        return BaseResponse.success(null)
    }
}
