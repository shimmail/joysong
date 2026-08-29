package com.joysong.server.common.privatefile

import java.nio.file.Path

data class PrivateFilePolicy(
    val allowedContentTypes: Map<String, String>,
    val maxFileSizeBytes: Long = 10L * 1024 * 1024,
    val requireMatchingExtension: Boolean = true,
)

data class StoredPrivateFile(
    val fileId: String,
    val ownerUserId: String,
    val purpose: String,
    val storageKey: String,
    val originalName: String,
    val contentType: String,
    val sizeBytes: Long,
)

data class PrivateFileDownload(
    val path: Path,
    val originalName: String,
    val contentType: String,
)
