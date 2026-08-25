package com.joysong.server.legal.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class LegalDocumentHtmlSanitizerTest {
    private val sanitizer = LegalDocumentHtmlSanitizer()

    @Test
    fun `sanitize removes executable markup event handlers and unsafe links`() {
        val sanitized = sanitizer.sanitize(
            "<p onclick=\"alert(1)\">Safe<script>alert(2)</script></p><a href=\"javascript:alert(3)\">link</a>"
        )

        assertEquals("<p>Safe</p><a>link</a>", sanitized.html)
        assertEquals("Safe link", sanitized.visibleText)
        assertFalse(sanitized.html.contains("script", ignoreCase = true))
        assertFalse(sanitized.html.contains("onclick", ignoreCase = true))
        assertFalse(sanitized.html.contains("javascript:", ignoreCase = true))
    }

    @Test
    fun `sanitize retains only explicitly allowed link protocols`() {
        val sanitized = sanitizer.sanitize(
            "<a href=\"https://example.com\">web</a><a href=\"mailto:legal@example.com\">mail</a><a href=\"tel:+86123\">phone</a><a href=\"data:text/html,bad\">bad</a>"
        )

        assertEquals(
            "<a href=\"https://example.com\">web</a><a href=\"mailto:legal@example.com\">mail</a><a href=\"tel:+86123\">phone</a><a>bad</a>",
            sanitized.html
        )
    }

    @Test
    fun `sha256 changes when only the title changes`() {
        val html = sanitizer.sanitize("<p>Same body</p>").html

        assertNotEquals(sanitizer.sha256("Title A", html), sanitizer.sha256("Title B", html))
    }
}
