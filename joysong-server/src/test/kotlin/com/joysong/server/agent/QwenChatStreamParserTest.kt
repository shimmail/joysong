package com.joysong.server.agent

import com.joysong.server.agent.provider.QwenChatStreamParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

class QwenChatStreamParserTest {

    @Test
    fun `parses UTF-8 deltas across byte and SSE boundaries until DONE`() {
        val deltas = mutableListOf<String>()
        val input = chunkedInput(
            "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                ": keep-alive\n\n" +
                "data: {\"choices\":[{\"delta\":{}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: [DONE]\n\n",
            1
        )

        val result = QwenChatStreamParser.parse(input, deltas::add)

        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", result)
    }

    @Test
    fun `rejects malformed JSON without exposing provider payload`() {
        val exception = assertThrows(QwenChatStreamParser.ParseException::class.java) {
            QwenChatStreamParser.parse(
                ByteArrayInputStream("data: {not-json-secret}\n\ndata: [DONE]\n".toByteArray()),
            ) {}
        }

        assertEquals("Invalid provider stream event", exception.message)
    }

    @Test
    fun `rejects upstream error events without exposing provider payload`() {
        val exception = assertThrows(QwenChatStreamParser.ParseException::class.java) {
            QwenChatStreamParser.parse(
                ByteArrayInputStream("event: error\ndata: {\"message\":\"secret\"}\n\n".toByteArray()),
            ) {}
        }

        assertEquals("Provider stream returned an error", exception.message)
    }

    @Test
    fun `rejects EOF before DONE`() {
        val exception = assertThrows(QwenChatStreamParser.ParseException::class.java) {
            QwenChatStreamParser.parse(
                ByteArrayInputStream("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n".toByteArray()),
            ) {}
        }

        assertEquals("Provider stream ended before completion", exception.message)
    }

    private fun chunkedInput(content: String, chunkSize: Int): InputStream {
        val delegate = ByteArrayInputStream(content.toByteArray(StandardCharsets.UTF_8))
        return object : InputStream() {
            override fun read(): Int = delegate.read()

            override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
                delegate.read(buffer, offset, minOf(length, chunkSize))
        }
    }
}
