package com.joysong.app.data.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.joysong.app.BuildConfig
import java.io.File

/** Copies a content URI into the app cache so Retrofit can upload it as a multipart file. */
fun copyImageToCache(context: Context, uri: Uri): File {
    val target = File(context.cacheDir, "chat_${System.currentTimeMillis()}.jpg")
    context.contentResolver.openInputStream(uri)?.use { input ->
        target.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalArgumentException("无法读取所选图片")
    return target
}

/** Saves a camera preview to the app cache before multipart upload. */
fun saveCameraImageToCache(context: Context, bitmap: Bitmap): File {
    val target = File(context.cacheDir, "chat_${System.currentTimeMillis()}.jpg")
    target.outputStream().use { output ->
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)) {
            throw IllegalArgumentException("无法保存拍摄的图片")
        }
    }
    return target
}

/** Makes images saved by older local server configurations reachable from the app. */
fun resolveImageUrl(url: String): String =
    url.replace("http://localhost:8080/", BuildConfig.SERVER_URL)
