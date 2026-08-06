package com.joysong.app.ui.detail

/**
 * 从 HTML 格式的 detailContent 中按 <h1> 标签拆分为多个段落，
 * 每段包含 title（标题）和 content（该标题下的 HTML 内容）。
 */
internal data class HtmlSection(val title: String, val content: String)

/**
 * 按 <h1> 标签拆分 HTML 内容为多个段落。
 * 如果无 <h1> 标签，整段作为单一返回（title 为空字符串）。
 */
internal fun parseDetailSections(html: String?): List<HtmlSection> {
    if (html.isNullOrBlank()) return emptyList()
    val pattern = Regex("<h1>(.*?)</h1>", RegexOption.IGNORE_CASE)
    val matches = pattern.findAll(html).toList()
    if (matches.isEmpty()) {
        return listOf(HtmlSection("", html))
    }
    val sections = mutableListOf<HtmlSection>()
    for (i in matches.indices) {
        val title = matches[i].groupValues[1].trim()
        val start = matches[i].range.last + 1
        val end = if (i + 1 < matches.size) matches[i + 1].range.first else html.length
        val content = html.substring(start, end).trim()
        sections.add(HtmlSection(title, content))
    }
    return sections
}

/**
 * 将 HTML 内容提取纯文本摘要（去掉标签），限制字符数。
 */
internal fun htmlToPlainText(html: String, maxLen: Int = 80): String {
    val plain = html.replace(Regex("<[^>]*>"), "").replace("&nbsp;", " ").trim()
    return if (plain.length > maxLen) plain.take(maxLen) + "..." else plain
}

/**
 * 将 HTML 片段包装为完整可渲染的 HTML 页面。
 */
internal fun wrapHtmlSection(section: HtmlSection): String {
    val body = buildString {
        append("<html><head><meta charset='utf-8'></head>")
        append("<body style='font-size:14px;color:#666666;line-height:1.8;padding:4px;'>")
        if (section.title.isNotBlank()) {
            append("<h2 style='color:#1A1A1A;font-size:17px;margin-bottom:8px;'>${section.title}</h2>")
        }
        append(section.content)
        append("</body></html>")
    }
    return body
}
