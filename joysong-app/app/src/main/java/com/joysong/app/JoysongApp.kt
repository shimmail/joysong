package com.joysong.app

import android.app.Application
import com.joysong.app.data.local.MessageUnreadManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class JoysongApp : Application() {

    @Inject
    lateinit var messageUnreadManager: MessageUnreadManager

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        messageUnreadManager.startPolling(applicationScope)
    }
}
