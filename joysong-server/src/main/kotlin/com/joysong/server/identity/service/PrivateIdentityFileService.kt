package com.joysong.server.identity.service

import com.joysong.server.common.privatefile.PrivateFilePolicy
import com.joysong.server.common.privatefile.PrivateFileStorageService
import com.joysong.server.user.service.AccountLifecycleGuard
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path

val IDENTITY_DOCUMENT_TYPES = setOf(
    "BUSINESS_LICENSE",
    "ID_CARD_FRONT",
    "ID_CARD_BACK",
    "ID_CARD_HANDHELD",
    "DOCTOR_QUALIFICATION",
    "DOCTOR_PRACTICE_CERTIFICATE",
    "CONSULTANT_PROOF",
)

@Service
class PrivateIdentityFileService(
    private val jdbcTemplate: JdbcTemplate,
    private val lifecycleGuard: AccountLifecycleGuard,
    private val privateFileStorageService: PrivateFileStorageService,
) {
    private val identityFilePolicy = PrivateFilePolicy(
        allowedContentTypes = mapOf(
            "image/jpeg" to "jpg",
            "image/png" to "png",
            "image/webp" to "webp",
            "application/pdf" to "pdf",
        ),
    )

    @Transactional
    fun upload(userId: String, purpose: String, file: MultipartFile): PrivateIdentityFileView {
        lifecycleGuard.requireActiveForWrite(userId)
        val normalizedPurpose = purpose.trim().uppercase()
        require(normalizedPurpose in IDENTITY_DOCUMENT_TYPES) { "不支持的认证材料类型" }
        val stored = privateFileStorageService.store(
            ownerUserId = userId,
            purpose = normalizedPurpose,
            file = file,
            policy = identityFilePolicy,
            assetType = "IDENTITY",
        )
        return PrivateIdentityFileView(
            fileId = stored.fileId,
            purpose = stored.purpose,
            originalName = stored.originalName,
            contentType = stored.contentType,
            sizeBytes = stored.sizeBytes,
        )
    }

    @Transactional
    fun deleteDraft(userId: String, fileId: String) {
        lifecycleGuard.requireActiveForWrite(userId)
        val eligible = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM private_files pf
            WHERE pf.id = ? AND pf.owner_user_id = ? AND pf.status = 'ACTIVE' AND pf.deleted_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = pf.id
              )
            """.trimIndent(),
            Int::class.java,
            fileId,
            userId,
        ) ?: 0
        if (eligible == 0) throw IllegalArgumentException("认证材料不存在、已提交或不属于当前用户")
        val file = loadIdentityFile(fileId, "认证材料不存在、已提交或不属于当前用户")
        jdbcTemplate.update(
            "UPDATE private_files SET status = 'DELETED', deleted_at = NOW() WHERE id = ? AND owner_user_id = ?",
            fileId,
            userId,
        )
        Files.deleteIfExists(file.path)
    }

    fun loadSubmittedFileForAdmin(fileId: String): PrivateIdentityFileDownload {
        val submitted = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM private_files pf
            WHERE pf.id = ? AND pf.status = 'ACTIVE' AND pf.deleted_at IS NULL
              AND EXISTS (
                SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = pf.id
              )
            """.trimIndent(),
            Int::class.java,
            fileId,
        ) ?: 0
        if (submitted == 0) throw IllegalArgumentException("认证材料不存在或尚未提交")
        return loadIdentityFile(fileId, "认证材料文件不存在")
    }

    private fun loadIdentityFile(fileId: String, notFoundMessage: String): PrivateIdentityFileDownload = try {
        val stored = privateFileStorageService.loadActive(fileId)
        PrivateIdentityFileDownload(
            path = stored.path,
            originalName = stored.originalName,
            contentType = stored.contentType,
        )
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(notFoundMessage)
    }
}

data class PrivateIdentityFileDownload(
    val path: Path,
    val originalName: String,
    val contentType: String,
)

data class PrivateIdentityFileView(
    val fileId: String,
    val purpose: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
)
