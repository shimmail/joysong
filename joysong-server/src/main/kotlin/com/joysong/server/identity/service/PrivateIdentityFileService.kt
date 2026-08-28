package com.joysong.server.identity.service

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.deletion.UserMediaStorageProvider
import com.joysong.server.user.service.AccountLifecycleGuard
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

val IDENTITY_DOCUMENT_TYPES = setOf(
    "BUSINESS_LICENSE",
    "ID_CARD_FRONT",
    "ID_CARD_BACK",
    "ID_CARD_HANDHELD",
    "DOCTOR_QUALIFICATION",
    "DOCTOR_PRACTICE_CERTIFICATE",
    "CONSULTANT_PROOF"
)

@Service
class PrivateIdentityFileService(
    private val jdbcTemplate: JdbcTemplate,
    private val lifecycleGuard: AccountLifecycleGuard,
    private val userMediaAssetService: UserMediaAssetService,
    @Value("\${upload.private-dir:./data/private}") private val privateUploadDirectory: String
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val maxFileSize = 10 * 1024 * 1024L
    private val allowedContentTypes = mapOf(
        "image/jpeg" to "jpg",
        "image/png" to "png",
        "image/webp" to "webp",
        "application/pdf" to "pdf"
    )

    @Transactional
    fun upload(userId: String, purpose: String, file: MultipartFile): PrivateIdentityFileView {
        lifecycleGuard.requireActiveForWrite(userId)
        val normalizedPurpose = purpose.trim().uppercase()
        require(normalizedPurpose in IDENTITY_DOCUMENT_TYPES) { "不支持的认证材料类型" }
        require(!file.isEmpty) { "认证材料不能为空" }
        require(file.size <= maxFileSize) { "单个认证文件不能超过 10MB" }

        val contentType = file.contentType?.lowercase()
            ?: throw IllegalArgumentException("无法识别文件格式")
        val extension = allowedContentTypes[contentType]
            ?: throw IllegalArgumentException("认证材料仅支持 JPG、PNG、WebP 或 PDF")
        require(hasValidSignature(file.bytes, contentType)) { "文件内容与认证材料格式不匹配" }
        val originalName = file.originalFilename?.take(255).orEmpty()
        val id = UUID.randomUUID().toString()
        val relativeStorageKey = "$userId/$normalizedPurpose/$id.$extension"
        val baseDirectory = Path.of(privateUploadDirectory).toAbsolutePath().normalize()
        val targetFile = baseDirectory.resolve(relativeStorageKey).normalize()
        require(targetFile.startsWith(baseDirectory)) { "认证材料存储路径不合法" }
        Files.createDirectories(targetFile.parent)

        try {
            file.transferTo(targetFile)
            deleteWrittenFileIfTransactionFails(targetFile)
            val sha256 = Files.newInputStream(targetFile).use { input ->
                MessageDigest.getInstance("SHA-256").digest(input.readBytes()).joinToString("") { "%02x".format(it) }
            }
            jdbcTemplate.update(
                """
                INSERT INTO private_files
                    (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                id,
                userId,
                normalizedPurpose,
                relativeStorageKey,
                originalName,
                contentType,
                file.size,
                sha256
            )
            userMediaAssetService.register(
                userId = userId,
                storageKey = relativeStorageKey,
                assetType = "IDENTITY",
                storageProvider = UserMediaStorageProvider.LOCAL_PRIVATE,
            )
            return PrivateIdentityFileView(id, normalizedPurpose, originalName, contentType, file.size)
        } catch (error: Exception) {
            Files.deleteIfExists(targetFile)
            throw error
        }
    }

    private fun deleteWrittenFileIfTransactionFails(targetFile: Path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return
                try {
                    Files.deleteIfExists(targetFile)
                } catch (error: Exception) {
                    logger.error(
                        "Failed to remove private identity file after transaction failure path={}",
                        targetFile.fileName,
                        error,
                    )
                }
            }
        })
    }

    @Transactional
    fun deleteDraft(userId: String, fileId: String) {
        lifecycleGuard.requireActiveForWrite(userId)
        val file = findFile(
            """
            SELECT storage_key, original_name, content_type
            FROM private_files pf
            WHERE pf.id = ? AND pf.owner_user_id = ? AND pf.status = 'ACTIVE' AND pf.deleted_at IS NULL
              AND NOT EXISTS (
                SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = pf.id
              )
            """.trimIndent(),
            fileId,
            userId
        ) ?: throw IllegalArgumentException("认证材料不存在、已提交或不属于当前用户")

        val targetFile = resolvePrivateFile(file.storageKey)
        jdbcTemplate.update(
            "UPDATE private_files SET status = 'DELETED', deleted_at = NOW() WHERE id = ? AND owner_user_id = ?",
            fileId,
            userId
        )
        Files.deleteIfExists(targetFile)
    }

    fun loadSubmittedFileForAdmin(fileId: String): PrivateIdentityFileDownload {
        val file = findFile(
            """
            SELECT pf.storage_key, pf.original_name, pf.content_type
            FROM private_files pf
            WHERE pf.id = ? AND pf.status = 'ACTIVE' AND pf.deleted_at IS NULL
              AND EXISTS (
                SELECT 1 FROM identity_application_documents iad WHERE iad.file_id = pf.id
              )
            """.trimIndent(),
            fileId
        ) ?: throw IllegalArgumentException("认证材料不存在或尚未提交")

        val targetFile = resolvePrivateFile(file.storageKey)
        require(Files.isRegularFile(targetFile)) { "认证材料文件不存在" }
        return PrivateIdentityFileDownload(
            path = targetFile,
            originalName = file.originalName.ifBlank { fileId },
            contentType = file.contentType
        )
    }

    private fun findFile(sql: String, vararg args: Any): StoredPrivateFile? =
        jdbcTemplate.query(
            sql,
            { rs, _ ->
                StoredPrivateFile(
                    storageKey = rs.getString("storage_key"),
                    originalName = rs.getString("original_name"),
                    contentType = rs.getString("content_type")
                )
            },
            *args
        ).firstOrNull()

    private fun resolvePrivateFile(storageKey: String): Path {
        val baseDirectory = Path.of(privateUploadDirectory).toAbsolutePath().normalize()
        val targetFile = baseDirectory.resolve(storageKey).normalize()
        require(targetFile.startsWith(baseDirectory)) { "认证材料存储路径不合法" }
        return targetFile
    }

    private fun hasValidSignature(bytes: ByteArray, contentType: String): Boolean {
        if (bytes.size < 12) return false
        return when (contentType) {
            "image/jpeg" -> bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
            "image/png" -> bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )
            "image/webp" -> String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
            "application/pdf" -> String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"
            else -> false
        }
    }
}

private data class StoredPrivateFile(
    val storageKey: String,
    val originalName: String,
    val contentType: String
)

data class PrivateIdentityFileDownload(
    val path: Path,
    val originalName: String,
    val contentType: String
)

data class PrivateIdentityFileView(
    val fileId: String,
    val purpose: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long
)
