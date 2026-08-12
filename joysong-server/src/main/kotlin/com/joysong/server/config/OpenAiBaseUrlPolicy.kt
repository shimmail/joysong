package com.joysong.server.config

import java.net.URI
import java.net.URISyntaxException

enum class AiAgentProvider {
    QWEN,
    OPENAI_COMPATIBLE
}

object AiAgentProviderUrlPolicy {
    private const val DASHSCOPE_HOST = "dashscope.aliyuncs.com"
    private const val FAST_AI_TOKEN_HOST = "www.fastaitoken.com"

    fun normalizeAllowed(provider: AiAgentProvider, value: String): String? {
        return try {
            val uri = URI(value.trim())
            if (!uri.scheme.equals("https", ignoreCase = true) ||
                uri.host?.lowercase() != allowedHost(provider) ||
                uri.userInfo != null ||
                uri.port !in setOf(-1, 443) ||
                uri.rawQuery != null ||
                uri.rawFragment != null ||
                !isAllowedPath(provider, uri.rawPath.orEmpty())
            ) {
                null
            } else {
                val path = uri.rawPath.orEmpty().trimEnd('/')
                "https://${allowedHost(provider)}$path"
            }
        } catch (_: URISyntaxException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun allowedHost(provider: AiAgentProvider): String = when (provider) {
        AiAgentProvider.QWEN -> DASHSCOPE_HOST
        AiAgentProvider.OPENAI_COMPATIBLE -> FAST_AI_TOKEN_HOST
    }

    private fun isAllowedPath(provider: AiAgentProvider, path: String): Boolean = when (provider) {
        AiAgentProvider.QWEN ->
            (path == "/compatible-mode" || path.startsWith("/compatible-mode/")) &&
                path.split('/').none(::isDotSegment)
        AiAgentProvider.OPENAI_COMPATIBLE -> true
    }

    private fun isDotSegment(segment: String): Boolean =
        segment.equals(".", ignoreCase = true) ||
            segment.equals("..", ignoreCase = true) ||
            segment.replace(Regex("%2e", RegexOption.IGNORE_CASE), ".") in setOf(".", "..")
}

@Deprecated("Use AiAgentProviderUrlPolicy with an explicit provider")
object OpenAiBaseUrlPolicy {
    fun isAllowed(value: String): Boolean =
        AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.OPENAI_COMPATIBLE, value) != null

    fun normalizeAllowed(value: String): String? =
        AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.OPENAI_COMPATIBLE, value)
}
