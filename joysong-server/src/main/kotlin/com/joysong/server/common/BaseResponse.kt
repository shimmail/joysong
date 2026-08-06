package com.joysong.server.common

data class BaseResponse<T>(
    val code: Int = 200,
    val message: String = "success",
    val data: T? = null
) {
    companion object {
        fun <T> success(data: T): BaseResponse<T> = BaseResponse(data = data)
        fun <T> error(message: String, code: Int = 400): BaseResponse<T> = BaseResponse(code = code, message = message)
    }
}
