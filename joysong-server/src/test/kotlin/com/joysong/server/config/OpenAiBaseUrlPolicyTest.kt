package com.joysong.server.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiBaseUrlPolicyTest {

    @Test
    fun `production accepts the FastAIToken HTTPS endpoint`() {
        assertTrue(OpenAiBaseUrlPolicy.isAllowed("https://www.fastaitoken.com/v1"))
    }

    @Test
    fun `production rejects missing insecure and non-exact endpoints`() {
        assertFalse(OpenAiBaseUrlPolicy.isAllowed(""))
        assertFalse(OpenAiBaseUrlPolicy.isAllowed("https://api.openai.com/v1"))
        assertFalse(OpenAiBaseUrlPolicy.isAllowed("https://www.fastaitoken.com.evil.test/v1"))
        assertFalse(OpenAiBaseUrlPolicy.isAllowed("http://www.fastaitoken.com/v1"))
    }
}
