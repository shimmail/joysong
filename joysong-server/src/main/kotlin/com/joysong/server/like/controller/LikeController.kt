package com.joysong.server.like.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.like.entity.dto.LikeRequest
import com.joysong.server.like.entity.dto.LikeResponse
import com.joysong.server.like.service.LikeService
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/likes")
class LikeController(private val likeService: LikeService) {

    /**
     * 点赞
     */
    @PostMapping
    fun like(
        authentication: Authentication,
        @Valid @RequestBody request: LikeRequest
    ): BaseResponse<String> {
        val userId = authentication.principal as String
        val result = likeService.addLike(userId, request)
        return handleResult(result)
    }

    /**
     * 取消点赞
     */
    @DeleteMapping("/{targetType}/{targetId}")
    fun unlike(
        authentication: Authentication,
        @PathVariable targetType: String,
        @PathVariable targetId: String
    ): BaseResponse<String> {
        val userId = authentication.principal as String
        val result = likeService.removeLike(userId, targetType, targetId)
        return handleResult(result)
    }

    /**
     * 检查是否已点赞 & 获取点赞数
     */
    @GetMapping("/{targetType}/{targetId}")
    fun checkLike(
        authentication: Authentication,
        @PathVariable targetType: String,
        @PathVariable targetId: String
    ): BaseResponse<LikeResponse> {
        val userId = authentication.principal as String
        return BaseResponse.success(likeService.checkLike(userId, targetType, targetId))
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleResult(result: Any): BaseResponse<String> {
        if (result is Map<*, *> && result.containsKey("error")) {
            val error = result["error"] as String
            val code = result["code"] as Int
            return BaseResponse.error<String>(error, code)
        }
        return BaseResponse.success(result as String)
    }
}
