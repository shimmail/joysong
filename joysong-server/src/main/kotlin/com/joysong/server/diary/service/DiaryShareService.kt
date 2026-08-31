package com.joysong.server.diary.service

import com.joysong.server.diary.entity.DiaryShareEntity
import com.joysong.server.diary.entity.dto.*
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.diary.repository.DiaryShareRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.joysong.server.user.service.AccountLifecycleGuard
import java.net.URI
import java.security.MessageDigest
import java.security.SecureRandom
import java.nio.charset.StandardCharsets
import java.time.LocalDateTime
import java.util.Base64
import java.util.UUID

@Service
class DiaryShareService(
    private val diaryRepository: DiaryRepository,
    private val shareRepository: DiaryShareRepository,
    @Value("\${app.share-base-url:}") private val shareBaseUrl: String,
    @Value("\${app.share-request-origin-fallback-enabled:false}")
    private val shareRequestOriginFallbackEnabled: Boolean,
    private val accountLifecycleGuard: AccountLifecycleGuard? = null,
) {
    private val random = SecureRandom()

    @Transactional
    fun create(
        userId: String,
        diaryId: String,
        request: CreateDiaryShareRequest,
        requestShareBaseUrl: String,
    ): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val diary = diaryRepository.findById(diaryId).orElse(null) ?: return error("日记不存在", 404)
        if (diary.userId != userId) return error("无权分享他人日记", 403)
        if (diary.status != "published") return error("仅已发布日记可以分享", 403)
        shareRepository.findByDiaryIdAndRevokedAtIsNull(diaryId)?.let {
            it.revokedAt = LocalDateTime.now()
            shareRepository.save(it)
        }
        val token = ByteArray(32).also(random::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val expiresAt = request.expiresInDays?.let {
            require(it in 1..365) { "分享有效期必须在 1-365 天之间" }
            LocalDateTime.now().plusDays(it.toLong())
        }
        shareRepository.save(DiaryShareEntity(UUID.randomUUID().toString(), diaryId, hash(token), expiresAt))
        return DiaryShareResponse(
            url(token, requestShareBaseUrl),
            token,
            expiresAt,
            DiarySharePreview(diary.title, diary.authorName, diary.coverImage),
        )
    }

    @Transactional
    fun get(token: String): PublicDiaryShareResponse? {
        val share = shareRepository.findByTokenHash(hash(token)) ?: return null
        val now = LocalDateTime.now()
        if (share.revokedAt != null || (share.expiresAt != null && !share.expiresAt.isAfter(now))) return null
        val diary = diaryRepository.findById(share.diaryId).orElse(null) ?: return null
        if (diary.status != "published") return null
        share.accessCount += 1
        share.lastAccessAt = now
        return diary.toPublicShareResponse()
    }

    @Transactional
    fun revoke(userId: String, diaryId: String): Any {
        accountLifecycleGuard?.requireActiveForWrite(userId)
        val diary = diaryRepository.findById(diaryId).orElse(null) ?: return error("日记不存在", 404)
        if (diary.userId != userId) return error("无权撤销他人日记分享", 403)
        shareRepository.revokeActiveByDiaryId(diaryId, LocalDateTime.now())
        return mapOf("message" to "已撤销日记分享")
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun url(value: String, requestShareBaseUrl: String): String {
        val baseUrl = shareBaseUrl.takeIf(String::isNotBlank) ?: run {
            check(shareRequestOriginFallbackEnabled) {
                "Diary share request-origin fallback is disabled; configure app.share-base-url"
            }
            requestShareBaseUrl
        }
        return DiaryShareUrlPolicy.normalizeBaseUrl(baseUrl) + "/" + value
    }

    private fun error(message: String, code: Int) = mapOf("error" to message, "code" to code)
}

internal object DiaryShareUrlPolicy {
    fun normalizeBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val uri = runCatching { URI(normalized) }.getOrNull()
        require(
            uri != null &&
                uri.isAbsolute &&
                (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null
        ) {
            "Diary share base URL must be an absolute HTTP(S) URL without user info, query, or fragment"
        }
        return normalized
    }

    fun isValidBaseUrl(value: String): Boolean = runCatching { normalizeBaseUrl(value) }.isSuccess
}
