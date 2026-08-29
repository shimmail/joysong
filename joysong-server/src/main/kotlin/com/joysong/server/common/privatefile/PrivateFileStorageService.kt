package com.joysong.server.common.privatefile

import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.deletion.UserMediaStorageProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID

@Service
class PrivateFileStorageService(
    private val jdbcTemplate: JdbcTemplate,
    private val userMediaAssetService: UserMediaAssetService,
    @Value("\${upload.private-dir:./data/private}") private val privateUploadDirectory: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun store(
        ownerUserId: String,
        purpose: String,
        file: MultipartFile,
        policy: PrivateFilePolicy,
        assetType: String,
    ): StoredPrivateFile {
        require(!file.isEmpty) { "私有文件不能为空" }
        require(file.size <= policy.maxFileSizeBytes) { "单个私有文件不能超过 10MB" }

        val contentType = file.contentType?.trim()?.lowercase()
            ?: throw IllegalArgumentException("无法识别文件格式")
        val canonicalExtension = policy.allowedContentTypes[contentType]
            ?: throw IllegalArgumentException("不支持的私有文件格式")
        val declaredExtension = file.originalFilename
            ?.substringAfterLast('.', missingDelimiterValue = "")
            ?.lowercase()
            .orEmpty()
        val extensionMatches = declaredExtension == canonicalExtension ||
            (contentType == "image/jpeg" && declaredExtension == "jpeg" && canonicalExtension == "jpg")
        require(!policy.requireMatchingExtension || extensionMatches) { "文件扩展名与声明格式不匹配" }

        val bytes = file.bytes
        require(bytes.isNotEmpty()) { "私有文件不能为空" }
        require(bytes.size.toLong() <= policy.maxFileSizeBytes) { "单个私有文件不能超过 10MB" }
        require(hasValidSignature(bytes, contentType)) { "文件内容与声明格式不匹配" }

        val normalizedPurpose = purpose.trim().uppercase()
        require(normalizedPurpose.matches(Regex("^[A-Z0-9_-]{1,40}$"))) { "私有文件用途不合法" }
        val id = UUID.randomUUID().toString()
        val relativeStorageKey = "$ownerUserId/$normalizedPurpose/$id.$canonicalExtension"
        val targetFile = resolvePrivateFile(relativeStorageKey)
        val originalName = sanitizeOriginalName(file.originalFilename.orEmpty())
        Files.createDirectories(targetFile.parent)

        try {
            file.transferTo(targetFile)
            deleteWrittenFileIfTransactionFails(targetFile)
            val sha256 = Files.newInputStream(targetFile).use { input ->
                MessageDigest.getInstance("SHA-256")
                    .digest(input.readBytes())
                    .joinToString("") { "%02x".format(it) }
            }
            jdbcTemplate.update(
                """
                INSERT INTO private_files
                    (id, owner_user_id, purpose, storage_key, original_name, content_type, size_bytes, sha256, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                """.trimIndent(),
                id,
                ownerUserId,
                normalizedPurpose,
                relativeStorageKey,
                originalName,
                contentType,
                file.size,
                sha256,
            )
            userMediaAssetService.register(
                userId = ownerUserId,
                storageKey = relativeStorageKey,
                assetType = assetType,
                storageProvider = UserMediaStorageProvider.LOCAL_PRIVATE,
            )
            return StoredPrivateFile(
                fileId = id,
                ownerUserId = ownerUserId,
                purpose = normalizedPurpose,
                storageKey = relativeStorageKey,
                originalName = originalName,
                contentType = contentType,
                sizeBytes = file.size,
            )
        } catch (error: Exception) {
            Files.deleteIfExists(targetFile)
            throw error
        }
    }

    fun loadActive(fileId: String): PrivateFileDownload {
        val file = resolveActive(fileId)
        if (!Files.isRegularFile(file.path)) throw IllegalArgumentException(PRIVATE_FILE_NOT_FOUND)
        return file
    }

    internal fun resolveActive(fileId: String): PrivateFileDownload {
        val metadata = jdbcTemplate.query(
            """
            SELECT storage_key, original_name, content_type
            FROM private_files
            WHERE id = ? AND status = 'ACTIVE' AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getString("storage_key"),
                    rs.getString("original_name"),
                    rs.getString("content_type"),
                )
            },
            fileId,
        ).firstOrNull() ?: throw IllegalArgumentException(PRIVATE_FILE_NOT_FOUND)
        val path = try {
            resolvePrivateFile(metadata.first)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException(PRIVATE_FILE_NOT_FOUND)
        }
        return PrivateFileDownload(
            path = path,
            originalName = metadata.second.ifBlank { fileId },
            contentType = metadata.third,
        )
    }

    private fun resolvePrivateFile(storageKey: String): Path {
        val baseDirectory = Path.of(privateUploadDirectory).toAbsolutePath().normalize()
        val targetFile = baseDirectory.resolve(storageKey).normalize()
        require(targetFile.startsWith(baseDirectory)) { "私有文件存储路径不合法" }
        return targetFile
    }

    private fun deleteWrittenFileIfTransactionFails(targetFile: Path) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCompletion(status: Int) {
                if (status == TransactionSynchronization.STATUS_COMMITTED) return
                try {
                    Files.deleteIfExists(targetFile)
                } catch (error: Exception) {
                    logger.error("Failed to remove private file after transaction failure file={}", targetFile.fileName, error)
                }
            }
        })
    }

    private fun sanitizeOriginalName(originalName: String): String = originalName
        .filterNot { character ->
            character == '\r' || character == '\n' || character == '/' || character == '\\' ||
                character == '"' || character == '\'' || character.isISOControl()
        }
        .take(255)

    private fun hasValidSignature(bytes: ByteArray, contentType: String): Boolean = when (contentType) {
        "image/jpeg" -> bytes.size >= 3 &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()
        "image/png" -> bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)
        "image/webp" -> bytes.size >= 12 &&
            String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
        "application/pdf" -> bytes.size >= 5 && String(bytes, 0, 5, Charsets.US_ASCII) == "%PDF-"
        else -> false
    }

    companion object {
        private const val PRIVATE_FILE_NOT_FOUND = "PRIVATE_FILE_NOT_FOUND"
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )
    }
}
