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
                !isAllowedPath(provider, uri.rawPath.orEmpty(), uri.path.orEmpty())
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

    private fun isAllowedPath(provider: AiAgentProvider, rawPath: String, decodedPath: String): Boolean = when (provider) {
        AiAgentProvider.QWEN -> isAllowedQwenPath(rawPath, decodedPath)
        AiAgentProvider.OPENAI_COMPATIBLE -> true
    }

    private fun isAllowedQwenPath(rawPath: String, decodedPath: String): Boolean {
        // Compatible-mode paths are ASCII canonical paths. Reject any escape so a downstream
        // server cannot reinterpret a doubly encoded separator or dot segment.
        if ('%' in rawPath) return false
        if (decodedPath.contains('\\')) return false
        if (decodedPath != "/compatible-mode" && !decodedPath.startsWith("/compatible-mode/")) return false
        return decodedPath.split('/').none { it == "." || it == ".." }
    }
}

@Deprecated("Use AiAgentProviderUrlPolicy with an explicit provider")
object OpenAiBaseUrlPolicy {
    fun isAllowed(value: String): Boolean =
        AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.OPENAI_COMPATIBLE, value) != null

    fun normalizeAllowed(value: String): String? =
        AiAgentProviderUrlPolicy.normalizeAllowed(AiAgentProvider.OPENAI_COMPATIBLE, value)
}
