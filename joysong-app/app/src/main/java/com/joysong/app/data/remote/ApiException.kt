package com.joysong.app.data.remote

/**
 * API 调用返回非 200 状态码时抛出的异常，携带 code 以便调用方判断。
 * 例如后端返回 403 表示会话/对话已被删除。
 */
class ApiException(
    val code: Int,
    override val message: String
) : Exception(message)
