package com.joysong.server.agent.streaming

import com.joysong.server.agent.provider.AgentProviderRequestFactory
import com.joysong.server.agent.provider.AgentRequestPurpose
import com.joysong.server.agent.provider.QwenChatStreamParser
import com.joysong.server.chat.dto.ChatMessageResponse
import com.joysong.server.chat.dto.ChatTurnResponse
import com.joysong.server.chat.dto.SendMessageRequest
import com.joysong.server.chat.entity.ChatMessageEntity
import com.joysong.server.chat.service.ChatService
import com.joysong.server.chat.service.ChatTurnResult
import com.joysong.server.chat.service.PreparedChatTurn
import com.joysong.server.config.AiAgentProperties
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.core.task.TaskExecutor
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.RestTemplate
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.FutureTask
import java.util.concurrent.atomic.AtomicReference

class AgentStreamSubscription internal constructor(
    private val cancelAction: (String) -> Unit
) {
    private val cancelled = AtomicBoolean(false)
    val isCancelled: Boolean get() = cancelled.get()

    fun cancel(code: String) {
        if (cancelled.compareAndSet(false, true)) cancelAction(code)
    }
}

@Service
class AgentStreamingService(
    private val chatService: ChatService,
    @Qualifier("agentLlmRestTemplate") private val restTemplate: RestTemplate,
    private val properties: AiAgentProperties,
    private val taskExecutor: TaskExecutor
) {
    fun stream(
        sessionId: String,
        userId: String,
        request: SendMessageRequest,
        sink: AgentStreamSink
    ): AgentStreamSubscription {
        val prepared = chatService.prepareStreamingMessage(sessionId, userId, request)
        val terminal = AtomicBoolean(false)
        val task = AtomicReference<FutureTask<Unit>>()
        val subscription = AgentStreamSubscription { code ->
            if (terminal.compareAndSet(false, true)) {
                task.get()?.cancel(true)
                if (prepared is PreparedChatTurn.Started) chatService.cancelStreamingMessage(prepared, code)
            }
        }
        if (prepared is PreparedChatTurn.Replayed) {
            taskExecutor.execute {
                if (sink.isOpen) sink.completed(AgentStreamEvent.Completed(prepared.turn.toResponse()))
            }
            return subscription
        }
        prepared as PreparedChatTurn.Started
        val providerTask = FutureTask<Unit> { consume(prepared, sink, terminal) }
        task.set(providerTask)
        try {
            taskExecutor.execute(providerTask)
        } catch (error: Exception) {
            if (terminal.compareAndSet(false, true)) chatService.failStreamingMessage(prepared, error)
            throw error
        }
        return subscription
    }

    private fun consume(prepared: PreparedChatTurn.Started, sink: AgentStreamSink, terminal: AtomicBoolean) {
        try {
            if (terminal.get()) return
            if (!sink.isOpen) {
                cancel(prepared, terminal)
                return
            }
            sink.started(
                AgentStreamEvent.Started(
                    prepared.traceId,
                    prepared.turnId,
                    prepared.userMessage.toResponse()
                )
            )
            val content = executeProvider(prepared) { chunk ->
                if (!sink.isOpen) throw ClientDisconnectedException()
                if (!prepared.planning) sink.delta(AgentStreamEvent.Delta(chunk))
            }
            if (!sink.isOpen) {
                cancel(prepared, terminal)
                return
            }
            if (!terminal.compareAndSet(false, true)) return
            val completed = chatService.completeStreamingMessage(prepared, content)
            if (sink.isOpen) {
                sink.completed(AgentStreamEvent.Completed(completed.toResponse()))
            }
        } catch (_: ClientDisconnectedException) {
            cancel(prepared, terminal)
        } catch (error: Exception) {
            fail(prepared, sink, terminal, error)
        }
    }

    private fun executeProvider(prepared: PreparedChatTurn.Started, onDelta: (String) -> Unit): String {
        val provider = properties.provider ?: error("AI provider is required")
        val headers = HttpHeaders().apply {
            setBearerAuth(properties.apiKey)
            contentType = MediaType.APPLICATION_JSON
            accept = listOf(MediaType.TEXT_EVENT_STREAM)
        }
        val body = AgentProviderRequestFactory.build(
            provider = provider,
            model = properties.model,
            messages = prepared.messages,
            maxOutputTokens = prepared.maxOutputTokens,
            purpose = AgentRequestPurpose.CHAT,
            streaming = true
        )
        return requireNotNull(
            restTemplate.execute(
                "${properties.baseUrl.trim().trimEnd('/')}/chat/completions",
                HttpMethod.POST,
                { request ->
                    request.headers.putAll(headers)
                    val payload = restTemplate.messageConverters.firstNotNullOf { converter ->
                        @Suppress("UNCHECKED_CAST")
                        (converter as? org.springframework.http.converter.HttpMessageConverter<Any>)
                            ?.takeIf { it.canWrite(body.javaClass, MediaType.APPLICATION_JSON) }
                    }
                    payload.write(body, MediaType.APPLICATION_JSON, request)
                },
                { response -> QwenChatStreamParser.parse(response.body, onDelta) }
            )
        )
    }

    private fun cancel(prepared: PreparedChatTurn.Started, terminal: AtomicBoolean) {
        if (terminal.compareAndSet(false, true)) {
            chatService.cancelStreamingMessage(prepared, "CLIENT_DISCONNECTED")
        }
    }

    private fun fail(
        prepared: PreparedChatTurn.Started,
        sink: AgentStreamSink,
        terminal: AtomicBoolean,
        error: Exception
    ) {
        if (!terminal.compareAndSet(false, true)) return
        val code = chatService.failStreamingMessage(prepared, error)
        if (sink.isOpen) {
            runCatching {
                sink.failed(
                    AgentStreamEvent.Failed(
                        code = code,
                        traceId = prepared.traceId,
                        retryable = code in setOf("AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE")
                    )
                )
            }
        }
    }

    private fun ChatMessageEntity.toResponse() = ChatMessageResponse(
        id = id,
        sessionId = sessionId,
        role = role,
        content = content,
        createdAt = createdAt.toString()
    )

    private fun ChatTurnResult.toResponse() = ChatTurnResponse(
        message = message.toResponse(),
        catalogReport = catalogReport,
        catalogItems = catalogItems,
        intent = intent,
        queryTarget = queryTarget,
        nextAction = nextAction,
        traceId = traceId
    )

    private class ClientDisconnectedException : RuntimeException()
}
