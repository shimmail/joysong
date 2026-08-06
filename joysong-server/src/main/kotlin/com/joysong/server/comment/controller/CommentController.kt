package com.joysong.server.comment.controller

import com.joysong.server.comment.entity.dto.PublishCommentRequest
import com.joysong.server.comment.service.CommentService
import com.joysong.server.common.BaseResponse
import com.joysong.server.common.apiSlice
import jakarta.validation.Valid
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/comments")
class CommentController(private val commentService: CommentService) {

    /**
     * 获取日记评论列表（仅顶级评论）
     */
    @GetMapping
    fun getComments(
        authentication: Authentication?,
        @RequestParam diaryId: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        val currentUserId = authentication?.principal as? String
        return BaseResponse.success(commentService.getComments(diaryId, currentUserId).apiSlice(offset, limit))
    }

    /**
     * 获取某评论的所有回复
     */
    @GetMapping("/replies")
    fun getReplies(
        authentication: Authentication?,
        @RequestParam parentId: String,
        @RequestParam(defaultValue = "0") offset: Int,
        @RequestParam(defaultValue = "50") limit: Int
    ): BaseResponse<*> {
        val currentUserId = authentication?.principal as? String
        return BaseResponse.success(commentService.getReplies(parentId, currentUserId).apiSlice(offset, limit))
    }

    /**
     * 发表评论/回复
     */
    @PostMapping
    fun publishComment(
        authentication: Authentication,
        @Valid @RequestBody request: PublishCommentRequest
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = commentService.publishComment(userId, request)
        return handleResult(result)
    }

    /**
     * 删除评论（仅作者本人）
     */
    @DeleteMapping("/{id}")
    fun deleteComment(
        authentication: Authentication,
        @PathVariable id: String
    ): BaseResponse<*> {
        val userId = authentication.principal as String
        val result = commentService.deleteComment(userId, id)
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
