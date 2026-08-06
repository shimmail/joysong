package com.joysong.app.di

import com.joysong.app.data.local.TokenManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
interface TokenManagerEntryPoint {
    fun tokenManager(): TokenManager
}
