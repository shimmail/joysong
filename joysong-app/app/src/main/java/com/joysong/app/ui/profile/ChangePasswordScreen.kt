package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

@Composable
fun ChangePasswordScreen(
    onBackClick: () -> Unit,
    onPasswordChanged: () -> Unit,
    hasPassword: Boolean = true,
    onForgotPasswordClick: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    if (hasPassword) {
        ChangePasswordContent(
            onBackClick = onBackClick,
            onPasswordChanged = onPasswordChanged,
            onForgotPasswordClick = onForgotPasswordClick,
            viewModel = viewModel
        )
    } else {
        SetPasswordContent(
            onBackClick = onBackClick,
            onPasswordChanged = onPasswordChanged,
            viewModel = viewModel
        )
    }
}

/** 已设置密码：修改密码 + 忘记密码入口 */
@Composable
private fun ChangePasswordContent(
    onBackClick: () -> Unit,
    onPasswordChanged: () -> Unit,
    onForgotPasswordClick: () -> Unit,
    viewModel: ProfileViewModel
) {
    val changePasswordState by viewModel.changePasswordState.collectAsState()

    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showOldPassword by remember { mutableStateOf(false) }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val oldPasswordEmptyMsg = stringResource(R.string.old_password_empty)
    val newPasswordTooShortMsg = stringResource(R.string.new_password_too_short)
    val passwordMismatchMsg = stringResource(R.string.password_mismatch)
    val oldPasswordIncorrectMsg = stringResource(R.string.old_password_incorrect)

    LaunchedEffect(changePasswordState) {
        when (changePasswordState) {
            is ProfileActionState.Success -> {
                viewModel.resetActionStates()
                onPasswordChanged()
            }
            is ProfileActionState.Error -> {
                val msg = (changePasswordState as ProfileActionState.Error).message
                errorMessage = if (msg?.contains("原密码错误") == true) oldPasswordIncorrectMsg else msg
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.security_change_password), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.change_password_subtitle),
                fontSize = 14.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 原密码
            PasswordField(
                value = oldPassword,
                onValueChange = { oldPassword = it },
                placeholder = stringResource(R.string.old_password_hint),
                showPassword = showOldPassword,
                onToggleVisibility = { showOldPassword = !showOldPassword }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 新密码
            PasswordField(
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = stringResource(R.string.new_password_hint),
                showPassword = showNewPassword,
                onToggleVisibility = { showNewPassword = !showNewPassword }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 确认新密码
            PasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                placeholder = stringResource(R.string.confirm_password_hint),
                showPassword = showConfirmPassword,
                onToggleVisibility = { showConfirmPassword = !showConfirmPassword }
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    when {
                        oldPassword.isEmpty() -> errorMessage = oldPasswordEmptyMsg
                        newPassword.length < 6 -> errorMessage = newPasswordTooShortMsg
                        newPassword != confirmPassword -> errorMessage = passwordMismatchMsg
                        else -> {
                            errorMessage = null
                            viewModel.changePassword(oldPassword, newPassword)
                        }
                    }
                },
                enabled = oldPassword.isNotEmpty() && newPassword.isNotEmpty() && confirmPassword.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    disabledContainerColor = SurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.confirm_change_password),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextOnPrimary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 忘记密码
            TextButton(
                onClick = onForgotPasswordClick,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            ) {
                Text(
                    text = stringResource(R.string.forgot_password),
                    fontSize = 14.sp,
                    color = PrimaryDark
                )
            }
        }
    }

    // 错误提示
    errorMessage?.let { msg ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { errorMessage = null },
            title = { Text(stringResource(R.string.error_title)) },
            text = { Text(msg) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { errorMessage = null }) {
                    Text(stringResource(R.string.btn_confirm))
                }
            }
        )
    }
}

/** 未设置密码：身份验证 + 设置新密码 */
@Composable
private fun SetPasswordContent(
    onBackClick: () -> Unit,
    onPasswordChanged: () -> Unit,
    viewModel: ProfileViewModel
) {
    val changePasswordState by viewModel.changePasswordState.collectAsState()
    val sendCodeState by viewModel.sendCodeState.collectAsState()
    val phoneChangeState by viewModel.phoneChangeState.collectAsState()
    val user by viewModel.user.collectAsState()
    val maskedCurrentPhone by viewModel.maskedCurrentPhone.collectAsState()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var countryCode by remember { mutableStateOf("+86") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var identityCode by remember { mutableStateOf("") }
    var identityCodeSent by remember { mutableStateOf(false) }
    var identityVerified by remember { mutableStateOf(false) }
    var requestCurrentCode by remember { mutableStateOf(false) }
    var requestIdentityVerification by remember { mutableStateOf(false) }

    val subtitle = stringResource(R.string.identity_verify_subtitle)
    val phoneHint = stringResource(R.string.identity_verify_phone_hint)
    val newPasswordTooShortMsg = stringResource(R.string.new_password_too_short)
    val passwordMismatchMsg = stringResource(R.string.password_mismatch)
    val phoneMismatchMsg = stringResource(R.string.phone_mismatch)

    LaunchedEffect(changePasswordState) {
        when (changePasswordState) {
            is ProfileActionState.Success -> {
                viewModel.resetActionStates()
                onPasswordChanged()
            }
            is ProfileActionState.Error -> {
                val msg = (changePasswordState as ProfileActionState.Error).message
                errorMessage = when {
                    msg?.contains("手机号与账号关联手机号不一致") == true -> phoneMismatchMsg
                    else -> msg
                }
            }
            else -> {}
        }
    }

    LaunchedEffect(sendCodeState) {
        when (sendCodeState) {
            is ProfileActionState.Error -> {
                errorMessage = (sendCodeState as ProfileActionState.Error).message
            }
            else -> {}
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
        if (phoneChangeState is ProfileActionState.Error) {
            errorMessage = (phoneChangeState as ProfileActionState.Error).message
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.set_password_title), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 手机号输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountryCodePicker(
                    selectedCode = countryCode,
                    onCodeChange = { countryCode = it }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    decorationBox = { innerTextField ->
                        Box {
                            if (phone.isEmpty()) {
                                Text(
                                    text = phoneHint,
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 验证码输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    decorationBox = { innerTextField ->
                        Box {
                            if (code.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.verification_code),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                TextButton(
                    onClick = {
                        if (phone.length >= 10) {
                            viewModel.sendVerificationCode(countryCode + phone)
                            countdown = 60
                        }
                    },
                    enabled = countdown == 0 && phone.length >= 10
                ) {
                    Text(
                        text = if (countdown > 0) stringResource(R.string.resend_code, countdown)
                               else stringResource(R.string.get_code),
                        fontSize = 13.sp,
                        color = if (countdown > 0) TextHint else PrimaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 新密码输入
            PasswordField(
                value = newPassword,
                onValueChange = { newPassword = it },
                placeholder = stringResource(R.string.new_password_hint),
                showPassword = showNewPassword,
                onToggleVisibility = { showNewPassword = !showNewPassword }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 确认新密码输入
            PasswordField(
                value = confirmPassword,
                onValueChange = { confirmPassword = it },
                placeholder = stringResource(R.string.confirm_password_hint),
                showPassword = showConfirmPassword,
                onToggleVisibility = { showConfirmPassword = !showConfirmPassword }
            )

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    fontSize = 13.sp,
                    color = androidx.compose.ui.graphics.Color(0xFFE53935)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    errorMessage = null
                    if (newPassword.length < 6) {
                        errorMessage = newPasswordTooShortMsg
                        return@Button
                    }
                    if (newPassword != confirmPassword) {
                        errorMessage = passwordMismatchMsg
                        return@Button
                    }
                    viewModel.setPassword(countryCode + phone, code, newPassword)
                },
                enabled = phone.length >= 10 && code.length == 6 && newPassword.isNotEmpty() && confirmPassword.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    disabledContainerColor = SurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.btn_set_password),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextOnPrimary
                )
            }
        }
    }

    val currentPhone = user?.phone.orEmpty()
    if (currentPhone.isNotBlank() && !identityVerified) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onBackClick,
            title = { Text("身份确认") },
            text = {
                Column {
                    Text("为保障账号安全，请先验证当前绑定手机号。")
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("向${maskedCurrentPhone.ifBlank { maskCurrentPhone(currentPhone) }}发送验证码")
                    if (identityCodeSent) {
                        Spacer(modifier = Modifier.height(12.dp))
                        BasicTextField(
                            value = identityCode,
                            onValueChange = { identityCode = it.filter(Char::isDigit).take(6) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { field ->
                                if (identityCode.isEmpty()) Text(stringResource(R.string.verification_code), color = TextHint)
                                field()
                            }
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (identityCodeSent) {
                            requestIdentityVerification = true
                            viewModel.verifyCurrentPhoneChangeCode(identityCode)
                        } else {
                            requestCurrentCode = true
                            viewModel.sendCurrentPhoneChangeCode()
                        }
                    },
                    enabled = !identityCodeSent || identityCode.length == 6
                ) { Text(if (identityCodeSent) "确认验证" else "发送验证码") }
            },
            dismissButton = { TextButton(onClick = onBackClick) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun maskCurrentPhone(phone: String): String = phone.take(5) + "******" + phone.takeLast(2)

@Composable
private fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    showPassword: Boolean,
    onToggleVisibility: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(20.dp)
                .background(SurfaceVariant)
        )
        Spacer(modifier = Modifier.width(12.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 16.sp,
                color = TextPrimary
            ),
            visualTransformation = if (showPassword) VisualTransformation.None
                else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            decorationBox = { innerTextField ->
                Box {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            fontSize = 16.sp,
                            color = TextHint
                        )
                    }
                    innerTextField()
                }
            }
        )
        Icon(
            imageVector = if (showPassword) Icons.Outlined.VisibilityOff
                else Icons.Outlined.Visibility,
            contentDescription = null,
            tint = TextHint,
            modifier = Modifier
                .size(20.dp)
                .clickable { onToggleVisibility() }
        )
    }
}
