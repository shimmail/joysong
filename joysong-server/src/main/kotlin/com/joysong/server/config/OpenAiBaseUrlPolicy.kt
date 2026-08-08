package com.joysong.server.config

import java.net.URI

object OpenAiBaseUrlPolicy {
    private val allowedHosts = setOf("api.openai.com")

    fun isAllowed(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() in allowedHosts &&
            uri.userInfo == null
    } catch (_: IllegalArgumentException) {
        false
    }
}
