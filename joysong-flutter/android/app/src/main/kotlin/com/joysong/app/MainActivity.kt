package com.joysong.app

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private var pendingFileResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            FILE_PICKER_CHANNEL,
        ).setMethodCallHandler { call, result ->
            if (call.method != "pickFile") {
                result.notImplemented()
                return@setMethodCallHandler
            }
            if (pendingFileResult != null) {
                result.error("PICK_IN_PROGRESS", "A file picker is already open.", null)
                return@setMethodCallHandler
            }
            val extensions = call.argument<List<String>>("extensions").orEmpty()
            val mimeTypes = extensions.mapNotNull(::mimeTypeForExtension).distinct()
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (mimeTypes.size == 1) mimeTypes.first() else "*/*"
                if (mimeTypes.size > 1) {
                    putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes.toTypedArray())
                }
            }
            pendingFileResult = result
            startActivityForResult(intent, FILE_PICKER_REQUEST)
        }
    }

    @Deprecated("Deprecated in Android SDK; required by FlutterActivity compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_PICKER_REQUEST) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val result = pendingFileResult ?: return
        pendingFileResult = null
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            result.success(null)
            return
        }
        try {
            val uri = data.data!!
            val metadata = queryMetadata(uri)
            if (metadata.second > MAX_FILE_BYTES) {
                result.error("FILE_TOO_LARGE", "The selected file exceeds 10 MB.", null)
                return
            }
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            if (bytes == null || bytes.isEmpty()) {
                result.error("FILE_UNREADABLE", "Unable to read the selected file.", null)
                return
            }
            if (bytes.size > MAX_FILE_BYTES) {
                result.error("FILE_TOO_LARGE", "The selected file exceeds 10 MB.", null)
                return
            }
            val fileName = metadata.first.ifBlank { "upload" }
            result.success(
                mapOf(
                    "bytes" to bytes,
                    "fileName" to fileName,
                    "mimeType" to (contentResolver.getType(uri)
                        ?: mimeTypeForExtension(fileName.substringAfterLast('.', ""))
                        ?: "application/octet-stream"),
                ),
            )
        } catch (error: Exception) {
            result.error("FILE_UNREADABLE", "Unable to read the selected file.", null)
        }
    }

    private fun queryMetadata(uri: Uri): Pair<String, Long> {
        var name = ""
        var size = -1L
        val cursor: Cursor? = contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) name = it.getString(nameIndex).orEmpty()
                if (sizeIndex >= 0 && !it.isNull(sizeIndex)) size = it.getLong(sizeIndex)
            }
        }
        return name to size
    }

    private fun mimeTypeForExtension(extension: String): String? =
        when (extension.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "pdf" -> "application/pdf"
            else -> null
        }

    companion object {
        private const val FILE_PICKER_CHANNEL = "com.joysong.app/file_picker"
        private const val FILE_PICKER_REQUEST = 7301
        private const val MAX_FILE_BYTES = 10 * 1024 * 1024
    }
}
