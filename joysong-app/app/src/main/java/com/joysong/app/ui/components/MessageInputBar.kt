package com.joysong.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

/**
 * Shared lightweight composer for AI and direct messages.
 * Focus is reported before the IME animation starts so the outer navigation bar
 * can disappear without reacting to every animated inset frame.
 */
@Composable
fun MessageInputBar(
    placeholder: String,
    sendContentDescription: String,
    isSending: Boolean,
    onSend: (String) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: Boolean = true,
    disabledPlaceholder: String = placeholder,
    moreContentDescription: String = "",
    cameraContentDescription: String = "",
    albumContentDescription: String = "",
    onTakePhoto: (() -> Unit)? = null,
    onPickImage: (() -> Unit)? = null
) {
    var text by rememberSaveable { mutableStateOf("") }
    var showExtensions by rememberSaveable { mutableStateOf(false) }
    val reportInputFocus = LocalMessageInputFocusHandler.current
    val hasAttachments = onTakePhoto != null || onPickImage != null

    DisposableEffect(Unit) {
        onDispose { reportInputFocus(false) }
    }

    fun submit() {
        val content = text.trim()
        if (content.isNotEmpty() && isEnabled && !isSending) {
            onSend(content)
            text = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Surface)
            .imePadding()
    ) {
        if (showExtensions && hasAttachments) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                onTakePhoto?.let { action ->
                    OutlinedIconButton(
                        onClick = action,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = TextSecondary)
                    ) { Icon(Icons.Default.CameraAlt, contentDescription = cameraContentDescription) }
                }
                onPickImage?.let { action ->
                    OutlinedIconButton(
                        onClick = action,
                        modifier = Modifier.size(48.dp),
                        colors = IconButtonDefaults.outlinedIconButtonColors(contentColor = TextSecondary)
                    ) { Icon(Icons.Default.PhotoLibrary, contentDescription = albumContentDescription) }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasAttachments) {
                IconButton(onClick = { showExtensions = !showExtensions }, enabled = isEnabled) {
                    Icon(Icons.Default.Add, contentDescription = moreContentDescription, tint = TextSecondary)
                }
                Spacer(Modifier.width(4.dp))
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp, max = 112.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(SurfaceVariant)
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(2000) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { reportInputFocus(it.isFocused) },
                    textStyle = TextStyle(color = TextPrimary, fontSize = 15.sp),
                    cursorBrush = SolidColor(PrimaryDark),
                    enabled = isEnabled,
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    decorationBox = { innerTextField ->
                        Box {
                            if (text.isEmpty()) {
                                androidx.compose.material3.Text(
                                    text = if (isEnabled) placeholder else disabledPlaceholder,
                                    color = TextHint,
                                    fontSize = 15.sp
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(Modifier.width(8.dp))
            if (isSending) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Primary)
            } else {
                IconButton(onClick = ::submit, enabled = text.isNotBlank() && isEnabled) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = sendContentDescription,
                        tint = if (text.isNotBlank() && isEnabled) PrimaryDark else TextHint
                    )
                }
            }
        }
    }
}
