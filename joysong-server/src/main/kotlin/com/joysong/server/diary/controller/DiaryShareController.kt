package com.joysong.server.diary.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.entity.dto.CreateDiaryShareRequest
import com.joysong.server.diary.entity.dto.PublicDiaryShareResponse
import com.joysong.server.diary.service.DiaryShareService
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.*

@RestController
class DiaryShareController(private val service: DiaryShareService) {
    @PostMapping("/api/diaries/{id}/share")
    fun create(@PathVariable id: String, @RequestBody(required = false) request: CreateDiaryShareRequest?, authentication: Authentication): BaseResponse<*> = result(service.create(authentication.principal as String, id, request ?: CreateDiaryShareRequest()))

    @DeleteMapping("/api/diaries/{id}/share")
    fun revoke(@PathVariable id: String, authentication: Authentication): BaseResponse<*> = result(service.revoke(authentication.principal as String, id))

    @GetMapping("/api/public/diary-shares/{token}")
    fun get(@PathVariable token: String): BaseResponse<*> {
        val response = service.get(token)
        return if (response != null) {
            BaseResponse.success<Any>(response)
        } else {
            BaseResponse.error<Any>("日记分享不存在或已失效", 404)
        }
    }

    @GetMapping("/s/diary/{token}", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(@PathVariable token: String): ResponseEntity<String> {
        val response = service.get(token)
        return if (response != null) {
            ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .body(renderPage(token, response))
        } else {
            ResponseEntity.status(404)
                .contentType(MediaType.TEXT_HTML)
                .body(renderErrorPage("日记分享不存在或已失效"))
        }
    }

    private fun result(value: Any): BaseResponse<*> =
        if (value is Map<*, *> && value["error"] != null) {
            BaseResponse.error<Any>(value["error"].toString(), value["code"] as Int)
        } else {
            BaseResponse.success<Any>(value)
        }

    private fun renderPage(token: String, diary: PublicDiaryShareResponse): String {
        val title = escapeHtml(diary.title)
        val author = escapeHtml(diary.author.name.ifBlank { "用户" })
        val content = escapeHtml(diary.content).replace("\n", "<br>")
        val tags = diary.tags.joinToString("") { """<span class="tag">#${escapeHtml(it)}</span>""" }
        val associations = listOfNotNull(
            diary.project?.name?.takeIf(String::isNotBlank)?.let { "项目：${escapeHtml(it)}" },
            diary.doctor?.name?.takeIf(String::isNotBlank)?.let { "医生：${escapeHtml(it)}" },
            diary.institution?.name?.takeIf(String::isNotBlank)?.let { "机构：${escapeHtml(it)}" }
        ).joinToString("") { """<span class="pill">$it</span>""" }
        val allImages = (diary.images.map { it to "" } +
            diary.beforeImages.map { it to "术前" } +
            diary.afterImages.map { it to "术后" })
        val images = renderImages(allImages)
        val appLink = buildAppLink(token)
        return """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>$title - 娇颜颂</title>
  <style>
    :root { color-scheme: light; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    body { margin: 0; background: #f7f5f2; color: #242424; }
    .page { max-width: 720px; margin: 0 auto; padding: 12px 12px 40px; }
    .card { background: #fff; border: 1px solid #eee9e4; border-radius: 20px; padding: 20px 18px 22px; box-shadow: 0 6px 22px rgba(73,52,35,.07); }
    .eyebrow { color: #a27b61; font-size: 12px; letter-spacing: .08em; margin-bottom: 8px; }
    h1 { margin: 0 0 8px; font-size: 25px; line-height: 1.3; letter-spacing: -.01em; }
    .meta { color: #8a817a; font-size: 13px; line-height: 1.6; }
    .stats { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 16px; }
    .pill, .tag { display: inline-flex; padding: 6px 10px; border-radius: 999px; background: #f8f3ee; color: #6d5848; font-size: 12px; margin: 5px 5px 0 0; }
    .floating-actions { position: fixed; left: 50%; bottom: 18px; transform: translateX(-50%); display: flex; align-items: center; gap: 10px; z-index: 20; padding: 6px; border-radius: 999px; background: rgba(255,255,255,.94); box-shadow: 0 8px 24px rgba(0,0,0,.16); }
    .floating-actions.hidden { display: none; }
    .action { display: inline-flex; align-items: center; justify-content: center; height: 46px; padding: 0 18px; border-radius: 999px; text-decoration: none; font-weight: 600; font-size: 15px; }
    .action-primary { background: #2563eb; color: #fff; min-width: 180px; }
    .action-close { width: 48px; padding: 0; border: 0; background: #fff; color: #666; cursor: pointer; }
    .safe-bottom { height: 84px; }
    .media { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 20px 0 8px; }
    .photo { position: relative; overflow: hidden; border-radius: 14px; background: #eee; aspect-ratio: 1 / 1; }
    .photo img { width: 100%; height: 100%; object-fit: cover; display: block; }
    .badge { position: absolute; left: 8px; top: 8px; padding: 4px 8px; border-radius: 999px; background: rgba(0,0,0,.58); color: #fff; font-size: 12px; }
    .content { margin-top: 18px; padding-top: 16px; border-top: 1px solid #f1ece7; font-size: 16px; line-height: 1.85; white-space: normal; }
    .more { color: #9a8b80; font-size: 12px; margin-top: 8px; }
    .brand { margin-top: 18px; color: #888; font-size: 13px; text-align: center; }
    .viewer { position: fixed; inset: 0; display: none; align-items: center; justify-content: center; background: rgba(0,0,0,.92); z-index: 50; touch-action: pan-y; }
    .viewer.open { display: flex; }
    .viewer img { max-width: 94vw; max-height: 86vh; object-fit: contain; user-select: none; }
    .viewer-close, .viewer-prev, .viewer-next { position: absolute; border: 0; color: #fff; background: rgba(255,255,255,.16); cursor: pointer; border-radius: 999px; }
    .viewer-close { top: 22px; right: 22px; width: 42px; height: 42px; font-size: 26px; }
    .viewer-prev, .viewer-next { top: 50%; width: 44px; height: 44px; margin-top: -22px; font-size: 28px; }
    .viewer-prev { left: 14px; } .viewer-next { right: 14px; }
    .viewer-count { position: absolute; bottom: 26px; color: #fff; font-size: 13px; }
  </style>
</head>
<body>
  <main class="page">
    <section class="card">
      <div class="eyebrow">JOYSONG · 日记分享</div>
      <h1>$title</h1>
      <div class="meta">作者：$author · ${diary.publishDate}</div>
      <div class="stats">
        <span class="pill">评分 ${diary.rating}</span>
        <span class="pill">点赞 ${diary.likeCount}</span>
        <span class="pill">评论 ${diary.commentCount}</span>
      </div>
      <div>$associations</div>
      <div>$tags</div>
      $images
      <div class="content">$content</div>
    </section>
    <div class="safe-bottom"></div>
    <div class="floating-actions" aria-label="分享操作">
      <a class="action action-primary" href="$appLink">在 App 中打开</a>
      <button class="action action-close" type="button" aria-label="关闭" onclick="document.querySelector('.floating-actions').classList.add('hidden');">×</button>
    </div>
    <div class="brand">来自 Joysong 娇颜颂</div>
  </main>
  <div class="viewer" id="viewer" role="dialog" aria-modal="true" aria-label="图片预览">
    <button class="viewer-close" type="button" aria-label="关闭">×</button>
    <button class="viewer-prev" type="button" aria-label="上一张">‹</button>
    <img id="viewer-image" alt="图片预览">
    <button class="viewer-next" type="button" aria-label="下一张">›</button>
    <div class="viewer-count" id="viewer-count"></div>
  </div>
  <script>
    (function () {
      const images = ${renderImageUrls(allImages)};
      if (!images.length) return;
      const viewer = document.getElementById('viewer');
      const image = document.getElementById('viewer-image');
      const count = document.getElementById('viewer-count');
      let index = 0, startX = 0;
      function show(next) {
        index = (next + images.length) % images.length;
        image.src = images[index];
        count.textContent = (index + 1) + ' / ' + images.length;
      }
      document.querySelectorAll('.photo').forEach(function (el) {
        el.addEventListener('click', function (event) {
          event.preventDefault();
          show(Number(el.dataset.index || 0));
          viewer.classList.add('open');
        });
      });
      viewer.querySelector('.viewer-close').addEventListener('click', function () { viewer.classList.remove('open'); });
      viewer.querySelector('.viewer-prev').addEventListener('click', function () { show(index - 1); });
      viewer.querySelector('.viewer-next').addEventListener('click', function () { show(index + 1); });
      viewer.addEventListener('click', function (event) { if (event.target === viewer) viewer.classList.remove('open'); });
      viewer.addEventListener('touchstart', function (event) { startX = event.changedTouches[0].screenX; }, { passive: true });
      viewer.addEventListener('touchend', function (event) {
        const delta = event.changedTouches[0].screenX - startX;
        if (Math.abs(delta) > 40) show(index + (delta < 0 ? 1 : -1));
      }, { passive: true });
      document.addEventListener('keydown', function (event) {
        if (!viewer.classList.contains('open')) return;
        if (event.key === 'Escape') viewer.classList.remove('open');
        if (event.key === 'ArrowLeft') show(index - 1);
        if (event.key === 'ArrowRight') show(index + 1);
      });
    })();
  </script>
</body>
</html>
        """.trimIndent()
    }

    private fun renderImages(images: List<Pair<String, String>>): String {
        if (images.isEmpty()) return ""
        val items = images.take(3).mapIndexed { index, (url, label) ->
            val safeUrl = escapeHtml(url)
            val badge = if (label.isBlank()) "" else """<span class="badge">$label</span>"""
            """<a class="photo" data-index="$index" href="$safeUrl"><img src="$safeUrl" alt="$label">$badge</a>"""
        }.joinToString("")
        val more = if (images.size > 3) {
            """<div class="more">仅展示前 3 张图片，点击图片可滑动查看全部 ${images.size} 张</div>"""
        } else {
            ""
        }
        return """<div class="media">$items</div>$more"""
    }

    private fun renderImageUrls(images: List<Pair<String, String>>): String =
        images.joinToString(",") { (url, _) ->
            "'" + url.replace("\\", "\\\\").replace("'", "\\'").replace("\r", "\\r").replace("\n", "\\n") + "'"
        }.let { "[$it]" }

    private fun renderErrorPage(message: String): String = """
<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>分享不可用 - 娇颜颂</title>
  <style>
    body { margin: 0; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #fafafa; color: #202124; }
    .page { min-height: 100vh; display: grid; place-items: center; padding: 24px; box-sizing: border-box; }
    .card { max-width: 420px; background: #fff; border: 1px solid #eee; border-radius: 16px; padding: 24px; text-align: center; }
    h1 { margin: 0 0 10px; font-size: 22px; }
    p { margin: 0; color: #666; line-height: 1.7; }
  </style>
</head>
<body><main class="page"><section class="card"><h1>分享不可用</h1><p>${escapeHtml(message)}</p></section></main></body>
</html>
    """.trimIndent()

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private fun buildAppLink(token: String): String {
        val safeToken = escapeHtml(token)
        return "joysong://s/diary/$safeToken"
    }
}
