package com.joysong.server.favorite.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.common.apiSlice
import com.joysong.server.favorite.entity.dto.AddFavoriteRequest
import com.joysong.server.favorite.service.FavoriteService
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/favorites")
class FavoriteController(private val favoriteService: FavoriteService) {

    /**
     * 获取当前用户所有收藏列表
     */
    @GetMapping
    fun getFavorites(
        authentication: Authentication,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(favoriteService.getFavorites(userId).apiSlice(offset, limit))
    }

    /**
     * 添加收藏
     */
    @PostMapping
    fun addFavorite(
        authentication: Authentication,
        @Valid @RequestBody request: AddFavoriteRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = favoriteService.addFavorite(userId, request)
        return handleResult(result)
    }

    /**
     * 取消收藏
     */
    @DeleteMapping("/{type}/{targetId}")
    fun removeFavorite(
        authentication: Authentication,
        @PathVariable type: String,
        @PathVariable targetId: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = favoriteService.removeFavorite(userId, type, targetId)
        return handleResult(result)
    }

    /**
     * 检查是否已收藏 & 获取收藏总数
     */
    @GetMapping("/{type}/{targetId}")
    fun isFavorite(
        authentication: Authentication,
        @PathVariable type: String,
        @PathVariable targetId: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        return BaseResponse.success(favoriteService.isFavorite(userId, type, targetId))
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
