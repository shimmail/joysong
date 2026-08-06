package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.components.CountryCodePicker
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun BindPhoneScreen(
    onBackClick: () -> Unit,
    onBindSuccess: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countryCode by remember { mutableStateOf("+86") }
    var countdown by remember { mutableStateOf(0) }
    var isCodeSent by remember { mutableStateOf(false) }
    var identityCode by remember { mutableStateOf("") }
    var identityCodeSent by remember { mutableStateOf(false) }
    var identityVerified by remember { mutableStateOf(false) }
    var requestCurrentCode by remember { mutableStateOf(false) }
    var requestIdentityVerification by remember { mutableStateOf(false) }
    var requestNewPhoneCode by remember { mutableStateOf(false) }
    var showPhoneRegisteredDialog by remember { mutableStateOf(false) }
    val bindState by viewModel.bindPhoneState.collectAsState()
    val sendCodeState by viewModel.sendCodeState.collectAsState()
    val phoneChangeState by viewModel.phoneChangeState.collectAsState()
    val user by viewModel.user.collectAsState()
    val maskedCurrentPhone by viewModel.maskedCurrentPhone.collectAsState()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    LaunchedEffect(bindState) {
        if (bindState is ProfileActionState.Success) {
            viewModel.resetActionStates()
            onBindSuccess()
        }
    }

    LaunchedEffect(phoneChangeState) {
        if (phoneChangeState is ProfileActionState.Success) {
            if (requestCurrentCode) {
                identityCodeSent = true
                requestCurrentCode = false
            }
            if (requestIdentityVerification) {
                identityVerified = true
                requestIdentityVerification = false
            }
        }
    }

    LaunchedEffect(sendCodeState) {
        if (sendCodeState is ProfileActionState.Success && requestNewPhoneCode) {
            isCodeSent = true
            countdown = 60
            requestNewPhoneCode = false
        }
        if (sendCodeState is ProfileActionState.Error && requestNewPhoneCode) {
            val message = (sendCodeState as ProfileActionState.Error).message
            if (message == "该手机号已被注册") {
                showPhoneRegisteredDialog = true
            }
            requestNewPhoneCode = false
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.security_modify_phone), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.bind_phone_desc),
                fontSize = 14.sp,
                color = TextSecondary
            )

            // 手机号输入（区号 + 号码）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CountryCodePicker(
                    selectedCode = countryCode,
                    onCodeChange = { countryCode = it }
                )
                OutlinedInputBox(
                    value = phone,
                    onValueChange = {
                        if (it.length <= 15) {
                            phone = it
                            viewModel.resetActionStates()
                        }
                    },
                    placeholder = stringResource(R.string.phone_hint),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedInputBox(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it },
                    placeholder = stringResource(R.string.verification_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        if (countdown == 0 && phone.isNotBlank()) {
                            viewModel.resetActionStates()
                            requestNewPhoneCode = true
                            viewModel.sendNewPhoneChangeCode(countryCode + phone)
                        }
                    },
                    enabled = countdown == 0 && phone.isNotBlank()
                ) {
                    Text(
                        text = if (countdown > 0) stringResource(R.string.resend_code, countdown)
                        else if (isCodeSent) stringResource(R.string.resend)
                        else stringResource(R.string.get_code),
                        color = if (countdown == 0 && phone.isNotBlank()) PrimaryDark else TextHint,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isLoading = bindState is ProfileActionState.Loading
            Button(
                onClick = { viewModel.changePhone(countryCode + phone, code) },
                enabled = phone.isNotBlank() && code.isNotBlank() && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    contentColor = TextOnPrimary,
                    disabledContainerColor = SurfaceVariant,
                    disabledContentColor = TextHint
                )
            ) {
                Text(
                    text = if (isLoading) stringResource(R.string.binding) else stringResource(R.string.confirm_bind),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (bindState is ProfileActionState.Error) {
                Text(
                    text = (bindState as ProfileActionState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }
            if (sendCodeState is ProfileActionState.Error) {
                Text(
                    text = (sendCodeState as ProfileActionState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }
        }
    }

    val currentPhone = user?.phone.orEmpty()
    if (currentPhone.isNotBlank() && !identityVerified) {
        AlertDialog(
            onDismissRequest = onBackClick,
            title = { Text("身份确认") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("为保障账号安全，请先验证当前绑定手机号。")
                    Text("向${maskedCurrentPhone.ifBlank { maskPhone(currentPhone) }}发送验证码")
                    if (identityCodeSent) {
                        OutlinedInputBox(
                            value = identityCode,
                            onValueChange = { if (it.length <= 6) identityCode = it },
                            placeholder = stringResource(R.string.verification_code),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    if (phoneChangeState is ProfileActionState.Error) {
                        Text((phoneChangeState as ProfileActionState.Error).message, color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (identityCodeSent) {
                        requestIdentityVerification = true
                        viewModel.verifyCurrentPhoneChangeCode(identityCode)
                    } else {
                        requestCurrentCode = true
                        viewModel.sendCurrentPhoneChangeCode()
                    }
                }, enabled = if (identityCodeSent) identityCode.length == 6 else true) {
                    Text(if (identityCodeSent) "确认验证" else "发送验证码")
                }
            },
            dismissButton = { TextButton(onClick = onBackClick) { Text(stringResource(R.string.cancel)) } }
        )
    }

    if (showPhoneRegisteredDialog) {
        AlertDialog(
            onDismissRequest = { showPhoneRegisteredDialog = false },
            title = { Text("手机号已注册") },
            text = { Text("该手机号已被注册，请更换其他手机号。") },
            confirmButton = {
                Button(onClick = {
                    showPhoneRegisteredDialog = false
                    viewModel.resetActionStates()
                }) { Text("我知道了") }
            }
        )
    }
}

private fun maskPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)

@Composable
private fun OutlinedInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            keyboardOptions = keyboardOptions,
            singleLine = true,
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextHint,
                        fontSize = 15.sp
                    )
                }
                innerTextField()
            }
        )
    }
}
