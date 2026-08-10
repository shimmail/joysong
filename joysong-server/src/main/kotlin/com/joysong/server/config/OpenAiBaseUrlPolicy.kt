package com.joysong.server.config

import java.net.URI

object OpenAiBaseUrlPolicy {
    private const val ALLOWED_HOST = "www.fastaitoken.com"

    fun isAllowed(value: String): Boolean = try {
        val uri = URI(value.trim())
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host?.lowercase() == ALLOWED_HOST &&
            uri.userInfo == null
    } catch (_: IllegalArgumentException) {
        false
    }
}
