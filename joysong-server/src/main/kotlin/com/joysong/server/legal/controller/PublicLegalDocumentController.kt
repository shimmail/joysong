package com.joysong.server.legal.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.legal.dto.PublicLegalDocumentView
import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentType
import com.joysong.server.legal.service.LegalDocumentNotFoundException
import com.joysong.server.legal.service.LegalDocumentService
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils

@RestController
class PublicLegalDocumentController(
    private val legalDocumentService: LegalDocumentService
) {
    @GetMapping("/api/public/legal-documents/{type}")
    fun getJson(
        @PathVariable type: String,
        @RequestParam locale: String,
        @org.springframework.web.bind.annotation.RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String? = null
    ): ResponseEntity<BaseResponse<PublicLegalDocumentView>> {
        val view = published(type, locale)
        return if (ifNoneMatch.matchesCurrentEtag(view.contentSha256)) {
            ResponseEntity.status(304).eTag(view.contentSha256).cacheControl(CacheControl.noCache()).build()
        } else {
            ResponseEntity.ok().eTag(view.contentSha256).cacheControl(CacheControl.noCache()).body(BaseResponse.success(view))
        }
    }

    @GetMapping("/legal/{type}", produces = [MediaType.TEXT_HTML_VALUE])
    fun getHtml(
        @PathVariable type: String,
        @RequestParam locale: String
    ): ResponseEntity<String> {
        val documentType = LegalDocumentType.fromSlug(type)
        val documentLocale = LegalDocumentLocale.fromTag(locale)
        val view = legalDocumentService.findPublished(documentType, documentLocale)
            ?: throw LegalDocumentNotFoundException("公开协议不存在")
        val alternate = LegalDocumentLocale.entries.first { it != documentLocale }
        val html = """<!doctype html>
<html lang="${escape(view.locale)}">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${escape(view.title)}</title>
<style>body{margin:0;background:#fff;color:#1f2937;font:16px/1.65 system-ui,sans-serif}.page{max-width:760px;margin:auto;padding:24px}.meta{color:#6b7280;font-size:14px}.languages{margin:20px 0}.document{overflow-wrap:anywhere}a{color:#2563eb}</style>
</head>
<body><main class="page"><h1>${escape(view.title)}</h1><p class="meta">${escape(view.type)} · ${escape(view.locale)} · v${view.version} · ${escape(view.publishedAt.toString())}</p>
<nav class="languages" aria-label="Language"><a href="/legal/${documentType.slug}?locale=${alternate.tag}">${languageLabel(alternate)}</a></nav>
<article class="document">${view.contentHtml}</article></main></body>
</html>"""
        return ResponseEntity.ok()
            .contentType(MediaType(MediaType.TEXT_HTML, Charsets.UTF_8))
            .header("Content-Security-Policy", HTML_CSP)
            .body(html)
    }

    private fun published(type: String, locale: String): PublicLegalDocumentView = legalDocumentService
        .findPublished(LegalDocumentType.fromSlug(type), LegalDocumentLocale.fromTag(locale))
        ?: throw LegalDocumentNotFoundException("公开协议不存在")

    private fun quoted(value: String) = "\"$value\""

    private fun String?.matchesCurrentEtag(contentSha256: String): Boolean {
        val expected = quoted(contentSha256)
        return this?.split(',')?.any { tag ->
            val normalized = tag.trim()
            normalized == "*" || normalized.removePrefix("W/") == expected
        } == true
    }

    private fun escape(value: String) = HtmlUtils.htmlEscape(value)

    private fun languageLabel(locale: LegalDocumentLocale) = if (locale == LegalDocumentLocale.ZH_CN) "简体中文" else "English"

    private companion object {
        const val HTML_CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}
