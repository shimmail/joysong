package com.joysong.server.config

import java.net.URI
import java.net.URISyntaxException

object OpenAiBaseUrlPolicy {
    private const val ALLOWED_HOST = "www.fastaitoken.com"

    fun isAllowed(value: String): Boolean = normalizeAllowed(value) != null

    fun normalizeAllowed(value: String): String? {
        return try {
            val uri = URI(value.trim())
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host?.lowercase() != ALLOWED_HOST ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443) ||
                uri.rawQuery != null ||
                uri.rawFragment != null
            ) {
                null
            } else {
                val path = uri.rawPath.orEmpty().trimEnd('/')
                "https://$ALLOWED_HOST$path"
            }
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
