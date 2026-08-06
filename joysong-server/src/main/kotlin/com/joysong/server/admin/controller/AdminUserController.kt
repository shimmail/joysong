package com.joysong.server.admin.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.service.DiaryService
import com.joysong.server.user.service.UserProfileService
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/admin")
class AdminUserController(
    private val userProfileService: UserProfileService,
    private val diaryService: DiaryService
) {

    @GetMapping("/users")
    fun listUsers(@RequestParam(required = false) keyword: String?): BaseResponse<*> {
        return BaseResponse.success(userProfileService.adminListUsers(keyword))
    }

    @GetMapping("/users/{id}")
    fun getUser(@PathVariable id: String): BaseResponse<*> {
        val user = userProfileService.adminFindById(id)
            ?: return BaseResponse.error<Any>("用户不存在")
        return BaseResponse.success(user)
    }

    @PutMapping("/users/{id}/role")
    fun updateUserRole(@PathVariable id: String, @RequestBody body: Map<String, String>): BaseResponse<*> {
        val newRole = body["role"] ?: return BaseResponse.error<Any>("角色不能为空")
        val result = userProfileService.adminUpdateRole(id, newRole)
            ?: return BaseResponse.error<Any>("用户不存在")
        return BaseResponse.success(result)
    }

    @PutMapping("/users/{id}/deactivate")
    fun deactivateUser(@PathVariable id: String): BaseResponse<*> {
        val (success, message) = userProfileService.adminDeactivate(id)
        return if (success) BaseResponse.success(null) else BaseResponse.error<Any>(message)
    }

    @PutMapping("/users/{id}/reactivate")
    fun reactivateUser(@PathVariable id: String): BaseResponse<*> {
        val result = userProfileService.adminReactivate(id)
            ?: return BaseResponse.error<Any>("用户不存在")
        return BaseResponse.success(result)
    }

    @GetMapping("/users/{id}/diaries")
    fun listDiariesByUser(@PathVariable id: String): BaseResponse<*> {
        val user = userProfileService.adminFindById(id)
            ?: return BaseResponse.error<Any>("用户不存在")
        return BaseResponse.success(diaryService.findByAuthorName(user.nickname))
    }
}
