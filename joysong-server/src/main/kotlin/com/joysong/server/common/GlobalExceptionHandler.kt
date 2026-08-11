package com.joysong.server.common

import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.security.access.AccessDeniedException
import com.joysong.server.auth.service.InvalidRefreshTokenException
import com.joysong.server.doctor.service.DoctorProfileNotFoundException
import java.io.IOException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse

@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(e: InvalidRefreshTokenException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(BaseResponse.error(e.message ?: "刷新令牌无效或已过期", 401))

    @ExceptionHandler(AccessDeniedException::class)
    fun handleAccessDenied(e: AccessDeniedException): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(BaseResponse.error(e.message ?: "无权执行此操作", 403))

    @ExceptionHandler(DoctorProfileNotFoundException::class)
    fun handleDoctorProfileNotFound(
        e: DoctorProfileNotFoundException
    ): ResponseEntity<BaseResponse<Nothing>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(BaseResponse.error(e.message ?: "医生档案不存在", 404))

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(
        e: IllegalArgumentException,
        request: HttpServletRequest
    ): ResponseEntity<BaseResponse<Nothing>> {
        log.warn("参数错误: {}", e.message)
        if (request.requestURI.usesRealHttpErrorStatus()) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(BaseResponse.error(e.message ?: "请求参数错误", 400))
        }
        return ResponseEntity.ok(BaseResponse.error<Nothing>(e.message ?: "请求参数错误"))
    }

    @ExceptionHandler(RuntimeException::class)
    fun handleRuntime(
        e: RuntimeException,
        request: HttpServletRequest
    ): ResponseEntity<BaseResponse<Nothing>> {
        log.error("服务器错误", e)
        if (request.requestURI.usesRealHttpErrorStatus()) {
            return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(BaseResponse.error("服务器内部错误", 500))
        }
        return ResponseEntity.ok(BaseResponse.error<Nothing>(e.message ?: "服务器内部错误", 500))
    }

    /**
     * 处理客户端中断连接异常（如用户中途关闭页面、网络断开）
     * 客户端已断开，不再写响应，仅记录 warn 日志
     */
    @ExceptionHandler(ClientAbortException::class)
    fun handleClientAbort(e: ClientAbortException) {
        log.warn("客户端连接中断: {}", e.message)
    }

    /**
     * 处理 IO 异常（Broken pipe / Connection reset 等）
     * 与 ClientAbortException 类似，客户端已断开，不再写响应
     */
    @ExceptionHandler(IOException::class)
    fun handleIOException(e: IOException) {
        val msg = e.message ?: ""
        if (msg.contains("Broken pipe", ignoreCase = true) ||
            msg.contains("Connection reset", ignoreCase = true) ||
            msg.contains("已建立的连接", ignoreCase = true)
        ) {
            log.warn("客户端IO中断: {}", e.message)
        } else {
            log.error("IO异常: {}", e.message, e)
        }
    }

    @ExceptionHandler(Exception::class)
    fun handleGeneral(
        e: Exception,
        request: HttpServletRequest,
        response: HttpServletResponse
    ): ResponseEntity<BaseResponse<Nothing>>? {
        // 判断是否为图片资源请求
        val requestUri = request.requestURI
        val contentType = response.contentType ?: ""
        val acceptHeader = request.getHeader("Accept") ?: ""
        val isImageRequest = requestUri.startsWith("/images/") ||
            contentType.startsWith("image/") ||
            acceptHeader.contains("image/")

        // 如果 response 已 committed（如正在写图片流），不再尝试写JSON响应
        if (response.isCommitted) {
            log.warn("响应已committed，跳过异常处理: {}", e.message)
            return null
        }

        if (isImageRequest) {
            // 图片请求异常：仅记录 warn 日志，不返回 JSON 响应（避免 Content-Type 冲突）
            log.warn("图片请求异常 (uri={}): {}", requestUri, e.message)
            return null
        }

        log.error("未知错误", e)
        val body = BaseResponse.error<Nothing>("服务器异常，请稍后重试", 500)
        return if (requestUri.usesRealHttpErrorStatus()) {
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body)
        } else {
            ResponseEntity.ok(body)
        }
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(e: MethodArgumentNotValidException): ResponseEntity<BaseResponse<Nothing>> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage ?: "请求参数不正确"
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error(message, 400))
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(): ResponseEntity<BaseResponse<Nothing>> {
        return ResponseEntity
            .status(HttpStatus.BAD_REQUEST)
            .body(BaseResponse.error("请求内容格式不正确", 400))
    }

    private fun String.usesRealHttpErrorStatus(): Boolean =
        startsWith("/api/admin/") || this == "/api/management/doctor-profile"
}
