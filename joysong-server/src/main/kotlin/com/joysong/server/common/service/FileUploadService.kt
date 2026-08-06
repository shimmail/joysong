package com.joysong.server.common.service

import com.aliyun.oss.OSS
import com.aliyun.oss.model.ObjectMetadata
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

@Service
class FileUploadService(
    @Value("\${server.base-url:http://localhost:8080}") private val baseUrl: String,
    @Value("\${upload.local-dir:./data/uploads}") private val uploadDirectory: String,
    @Value("\${oss.enabled:false}") private val ossEnabled: Boolean,
    @Value("\${oss.endpoint:}") private val ossEndpoint: String,
    @Value("\${oss.bucket-name:}") private val ossBucketName: String,
    @Value("\${oss.public-base-url:}") private val ossPublicBaseUrl: String,
    private val ossClientProvider: ObjectProvider<OSS>
) {
    private val logger = LoggerFactory.getLogger(FileUploadService::class.java)
    private val allowedExtensions = setOf("jpg", "jpeg", "png", "webp", "gif")
    private val maxFileSize = 10 * 1024 * 1024L // 10MB

    fun upload(file: MultipartFile, folder: String = "general", customFileName: String? = null): String {
        require(!file.isEmpty) { "上传文件不能为空" }
        // 校验文件大小
        if (file.size > maxFileSize) {
            throw IllegalArgumentException("文件大小不能超过 10MB")
        }

        // 校验文件类型
        val originalFilename = file.originalFilename ?: "unknown"
        val declaredExtension = originalFilename.substringAfterLast(".", "").lowercase()
        if (declaredExtension !in allowedExtensions) {
            throw IllegalArgumentException("仅支持 jpg、jpeg、png、webp、gif 格式的图片")
        }
        val bytes = file.bytes
        val detectedExtension = detectImageExtension(bytes)
            ?: throw IllegalArgumentException("无法识别图片格式或文件内容已损坏")
        // 部分图片下载或转存后会保留错误的文件后缀（例如 JPEG 内容被命名为 .png）。
        // 以文件签名识别出的真实格式保存，既兼容这类合法图片，也避免信任用户提供的后缀。
        val extension = if (detectedExtension == "jpeg") "jpg" else detectedExtension

        // 目录和文件名只允许安全字符，防止通过请求参数写入上传目录之外的位置。
        require(folder.matches(Regex("^[A-Za-z0-9_-]{1,64}$"))) { "上传目录名称不合法" }
        val fileName = customFileName?.also {
            require(it.matches(Regex("^[A-Za-z0-9_-]{1,100}$"))) { "自定义文件名不合法" }
        } ?: UUID.randomUUID().toString()
        val relativePath = "$folder/$fileName.$extension"

        if (ossEnabled) {
            return uploadToOss(
                relativePath = relativePath,
                bytes = bytes,
                contentType = imageContentType(extension)
            )
        }

        // 上传根目录由 UPLOAD_LOCAL_DIR / upload.local-dir 配置；默认值仅用于本地开发。
        val baseDirectory = Path.of(uploadDirectory).toAbsolutePath().normalize()
        val targetFile = baseDirectory.resolve(relativePath).normalize()
        require(targetFile.startsWith(baseDirectory)) { "上传路径不合法" }
        Files.createDirectories(targetFile.parent)
        file.transferTo(targetFile)

        // 返回 HTTP 可访问 URL
        val url = "${baseUrl.trimEnd('/')}/images/$relativePath"
        logger.info("文件上传成功 - 本地路径: $targetFile, URL: $url")
        return url
    }

    private fun uploadToOss(relativePath: String, bytes: ByteArray, contentType: String): String {
        require(ossBucketName.isNotBlank()) { "OSS_BUCKET_NAME is required" }
        require(ossEndpoint.isNotBlank()) { "OSS_ENDPOINT is required" }
        val client = ossClientProvider.ifAvailable
            ?: throw IllegalStateException("STORAGE_PROVIDER_UNAVAILABLE")
        val metadata = ObjectMetadata().apply {
            contentLength = bytes.size.toLong()
            this.contentType = contentType
        }
        ByteArrayInputStream(bytes).use { input ->
            client.putObject(ossBucketName, relativePath, input, metadata)
        }
        val publicBase = ossPublicBaseUrl.trim().trimEnd('/').ifBlank {
            val endpointHost = ossEndpoint
                .removePrefix("https://")
                .removePrefix("http://")
                .trimEnd('/')
            "https://$ossBucketName.$endpointHost"
        }
        val url = "$publicBase/$relativePath"
        logger.info("Public media uploaded to object storage: {}", relativePath)
        return url
    }

    private fun detectImageExtension(bytes: ByteArray): String? {
        if (bytes.size < 12) return null
        if (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()) return "jpg"
        if (bytes.copyOfRange(0, 8).contentEquals(
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            )) return "png"
        if (String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a")) return "gif"
        if (String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
            String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP") return "webp"
        return null
    }

    private fun imageContentType(extension: String) = when (extension) {
        "jpg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> "application/octet-stream"
    }
}
