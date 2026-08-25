package com.joysong.server.legal.service

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.safety.Safelist
import org.springframework.stereotype.Component
import java.security.MessageDigest

data class SanitizedLegalHtml(val html: String, val visibleText: String)

@Component
class LegalDocumentHtmlSanitizer {
    fun sanitize(rawHtml: String): SanitizedLegalHtml {
        val cleaned = Jsoup.clean(rawHtml, "", safelist, Document.OutputSettings().prettyPrint(false))
        val visibleText = Jsoup.parseBodyFragment(cleaned).text().trim()
        return SanitizedLegalHtml(cleaned, visibleText)
    }

    fun sha256(title: String, sanitizedHtml: String): String = MessageDigest
        .getInstance("SHA-256")
        .digest("${title.trim()}\u0000$sanitizedHtml".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private companion object {
        val safelist = Safelist.none()
            .addTags("p", "h1", "h2", "h3", "h4", "ul", "ol", "li", "strong", "em", "u", "blockquote", "br", "a")
            .addAttributes("a", "href")
            .addProtocols("a", "href", "https", "mailto", "tel")
    }
}
