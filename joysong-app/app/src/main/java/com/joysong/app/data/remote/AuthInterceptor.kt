package com.joysong.app.data.remote

import com.joysong.app.data.local.TokenManager
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale

class AuthInterceptor(private val tokenManager: TokenManager) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val token = runBlocking { tokenManager.getToken() }
        val builder = chain.request().newBuilder()
            .header("Accept-Language", Locale.getDefault().language)
        val request = if (token != null) {
            builder
                .addHeader("Authorization", "Bearer $token")
                .build()
        } else {
            builder.build()
        }
        val response = chain.proceed(request)

        // Token 过期或服务端拒绝：清除本地 Token
        if (response.code == 401) {
            runBlocking { tokenManager.clear() }
        }

        return response
    }
}
