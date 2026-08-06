package com.joysong.app.domain.repository

import java.io.File

interface FileRepository {
    suspend fun uploadImage(file: File, folder: String = "general", customFileName: String? = null): Result<String>
}
