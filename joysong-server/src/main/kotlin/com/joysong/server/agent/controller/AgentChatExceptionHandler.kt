package com.joysong.server.agent.controller

import com.joysong.server.agent.orchestration.AgentChatException
import com.joysong.server.chat.controller.ChatController
import com.joysong.server.common.BaseResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

data class AgentErrorData(val traceId: String? = null)

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes = [ChatController::class])
class AgentChatExceptionHandler {

    @ExceptionHandler(AgentChatException::class)
    fun handle(error: AgentChatException): ResponseEntity<BaseResponse<AgentErrorData>> {
        val publicCode = error.code.takeIf(stableCodes::contains) ?: "AGENT_INTERNAL_ERROR"
        val status = statusFor(publicCode)
        return ResponseEntity.status(status).body(
            BaseResponse(
                code = status.value(),
                message = publicCode,
                data = AgentErrorData(error.traceId)
            )
        )
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(error: IllegalArgumentException): ResponseEntity<BaseResponse<AgentErrorData>> =
        error(
            HttpStatus.BAD_REQUEST,
            error.message.takeIf { it == "INVALID_IDEMPOTENCY_KEY" } ?: "INVALID_REQUEST"
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(): ResponseEntity<BaseResponse<AgentErrorData>> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(): ResponseEntity<BaseResponse<AgentErrorData>> =
        error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(): ResponseEntity<BaseResponse<AgentErrorData>> =
        error(HttpStatus.INTERNAL_SERVER_ERROR, "AGENT_INTERNAL_ERROR")

    private fun error(status: HttpStatus, code: String) = ResponseEntity.status(status).body(
        BaseResponse(code = status.value(), message = code, data = AgentErrorData())
    )

    private fun statusFor(code: String): HttpStatus = when (code) {
        "INVALID_REQUEST", "INVALID_IDEMPOTENCY_KEY" -> HttpStatus.BAD_REQUEST
        "SESSION_NOT_FOUND", "AGENT_STREAMING_DISABLED" -> HttpStatus.NOT_FOUND
        "TURN_IN_PROGRESS", "IDEMPOTENCY_KEY_CONFLICT", "IDEMPOTENCY_EXPIRED", "IDEMPOTENCY_REPLAY_EXPIRED" ->
            HttpStatus.CONFLICT
        "AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE" -> HttpStatus.SERVICE_UNAVAILABLE
        else -> HttpStatus.INTERNAL_SERVER_ERROR
    }

    private companion object {
        val stableCodes = setOf(
            "INVALID_REQUEST",
            "INVALID_IDEMPOTENCY_KEY",
            "SESSION_NOT_FOUND",
            "AGENT_STREAMING_DISABLED",
            "TURN_IN_PROGRESS",
            "IDEMPOTENCY_KEY_CONFLICT",
            "IDEMPOTENCY_EXPIRED",
            "IDEMPOTENCY_REPLAY_EXPIRED",
            "AI_PROVIDER_TIMEOUT",
            "AI_PROVIDER_UNAVAILABLE"
        )
    }
}
