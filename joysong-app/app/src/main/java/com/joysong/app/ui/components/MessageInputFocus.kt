package com.joysong.app.ui.components

import androidx.compose.runtime.staticCompositionLocalOf

val LocalMessageInputFocusHandler = staticCompositionLocalOf<(Boolean) -> Unit> { { } }
