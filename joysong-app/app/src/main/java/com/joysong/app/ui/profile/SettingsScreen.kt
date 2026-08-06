package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.HelpCenter
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.util.LocaleHelper

private data class SettingsItem(
    val icon: ImageVector,
    val titleRes: Int,
    val trailing: String = "",
    val onClick: () -> Unit = {}
)

@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    onLanguageChanged: () -> Unit = {},
    onLogout: () -> Unit = {},
    onAccountDeleted: () -> Unit = {}
) {
    val context = LocalContext.current
    val currentLang = LocaleHelper.getLocale(context)
    var showLanguageDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val sections = listOf(
        stringResource(R.string.section_app) to listOf(
            SettingsItem(Icons.Outlined.Delete, R.string.setting_clear_cache),
            SettingsItem(Icons.Outlined.Policy, R.string.setting_privacy_policy),
            SettingsItem(Icons.Outlined.HelpCenter, R.string.setting_terms),
            SettingsItem(Icons.Outlined.HelpCenter, R.string.setting_about, "v1.0.0"),
            SettingsItem(Icons.Outlined.Language, R.string.setting_language,
                if (currentLang == LocaleHelper.LANG_CHINESE) "中文" else "English",
                { showLanguageDialog = true })
        )
    )

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.settings_title), onBackClick = onBackClick) }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
        ) {
            sections.forEach { (title, items) ->
                item {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelLarge,
                        color = TextSecondary,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(items.size) { index ->
                    SettingsRow(item = items[index])
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onLogout,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                ) {
                    Text(
                        text = stringResource(R.string.logout_button),
                        color = TextOnPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            item {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = { showDeleteDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Surface)
                ) {
                    Text(
                        text = stringResource(R.string.delete_account),
                        color = androidx.compose.ui.graphics.Color(0xFFD32F2F),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    if (showLanguageDialog) {
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text(stringResource(R.string.setting_language)) },
            text = {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LocaleHelper.setLocale(context, LocaleHelper.LANG_ENGLISH)
                                showLanguageDialog = false
                                onLanguageChanged()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentLang == LocaleHelper.LANG_ENGLISH,
                            onClick = {
                                LocaleHelper.setLocale(context, LocaleHelper.LANG_ENGLISH)
                                showLanguageDialog = false
                                onLanguageChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.language_english), fontSize = 16.sp)
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                LocaleHelper.setLocale(context, LocaleHelper.LANG_CHINESE)
                                showLanguageDialog = false
                                onLanguageChanged()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentLang == LocaleHelper.LANG_CHINESE,
                            onClick = {
                                LocaleHelper.setLocale(context, LocaleHelper.LANG_CHINESE)
                                showLanguageDialog = false
                                onLanguageChanged()
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.language_chinese), fontSize = 16.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLanguageDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.delete_account_title)) },
            text = { Text(stringResource(R.string.delete_account_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onAccountDeleted()
                }) {
                    Text(
                        text = stringResource(R.string.confirm_delete),
                        color = androidx.compose.ui.graphics.Color(0xFFD32F2F)
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SettingsRow(item: SettingsItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clickable(onClick = item.onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = PrimaryDark
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = stringResource(item.titleRes),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (item.trailing.isNotBlank()) {
                Text(
                    text = item.trailing,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = TextSecondary
            )
        }
    }
}
