package com.joysong.app.data.remote

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.dto.ApiResponse
import com.joysong.app.data.remote.dto.LoginResponseDto
import com.joysong.app.data.remote.dto.RefreshTokenRequestDto
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * 仅在服务端返回 HTTP 401 时轮换 Refresh Token。
 * 使用独立客户端调用刷新接口，避免认证器递归，并通过同步锁合并并发刷新请求。
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
    private val gson: Gson = Gson()
) : Authenticator {
    private val refreshClient = OkHttpClient.Builder().build()
    private val refreshLock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        if (response.request.url.encodedPath == "/api/auth/refresh" || responseCount(response) >= 2) {
            runBlocking { tokenManager.clear() }
            return null
        }

        val attemptedToken = response.request.header("Authorization")
            ?.removePrefix("Bearer ")
            ?.takeIf { it.isNotBlank() }

        return synchronized(refreshLock) {
            val currentToken = runBlocking { tokenManager.getToken() }
            if (!currentToken.isNullOrBlank() && currentToken != attemptedToken) {
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer $currentToken")
                    .build()
            }

            val refreshToken = runBlocking { tokenManager.getRefreshToken() }
            if (refreshToken.isNullOrBlank()) {
                runBlocking { tokenManager.clear() }
                return@synchronized null
            }

            val refreshUrl = response.request.url.newBuilder()
                .encodedPath("/api/auth/refresh")
                .query(null)
                .build()
            val refreshRequest = Request.Builder()
                .url(refreshUrl)
                .post(
                    gson.toJson(RefreshTokenRequestDto(refreshToken))
                        .toRequestBody(JSON_MEDIA_TYPE)
                )
                .header("Accept", "application/json")
                .build()

            val refreshed = runCatching {
                refreshClient.newCall(refreshRequest).execute().use { refreshResponse ->
                    if (!refreshResponse.isSuccessful) return@use null
                    val rawBody = refreshResponse.body?.string() ?: return@use null
                    val responseType = object : TypeToken<ApiResponse<LoginResponseDto>>() {}.type
                    val envelope: ApiResponse<LoginResponseDto> = gson.fromJson(rawBody, responseType)
                    envelope.data?.takeIf { envelope.code == 200 }
                }
            }.getOrNull()

            if (refreshed == null || refreshed.refreshToken.isBlank()) {
                runBlocking { tokenManager.clear() }
                return@synchronized null
            }

            val newAccessToken = refreshed.accessToken.ifBlank { refreshed.token }
            runBlocking {
                tokenManager.saveTokens(newAccessToken, refreshed.refreshToken)
                tokenManager.updateSavedAccountTokens(
                    oldAccessToken = attemptedToken ?: currentToken.orEmpty(),
                    newAccessToken = newAccessToken,
                    newRefreshToken = refreshed.refreshToken
                )
            }
            response.request.newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
        }
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
