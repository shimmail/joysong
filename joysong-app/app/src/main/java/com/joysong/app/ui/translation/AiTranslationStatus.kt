package com.joysong.app.ui.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun AiTranslationStatus(
    state: TranslationUiState?,
    onTranslate: () -> Unit,
    onShowOriginal: () -> Unit,
    onShowTranslation: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showInitialAction: Boolean = true,
    showVisibilityAction: Boolean = true
) {
    when (state) {
        null -> if (showInitialAction) {
            Text(
                text = stringResource(R.string.translate_with_ai),
                color = PrimaryDark,
                fontSize = if (compact) 12.sp else 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = modifier
                    .padding(top = if (compact) 4.dp else 8.dp)
                    .clickable(onClick = onTranslate)
            )
        }

        TranslationUiState.Loading -> Row(
            modifier = modifier.padding(top = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
            Text(
                text = stringResource(R.string.translating),
                color = TextHint,
                fontSize = if (compact) 11.sp else 12.sp
            )
        }

        is TranslationUiState.Success -> if (state.showingTranslation || showVisibilityAction) {
            Column(modifier = modifier.padding(top = if (compact) 4.dp else 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (state.showingTranslation) {
                            stringResource(R.string.ai_translation)
                        } else {
                            stringResource(R.string.original_text)
                        },
                        color = if (state.showingTranslation) PrimaryDark else TextSecondary,
                        fontSize = if (compact) 11.sp else 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (showVisibilityAction) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = if (state.showingTranslation) {
                                stringResource(R.string.show_original)
                            } else {
                                stringResource(R.string.show_translation)
                            },
                            color = PrimaryDark,
                            fontSize = if (compact) 11.sp else 12.sp,
                            modifier = Modifier.clickable {
                                if (state.showingTranslation) onShowOriginal() else onShowTranslation()
                            }
                        )
                    }
                }
            }
        }

        is TranslationUiState.Error -> Row(
            modifier = modifier.padding(top = if (compact) 4.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.translation_failed),
                color = TextHint,
                fontSize = if (compact) 11.sp else 12.sp
            )
            Text(
                text = stringResource(R.string.retry_translation),
                color = PrimaryDark,
                fontSize = if (compact) 11.sp else 12.sp,
                modifier = Modifier.clickable(onClick = onTranslate)
            )
        }
    }
}
