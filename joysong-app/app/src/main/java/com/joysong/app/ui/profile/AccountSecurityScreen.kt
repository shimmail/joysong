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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.data.local.SavedAccount
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

private data class SecurityItem(
    val icon: ImageVector,
    val titleRes: Int,
    val subtitleRes: Int,
    val onClick: () -> Unit
)

private fun maskPhone(phone: String): String {
    if (phone.startsWith("google_")) return "Google"
    return if (phone.length >= 7) {
        phone.substring(0, 3) + "****" + phone.substring(phone.length - 4)
    } else phone
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSecurityScreen(
    onBackClick: () -> Unit,
    onChangePasswordClick: () -> Unit = {},
    onBindPhoneClick: () -> Unit = {},
    onBindEmailClick: () -> Unit = {},
    onOfficialVerificationClick: () -> Unit = {},
    savedAccounts: List<SavedAccount> = emptyList(),
    currentPhone: String = "",
    onSwitchAccount: (SavedAccount) -> Unit = {},
    onRemoveAccount: (String) -> Unit = {},
    onAddNewAccount: () -> Unit = {},
    isSwitching: Boolean = false,
    onAccountDeleted: () -> Unit = {}
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showSwitchDialog by remember { mutableStateOf(false) }

    val items = listOf(
        SecurityItem(
            Icons.Outlined.VerifiedUser,
            R.string.security_official_verification,
            R.string.security_official_verification_desc,
            onOfficialVerificationClick
        ),
        SecurityItem(
            Icons.Outlined.Lock,
            R.string.security_change_password,
            R.string.security_change_password_desc,
            onChangePasswordClick
        ),
        SecurityItem(
            Icons.Outlined.Phone,
            R.string.security_modify_phone,
            R.string.security_bind_phone_desc,
            onBindPhoneClick
        ),
        SecurityItem(
            Icons.Outlined.Email,
            R.string.security_bind_email,
            R.string.security_bind_email_desc,
            onBindEmailClick
        ),
        SecurityItem(
            Icons.Default.SwapHoriz,
            R.string.security_switch_account,
            R.string.security_switch_account_desc,
            { showSwitchDialog = true }
        )
    )

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.account_security), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .background(Background)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.account_security_desc),
                fontSize = 14.sp,
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 4.dp)
            )

            items.forEach { item ->
                SecurityCard(item = item)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 注销账号按钮
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
                    color = Color(0xFFD32F2F),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }

    // 注销确认弹窗
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
                        color = Color(0xFFD32F2F)
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

    // 切换账号底部弹窗
    if (showSwitchDialog) {
        ModalBottomSheet(
            onDismissRequest = { showSwitchDialog = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 32.dp)
            ) {
                Text(
                    text = stringResource(R.string.switch_account_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )

                if (isSwitching) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = PrimaryDark)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.switching_account),
                            color = TextSecondary,
                            fontSize = 14.sp
                        )
                    }
                } else {
                    savedAccounts.filter { it.phone != currentPhone }.forEach { account ->
                        AccountItem(
                            account = account,
                            onSwitch = {
                                onSwitchAccount(account)
                            },
                            onRemove = { onRemoveAccount(account.phone) }
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // 添加新账号按钮
                    Button(
                        onClick = {
                            showSwitchDialog = false
                            onAddNewAccount()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.add_new_account), color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountItem(
    account: SavedAccount,
    onSwitch: () -> Unit,
    onRemove: () -> Unit
) {
    var showRemoveDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable(onClick = onSwitch),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 头像
            AsyncImage(
                model = account.avatar.ifBlank { null },
                contentDescription = null,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFE8EAF6)),
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.nickname.ifBlank { account.phone },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = maskPhone(account.phone),
                    fontSize = 13.sp,
                    color = TextSecondary
                )
            }
            // 删除按钮
            IconButton(onClick = { showRemoveDialog = true }) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = TextHint
                )
            }
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.remove_account_title)) },
            text = { Text(stringResource(R.string.remove_account_confirm, maskPhone(account.phone))) },
            confirmButton = {
                TextButton(onClick = {
                    showRemoveDialog = false
                    onRemove()
                }) {
                    Text(stringResource(R.string.confirm), color = Color(0xFFD32F2F))
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun SecurityCard(item: SecurityItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
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
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(item.titleRes),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    text = stringResource(item.subtitleRes),
                    fontSize = 13.sp,
                    color = TextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = TextHint
            )
        }
    }
}

