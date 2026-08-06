package com.joysong.app.data.repository

import com.joysong.app.data.remote.ApiService
import com.joysong.app.domain.repository.FileRepository
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import retrofit2.HttpException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileRepositoryImpl @Inject constructor(
    private val apiService: ApiService
) : FileRepository {

    override suspend fun uploadImage(file: File, folder: String, customFileName: String?): Result<String> {
        return try {
            val requestBody = file.asRequestBody("image/*".toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", file.name, requestBody)
            val response = apiService.uploadImage(part, folder, customFileName)
            if (response.code == 200 && response.data != null) {
                Result.success(response.data.url)
            } else {
                Result.failure(Exception(response.message))
            }
        } catch (e: HttpException) {
            Result.failure(Exception(e.message()))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
