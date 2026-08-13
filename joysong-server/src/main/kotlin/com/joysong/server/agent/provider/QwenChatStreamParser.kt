package com.joysong.server.agent.provider

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets

object QwenChatStreamParser {
    private val objectMapper = jacksonObjectMapper()

    fun parse(input: InputStream, onDelta: (String) -> Unit): String {
        val accumulated = StringBuilder()
        var complete = false
        var eventType: String? = null
        val data = StringBuilder()

        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) {
                    if (eventType == "error") throw ParseException("Provider stream returned an error")
                    if (data.isNotEmpty()) {
                        if (data.toString() == "[DONE]") {
                            complete = true
                            break
                        }
                        emitDelta(data.toString(), accumulated, onDelta)
                    }
                    eventType = null
                    data.setLength(0)
                    continue
                }
                if (line.startsWith(":")) continue
                when {
                    line.startsWith("event:") -> eventType = line.substringAfter(':').trim()
                    line.startsWith("data:") -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(line.substringAfter(':').trimStart())
                    }
                }
            }
        }
        if (!complete) throw ParseException("Provider stream ended before completion")
        return accumulated.toString()
    }

    private fun emitDelta(data: String, accumulated: StringBuilder, onDelta: (String) -> Unit) {
        val root = try {
            objectMapper.readTree(data)
        } catch (_: Exception) {
            throw ParseException("Invalid provider stream event")
        }
        val content = root.path("choices").firstOrNull()?.path("delta")?.path("content")
        if (content != null && !content.isMissingNode && !content.isNull) {
            val delta = content.asText()
            if (delta.isNotEmpty()) {
                accumulated.append(delta)
                onDelta(delta)
            }
        }
    }

    class ParseException(message: String) : RuntimeException(message)
}
