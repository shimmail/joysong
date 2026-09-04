package com.joysong.server.common.service

import com.aliyun.oss.OSS
import com.aliyun.oss.model.ObjectMetadata
import com.aliyun.oss.model.PutObjectRequest
import com.joysong.server.user.deletion.UserMediaAssetService
import com.joysong.server.user.deletion.UserMediaStorageProvider
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

@Service
class FileUploadService(
    @Value("\${server.base-url:http://localhost:8080}") private val baseUrl: String,
    @Value("\${upload.local-dir:./data/uploads}") private val uploadDirectory: String,
    @Value("\${oss.enabled:false}") private val ossEnabled: Boolean,
    @Value("\${oss.endpoint:}") private val ossEndpoint: String,
    @Value("\${oss.bucket-name:}") private val ossBucketName: String,
    @Value("\${oss.public-base-url:}") private val ossPublicBaseUrl: String,
    private val ossClientProvider: ObjectProvider<OSS>,
    private val userMediaAssetService: UserMediaAssetService? = null,
    @Value("\${upload.staging-dir:}") private val uploadStagingDirectory: String = "",
    private val metrics: PublicUploadMetrics? = null,
) {
    private val logger = LoggerFactory.getLogger(FileUploadService::class.java)
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")

    fun upload(file: MultipartFile, folder: String = "general", customFileName: String? = null): String =
        prepare(file).use { prepared ->
            uploadPrepared(null, prepared, folder, customFileName).url
        }

    fun upload(
        userId: String,
        file: MultipartFile,
        folder: String = "general",
        customFileName: String? = null,
    ): String = uploadDetailed(userId, file, folder, customFileName).url

    internal fun uploadDetailed(
        userId: String,
        file: MultipartFile,
        folder: String,
        customFileName: String?,
    ): PublicUploadReceipt = prepare(file).use { prepared ->
        uploadPrepared(userId, prepared, folder, customFileName)
    }

    internal fun prepare(file: MultipartFile): PreparedPublicUpload {
        require(!file.isEmpty) { "上传文件不能为空" }
        if (file.size > MAX_FILE_SIZE) {
            throw IllegalArgumentException("文件大小不能超过 10MB")
        }
        val declaredExtension = file.originalFilename
            ?.substringAfterLast(".", "")
            ?.lowercase()
            .orEmpty()
        require(declaredExtension in allowedExtensions) {
            "仅支持 jpg、jpeg、png、webp、gif 格式的图片"
        }

        val startedAt = System.nanoTime()
        val stagingRoot = uploadStagingDirectory
            .takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("java.io.tmpdir"), "joysong-upload-staging")
        Files.createDirectories(stagingRoot)
        val temporaryPath = Files.createTempFile(stagingRoot, "public-upload-", ".part")

        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val signature = ByteArray(IMAGE_SIGNATURE_LENGTH)
            var signatureBytes = 0
            var totalBytes = 0L
            file.inputStream.buffered().use { input ->
                Files.newOutputStream(temporaryPath).buffered().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        totalBytes += count
                        if (totalBytes > MAX_FILE_SIZE) {
                            throw IllegalArgumentException("文件大小不能超过 10MB")
                        }
                        if (signatureBytes < signature.size) {
                            val copied = minOf(count, signature.size - signatureBytes)
                            buffer.copyInto(signature, signatureBytes, 0, copied)
                            signatureBytes += copied
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(totalBytes > 0) { "上传文件不能为空" }
            val detectedExtension = detectImageExtension(signature, signatureBytes)
                ?: throw IllegalArgumentException("无法识别图片格式或文件内容已损坏")
            val extension = if (detectedExtension == "jpeg") "jpg" else detectedExtension
            val elapsed = System.nanoTime() - startedAt
            return PreparedPublicUpload(
                temporaryPath = temporaryPath,
                contentSha256 = digest.digest().toHex(),
                contentLength = totalBytes,
                contentType = imageContentType(extension),
                extension = extension,
                stageDurationNanos = elapsed,
            )
        } catch (error: Exception) {
            deleteStagedFile(temporaryPath)
            throw error
        } finally {
            metrics?.recordPhase(UploadMetricPhase.STAGE, System.nanoTime() - startedAt)
        }
    }

    internal fun storageKey(
        prepared: PreparedPublicUpload,
        folder: String,
        customFileName: String?,
    ): String {
        require(folder.matches(SAFE_FOLDER)) { "上传目录名称不合法" }
        val fileName = customFileName?.also {
            require(it.matches(SAFE_FILE_NAME)) { "自定义文件名不合法" }
        } ?: UUID.randomUUID().toString()
        return "$folder/$fileName.${prepared.extension}"
    }

    internal fun uploadPrepared(
        userId: String?,
        prepared: PreparedPublicUpload,
        folder: String,
        customFileName: String?,
    ): PublicUploadReceipt = uploadPreparedAtKey(
        userId = userId,
        prepared = prepared,
        folder = folder,
        storageKey = storageKey(prepared, folder, customFileName),
    )

    internal fun uploadPreparedAtKey(
        userId: String?,
        prepared: PreparedPublicUpload,
        folder: String,
        storageKey: String,
    ): PublicUploadReceipt {
        require(folder.matches(SAFE_FOLDER)) { "上传目录名称不合法" }
        UserMediaAssetService.validateStorageKey(storageKey)
        val storageStartedAt = System.nanoTime()
        var storageDuration = 0L
        val uploaded = try {
            if (ossEnabled) {
                uploadToOss(storageKey, prepared)
            } else {
                uploadLocally(storageKey, prepared)
            }
        } finally {
            storageDuration = System.nanoTime() - storageStartedAt
            metrics?.recordPhase(UploadMetricPhase.STORAGE, storageDuration)
        }

        var registryDuration = 0L
        if (userId != null) {
            val registryStartedAt = System.nanoTime()
            try {
                requireNotNull(userMediaAssetService) { "USER_MEDIA_REGISTRY_UNAVAILABLE" }
                    .register(userId, uploaded.storageKey, folder.uppercase().take(32), uploaded.provider)
            } catch (error: Exception) {
                removeUploadedObject(uploaded)
                throw error
            } finally {
                registryDuration = System.nanoTime() - registryStartedAt
                metrics?.recordPhase(UploadMetricPhase.REGISTRY, registryDuration)
            }
        }
        return PublicUploadReceipt(
            url = uploaded.url,
            storageKey = uploaded.storageKey,
            provider = uploaded.provider,
            contentSha256 = prepared.contentSha256,
            contentLength = prepared.contentLength,
            contentType = prepared.contentType,
            stageDurationNanos = prepared.stageDurationNanos,
            storageDurationNanos = storageDuration,
            registryDurationNanos = registryDuration,
        )
    }

    private fun uploadLocally(storageKey: String, prepared: PreparedPublicUpload): UploadedMedia {
        val baseDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize()
        val targetFile = baseDirectory.resolve(storageKey).normalize()
        require(targetFile.startsWith(baseDirectory)) { "上传路径不合法" }
        Files.createDirectories(targetFile.parent)
        try {
            Files.copy(prepared.temporaryPath, targetFile, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            Files.deleteIfExists(targetFile)
            throw error
        }
        return UploadedMedia(
            url = "${baseUrl.trimEnd('/')}/images/$storageKey",
            storageKey = storageKey,
            provider = UserMediaStorageProvider.LOCAL_PUBLIC,
        )
    }

    private fun uploadToOss(storageKey: String, prepared: PreparedPublicUpload): UploadedMedia {
        val normalizedBucketName = ossBucketName.trim()
        val normalizedEndpoint = ossEndpoint.trim()
        require(normalizedBucketName.isNotBlank()) { "OSS_BUCKET_NAME is required" }
        require(normalizedEndpoint.isNotBlank()) { "OSS_ENDPOINT is required" }
        val client = ossClientProvider.ifAvailable
            ?: throw IllegalStateException("STORAGE_PROVIDER_UNAVAILABLE")
        val metadata = ObjectMetadata().apply {
            contentLength = prepared.contentLength
            contentType = prepared.contentType
            cacheControl = "public, max-age=3600"
        }
        client.putObject(
            PutObjectRequest(normalizedBucketName, storageKey, prepared.temporaryPath.toFile(), metadata),
        )
        val publicBase = ossPublicBaseUrl.trim().trimEnd('/').ifBlank {
            val endpointHost = normalizedEndpoint
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            "https://$normalizedBucketName.$endpointHost"
        }
        return UploadedMedia(
            url = "$publicBase/$storageKey",
            storageKey = storageKey,
            provider = UserMediaStorageProvider.OSS_PUBLIC,
        )
    }

    private fun removeUploadedObject(uploaded: UploadedMedia) {
        runCatching {
            when (uploaded.provider) {
                UserMediaStorageProvider.LOCAL_PUBLIC -> {
                    val base = Path.of(uploadDirectory).toAbsolutePath().normalize()
                    val target = base.resolve(uploaded.storageKey).normalize()
                    if (target.startsWith(base)) Files.deleteIfExists(target)
                    Unit
                }
                UserMediaStorageProvider.OSS_PUBLIC ->
                    ossClientProvider.ifAvailable?.deleteObject(ossBucketName.trim(), uploaded.storageKey)
                UserMediaStorageProvider.LOCAL_PRIVATE,
                UserMediaStorageProvider.OSS_PRIVATE,
                -> Unit
            }
        }.onFailure {
            logger.warn("Uploaded media registry failed and cleanup could not complete")
        }
    }

    private fun detectImageExtension(signature: ByteArray, length: Int): String? {
        if (length >= 3 &&
            signature[0] == 0xFF.toByte() &&
            signature[1] == 0xD8.toByte() &&
            signature[2] == 0xFF.toByte()
        ) return "jpg"
        if (length >= 8 && signature.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return "png"
        if (length >= 6 && String(signature, 0, 6, Charsets.US_ASCII) in GIF_SIGNATURES) return "gif"
        if (length >= 12 &&
            String(signature, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(signature, 8, 4, Charsets.US_ASCII) == "WEBP"
        ) return "webp"
        return null
    }

    private fun imageContentType(extension: String) = when (extension) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }

    private data class UploadedMedia(
        val url: String,
        val storageKey: String,
        val provider: UserMediaStorageProvider,
    )

    private companion object {
        const val MAX_FILE_SIZE = 10 * 1024 * 1024L
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val IMAGE_SIGNATURE_LENGTH = 12
        val SAFE_FOLDER = Regex("^[A-Za-z0-9_-]{1,64}$")
        val SAFE_FILE_NAME = Regex("^[A-Za-z0-9_-]{1,100}$")
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47,
            0x0D, 0x0A, 0x1A, 0x0A,
        )
        val GIF_SIGNATURES = setOf("GIF87a", "GIF89a")
    }
}

internal data class PublicUploadReceipt(
    val url: String,
    val storageKey: String,
    val provider: UserMediaStorageProvider,
    val contentSha256: String,
    val contentLength: Long,
    val contentType: String,
    val stageDurationNanos: Long,
    val storageDurationNanos: Long,
    val registryDurationNanos: Long,
)

internal data class PreparedPublicUpload(
    val temporaryPath: Path,
    val contentSha256: String,
    val contentLength: Long,
    val contentType: String,
    val extension: String,
    val stageDurationNanos: Long,
) : AutoCloseable {
    override fun close() {
        deleteStagedFile(temporaryPath)
    }
}

private fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun deleteStagedFile(path: Path) {
    runCatching { Files.deleteIfExists(path) }
        .onFailure {
            LoggerFactory.getLogger("PublicUploadStaging")
                .warn("Public upload staging cleanup failed")
        }
}
