package com.joysong.app.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.IdentityApplicationDocumentDto
import com.joysong.app.data.remote.dto.IdentityApplicationDto
import com.joysong.app.data.remote.dto.IdentityOverviewDto
import com.joysong.app.data.remote.dto.PrivateIdentityFileDto
import com.joysong.app.data.remote.dto.SubmitIdentityApplicationRequestDto
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IdentityRepository @Inject constructor(
    private val apiService: ApiService,
    @ApplicationContext private val context: Context
) {
    suspend fun getOverview(): Result<IdentityOverviewDto> = runCatching {
        val response = apiService.getIdentityOverview()
        require(response.code == 200 && response.data != null) { response.message }
        response.data
    }

    suspend fun uploadDocument(uri: Uri, purpose: String): Result<PrivateIdentityFileDto> = runCatching {
        val contentType = context.contentResolver.getType(uri) ?: "image/jpeg"
        require(contentType.startsWith("image/") || contentType == "application/pdf") { "仅支持图片或 PDF 文件" }
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(contentType) ?: "jpg"
        val displayName = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: "identity_${System.currentTimeMillis()}.$extension"
        val cacheFile = File.createTempFile("identity_", ".$extension", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                cacheFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalArgumentException("无法读取所选文件")
            val body = cacheFile.asRequestBody(contentType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", displayName, body)
            val response = apiService.uploadIdentityFile(part, purpose)
            require(response.code == 200 && response.data != null) { response.message }
            response.data
        } finally {
            cacheFile.delete()
        }
    }

    suspend fun submitApplication(
        roleCode: String,
        applicationData: Map<String, String>,
        files: Map<String, PrivateIdentityFileDto>
    ): Result<IdentityApplicationDto> = runCatching {
        val request = SubmitIdentityApplicationRequestDto(
            roleCode = roleCode,
            applicationData = applicationData,
            documents = files.map { (documentType, file) ->
                IdentityApplicationDocumentDto(fileId = file.fileId, documentType = documentType)
            }
        )
        val response = apiService.submitIdentityApplication(request)
        require(response.code == 200 && response.data != null) { response.message }
        response.data
    }

    suspend fun deleteDraftFile(fileId: String): Result<Unit> = runCatching {
        val response = apiService.deleteIdentityFile(fileId)
        require(response.code == 200) { response.message }
    }
}
