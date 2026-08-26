package com.joysong.server.common

import com.fasterxml.jackson.annotation.JsonInclude

data class BaseResponse<T>(
    val code: Int = 200,
    val message: String = "success",
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val errorCode: String? = null,
    val data: T? = null
) {
    companion object {
        fun <T> success(data: T): BaseResponse<T> = BaseResponse(data = data)
        fun <T> error(
            message: String,
            code: Int = 400,
            errorCode: String? = null
        ): BaseResponse<T> = BaseResponse(code, message, errorCode, null)
    }
}
